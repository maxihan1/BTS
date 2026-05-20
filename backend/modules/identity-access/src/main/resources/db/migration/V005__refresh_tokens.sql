-- FR-AU-09 Refresh Token 테이블 — rotation chain 추적 + session 단위 폐기 (SDD 19.5)

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    replaced_by UUID        REFERENCES refresh_tokens(id)
);

-- session_id 기준 조회 — logout/rotation 시 session 전체 token chain 조회
CREATE INDEX idx_refresh_session ON refresh_tokens(session_id);
