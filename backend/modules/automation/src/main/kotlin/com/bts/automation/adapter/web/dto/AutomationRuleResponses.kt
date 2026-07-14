// AutomationRuleController 응답 DTO — 표준 룰 응답(토큰 미노출) + 생성 1회 웹훅 토큰 응답 + 조건 게이트 (FR-AT-03 Task 8)

package com.bts.automation.adapter.web.dto

import com.bts.automation.application.CreatedAutomationRule
import com.bts.automation.application.ImportOutcome
import com.bts.automation.application.ImportedWebhookToken
import com.bts.automation.application.PatchedAutomationRule
import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictSeverity
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.RuleConflict
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * 자동화 룰 표준 응답 DTO — 목록/단건/PATCH 응답에 공통으로 쓰인다.
 *
 * **웹훅 토큰 원문·해시 어느 쪽도 필드로 두지 않는다**(DEVELOPMENT.md §1 — 평문 토큰 저장/노출 금지,
 * slack `SlackInstallationResponse` 선례 — 타입 상 새어 나갈 수 없게 애초에 필드를 만들지 않는 방어).
 * WEBHOOK 룰인지 여부만 [hasWebhookToken] 으로 노출한다.
 *
 * @property id 룰 식별자.
 * @property projectKey 룰이 속한 프로젝트 키.
 * @property name 룰 표시 이름.
 * @property enabled 활성화 여부.
 * @property triggerType 트리거 타입.
 * @property triggerConfig 트리거별 설정 JSON 문자열.
 * @property actions 발화 시 순차 실행할 액션 목록(FR-AT-02, 실행 순서 그대로).
 * @property actorUserId 액션 실행 주체(rule actor, FR-AT-02).
 * @property condition 트리거 발화 후 액션 실행 여부를 가르는 조건 게이트 표현식(FR-AT-03) JSON 문자열.
 *   [triggerConfig] 와 대칭으로 원본 JSON 텍스트를 그대로 노출한다(재귀 트리라 [ActionResponse.config]
 *   처럼 고정 필드셋 맵으로 분해할 수 없다 — And/Or/Not/Comparison 4개 변형이 각기 다른 모양). 조건이
 *   없으면 `null`.
 * @property hasWebhookToken WEBHOOK 트리거이고 토큰이 발급되어 있으면 true. 원문/해시는 노출하지 않는다.
 * @property nextFireAt SCHEDULED 트리거의 다음 발화 예정 시각. 그 외 타입은 null.
 * @property createdBy 룰을 생성한 사용자 id.
 * @property createdAt 생성 시각.
 * @property updatedAt 마지막 변경 시각.
 * @property version OCC 버전.
 * @property conflicts 저장 직후 검출된 규칙 충돌 목록(FR-AT-04 Task 5). create/patch 응답에만 채워지고,
 *   GET(단건/목록)은 매 조회마다 프로젝트 전체를 재분석하는 비용을 피하려고 항상 `null`이다 — `null`이면
 *   [JsonInclude.Include.NON_NULL] 로 JSON 키 자체를 생략한다(하위호환, 기존 GET 응답 계약 불변).
 */
