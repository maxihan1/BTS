-- issue.mentioned 멘션 알림 기본 정책 시드 (FR-NT-02, MENTIONED×IN_APP)

-- project_key=NULL: 전역 기본 정책 (모든 프로젝트에 적용, 프로젝트 전용 정책이 없을 때 폴백).
-- created_by=NULL: 시스템 시드 (사람이 만든 정책이 아님).
-- ON CONFLICT DO NOTHING: 재실행 시 멱등 보장.

INSERT INTO notification_policies (event_type, recipient_role, channel, enabled, project_key, created_by)
VALUES
    -- issue.mentioned: 멘션된 사용자에게 인앱 알림
    ('issue.mentioned', 'MENTIONED', 'IN_APP', TRUE, NULL, NULL)

ON CONFLICT ON CONSTRAINT uq_notification_policy DO NOTHING;
