-- issue-tracking 모듈 pgmq 큐 — q_webhook_events. Webhook 아웃바운드 발송 dual-send 전용 큐 (FR-API-03 PR3).

-- pgmq extension 보장 — V002 에서 이미 적용됐으나 모듈별 Flyway namespace 라 명시
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_webhook_events
SELECT pgmq.create('q_webhook_events');
