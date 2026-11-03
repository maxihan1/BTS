-- S24 통합 로그아웃 — Auth_platform 로그인 세션에 Auth 세션 id(sid)와 로그아웃 때 쓸 암호화 id_token 을 둔다 (FR-AU-04 · FR-AU-09)

ALTER TABLE sessions ADD COLUMN auth_sid TEXT;
ALTER TABLE sessions ADD COLUMN auth_id_token_enc TEXT;

-- back-channel logout 이 sid 로 살아 있는 세션을 찾는다. plain CREATE INDEX (V022 형제 일관) — 마이그레이션은
-- 기동 때 요청을 받기 전에 돈다(FlywayAssemblyConfig). 새 칸이 모두 NULL 이라 쓰기 잠금도 짧다
CREATE INDEX idx_sessions_auth_sid_active ON sessions (auth_sid) WHERE revoked_at IS NULL;

COMMENT ON COLUMN sessions.auth_sid          IS 'S24 — Auth_platform id_token 의 sid(Auth 세션 id 해시). back-channel logout_token 의 sid 와 맞춘다. 다른 로그인은 NULL';
COMMENT ON COLUMN sessions.auth_id_token_enc IS 'S24 — 로그아웃 때 Auth /connect/logout 의 id_token_hint 로 쓰는 id_token. oidcSecretEncryptor 암호화(평문 금지 DEVELOPMENT §1.1-1). 다른 로그인은 NULL';
