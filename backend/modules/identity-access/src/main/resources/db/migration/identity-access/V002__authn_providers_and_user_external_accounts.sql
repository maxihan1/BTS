-- 인증 공급자 메타데이터 + User × External Identity 매핑 (FR-AU-02)

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid() 안전망 (V001 미적용 환경 대비)

-- ── authn_providers ──────────────────────────────────────────────────────────
-- LDAP/SAML/OIDC Provider 의 연결 정보와 정책을 JSONB 로 저장한다.
-- 한 타입에 여러 Provider 가 있을 수 있으나, FR-AU-06 이전까지는 타입당 1개 활성 가정.
CREATE TABLE authn_providers (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    type       VARCHAR(16)  NOT NULL,                      -- LOCAL / LDAP / SAML / OIDC
    name       VARCHAR(64)  NOT NULL UNIQUE,               -- 관리자 식별 이름 (예: "사내 LDAP")
    config     JSONB        NOT NULL,                      -- LdapConfig 등 공급자별 설정
    enabled    BOOLEAN      NOT NULL DEFAULT true,
    sort_order INTEGER      NOT NULL DEFAULT 0,            -- 다중 공급자 우선순위 (FR-AU-06)
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- type + enabled 복합 부분 인덱스 — enabled=true 행만 포함 (불필요한 비활성 Provider 제외)
CREATE INDEX idx_authn_providers_type_enabled
    ON authn_providers (type, enabled)
    WHERE enabled = true;

COMMENT ON TABLE  authn_providers            IS 'BTS 인증 공급자 메타데이터 — LDAP/SAML/OIDC config 저장';
COMMENT ON COLUMN authn_providers.type       IS '공급자 유형: LOCAL, LDAP, SAML, OIDC';
COMMENT ON COLUMN authn_providers.config     IS '공급자별 설정 JSONB (LdapConfig, SamlConfig 등)';
COMMENT ON COLUMN authn_providers.sort_order IS '동일 타입 다중 활성 시 우선순위 (FR-AU-06 이후 사용)';

-- ── user_external_accounts ───────────────────────────────────────────────────
-- 외부 IdP 의 사용자 식별자(external_subject)와 BTS users.id 를 연결한다.
-- 계정 잠금(lockout) 상태와 그룹 정보도 여기에 저장한다.
-- ON DELETE RESTRICT — Provider 삭제 시 매핑 행이 남아 있으면 삭제 차단 (미아 데이터 보호)
-- ON DELETE CASCADE  — User 삭제 시 매핑 행도 함께 삭제 (GDPR 삭제 요청 대응)
CREATE TABLE user_external_accounts (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id      UUID         NOT NULL REFERENCES authn_providers (id) ON DELETE RESTRICT,
    external_subject VARCHAR(512) NOT NULL,               -- LDAP DN / OIDC sub / SAML NameID
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    groups           JSONB        NOT NULL DEFAULT '[]'::jsonb,  -- LDAP 그룹 DN 목록 (FR-PM-01 에서 role 매핑)
    failed_attempts  INTEGER      NOT NULL DEFAULT 0,     -- 연속 실패 횟수 (LockoutPolicy.maxAttempts 비교)
    locked_until     TIMESTAMPTZ,                         -- null = 잠금 없음, 과거 = 자동 해제
    last_login_at    TIMESTAMPTZ,                         -- 마지막 성공 로그인 시각
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (provider_id, external_subject)                -- 동일 Provider 내 externalSubject 중복 불가
);

CREATE INDEX idx_uea_user_id
    ON user_external_accounts (user_id);

-- 로그인 조회 핫패스 인덱스 (provider_id + external_subject 로 기존 매핑 빠르게 탐색)
CREATE INDEX idx_uea_provider_subject
    ON user_external_accounts (provider_id, external_subject);

COMMENT ON TABLE  user_external_accounts                  IS 'User × External Identity 매핑 — LDAP DN / OIDC sub / SAML NameID';
COMMENT ON COLUMN user_external_accounts.external_subject IS '외부 IdP 발급 고유 식별자 (LDAP DN, OIDC sub, SAML NameID)';
COMMENT ON COLUMN user_external_accounts.groups           IS 'LDAP 그룹 DN 목록 JSONB — 권한 매핑은 FR-PM-01 에서 처리';
COMMENT ON COLUMN user_external_accounts.failed_attempts  IS '연속 인증 실패 횟수 (성공 시 0 으로 reset)';
COMMENT ON COLUMN user_external_accounts.locked_until     IS 'LockoutPolicy 적용 잠금 만료 시각 (null = 잠금 없음)';
