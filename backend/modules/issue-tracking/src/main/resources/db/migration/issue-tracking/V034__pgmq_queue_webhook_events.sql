-- issue-tracking 모듈 pgmq 큐 — q_webhook_events. 구독형 아웃바운드 Webhook 발송용 dual-send 전용 큐.
--
-- 목적 (FR-API-03 PR3 — Webhook 발송 실행 계층):
--   IssueEventPublisher.publish() 가 PUBLISHABLE 이벤트(issue.created / issue.transitioned) 발행 시
--   기존 q_issue_events 뿐 아니라 이 큐에도 동일 이벤트를 dual-send 한다.
--   q_issue_events 는 NotificationWorker 가 독점 소비하므로, 경합 없이 별도 소비 흐름을 두기 위해
--   전용 큐로 분리했다(ADR 2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse).
--
-- 컨슈머: search-export-import 모듈의 Webhook 발송 워커(@Scheduled 폴링, 본 마이그레이션 범위 외).
--         큐 소비 → 구독(outbound_webhooks) event_filter 매칭 → HMAC 서명 발송 → webhook_deliveries 이력 기록.
--
-- enqueue 방식: pgmq.send(queue_name, payload::jsonb) — DATA.md §7.2 트랜잭션 발행 규칙 준수.

-- pgmq extension 보장 — V002 에서 이미 적용됐으나 모듈별 Flyway namespace 라 명시
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_webhook_events
SELECT pgmq.create('q_webhook_events');
