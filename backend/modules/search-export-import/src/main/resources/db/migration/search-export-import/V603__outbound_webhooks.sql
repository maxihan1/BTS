-- search-export-import BC 아웃바운드 Webhook 구독 — outbound_webhooks(구독) + webhook_deliveries(발송 이력) 테이블 (FR-API-03 PR2)
--
-- outbound_webhooks: 외부 시스템이 URL+secret+event_filter 를 등록해 두는 구독 애그리거트.
--   매칭 이벤트 발생 시 BTS 가 HMAC 서명과 함께 HTTP 로 통지한다(발송 로직은 PR3, 본 PR 은 구독 CRUD 만).
--   소프트 삭제(deleted_at) 대상 — DATA.md §1.2 신규 엔티티 테이블 기본. OCC(version) 로 동시 수정 제어.
--   secret 은 AES-256-GCM 암호문(secret_encrypted)만 저장, 원문 비저장(DEVELOPMENT §1.1.2).
--
-- webhook_deliveries: 발송 시도/결과 이력. ★append-only 발송 로그(audit_logs 동류) — 소프트삭제 대상 아님.
--   발송 시도는 사실 기록이라 수정/삭제하지 않는다. DATA.md §1.2 소프트삭제 기본의 명시적 예외(append-only 로그).
--   본 PR 은 테이블만 생성(기록/조회는 PR3). 보존 정책(TTL 등)이 필요하면 PR3 에서 별도 결정.

CREATE TABLE outbound_webhooks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    url              TEXT NOT NULL,
    secret_encrypted TEXT,                       -- nullable, AES-256-GCM hex (원문 비저장)
    event_filter     TEXT[] NOT NULL,            -- wireValue 집합, 비어있지 않음
    project_key      VARCHAR(100),               -- nullable, null=전체 프로젝트 (BC 격리, FK 미적용)
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_by       UUID NOT NULL,              -- SYSTEM_ADMIN actor (BC 격리, FK 미적용)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,                -- 소프트 삭제(DATA.md 신규테이블 기본)
    version          BIGINT NOT NULL DEFAULT 0   -- OCC
);

-- 활성 구독 스캔(PR3 발송 대상 후보) — 소프트삭제 제외 부분 인덱스.
CREATE INDEX idx_outbound_webhooks_enabled_notdeleted
    ON outbound_webhooks (enabled) WHERE deleted_at IS NULL;
-- PR3 이벤트 매칭(event_filter && overlap) 대비 — GIN 인덱스.
CREATE INDEX idx_outbound_webhooks_event_filter_gin
    ON outbound_webhooks USING GIN (event_filter);

CREATE TABLE webhook_deliveries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_id    UUID NOT NULL REFERENCES outbound_webhooks(id),
    event_type    VARCHAR(100) NOT NULL,
    status        VARCHAR(20) NOT NULL,          -- PENDING/SUCCEEDED/FAILED (PR3)
    response_code INT,                           -- nullable HTTP status
    attempt_count INT NOT NULL DEFAULT 0,
    error_detail  TEXT,                          -- nullable
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at  TIMESTAMPTZ                    -- nullable
);

-- FK 조회 인덱스 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다(DATA.md §4.1#6).
CREATE INDEX idx_webhook_deliveries_webhook_id ON webhook_deliveries (webhook_id);
