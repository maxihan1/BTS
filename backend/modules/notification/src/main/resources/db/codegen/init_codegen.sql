-- jOOQ 코드 생성용 초기화 SQL (notification BC) — V400 테이블 구조만 미러 (시드 제외, codegen 은 구조만 필요)

-- ── notification_policies ─────────────────────────────────────────────────────
CREATE TABLE notification_policies (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID,
    event_type      VARCHAR(64)  NOT NULL,
    recipient_role  VARCHAR(32)  NOT NULL,
    channel         VARCHAR(16)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_policy
        UNIQUE NULLS NOT DISTINCT (project_id, event_type, recipient_role, channel)
);

CREATE INDEX idx_notification_policy_lookup
    ON notification_policies (event_type, project_id)
    WHERE enabled = TRUE;
