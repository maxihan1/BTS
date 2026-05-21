-- personal_access_tokens 테이블 — PAT 발급/검증 모델 (EC-26 token_hash, EC-27 무기한 허용)

CREATE TABLE personal_access_tokens (
  id              UUID PRIMARY KEY,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name            VARCHAR(128) NOT NULL,
  token_hash      VARCHAR(64) NOT NULL UNIQUE,
  scopes          JSONB NOT NULL DEFAULT '[]',
  expires_at      TIMESTAMPTZ,
  last_used_at    TIMESTAMPTZ,
  revoked_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- partial index: 활성 PAT 를 user_id 로 빠르게 조회 (revoked 제외)
CREATE INDEX idx_pat_user_active ON personal_access_tokens(user_id) WHERE revoked_at IS NULL;

COMMENT ON COLUMN personal_access_tokens.token_hash IS 'SHA-256 hex 64자 — SHA-256(전체 token 그대로). 평문 token 미저장 (EC-26)';
COMMENT ON COLUMN personal_access_tokens.scopes IS 'JSON array of scope strings — SDD 19.2 supportedScopes 평가용';
COMMENT ON COLUMN personal_access_tokens.expires_at IS 'NULL 허용 — 무기한 PAT (EC-27). 후속 PR 에서 1년 default 강제 검토';
