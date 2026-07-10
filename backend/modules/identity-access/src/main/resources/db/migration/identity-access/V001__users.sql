-- BTS 사용자 계정 기본 테이블 (모든 인증 공급자 공통 — PoC #2 + FR-AU-02)

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid() 함수 활성화

CREATE TABLE users (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username     VARCHAR(255) NOT NULL UNIQUE,
    email        VARCHAR(255),
    display_name VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  users              IS 'BTS 사용자 계정 — Local/LDAP/SAML/OIDC 모든 인증 공급자 공통';
COMMENT ON COLUMN users.username     IS '로그인 식별자 (LDAP uid, OIDC sub, 이메일 등)';
COMMENT ON COLUMN users.email        IS '이메일 (nullable — 외부 IdP 에서 미제공 시 null)';
COMMENT ON COLUMN users.display_name IS '화면 표시 이름 (LDAP cn 등)';
