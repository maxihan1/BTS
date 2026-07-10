// AutomationRuleController 요청 DTO — 룰 생성/부분수정(PATCH) 요청 바디 (FR-AT-01 Task 6)

package com.bts.automation.adapter.web.dto

import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType

/**
 * 자동화 룰 생성 요청 DTO.
 *
 * spec API 표 `POST /api/v1/projects/{projectKey}/automation/rules` 요청 바디.
 *
 * name·triggerConfig 형식 검증은 도메인 계층([com.bts.automation.domain.AutomationRule.create]·
 * [TriggerConfig.validate])에서 수행한다. automation 모듈은 Bean Validation provider
 * (`spring-boot-starter-validation`)가 없으므로 `@field:NotBlank` 류 어노테이션은 무동작이다
 * (notification `FavoriteDtos` 선례 — 도메인 검증이 1차이자 유일한 방어선).
 *
 * @property name 룰 표시 이름.
 * @property triggerType 트리거 타입 5종 중 하나.
 * @property triggerConfig 트리거별 설정 JSON 문자열. 기본값은 빈 객체([TriggerConfig.EMPTY]).
 */
data class CreateAutomationRuleRequest(
    val name: String,
    val triggerType: TriggerType,
    val triggerConfig: String = TriggerConfig.EMPTY,
)

/**
 * 자동화 룰 부분 수정(PATCH) 요청 DTO.
 *
 * spec API 표 `PATCH /api/v1/projects/{projectKey}/automation/rules/{id}` 요청 바디 — name·enabled·
 * triggerConfig 를 개별 선택적으로 변경한다(null 필드는 미변경).
 *
 * [version] 은 OCC(낙관적 동시성 제어) 클라이언트 기대 버전이다 — 서버가 로드한 현재 버전과 다르면
 * [com.bts.automation.application.AutomationRuleVersionConflictException] 으로 409 를 반환한다.
 *
 * @property version 클라이언트가 마지막으로 읽은 룰의 version. 서버의 현재 version 과 다르면 409.
 * @property name 변경할 이름. null 이면 미변경.
 * @property enabled 변경할 활성화 여부. null 이면 미변경.
 * @property triggerConfig 변경할 triggerConfig JSON 문자열. null 이면 미변경. 형식 위반 시 400.
 */
data class PatchAutomationRuleRequest(
    val version: Long,
    val name: String? = null,
    val enabled: Boolean? = null,
    val triggerConfig: String? = null,
)