@Suppress("LongParameterList")
data class AutomationRuleResponse(
    val id: UUID,
    val projectKey: String,
    val name: String,
    val enabled: Boolean,
    val triggerType: TriggerType,
    val triggerConfig: String,
    val actions: List<ActionResponse>,
    val actorUserId: UUID,
    val condition: String?,
    val hasWebhookToken: Boolean,
    val nextFireAt: Instant?,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val conflicts: List<RuleConflictResponse>? = null,
) {
    companion object {
        /**
         * 도메인 [AutomationRule] 을 표준 응답 DTO 로 변환한다(GET 단건/목록 전용 — `conflicts` 는 항상
         * `null`, [RuleConflictAnalyzer][com.bts.automation.application.RuleConflictAnalyzer] 를 호출하지
         * 않는다, 클래스 KDoc §conflicts 참고).
         *
         * [rule.actions] 는 호출자([com.bts.automation.application.AutomationRuleService])가 이미 올바르게
         * 채운 상태여야 한다 — [com.bts.automation.adapter.AutomationRuleRepository] 의 find 계열은 actions
         * 를 로드하지 않는다(Task 6 결정, 클래스 KDoc 참고). [rule.condition] 도 마찬가지로 호출자가
         * [com.bts.automation.adapter.AutomationConditionRepository] 로 이미 채운 상태여야 한다(FR-AT-03
         * Task 8).
         *
         * @param rule 변환할 도메인 애그리거트.
         * @return 토큰 원문/해시를 포함하지 않고 `conflicts` 가 `null`인 응답 DTO.
         */
        fun from(rule: AutomationRule): AutomationRuleResponse = buildResponse(rule, conflicts = null)

        /**
         * 도메인 [AutomationRule] 을 규칙 충돌 목록과 함께 응답 DTO 로 변환한다(create/patch 전용).
         *
         * @param rule 변환할 도메인 애그리거트.
         * @param conflicts 저장 직후 검출된 규칙 충돌 목록(빈 리스트 허용, `null` 은 GET 전용이라 허용하지
         *   않는다 — create/patch 는 항상 리스트를 갖는다).
         * @return `conflicts` 가 채워진 응답 DTO.
         */
        fun from(
            rule: AutomationRule,
            conflicts: List<RuleConflict>,
        ): AutomationRuleResponse = buildResponse(rule, conflicts.map(RuleConflictResponse::from))

        /** [PatchedAutomationRule] 을 응답 DTO 로 변환한다(PATCH 전용, 컨트롤러 호출부는 오버로드 해석으로 그대로 동작한다). */
        fun from(patched: PatchedAutomationRule): AutomationRuleResponse = from(patched.rule, patched.conflicts)

        private fun buildResponse(
            rule: AutomationRule,
            conflicts: List<RuleConflictResponse>?,
        ): AutomationRuleResponse =
            AutomationRuleResponse(
                id = rule.id,
                projectKey = rule.projectKey,
                name = rule.name,
                enabled = rule.enabled,
                triggerType = rule.triggerType,
                triggerConfig = rule.triggerConfig,
                actions = rule.actions.map(ActionResponse::from),
                actorUserId = rule.actorUserId,
                condition = rule.condition?.toJson(),
                hasWebhookToken = rule.webhookTokenHash != null,
                nextFireAt = rule.nextFireAt,
                createdBy = rule.createdBy,
                createdAt = rule.createdAt,
                updatedAt = rule.updatedAt,
                version = rule.version,
                conflicts = conflicts,
            )
    }
}

/**
 * 규칙 충돌 1건의 응답 표현([com.bts.automation.domain.RuleConflict] 대칭, FR-AT-04 Task 5).
 *
 * @property type 충돌 종류.
 * @property severity 충돌 심각도.
 * @property ruleIds 충돌에 관련된 규칙 id 목록.
 * @property detail 충돌 내용을 설명하는 사용자 노출용 한국어 메시지.
 */
data class RuleConflictResponse(
    val type: ConflictType,
    val severity: ConflictSeverity,
    val ruleIds: List<UUID>,
    val detail: String,
) {
    companion object {
        /**
         * 도메인 [RuleConflict] 를 응답 DTO 로 변환한다.
         *
         * @param conflict 변환할 도메인 값 객체.
         * @return 대칭 필드로 구성된 응답 DTO.
         */
        fun from(conflict: RuleConflict): RuleConflictResponse =
            RuleConflictResponse(
                type = conflict.type,
                severity = conflict.severity,
                ruleIds = conflict.ruleIds,
                detail = conflict.detail,
            )
    }
}

