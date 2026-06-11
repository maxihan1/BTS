-- notification_policies 테이블 생성 (FR-NT-01 V400) — 알림 정책 Aggregate (전역/프로젝트 범위 이벤트×역할×채널 매트릭스)

-- ── notification_policies ─────────────────────────────────────────────────────
-- 알림 정책 Aggregate Root. project_key=NULL 이면 전역(시스템 기본) 정책이다.
-- project_key 가 있으면 해당 프로젝트 전용 정책으로 전역 기본을 덮어쓴다.
-- project_key(문자열)를 쓰는 이유: notification BC 는 BC 격리상 issue-tracking 의 projects 테이블을
-- 조회할 수 없어 projectKey→projectId(UUID) 변환이 불가하다. 정책을 소비하는 이벤트 payload
-- (예: IssueMentioned)도 projectKey(문자열)만 담으므로, 변환 없이 직접 매칭하려면 project_key 로 저장한다.
CREATE TABLE notification_policies (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key     VARCHAR(64),
    event_type      VARCHAR(64)  NOT NULL,
    recipient_role  VARCHAR(32)  NOT NULL,
    channel         VARCHAR(16)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- NULLS NOT DISTINCT: project_key=NULL 인 전역 정책도 (event_type, recipient_role, channel) 기준 중복을 막는다.
    -- PostgreSQL 15+ 기능. Testcontainers 16-alpine 이미지에서 지원 확인.
    CONSTRAINT uq_notification_policy
        UNIQUE NULLS NOT DISTINCT (project_key, event_type, recipient_role, channel)
);

COMMENT ON TABLE  notification_policies                IS '알림 정책 Aggregate — 이벤트×역할×채널 조합의 수신 여부 결정';
COMMENT ON COLUMN notification_policies.project_key    IS 'NULL=전역 기본 정책, non-NULL=프로젝트 전용 정책 (전역을 덮어씀). issue-tracking projects.key 문자열';
COMMENT ON COLUMN notification_policies.event_type     IS '이벤트 유형 (예: issue.created, sprint.started)';
COMMENT ON COLUMN notification_policies.recipient_role IS '수신 역할 (예: REPORTER, ASSIGNEE, WATCHER)';
COMMENT ON COLUMN notification_policies.channel        IS '알림 채널 (예: IN_APP, EMAIL)';
COMMENT ON COLUMN notification_policies.enabled        IS '정책 활성 여부 — FALSE 이면 해당 조합의 알림 발송 안 함';
COMMENT ON COLUMN notification_policies.created_by     IS '정책 생성자 (NULL=시스템 시드)';

-- 알림 발송 판정 경로 최적화 인덱스 — enabled=TRUE 행만 인덱싱 (부분 인덱스)
CREATE INDEX idx_notification_policy_lookup
    ON notification_policies (event_type, project_key)
    WHERE enabled = TRUE;
