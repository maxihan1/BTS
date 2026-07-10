// 이슈 이벤트(JSON)를 자동화 트리거 타입으로 매핑하고 ISSUE_UPDATED 필드 필터를 판정하는 순수 매칭 로직 (FR-AT-01 Task 7)

package com.bts.automation.application

import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * `q_automation_events` 로 fan-out 된 이슈 이벤트 JSON을 [TriggerType] 으로 매핑하는 순수 함수 모음.
 *
 * [com.bts.automation.worker.AutomationEventWorker] 가 pgmq 메시지 생명주기(read/delete/archive)를
 * 담당하는 반면, 이 객체는 "이 이벤트가 어떤 트리거를 발화시키는가"만 판정한다(DB·pgmq I/O 없음).
 *
 * ## 이벤트 → 트리거 타입 매핑
 * - `issue.created` → [TriggerType.ISSUE_CREATED]
 * - `issue.updated` → [TriggerType.ISSUE_UPDATED]
 * - `issue.commented` → [TriggerType.ISSUE_COMMENTED]
 * - 그 외(`issue.transitioned`/`issue.soft_deleted`/`issue.mentioned`/`issue.due_soon`/`issue.overdue`) →
 *   매핑 없음(automation 미관심 이벤트, [match] 가 `null` 반환)
 *
 * ## projectKey 파싱 (스펙 Brainstorming G1)
 * `issue.updated` 이벤트에는 `projectKey` 필드가 없다(issue-tracking `IssueUpdated` 이벤트 계약).
 * 워커는 프로젝트별 룰 매칭이 필요하므로, 이벤트에 `projectKey` 필드가 있어도 사용하지 않고
 * **항상** `issueKey`(`PROJECT-123` 형식)에서 하이픈 앞부분을 파싱해 일관되게 사용한다.
 *
 * ## BC 격리
 * issue-tracking 의 `IssueKey`/`IssueDomainEvent` 클래스를 직접 import 하지 않는다. pgmq JSON 을
 * [JsonNode] 로 직접 파싱해 내부 표현([MatchedIssueEvent])으로 변환한다(NotificationWorker 선례).
 */
object TriggerMatcher {
    private const val FIELD_TYPE = "type"
    private const val FIELD_ISSUE_KEY = "issueKey"
    private const val FIELD_FIELDS = "fields"
    private const val PROJECT_KEY_SEPARATOR = '-'

    private const val WIRE_ISSUE_CREATED = "issue.created"
    private const val WIRE_ISSUE_UPDATED = "issue.updated"
    private const val WIRE_ISSUE_COMMENTED = "issue.commented"

    /** 이슈 이벤트 wire type 문자열 → [TriggerType] 매핑 테이블. 없는 키는 automation 미관심 타입. */
    private val WIRE_TYPE_TO_TRIGGER_TYPE: Map<String, TriggerType> =
        mapOf(
            WIRE_ISSUE_CREATED to TriggerType.ISSUE_CREATED,
            WIRE_ISSUE_UPDATED to TriggerType.ISSUE_UPDATED,
            WIRE_ISSUE_COMMENTED to TriggerType.ISSUE_COMMENTED,
        )

    private val objectMapper = ObjectMapper()

    /**
     * pgmq 메시지 [event] 를 [MatchedIssueEvent] 로 매핑한다.
     *
     * 다음 경우 `null` 을 반환한다(호출자가 skip 처리) — [TriggerType] 매핑이 없는 타입(미관심 이벤트),
     * `issueKey` 필드 부재/공백, `issueKey` 형식에 하이픈이 없어 프로젝트 키를 파싱할 수 없는 경우
     * (malformed).
     *
     * @param event pgmq 메시지를 파싱한 JSON 루트 노드.
     * @return 매칭된 [MatchedIssueEvent] 또는 `null`(skip 대상).
     */
    fun match(event: JsonNode): MatchedIssueEvent? {
        val triggerType = WIRE_TYPE_TO_TRIGGER_TYPE[event.path(FIELD_TYPE).asText("")] ?: return null
        val issueKey = event.path(FIELD_ISSUE_KEY).asText(null).takeIf { it?.isNotBlank() == true } ?: return null
        val projectKey = projectKeyOf(issueKey) ?: return null
        val updatedFields =
            if (triggerType == TriggerType.ISSUE_UPDATED) parseFieldArray(event, FIELD_FIELDS) else emptySet()
        return MatchedIssueEvent(projectKey = projectKey, triggerType = triggerType, updatedFields = updatedFields)
    }

    /**
     * [TriggerType.ISSUE_UPDATED] 룰의 `triggerConfig` `{fields:[...]}` 필터를 [updatedFields] 와
     * 교집합 판정한다(스펙 S4).
     *
     * `fields` 가 없거나 빈 배열이면 모든 update 에 발화한다(전체 발화, FR4).
     *
     * @param triggerConfig 룰의 triggerConfig JSON 문자열([com.bts.automation.domain.TriggerConfig] 로
     *   이미 형식 검증된 값).
     * @param updatedFields 이벤트에서 변경된 필드 이름 집합.
     * @return 발화해야 하면 `true`.
     */
    fun matchesFieldFilter(
        triggerConfig: String,
        updatedFields: Set<String>,
    ): Boolean {
        val configuredFields = parseFieldArray(objectMapper.readTree(triggerConfig), FIELD_FIELDS)
        if (configuredFields.isEmpty()) return true
        return configuredFields.any { it in updatedFields }
    }

    /** `issueKey`(`PROJECT-123`) 에서 하이픈 앞 프로젝트 키를 파싱한다. 하이픈이 없으면 `null`. */
    private fun projectKeyOf(issueKey: String): String? {
        val separatorIndex = issueKey.indexOf(PROJECT_KEY_SEPARATOR)
        if (separatorIndex <= 0) return null
        return issueKey.substring(0, separatorIndex)
    }

    /** [node] 의 [fieldName] 배열 노드를 비어있지 않은 문자열 집합으로 파싱한다. 배열이 아니면 빈 집합. */
    private fun parseFieldArray(
        node: JsonNode,
        fieldName: String,
    ): Set<String> {
        val arrayNode = node.path(fieldName)
        if (!arrayNode.isArray) return emptySet()
        return arrayNode.mapNotNull { it.asText(null).takeIf { text -> text?.isNotBlank() == true } }.toSet()
    }
}

/**
 * [TriggerMatcher.match] 결과 — 이슈 이벤트가 매칭하는 트리거 컨텍스트.
 *
 * @property projectKey 이벤트의 `issueKey` 에서 파싱한 프로젝트 키(스펙 G1).
 * @property triggerType 매핑된 트리거 타입.
 * @property updatedFields [TriggerType.ISSUE_UPDATED] 인 경우 변경된 필드 집합. 그 외 타입은 빈 집합.
 */
data class MatchedIssueEvent(
    val projectKey: String,
    val triggerType: TriggerType,
    val updatedFields: Set<String>,
)
