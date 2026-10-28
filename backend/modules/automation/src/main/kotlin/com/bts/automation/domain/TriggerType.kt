// 자동화 트리거 타입 9종 — 이슈 이벤트 5종 + 스케줄 + 웹훅 + PR 머지 + 수동 실행

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
 * - [PR_MERGED] — GitHub/GitLab PR 머지 웹훅(FR-AT-07 PR-C). **제3의 경로**다 — [ISSUE_CREATED]/
 *   [ISSUE_UPDATED]/[ISSUE_COMMENTED]처럼 `q_automation_events`로 fan-out 되지 않는다(issue-tracking
 *   은 PR 머지를 알 수 없어 이 큐에 발행할 수 없다). [WEBHOOK]처럼 룰별 불투명 토큰 엔드포인트를 갖지도
 *   않는다. 대신 프로젝트 단위 Git 웹훅(`GitWebhookController`, Task 9/10)이 서명 검증 후 룰을 직접
 *   조회해 동기적으로 enqueue한다 — `TriggerMatcher`의 이슈 이벤트 wire 매핑 대상이 아니다.
 * - [ISSUE_TRANSITIONED]/[ISSUE_ASSIGNED] — `issue.transitioned`/`issue.assigned` fan-out(R9 · JAU-5).
 *   전환은 triggerConfig `fromStates`/`toStates` 로 거르고, 관리자 상태 이관(`cause` 있음)은 발화하지 않는다.
 * - [MANUAL] — 이슈 화면에서 사람이 골라 실행(`ManualRunController`, R9). 큐 fan-out 대상이 아니다.
 *
 * 스펙 `docs/specs/2026-10-28-r9-automation-catalog.md`.
 */
enum class TriggerType {
    ISSUE_CREATED,
    ISSUE_UPDATED,
    ISSUE_COMMENTED,
    SCHEDULED,
    WEBHOOK,
    PR_MERGED,
    ISSUE_TRANSITIONED,
    ISSUE_ASSIGNED,
    MANUAL,
}
