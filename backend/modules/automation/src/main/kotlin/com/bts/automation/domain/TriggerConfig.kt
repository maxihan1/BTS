// 트리거별 triggerConfig JSON 형식 검증 — 대상 존재/권한은 검증하지 않는다(형식만)

package com.bts.automation.domain

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.scheduling.support.CronExpression

/**
 * 트리거 타입별 `triggerConfig` JSON 형식을 검증한다.
 *
 * 검증 대상은 형식(cron 파싱 가능 여부, fields 배열 여부 등)뿐이며, config 가 가리키는 대상의
 * 존재/권한은 검증하지 않는다(favorites·가젯 카탈로그 선례, ADR D1 §트리거 도메인 모델).
 *
 * - [TriggerType.SCHEDULED] — `cron` 필드 필수 + Spring [CronExpression] 으로 파싱 가능해야 함.
 * - [TriggerType.ISSUE_UPDATED] — `fields` 필드는 선택. 있으면 비어있지 않은 문자열의 배열이어야 함.
 * - [TriggerType.PR_MERGED] — `targetBranch` 필드는 선택(미지정 시 전체 브랜치 발화). 있으면
 *   비어있지 않은 문자열이어야 함.
 * - [TriggerType.ISSUE_TRANSITIONED] — `fromStates`/`toStates` 필드는 선택(상태 키 배열). 있으면 비어있지
 *   않은 문자열의 배열이어야 함. 비우면 모든 전환에 발화.
 * - [TriggerType.ISSUE_CREATED]/[TriggerType.ISSUE_COMMENTED]/[TriggerType.WEBHOOK]/
 *   [TriggerType.ISSUE_ASSIGNED]/[TriggerType.MANUAL] — 빈 config(`{}`) 허용, 추가 형식 검증 없음.
 */
object TriggerConfig {
    /** 빈 config 를 나타내는 기본 JSON 문자열. */
    const val EMPTY: String = "{}"

    private const val FIELD_CRON = "cron"
    private const val FIELD_FIELDS = "fields"
    private const val FIELD_TARGET_BRANCH = "targetBranch"
    const val FIELD_FROM_STATES = "fromStates"
    const val FIELD_TO_STATES = "toStates"
    private const val MSG_INVALID_JSON = "triggerConfig는 유효한 JSON 객체여야 합니다."

    private val objectMapper = ObjectMapper()

    /**
     * `triggerType` 에 맞는 형식으로 `configJson` 을 검증한다.
     *
     * @param triggerType 검증 기준이 되는 트리거 타입
     * @param configJson 검증할 triggerConfig JSON 문자열
     * @throws TriggerConfigInvalidException 형식을 위반한 경우
     */
    fun validate(
        triggerType: TriggerType,
        configJson: String,
    ) {
        val node = parseJsonObject(configJson)
        when (triggerType) {
            TriggerType.SCHEDULED -> validateScheduled(node)
            TriggerType.ISSUE_UPDATED -> validateStringArray(node, FIELD_FIELDS, "ISSUE_UPDATED")
            TriggerType.ISSUE_TRANSITIONED -> {
                validateStringArray(node, FIELD_FROM_STATES, "ISSUE_TRANSITIONED")
                validateStringArray(node, FIELD_TO_STATES, "ISSUE_TRANSITIONED")
            }
            TriggerType.PR_MERGED -> validatePrMerged(node)
            TriggerType.ISSUE_CREATED,
            TriggerType.ISSUE_COMMENTED,
            TriggerType.WEBHOOK,
            TriggerType.ISSUE_ASSIGNED,
            TriggerType.MANUAL,
            -> Unit
        }
    }

    private fun parseJsonObject(configJson: String): JsonNode {
        val node = readJson(configJson)
        if (!node.isObject) {
            throw TriggerConfigInvalidException(MSG_INVALID_JSON)
        }
        return node
    }

    private fun readJson(configJson: String): JsonNode {
        if (configJson.isBlank()) {
            throw TriggerConfigInvalidException(MSG_INVALID_JSON)
        }
        return try {
            objectMapper.readTree(configJson)
        } catch (e: JsonParseException) {
            throw TriggerConfigInvalidException(MSG_INVALID_JSON, e)
        }
    }

    private fun validateScheduled(node: JsonNode) {
        val cronNode = node.get(FIELD_CRON)
        if (cronNode == null || !cronNode.isTextual || cronNode.asText().isBlank()) {
            throw TriggerConfigInvalidException("SCHEDULED 트리거는 cron 필드가 필요합니다.")
        }
        val cron = cronNode.asText()
        try {
            CronExpression.parse(cron)
        } catch (e: IllegalArgumentException) {
            throw TriggerConfigInvalidException("cron 표현식을 파싱할 수 없습니다: $cron", e)
        }
    }

    /** [fieldName] 이 있으면 비어있지 않은 문자열 배열인지 본다(없음 · null = 통과). [type] 은 메시지용. */
    private fun validateStringArray(
        node: JsonNode,
        fieldName: String,
        type: String,
    ) {
        val arrayNode = node.get(fieldName) ?: return
        if (arrayNode.isNull) return
        if (!arrayNode.isArray) {
            throw TriggerConfigInvalidException("$type 트리거의 ${fieldName}는 배열이어야 합니다.")
        }
        arrayNode.forEach { item ->
            if (!item.isTextual || item.asText().isBlank()) {
                throw TriggerConfigInvalidException(
                    "$type 트리거의 $fieldName 항목은 빈 문자열이 아닌 문자열이어야 합니다.",
                )
            }
        }
    }

    private fun validatePrMerged(node: JsonNode) {
        val targetBranchNode = node.get(FIELD_TARGET_BRANCH) ?: return
        if (targetBranchNode.isNull) return
        if (!targetBranchNode.isTextual || targetBranchNode.asText().isBlank()) {
            throw TriggerConfigInvalidException(
                "PR_MERGED 트리거의 targetBranch는 빈 문자열이 아닌 문자열이어야 합니다.",
            )
        }
    }
}