/**
 * 액션 1건의 응답 표현 — [com.bts.automation.adapter.web.dto.ActionRequest] 대칭 형태(FR-AT-02).
 *
 * [config] 는 [Action] 서브타입별 필드를 그대로 담은 맵이다(Jackson 이 중첩 객체로 직렬화 —
 * [com.fasterxml.jackson.databind.JsonNode] 값도 그대로 직렬화된다). `type`/`config` 필드 조립 로직은
 * [com.bts.automation.adapter.AutomationActionRepository]/[com.bts.automation.application.ActionExecutor]
 * 의 저장·실행측 매핑과 목적이 달라(HTTP 응답 전용) 별도로 둔다(같은 모듈 내 유사 매핑 중복은 기존
 * `ActionExecutor.actionTypeOf` 선례 동형).
 *
 * @property type 액션 타입.
 * @property config 액션별 설정 값 맵.
 */
data class ActionResponse(
    val type: ActionType,
    val config: Map<String, Any?>,
) {
    companion object {
        /**
         * 도메인 [Action] 을 응답 DTO 로 변환한다.
         *
         * @param action 변환할 도메인 액션.
         * @return 타입 + 설정 맵으로 구성된 응답 DTO.
         */
        fun from(action: Action): ActionResponse {
            return ActionResponse(type = actionTypeOf(action), config = actionConfigOf(action))
        }
    }
}

/** [Action] 서브타입 → [ActionType] 매핑(HTTP 응답 전용). */
private fun actionTypeOf(action: Action): ActionType =
    when (action) {
        is Action.SetFieldAction -> ActionType.SET_FIELD
        is Action.AssignAction -> ActionType.ASSIGN
        is Action.AddCommentAction -> ActionType.ADD_COMMENT
        is Action.CallWebhookAction -> ActionType.CALL_WEBHOOK
    }

/** [Action] 서브타입 → 설정 맵 매핑(HTTP 응답 전용, [ActionRequest.config] 필드명과 대칭). */
private fun actionConfigOf(action: Action): Map<String, Any?> =
    when (action) {
        is Action.SetFieldAction -> mapOf("field" to action.field, "value" to action.value)
        is Action.AssignAction -> mapOf("assigneeId" to action.assigneeId)
        is Action.AddCommentAction -> mapOf("body" to action.body)
        is Action.CallWebhookAction ->
            mapOf(
                "url" to action.url,
                "method" to action.method,
                "headers" to action.headers,
                "body" to action.body,
            )
    }

/**
 * 자동화 룰 생성 응답 DTO — `POST .../rules` 전용. WEBHOOK 트리거면 [webhookToken] 에 원문을 **1회만** 담는다.
 *
 * 이 원문은 이 응답 이후로는 다시 조회할 수 없다(DB 에는 SHA-256 해시만 저장 — spec S7·FR6). WEBHOOK 이
 * 아니거나 토큰 발급이 없으면 [webhookToken] 은 null.
 *
 * @property rule 생성된 룰의 표준 응답(토큰 미포함).
 * @property webhookToken WEBHOOK 트리거 생성 시 발급된 원문 토큰. 그 외에는 null.
 */
data class CreateAutomationRuleResponse(
    val rule: AutomationRuleResponse,
    val webhookToken: String?,
) {
    companion object {
        /**
         * 서비스 결과 [CreatedAutomationRule] 을 생성 응답 DTO 로 변환한다.
         *
         * [created.conflicts](FR-AT-04 Task 5)가 [rule] 응답에 함께 실린다 — [AutomationRuleResponse.from]
         * 의 2-인자 오버로드를 거친다.
         *
         * @param created 생성된 룰 + (WEBHOOK 이면) 발급된 원문 토큰 + 저장 후 검출된 규칙 충돌 목록.
         * @return 원문 토큰을 1회 동봉한 생성 응답 DTO.
         */
        fun from(created: CreatedAutomationRule): CreateAutomationRuleResponse =
            CreateAutomationRuleResponse(
                rule = AutomationRuleResponse.from(created.rule, created.conflicts),
                webhookToken = created.webhookToken,
            )
    }
}

