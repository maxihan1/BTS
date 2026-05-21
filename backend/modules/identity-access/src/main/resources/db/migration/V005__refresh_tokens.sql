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

-- SDD 19.5 명세 인용 컬럼 주석
COMMENT ON TABLE  refresh_tokens              IS 'FR-AU-09 Refresh Token — rotation chain 추적. session row 삭제 시 ON DELETE CASCADE. SDD 19.5';
COMMENT ON COLUMN refresh_tokens.id          IS 'Refresh Token ID. UUID v4 — 호출 측에서 생성하여 INSERT';
COMMENT ON COLUMN refresh_tokens.session_id  IS 'sessions(id) FK. ON DELETE CASCADE — session 폐기 시 token chain 전체 삭제';
COMMENT ON COLUMN refresh_tokens.token_hash  IS 'SHA-256 hex 64자 — 평문 token 미저장. EC-26: prefix 포함 전체 token 해시';
COMMENT ON COLUMN refresh_tokens.issued_at   IS 'token 발급 시각';
COMMENT ON COLUMN refresh_tokens.expires_at  IS 'token 만료 시각. 로그인 후 now()+14d. SDD 19.5';
COMMENT ON COLUMN refresh_tokens.used_at     IS 'NULL = 미사용(유효). NOT NULL = 사용 완료(rotation 또는 폐기). EC-05 replay 탐지 기준';
COMMENT ON COLUMN refresh_tokens.replaced_by IS 'rotation 후 새 token ID (self FK). NULL = 체인 말단. US-05 rotation chain 추적';
