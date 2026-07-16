// 자동화 액션 타입 5종 — 필드 설정/담당자 지정/댓글 추가/웹훅 호출/수정 예정 버전 설정

package com.bts.automation.domain

/**
 * 자동화 룰([AutomationRule])이 발화 시 실행하는 액션 타입.
 *
 * `automation_actions.action_type` DB CHECK 제약(V302 4종 + V306 확장, FR-AT-07)과 정확히 일치해야
 * 한다 — 앱(enum)과 DB 레벨 이중 방어([TriggerType] 선례 동형).
 *
 * - [SET_FIELD] — 이슈 필드 값 설정
 * - [ASSIGN] — 담당자 지정/해제
 * - [ADD_COMMENT] — 댓글 추가
 * - [CALL_WEBHOOK] — 아웃바운드 웹훅 호출
 * - [SET_FIX_VERSIONS] — 이슈 수정 예정 버전(Fix Version) 설정
 */
enum class ActionType {
    SET_FIELD,
    ASSIGN,
    ADD_COMMENT,
    CALL_WEBHOOK,
    SET_FIX_VERSIONS,
}
