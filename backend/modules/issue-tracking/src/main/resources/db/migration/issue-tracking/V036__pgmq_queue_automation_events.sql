-- issue-tracking 모듈 pgmq 큐 — q_automation_events. automation BC 트리거 감지 fan-out 전용 큐.
--
-- ★ V번호 머지직전 재확인 필요 — 동시 브랜치가 issue-tracking V036 을 선점했으면 다음 free 번호로 조정
--   ([[migration-vnumber-concurrent-branch-collision]]).
--
-- 목적 (FR-AT-01 — automation BC 트리거 감지, ADR 2026-07-10-fr-at-01-automation-triggers D2):
--   IssueEventPublisher.publish() 가 automation 관심 이벤트(issue.created / issue.updated /
--   issue.commented) 발행 시 기존 q_issue_events 뿐 아니라 이 큐에도 동일 이벤트를 fan-out 한다.
--   q_issue_events 는 NotificationWorker 가 독점 소비하는 경쟁소비(competing-consumer) 큐이므로,
--   automation 소비자가 직접 읽으면 알림 메시지를 훔쳐가는 사고가 난다 — 전용 큐로 분리한다
--   (q_webhook_events 선례, FR-API-03).
--
-- 컨슈머: automation 모듈의 AutomationEventWorker(@Scheduled 폴링, 본 마이그레이션 범위 외).
--         큐 소비 → 트리거 매칭(TriggerMatcher) → 매칭 룰 → q_automation_execution 큐 enqueue.
--
-- 큐 소유권: 생산자(issue-tracking)가 소유 — q_webhook_events 선례 준거(리뷰 E1). automation 소유
--   q_automation_execution(V300대)과는 별개 큐다.
--
-- enqueue 방식: pgmq.send(queue_name, payload::jsonb) — DATA.md §7.2 트랜잭션 발행 규칙 준수.

-- pgmq extension 보장 — V002 에서 이미 적용됐으나 모듈별 Flyway namespace 라 명시
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_automation_events
SELECT pgmq.create('q_automation_events');
