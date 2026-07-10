// AutomationRuleController 응답 DTO — 표준 룰 응답(토큰 미노출) + 생성 시 1회 웹훅 토큰 동봉 응답 (FR-AT-01 Task 6)

package com.bts.automation.adapter.web.dto

import com.bts.automation.application.CreatedAutomationRule
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
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
 * @property hasWebhookToken WEBHOOK 트리거이고 토큰이 발급되어 있으면 true. 원문/해시는 노출하지 않는다.
 * @property nextFireAt SCHEDULED 트리거의 다음 발화 예정 시각. 그 외 타입은 null.
 * @property createdBy 룰을 생성한 사용자 id.
 * @property createdAt 생성 시각.
 * @property updatedAt 마지막 변경 시각.
 * @property version OCC 버전.
 */
data class AutomationRuleResponse(
    val id: UUID,
    val projectKey: String,
    val name: String,
    val enabled: Boolean,
    val triggerType: TriggerType,
    val triggerConfig: String,
    val hasWebhookToken: Boolean,
    val nextFireAt: Instant?,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    companion object {
        /**
         * 도메인 [AutomationRule] 을 표준 응답 DTO 로 변환한다.
         *
         * @param rule 변환할 도메인 애그리거트.
         * @return 토큰 원문/해시를 포함하지 않는 응답 DTO.
         */
        fun from(rule: AutomationRule): AutomationRuleResponse =
            AutomationRuleResponse(
                id = rule.id,
                projectKey = rule.projectKey,
                name = rule.name,
                enabled = rule.enabled,
                triggerType = rule.triggerType,
                triggerConfig = rule.triggerConfig,
                hasWebhookToken = rule.webhookTokenHash != null,
                nextFireAt = rule.nextFireAt,
                createdBy = rule.createdBy,
                createdAt = rule.createdAt,
                updatedAt = rule.updatedAt,
                version = rule.version,
            )
    }
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
         * @param created 생성된 룰 + (WEBHOOK 이면) 발급된 원문 토큰.
         * @return 원문 토큰을 1회 동봉한 생성 응답 DTO.
         */
        fun from(created: CreatedAutomationRule): CreateAutomationRuleResponse =
            CreateAutomationRuleResponse(
                rule = AutomationRuleResponse.from(created.rule),
                webhookToken = created.webhookToken,
            )
    }
}
