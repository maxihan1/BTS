-- Slack 알림 전송 큐 + SLACK 채널 정책 시드 (FR-SL-02, SDD §9.4) — 멘션/할당 이벤트를 q_slack_deliveries로 발행

-- pgmq extension 보장 (V002 issue-tracking 에서 이미 적용됐으나 모듈별 Flyway namespace 라 멱등 재확인)
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_slack_deliveries (producer-creates 관례, NotificationWorker → SlackDeliverySender 발행 대상)
SELECT pgmq.create('q_slack_deliveries');

-- SLACK 채널 전역 기본 정책 시드 — 멘션/할당 이벤트
-- project_key=NULL: 전역 기본 정책 (모든 프로젝트에 적용, 프로젝트 전용 정책이 없을 때 폴백).
-- created_by=NULL: 시스템 시드 (사람이 만든 정책이 아님).
-- ON CONFLICT DO NOTHING: 재실행 시 멱등 보장.
INSERT INTO notification_policies (event_type, recipient_role, channel, enabled, project_key, created_by)
VALUES
    -- issue.mentioned: 멘션된 사용자에게 Slack 알림
    ('issue.mentioned', 'MENTIONED', 'SLACK', TRUE, NULL, NULL),
    -- issue.assigned: 이슈 담당자 지정 시 Slack 알림
    ('issue.assigned',  'ASSIGNEE',  'SLACK', TRUE, NULL, NULL)

ON CONFLICT ON CONSTRAINT uq_notification_policy DO NOTHING;
