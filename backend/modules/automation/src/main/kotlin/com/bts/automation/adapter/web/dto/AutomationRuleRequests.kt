// AutomationRuleController 요청 DTO — 룰 생성/수정 바디 + 액션 리스트 + 조건 게이트 (FR-AT-03 Task 8)

package com.bts.automation.adapter.web.dto

import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import java.util.UUID

/**
 * 자동화 룰 생성 요청 DTO.
 *
 * spec API 표 `POST /api/v1/projects/{projectKey}/automation/rules` 요청 바디.
 *
 * name·triggerConfig·action 형식 검증은 도메인 계층([com.bts.automation.domain.AutomationRule.create]·
 * [TriggerConfig.validate]·[com.bts.automation.domain.ActionConfig.validate])에서 수행한다. automation
 * 모듈은 Bean Validation provider(`spring-boot-starter-validation`)가 없으므로 `@field:NotBlank` 류
 * 어노테이션은 무동작이다(notification `FavoriteDtos` 선례 — 도메인 검증이 1차이자 유일한 방어선).
 *
 * @property name 룰 표시 이름.
 * @property triggerType 트리거 타입 5종 중 하나.
 * @property triggerConfig 트리거별 설정 JSON 문자열. 기본값은 빈 객체([TriggerConfig.EMPTY]).
 * @property actions 발화 시 순차 실행할 액션 목록(FR-AT-02). 기본값은 빈 리스트(트리거만 있는 룰도 유효).
 * @property actorUserId 액션 실행 주체(rule actor). 기본값 `null` — 서비스가 생성 요청자(actor)로 폴백한다.
 *   actor 변경 UI 는 D6 후속(spec FR5) — 이 필드는 **생성 시점** 초기값 지정용이다.
 * @property condition 트리거 발화 후 액션 실행 여부를 가르는 조건 게이트 표현식(FR-AT-03) JSON 문자열.
 *   [triggerConfig]/[ActionRequest.config] 와 동일하게 원본 JSON 텍스트로 받는다(파싱은
 *   [com.bts.automation.domain.Condition.fromJson] 이 서비스 계층에서 수행). 기본값 `null` — 조건 없이
 *   항상 통과.
 */
data class CreateAutomationRuleRequest(
    val name: String,
    val triggerType: TriggerType,
    val triggerConfig: String = TriggerConfig.EMPTY,
    val actions: List<ActionRequest> = emptyList(),
    val actorUserId: UUID? = null,
    val condition: String? = null,
)

/**
 * 자동화 룰 부분 수정(PATCH) 요청 DTO.
 *
 * spec API 표 `PATCH /api/v1/projects/{projectKey}/automation/rules/{id}` 요청 바디 — name·enabled·
 * triggerConfig·actions 를 개별 선택적으로 변경한다(null 필드는 미변경).
 *
 * [version] 은 OCC(낙관적 동시성 제어) 클라이언트 기대 버전이다 — 서버가 로드한 현재 버전과 다르면
 * [com.bts.automation.application.AutomationRuleVersionConflictException] 으로 409 를 반환한다.
 *
 * actor(실행 주체) 변경은 [actorUserId] 로 지원한다(FR-AT-02 Task 14, spec FR5 "룰 편집에서 프로젝트
 * 사용자로 변경 가능"). 변경 UI 는 D6 후속이고 이번 필드는 백엔드 지원(도메인
 * [com.bts.automation.domain.AutomationRule.changeActor] 경유)만 완결한다.
 *
 * @property version 클라이언트가 마지막으로 읽은 룰의 version. 서버의 현재 version 과 다르면 409.
 * @property name 변경할 이름. null 이면 미변경.
 * @property enabled 변경할 활성화 여부. null 이면 미변경.
 * @property triggerConfig 변경할 triggerConfig JSON 문자열. null 이면 미변경. 형식 위반 시 400.
 * @property actions 교체할 액션 목록(전체 교체, 부분 병합 아님). null 이면 미변경.
 * @property actorUserId 변경할 액션 실행 주체(rule actor). null 이면 미변경.
 * @property condition 교체할 조건 게이트 표현식(FR-AT-03) JSON 문자열. null 이면 미변경(기존 [name]/
 *   [triggerConfig]/[actorUserId] 와 동일한 "null=미변경" 부분 PATCH 관례). 조건을 완전히 제거하는
 *   전용 연산은 이 태스크 범위 밖이다(필요 시 후속 3-state 설계 검토).
 */
data class PatchAutomationRuleRequest(
    val version: Long,
    val name: String? = null,
    val enabled: Boolean? = null,
    val triggerConfig: String? = null,
    val actions: List<ActionRequest>? = null,
    val actorUserId: UUID? = null,
    val condition: String? = null,
)

/**
 * 액션 1건의 요청 표현 — `triggerType`/`triggerConfig` 쌍(트리거 표현)을 그대로 미러링한다(FR-AT-02).
 *
 * [type] 은 [com.bts.automation.domain.ActionType] 4종 중 하나의 이름 문자열이어야 한다. 잘못된 값은
 * 서비스가 [com.bts.automation.domain.ActionConfigInvalidException](400)으로 변환한다 — Jackson enum
 * 역직렬화 실패(그 경우 500/malformed-request 로 분류가 모호해짐)에 맡기지 않기 위해 의도적으로
 * `String` 으로 받는다.
 *
 * @property type 액션 타입 이름 문자열(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK).
 * @property config 액션별 설정 JSON 문자열. 기본값은 빈 객체(`{}`) — 필수 필드 누락이면 도메인이 400 을 던진다.
 */
data class ActionRequest(
    val type: String,
    val config: String = "{}",
)
