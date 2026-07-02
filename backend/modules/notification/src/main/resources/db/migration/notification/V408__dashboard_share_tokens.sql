-- 대시보드 공유 토큰 — 비로그인 URL 공유용 불투명 토큰의 SHA-256 해시만 보관 (FR-DB-03 V408)

-- ── dashboard_share_tokens ────────────────────────────────────────────────────
-- 대시보드를 URL 하나로 비로그인 사용자에게 공개하기 위한 공유 토큰. 원문 토큰은 저장하지 않고
-- SHA-256 해시(hex 소문자 64자)만 보관한다 — DB 유출 시에도 원문 토큰 복원 불가(단방향 해시).
-- created_by 는 identity-access users.id 를 직접 저장한다 (FK 없음 — BC 격리, V405 dashboards 선례).
-- 부모 대시보드 삭제 시 공유 토큰도 함께 제거되어야 하므로 dashboard_id FK 는 ON DELETE CASCADE.
CREATE TABLE dashboard_share_tokens (
    id               UUID PRIMARY KEY,
    dashboard_id     UUID NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    token_hash       TEXT NOT NULL,               -- SHA-256 hex(소문자 64자), 원문 미저장
    created_by       UUID NOT NULL,               -- users.id 논리참조 (BC 격리상 FK 없음)
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ,                 -- null = 무기한
    last_accessed_at TIMESTAMPTZ                  -- MVP 미갱신(공개 GET write-on-read 회피), 후속 여지
);

COMMENT ON TABLE  dashboard_share_tokens                  IS '대시보드 공유 토큰 — 비로그인 URL 공유용. 원문 미저장, SHA-256 해시만 보관';
COMMENT ON COLUMN dashboard_share_tokens.dashboard_id     IS '공유 대상 대시보드 ID (dashboards.id, ON DELETE CASCADE)';
COMMENT ON COLUMN dashboard_share_tokens.token_hash       IS '공유 토큰 원문의 SHA-256 해시 (hex 소문자 64자). 원문은 저장하지 않음';
COMMENT ON COLUMN dashboard_share_tokens.created_by       IS '토큰 생성자 사용자 ID (identity-access users.id). FK 없음 — BC 격리';
COMMENT ON COLUMN dashboard_share_tokens.expires_at       IS '만료 시각 (NULL=무기한)';
COMMENT ON COLUMN dashboard_share_tokens.last_accessed_at IS '마지막 접근 시각. MVP 미갱신(공개 GET write-on-read 회피), 후속 여지';

-- 공유 URL 접근 시 토큰 해시로 단건 조회 + 재발급 멱등성 보장 — UNIQUE 인덱스.
CREATE UNIQUE INDEX ux_dashboard_share_tokens_hash ON dashboard_share_tokens (token_hash);
-- FK(dashboard_id) 조회 경로 최적화 — 대시보드별 공유 토큰 목록/삭제 경로 (§7 FK 인덱스 필수).
CREATE INDEX ix_dashboard_share_tokens_dashboard ON dashboard_share_tokens (dashboard_id);
