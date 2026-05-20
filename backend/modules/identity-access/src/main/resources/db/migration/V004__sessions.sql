-- FR-AU-09 세션 관리 테이블 — 로그인 1회 = row 1건 (device 단위, SDD 19.5)

CREATE TABLE sessions (
    id                 UUID        PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_id        VARCHAR(64) NOT NULL,
    device_fingerprint VARCHAR(128),
    ip_address         INET,
    user_agent         TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at         TIMESTAMPTZ NOT NULL,
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at         TIMESTAMPTZ,
    revoke_reason      VARCHAR(64)
);

-- active session 조회 (user_id 기준) — revoked_at IS NULL 부분 인덱스
CREATE INDEX idx_sessions_user_active    ON sessions(user_id)    WHERE revoked_at IS NULL;
-- GC / 만료 정리 조회 (expires_at 기준) — revoked_at IS NULL 부분 인덱스
CREATE INDEX idx_sessions_expires_active ON sessions(expires_at) WHERE revoked_at IS NULL;