/**
 * GitOps YAML import 응답 DTO — `POST .../rules/import` 전용(FR-AT-06 GitOps Task 5).
 *
 * [webhookTokens] 는 이번 import 로 **새로 생성된** WEBHOOK 룰의 원문 토큰만 담는다(spec FR8 — 갱신된
 * WEBHOOK 룰은 토큰을 재mint 하지 않아 여기 담기지 않는다, [CreateAutomationRuleResponse.webhookToken] 1회
 * 노출 시맨틱 승계). 새로 생성된 WEBHOOK 룰이 하나도 없으면 `null` 로 두어([JsonInclude.Include.NON_NULL])
 * JSON 응답에서 키 자체가 생략된다 — `[]`(빈 배열)와 `null`(해당 없음)을 구분한다.
 *
 * [conflicts] 는 [com.bts.automation.adapter.web.AutomationRuleController.import] 가 저장 트랜잭션
 * **커밋 후** 별도로 호출한
 * [com.bts.automation.application.AutomationRuleService.analyzeProjectConflicts] 결과를 그대로 담는다
 * ([AutomationRuleResponse.conflicts] 와 달리 이 필드는 항상 결과 리스트를 받는다 — 빈 리스트여도
 * `[]`로 노출되고, `null`이 되는 경우는 없다. `@JsonInclude(NON_NULL)`은 [AutomationRuleResponse.conflicts]
 * 와 동일 어노테이션을 재사용한 것뿐이다).
 *
 * @property created 새로 생성된 규칙 수.
 * @property updated 갱신된 규칙 수.
 * @property total [created] + [updated](입력 커맨드 총 수와 같다).
 * @property ruleIds [com.bts.automation.gitops.ImportRuleCommand] 입력 순서를 보존한 규칙 id 목록.
 * @property webhookTokens 새로 생성된 WEBHOOK 룰의 1회 노출 원문 토큰 목록. 해당 없으면 `null`.
 * @property conflicts 커밋 후 검출된 규칙 충돌 목록.
 */
data class AutomationImportResponse(
    val created: Int,
    val updated: Int,
    val total: Int,
    val ruleIds: List<UUID>,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val webhookTokens: List<ImportedWebhookTokenResponse>? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val conflicts: List<RuleConflictResponse>? = null,
) {
    companion object {
        /**
         * 서비스 결과 [ImportOutcome] + 커밋 후 분석한 [conflicts] 를 import 응답 DTO 로 변환한다.
         *
         * @param outcome [com.bts.automation.application.AutomationRuleService.importRules] 결과.
         * @param conflicts 커밋 후 [com.bts.automation.application.AutomationRuleService.analyzeProjectConflicts]
         *   호출 결과.
         * @return created/updated/total/ruleIds + (있으면) webhookTokens + conflicts 로 구성된 응답 DTO.
         */
        fun from(
            outcome: ImportOutcome,
            conflicts: List<RuleConflict>,
        ): AutomationImportResponse =
            AutomationImportResponse(
                created = outcome.created,
                updated = outcome.updated,
                total = outcome.created + outcome.updated,
                ruleIds = outcome.ruleIds,
                webhookTokens = outcome.webhookTokens.ifEmpty { null }?.map(ImportedWebhookTokenResponse::from),
                conflicts = conflicts.map(RuleConflictResponse::from),
            )
    }
}

/**
 * [AutomationImportResponse.webhookTokens] 1건 — 새로 생성된 WEBHOOK 룰의 id·이름·1회 노출 원문 토큰
 * ([ImportedWebhookToken] 대칭, FR-AT-06 GitOps Task 5).
 *
 * @property ruleId 생성된 룰 id.
 * @property name 생성된 룰 이름(호출자가 어느 룰의 토큰인지 식별하기 위한 표시용).
 * @property token 발급된 원문 토큰(1회 노출, 이후 재조회 불가).
 */
data class ImportedWebhookTokenResponse(
    val ruleId: UUID,
    val name: String,
    val token: String,
) {
    companion object {
        /**
         * 도메인 [ImportedWebhookToken] 을 응답 DTO 로 변환한다.
         *
         * @param token 변환할 import 결과 값 객체.
         * @return 대칭 필드로 구성된 응답 DTO.
         */
        fun from(token: ImportedWebhookToken): ImportedWebhookTokenResponse =
            ImportedWebhookTokenResponse(ruleId = token.ruleId, name = token.name, token = token.token)
    }
}
