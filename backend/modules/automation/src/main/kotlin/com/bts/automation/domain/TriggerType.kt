// 자동화 트리거 타입 5종 — 이슈 이벤트 3종 + 스케줄 + 웹훅

package com.bts.automation.domain

/**
 * 자동화 룰([AutomationRule])이 감지하는 트리거 타입.
 *
 * 조건(FR-AT-03)·액션(FR-AT-02)은 이 enum 범위 밖이며, 이 값은 "무엇이 룰을 발화시키는가"만 나타낸다.
 *
 * - [ISSUE_CREATED]/[ISSUE_UPDATED]/[ISSUE_COMMENTED] — issue-tracking 도메인 이벤트
 *   fan-out(`q_automation_events`) 감지(`AutomationEventWorker`, FR-AT-01 Task 7).
 * - [SCHEDULED] — cron 기반 폴링(`AutomationScheduleWorker`, Task 8).
 * - [WEBHOOK] — 불투명 토큰 인바운드 HTTP 엔드포인트(Task 9).
 */
enum class TriggerType {
    ISSUE_CREATED,
    ISSUE_UPDATED,
    ISSUE_COMMENTED,
    SCHEDULED,
    WEBHOOK,
}
