// 자동화 액션 타입 4종 — 필드 설정/담당자 지정/댓글 추가/웹훅 호출

package com.bts.automation.domain

/**
 * 자동화 룰([AutomationRule])이 발화 시 실행하는 액션 타입.
 *
 * `automation_actions.action_type` DB CHECK 제약(V302, ADR D1)과 정확히 일치해야 한다 — 앱(enum)과
 * DB 레벨 이중 방어([TriggerType] 선례 동형).
 *
 * - [SET_FIELD] — 이슈 필드 값 설정
 * - [ASSIGN] — 담당자 지정/해제
 * - [ADD_COMMENT] — 댓글 추가
 * - [CALL_WEBHOOK] — 아웃바운드 웹훅 호출
 */
enum class ActionType {
    SET_FIELD,
    ASSIGN,
    ADD_COMMENT,
    CALL_WEBHOOK,
}
