-- 신뢰 디바이스(30일 MFA 면제)를 저장하는 테이블 (FR-MF-05). token_hash=SHA-256 해시만 저장(평문 비영속). identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE trusted_devices (
    id           UUID        PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   TEXT        NOT NULL UNIQUE,
    label        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ
);

-- FK 인덱스 — 사용자별 신뢰 디바이스 조회/CASCADE 삭제 성능(PostgreSQL은 FK 인덱스 자동 생성 안 함).
CREATE INDEX idx_trusted_devices_user ON trusted_devices(user_id);
-- token_hash 는 UNIQUE 제약이 곧 조회 인덱스(쿠키 rawToken 해시 → 행 조회).
