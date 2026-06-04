-- SAML IdP(Identity Provider) 연결 설정 + JIT 프로비저닝용 SAML authn_providers seed (FR-AU-03 SAML SSO)

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid() 안전망 (V001 미적용 환경 대비)

-- ── SAML authn_providers seed ────────────────────────────────────────────────
-- JIT(Just-In-Time) 자동 프로비저닝(AutoProvisionService.provision())이 providerId 로
-- authn_providers.id 를 요구한다. saml_idp_configs.authn_provider_id FK 가 참조할 수 있도록
-- 고정 UUID 의 SAML Provider row 를 먼저 INSERT 한다 (FK 위반 방지, C9).
-- authn_providers 컬럼: type / name(UNIQUE) / config(JSONB NOT NULL).
INSERT INTO authn_providers (id, type, name, config, enabled, sort_order)
VALUES (
    '00000000-0000-4a03-8000-000000000003',  -- SAML Provider 고정 UUID (FR-AU-03)
    'SAML',
    'SAML SSO',
    '{}'::jsonb,                               -- IdP 별 설정은 saml_idp_configs 에 정규화 저장
    true,
    0
)
ON CONFLICT (name) DO NOTHING;  -- 재적용/멱등성 안전망

-- ── saml_idp_configs ─────────────────────────────────────────────────────────
-- SAML IdP 등록 메타데이터를 IdP 단위로 정규화 저장한다.
-- registration_id 는 Spring Security RelyingPartyRegistration 의 식별자(URL 경로 포함)로 UNIQUE.
-- idp_x509_cert 는 IdP 의 공개 서명 인증서(PEM) — 공개값이라 평문 저장 허용.
CREATE TABLE saml_idp_configs (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id   TEXT         NOT NULL UNIQUE,               -- RelyingPartyRegistration 식별자 (URL 경로)
    display_name      TEXT         NOT NULL,                      -- 로그인 화면 표시 이름
    idp_entity_id     TEXT         NOT NULL,                      -- IdP EntityID (메타데이터 issuer)
    idp_sso_url       TEXT         NOT NULL,                      -- IdP SSO 리다이렉트/POST 엔드포인트
    idp_x509_cert     TEXT         NOT NULL,                      -- IdP 서명 검증용 공개 인증서 (PEM)
    authn_provider_id UUID         NOT NULL REFERENCES authn_providers (id),  -- JIT providerId (C9)
    enabled           BOOLEAN      NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  saml_idp_configs                   IS 'SAML IdP 연결 설정 — FR-AU-03 SAML SSO';
COMMENT ON COLUMN saml_idp_configs.registration_id   IS 'Spring Security RelyingPartyRegistration 식별자 (URL 경로 포함, UNIQUE)';
COMMENT ON COLUMN saml_idp_configs.idp_entity_id     IS 'IdP EntityID — SAML 메타데이터 issuer';
COMMENT ON COLUMN saml_idp_configs.idp_sso_url       IS 'IdP SSO 엔드포인트 URL (Redirect/POST 바인딩)';
COMMENT ON COLUMN saml_idp_configs.idp_x509_cert     IS 'IdP 서명 검증용 공개 X.509 인증서 (PEM, 공개값)';
COMMENT ON COLUMN saml_idp_configs.authn_provider_id IS 'JIT 프로비저닝 providerId — authn_providers(id) FK (C9)';
