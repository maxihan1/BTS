-- jOOQ 코드 생성용 초기화 SQL (notification BC) — V400 테이블 구조만 미러 (시드 제외, codegen 은 구조만 필요)

-- ── notification_policies ─────────────────────────────────────────────────────
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
    CONSTRAINT uq_notification_policy
        UNIQUE NULLS NOT DISTINCT (project_key, event_type, recipient_role, channel)
);

CREATE INDEX idx_notification_policy_lookup
    ON notification_policies (event_type, project_key)
    WHERE enabled = TRUE;

-- ── notifications (V402 미러) ─────────────────────────────────────────────────
CREATE TABLE notifications (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_user_id UUID         NOT NULL,
    event_type        TEXT         NOT NULL,
    channel           TEXT         NOT NULL,
    issue_key         TEXT,
    title             TEXT         NOT NULL,
    body              TEXT,
    payload           JSONB,
    status            TEXT         NOT NULL,
    dedup_key         TEXT         NOT NULL,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_notifications_dedup_key UNIQUE (dedup_key)
);

CREATE INDEX ix_notifications_recipient
    ON notifications (recipient_user_id, created_at DESC);
