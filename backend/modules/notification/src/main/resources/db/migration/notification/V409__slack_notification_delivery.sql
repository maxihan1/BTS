-- SLACK 채널 정책 시드 (FR-SL-02, SDD §9.4) — 멘션/할당 이벤트를 Slack DM으로 발송
-- 주의. q_slack_deliveries 큐 생성(CREATE EXTENSION pgmq + pgmq.create)은 slack-integration V701 로 이동했다.
--   notification 테스트 다수가 vanilla postgres:16-alpine 이미지(pgmq 미포함)를 쓰므로, 이 마이그레이션에
--   pgmq 확장을 요구하면 해당 테스트들이 부팅 실패한다. 큐는 소비 모듈(slack)이 자기 pgmq 이미지 위에서 생성한다.

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
