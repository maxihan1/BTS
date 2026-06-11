-- 전역 기본 알림 정책 시드 (FR-NT-01 V401) — SDD §9.1.2 매트릭스 19행, project_id=NULL, channel=IN_APP

-- project_id=NULL: 전역 기본 정책 (모든 프로젝트에 적용, 프로젝트 전용 정책이 없을 때 폴백).
-- created_by=NULL: 시스템 시드 (사람이 만든 정책이 아님).
-- ON CONFLICT DO NOTHING: 재실행 시 멱등 보장.

INSERT INTO notification_policies (event_type, recipient_role, channel, enabled, project_id, created_by)
VALUES
    -- issue.created: 이슈 생성 시 알림
    ('issue.created',      'REPORTER',       'IN_APP', TRUE, NULL, NULL),
    ('issue.created',      'WATCHER',        'IN_APP', TRUE, NULL, NULL),
    ('issue.created',      'COMPONENT_LEAD', 'IN_APP', TRUE, NULL, NULL),

    -- issue.assigned: 이슈 담당자 변경 시 알림
    ('issue.assigned',     'ASSIGNEE',          'IN_APP', TRUE, NULL, NULL),
    ('issue.assigned',     'PREVIOUS_ASSIGNEE', 'IN_APP', TRUE, NULL, NULL),

    -- issue.transitioned: 이슈 상태 전이 시 알림
    ('issue.transitioned', 'REPORTER',  'IN_APP', TRUE, NULL, NULL),
    ('issue.transitioned', 'ASSIGNEE',  'IN_APP', TRUE, NULL, NULL),
    ('issue.transitioned', 'WATCHER',   'IN_APP', TRUE, NULL, NULL),

    -- issue.commented: 이슈 댓글 등록 시 알림
    ('issue.commented',    'REPORTER',  'IN_APP', TRUE, NULL, NULL),
    ('issue.commented',    'ASSIGNEE',  'IN_APP', TRUE, NULL, NULL),
    ('issue.commented',    'WATCHER',   'IN_APP', TRUE, NULL, NULL),
    ('issue.commented',    'MENTIONED', 'IN_APP', TRUE, NULL, NULL),

    -- issue.due_soon: 이슈 마감 임박 시 알림
    ('issue.due_soon',     'ASSIGNEE',  'IN_APP', TRUE, NULL, NULL),

    -- issue.overdue: 이슈 마감 초과 시 알림
    ('issue.overdue',      'ASSIGNEE',  'IN_APP', TRUE, NULL, NULL),
    ('issue.overdue',      'REPORTER',  'IN_APP', TRUE, NULL, NULL),

    -- sprint.started: 스프린트 시작 시 알림
    ('sprint.started',     'PROJECT_MEMBER', 'IN_APP', TRUE, NULL, NULL),

    -- sprint.ended: 스프린트 종료 시 알림
    ('sprint.ended',       'PROJECT_MEMBER', 'IN_APP', TRUE, NULL, NULL),

    -- automation.failed: 자동화 규칙 실패 시 알림
    ('automation.failed',  'RULE_OWNER',    'IN_APP', TRUE, NULL, NULL),
    ('automation.failed',  'PROJECT_ADMIN', 'IN_APP', TRUE, NULL, NULL)

ON CONFLICT ON CONSTRAINT uq_notification_policy DO NOTHING;
