-- OIDC IdP 연결 설정 + JIT용 OIDC authn_providers seed (FR-AU-04 OIDC SSO)

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid() 안전망 (V001 미적용 환경 대비)

-- ── OIDC authn_providers seed ────────────────────────────────────────────────
-- JIT(Just-In-Time) 자동 프로비저닝(AutoProvisionService.provision())이 providerId 로
-- authn_providers.id 를 요구한다. oidc_provider_configs.authn_provider_id FK 가 참조할 수 있도록
-- 고정 UUID 의 OIDC Provider row 를 먼저 INSERT 한다 (FK 위반 방지, C9).
-- 고정 UUID 는 SAML(...-4a03-...-003)과 반드시 다른 값을 사용한다 (C4) — FR-AU-04 식별자(4a04/004).
-- authn_providers 컬럼: type / name(UNIQUE) / config(JSONB NOT NULL).
INSERT INTO authn_providers (id, type, name, config, enabled, sort_order)
VALUES (
    '00000000-0000-4a04-8000-000000000004',  -- OIDC Provider 고정 UUID (FR-AU-04, SAML 과 구분)
    'OIDC',
    'OIDC SSO',
    '{}'::jsonb,                               -- Provider 별 설정은 oidc_provider_configs 에 정규화 저장
    true,
    0
)
ON CONFLICT (name) DO NOTHING;  -- 재적용/멱등성 안전망

-- ── oidc_provider_configs ────────────────────────────────────────────────────
-- OIDC(OpenID Connect) Provider 등록 메타데이터를 Provider 단위로 정규화 저장한다.
-- registration_id 는 Spring Security ClientRegistration 의 식별자(URL 경로 포함)로 UNIQUE.
-- client_secret_encrypted 는 app key 로 암호화된 client_secret — 평문 저장 금지(§1.1.1).
CREATE TABLE oidc_provider_configs (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id          TEXT         NOT NULL UNIQUE,                       -- ClientRegistration 식별자 (URL 경로)
    display_name             TEXT         NOT NULL,                              -- 로그인 화면 버튼 라벨
    issuer_uri               TEXT         NOT NULL,                              -- IdP issuer (.well-known discovery 기준)
    client_id                TEXT         NOT NULL,                              -- OAuth2 client_id
    client_secret_encrypted  TEXT         NOT NULL,                              -- 암호화된 client_secret (평문 금지)
    scopes                   TEXT         NOT NULL DEFAULT 'openid,profile,email',  -- 요청 스코프 (CSV)
    authn_provider_id        UUID         NOT NULL REFERENCES authn_providers (id),  -- JIT providerId (C9)
    enabled                  BOOLEAN      NOT NULL DEFAULT true,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 활성 Provider 만 포함하는 부분 인덱스 — 로그인 화면/디스패치 시 enabled=true 행만 조회 (V002/V010 관례와 동일)
CREATE INDEX idx_oidc_provider_configs_enabled
    ON oidc_provider_configs (enabled)
    WHERE enabled = true;

-- FK(authn_provider_id) 조회용 인덱스 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다 (DATA.md §4)
CREATE INDEX idx_oidc_provider_configs_authn_provider_id
    ON oidc_provider_configs (authn_provider_id);

COMMENT ON TABLE  oidc_provider_configs                          IS 'OIDC Provider 연결 설정 — FR-AU-04 OIDC SSO';
COMMENT ON COLUMN oidc_provider_configs.registration_id          IS 'Spring Security ClientRegistration 식별자 (URL 경로 포함, UNIQUE)';
COMMENT ON COLUMN oidc_provider_configs.display_name             IS '로그인 화면 SSO 버튼 라벨';
COMMENT ON COLUMN oidc_provider_configs.issuer_uri               IS 'IdP issuer URI — OIDC .well-known discovery 기준';
COMMENT ON COLUMN oidc_provider_configs.client_id                IS 'OAuth2 client_id (공개값)';
COMMENT ON COLUMN oidc_provider_configs.client_secret_encrypted  IS 'app key 로 암호화된 client_secret — 평문 저장 금지 (DATA.md §1.1.1)';
COMMENT ON COLUMN oidc_provider_configs.scopes                   IS '요청 OAuth2/OIDC 스코프 (CSV, 기본 openid,profile,email)';
COMMENT ON COLUMN oidc_provider_configs.authn_provider_id        IS 'JIT 프로비저닝 providerId — authn_providers(id) FK (C9)';
