-- jOOQ 코드 생성용 초기화 SQL (search-export-import BC) — V600 saved_filters 구조 미러 (codegen 입력, V600 과 정확히 일치 유지)

CREATE TABLE saved_filters (
    id          UUID PRIMARY KEY,
    owner_id    UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    aql_query   TEXT         NOT NULL,
    project_key VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_saved_filters_owner_name UNIQUE (owner_id, name)
);

CREATE INDEX idx_saved_filters_owner ON saved_filters (owner_id);
CREATE INDEX idx_saved_filters_project ON saved_filters (project_key);

-- V601 saved_filter_shares 구조 미러 (codegen 입력, V601 과 정확히 일치 유지)

CREATE TABLE saved_filter_shares (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filter_id  UUID        NOT NULL REFERENCES saved_filters(id) ON DELETE CASCADE,
    share_type VARCHAR(20) NOT NULL CHECK (share_type IN ('PROJECT','GROUP','AUTHENTICATED')),
    target_id  VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_filter_shares UNIQUE NULLS NOT DISTINCT (filter_id, share_type, target_id),
    CONSTRAINT ck_saved_filter_shares_target
        CHECK ((share_type = 'AUTHENTICATED') = (target_id IS NULL))
);

CREATE INDEX idx_saved_filter_shares_filter ON saved_filter_shares (filter_id);
CREATE INDEX idx_saved_filter_shares_lookup ON saved_filter_shares (share_type, target_id);

-- V602 export_jobs 구조 미러 (codegen 입력, V602 CREATE TABLE 과 정확히 일치 유지 — FR-EX-02)
-- 주의: pgmq.create / CREATE EXTENSION 은 미포함 — jOOQ codegen 은 public 스키마만 introspect 하므로
-- pgmq 큐 메타는 불필요하고, codegen 컨테이너는 alpine(jdbc:tc:postgresql:16-alpine) 유지(jooq-init_codegen-mirror).

CREATE TABLE export_jobs (
    id                 UUID PRIMARY KEY,
    project_key        TEXT NOT NULL,
    query              TEXT NOT NULL,
    format             TEXT NOT NULL,
    columns            TEXT,
    requester_user_id  UUID NOT NULL,
    status             TEXT NOT NULL DEFAULT 'PENDING',
    progress           INT  NOT NULL DEFAULT 0,
    row_count          BIGINT,
    result_object_key  TEXT,
    error_code         TEXT,
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT chk_export_jobs_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_export_jobs_format CHECK (format IN ('CSV','XLSX'))
);

CREATE INDEX idx_export_jobs_requester ON export_jobs (requester_user_id, created_at DESC);
CREATE INDEX idx_export_jobs_expires ON export_jobs (expires_at) WHERE expires_at IS NOT NULL;

-- V603 outbound_webhooks / webhook_deliveries 구조 미러 (codegen 입력, V603 CREATE TABLE 과 정확히 일치 유지 — FR-API-03 PR2)

CREATE TABLE outbound_webhooks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    url              TEXT NOT NULL,
    secret_encrypted TEXT,
    event_filter     TEXT[] NOT NULL,
    project_key      VARCHAR(100),
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_by       UUID NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbound_webhooks_enabled_notdeleted
    ON outbound_webhooks (enabled) WHERE deleted_at IS NULL;
CREATE INDEX idx_outbound_webhooks_event_filter_gin
    ON outbound_webhooks USING GIN (event_filter);

CREATE TABLE webhook_deliveries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_id    UUID NOT NULL REFERENCES outbound_webhooks(id),
    event_type    VARCHAR(100) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    response_code INT,
    attempt_count INT NOT NULL DEFAULT 0,
    error_detail  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at  TIMESTAMPTZ
);

CREATE INDEX idx_webhook_deliveries_webhook_id ON webhook_deliveries (webhook_id);
