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

-- ── notifications (V402 + V407 미러) ──────────────────────────────────────────
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
    archived_at       TIMESTAMPTZ,
    actor_user_id     UUID,
    CONSTRAINT uq_notifications_dedup_key UNIQUE (dedup_key)
);

CREATE INDEX ix_notifications_recipient
    ON notifications (recipient_user_id, created_at DESC);

CREATE INDEX ix_notifications_recipient_unread
    ON notifications (recipient_user_id)
    WHERE read_at IS NULL AND archived_at IS NULL AND channel = 'IN_APP';

-- ── user_notification_subs (V404 미러) ─────────────────────────────────────────
CREATE TABLE user_notification_subs (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    event_type  TEXT         NOT NULL,
    channel     TEXT         NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_notif_subs UNIQUE (user_id, event_type, channel)
);

CREATE INDEX idx_user_notif_subs_disabled
    ON user_notification_subs (event_type, channel, user_id)
    WHERE enabled = false;

-- ── dashboards (V405 미러) ─────────────────────────────────────────────────────
CREATE TABLE dashboards (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    UUID        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    visibility  TEXT        NOT NULL,
    layout      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    version     BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_dashboards_owner ON dashboards(owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_dashboards_visibility ON dashboards(visibility) WHERE deleted_at IS NULL;

-- ── dashboard_shares (V405 미러) ───────────────────────────────────────────────
CREATE TABLE dashboard_shares (
    dashboard_id UUID NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL,
    PRIMARY KEY (dashboard_id, user_id)
);

CREATE INDEX idx_dashboard_shares_user ON dashboard_shares(user_id);

-- ── favorites (V406 미러) ──────────────────────────────────────────────────────
CREATE TABLE favorites (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL,
    target_type VARCHAR(20)  NOT NULL,
    target_id   VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_favorites_user_target UNIQUE (user_id, target_type, target_id)
);
