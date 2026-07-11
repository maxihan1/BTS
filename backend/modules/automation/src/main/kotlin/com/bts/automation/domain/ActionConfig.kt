// ActionType 별 action_config JSON 형식 검증 — 대상 존재/권한은 검증하지 않는다(형식만)

package com.bts.automation.domain

/**
 * 액션 타입별 `action_config` JSON 형식을 검증한다.
 *
 * 실제 파싱/검증 로직은 [Action.fromJson] 에 위임하고 결과만 버린다(중복 방지). 대상 존재/권한
 * (assigneeId 실존 등)은 검증하지 않는다(형식만 — [TriggerConfig] 선례 동형).
 */
object ActionConfig {
    /**
     * `actionType` 에 맞는 형식으로 `configJson` 을 검증한다.
     *
     * @param actionType 검증 기준이 되는 액션 타입
     * @param configJson 검증할 action_config JSON 문자열
     * @throws ActionConfigInvalidException 형식을 위반한 경우
     */
    fun validate(
        actionType: ActionType,
        configJson: String,
    ) {
        Action.fromJson(actionType, configJson)
    }
}

/**
 * `action_config` 가 `actionType` 이 요구하는 형식에 맞지 않을 때.
 *
 * 대상 존재/권한은 검증하지 않는다(형식만 검증 — [TriggerConfigInvalidException] 선례 동형). HTTP 400 으로
 * 매핑된다.
 *
 * @param message 위반 내용을 설명하는 메시지
 * @param cause JSON 파싱 실패 등 원인이 된 예외. 없으면 `null`(기본값)
 */
class ActionConfigInvalidException(message: String, cause: Throwable? = null) :
    AutomationDomainException(message, cause)
