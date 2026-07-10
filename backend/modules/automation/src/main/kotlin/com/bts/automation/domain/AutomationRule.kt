// AutomationRule Aggregate Root — 자동화 룰의 트리거 정의·불변식·OCC 버전 관리

package com.bts.automation.domain

import java.time.Instant
import java.util.UUID

/**
 * 자동화 룰(AutomationRule) Aggregate Root.
 *
 * FR-AT-01 범위에서는 트리거만 보유한다(조건/액션은 FR-AT-02/03이 후행 컬럼으로 추가한다).
 * 모든 필드는 `val` 로 선언해 한 번 생성된 이후 외부에서 직접 변경할 수 없으며, [create] 팩토리와
 * 동작 메서드([enable]/[disable]/[rename]/[updateConfig])를 통해서만 새 인스턴스를 얻는다.
 *
 * triggerConfig 형식 검증은 [TriggerConfig.validate] 에 위임한다(대상 존재/권한 검증은 하지 않음 —
 * FR2). webhookTokenHash 발급(토큰 생성·해시)과 nextFireAt 최초 계산은 이 애그리거트 밖의
 * 서비스 계층 책임이다(Task 6/8 scope).
 *
 * @property id 룰 식별자
 * @property projectKey 룰이 속한 프로젝트 키
 * @property name 룰 표시 이름 (1~[MAX_NAME_LENGTH]자)
 * @property enabled 활성화 여부. `false` 면 모든 트리거 감지 경로에서 매칭 대상 제외(EC7)
 * @property triggerType 트리거 타입 5종([TriggerType]) 중 하나
 * @property triggerConfig 트리거별 설정 JSON 문자열([TriggerConfig.validate] 로 형식 검증됨)
 * @property webhookTokenHash WEBHOOK 트리거의 토큰 해시. WEBHOOK 이 아니면 보통 `null`
 * @property nextFireAt SCHEDULED 트리거의 다음 발화 예정 시각. SCHEDULED 가 아니면 보통 `null`
 * @property createdBy 룰을 생성한 사용자 ID
 * @property createdAt 생성 시각
 * @property updatedAt 마지막 변경 시각
 * @property deletedAt 소프트 삭제 시각. `null` 이면 활성 상태
 * @property version OCC(낙관적 동시성 제어) 버전. 변경 동작 호출마다 +1
 */
data class AutomationRule(
    val id: UUID,
    val projectKey: String,
    val name: String,
    val enabled: Boolean,
    val triggerType: TriggerType,
    val triggerConfig: String,
    val webhookTokenHash: String?,
    val nextFireAt: Instant?,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val version: Long,
) {
    companion object {
        /** [name] 최대 허용 글자 수. */
        const val MAX_NAME_LENGTH: Int = 200

        /**
         * 검증을 거쳐 신규 [AutomationRule] 을 생성하는 팩토리 메서드.
         *
         * @param projectKey 룰이 속한 프로젝트 키
         * @param name 룰 표시 이름
         * @param triggerType 트리거 타입
         * @param triggerConfig 트리거별 설정 JSON 문자열. 기본값은 빈 객체([TriggerConfig.EMPTY])
         * @param createdBy 룰을 생성하는 사용자 ID
         * @param webhookTokenHash WEBHOOK 트리거 토큰 해시. 기본값 `null`
         * @param nextFireAt SCHEDULED 트리거의 최초 발화 예정 시각. 기본값 `null`
         * @param now 생성 시각(호출자 Clock 에서 주입)
         * @return 불변식이 검증된 신규 [AutomationRule] 인스턴스(enabled=true, version=0)
         * @throws AutomationRuleInvalidException projectKey·name 이 불변식을 위반한 경우
         * @throws TriggerConfigInvalidException triggerConfig 가 triggerType 형식을 위반한 경우
         */
        @Suppress("LongParameterList")
        fun create(
            projectKey: String,
            name: String,
            triggerType: TriggerType,
            triggerConfig: String = TriggerConfig.EMPTY,
            createdBy: UUID,
            webhookTokenHash: String? = null,
            nextFireAt: Instant? = null,
            now: Instant,
        ): AutomationRule {
            validateProjectKey(projectKey)
            validateName(name)
            TriggerConfig.validate(triggerType, triggerConfig)
            return AutomationRule(
                id = UUID.randomUUID(),
                projectKey = projectKey,
                name = name,
                enabled = true,
                triggerType = triggerType,
                triggerConfig = triggerConfig,
                webhookTokenHash = webhookTokenHash,
                nextFireAt = nextFireAt,
                createdBy = createdBy,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
                version = 0L,
            )
        }

        private fun validateProjectKey(projectKey: String) {
            if (projectKey.isBlank()) {
                throw AutomationRuleInvalidException("projectKey는 빈 문자열일 수 없습니다.")
            }
        }

        private fun validateName(name: String) {
            if (name.isBlank()) {
                throw AutomationRuleInvalidException("룰 이름은 빈 문자열 또는 공백일 수 없습니다.")
            }
            if (name.length > MAX_NAME_LENGTH) {
                throw AutomationRuleInvalidException(
                    "룰 이름은 ${MAX_NAME_LENGTH}자 이하여야 합니다. 현재: ${name.length}자",
                )
            }
        }
    }

    /**
     * 룰을 활성화한 새 인스턴스를 반환한다(OCC 버전 +1).
     *
     * @param now 변경 시각
     * @return `enabled=true`, `version+1` 인 새 [AutomationRule] 인스턴스
     */
    fun enable(now: Instant): AutomationRule = copy(enabled = true, updatedAt = now, version = version + 1)

    /**
     * 룰을 비활성화한 새 인스턴스를 반환한다(OCC 버전 +1).
     *
     * @param now 변경 시각
     * @return `enabled=false`, `version+1` 인 새 [AutomationRule] 인스턴스
     */
    fun disable(now: Instant): AutomationRule = copy(enabled = false, updatedAt = now, version = version + 1)

    /**
     * 룰 이름을 변경한 새 인스턴스를 반환한다(OCC 버전 +1).
     *
     * @param newName 변경할 이름
     * @param now 변경 시각
     * @return `name` 이 교체되고 `version+1` 인 새 [AutomationRule] 인스턴스
     * @throws AutomationRuleInvalidException newName 이 불변식을 위반한 경우
     */
    fun rename(
        newName: String,
        now: Instant,
    ): AutomationRule {
        validateName(newName)
        return copy(name = newName, updatedAt = now, version = version + 1)
    }

    /**
     * triggerConfig 를 교체한 새 인스턴스를 반환한다(OCC 버전 +1).
     *
     * 새 triggerConfig 는 이 룰의 [triggerType] 형식으로 검증된다 — triggerType 자체는 변경되지
     * 않는다(트리거 타입 변경은 스펙 범위 밖. 필요하면 룰을 새로 생성한다).
     *
     * @param newTriggerConfig 교체할 triggerConfig JSON 문자열
     * @param now 변경 시각
     * @return `triggerConfig` 가 교체되고 `version+1` 인 새 [AutomationRule] 인스턴스
     * @throws TriggerConfigInvalidException newTriggerConfig 가 triggerType 형식을 위반한 경우
     */
    fun updateConfig(
        newTriggerConfig: String,
        now: Instant,
    ): AutomationRule {
        TriggerConfig.validate(triggerType, newTriggerConfig)
        return copy(triggerConfig = newTriggerConfig, updatedAt = now, version = version + 1)
    }
}
