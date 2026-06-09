-- 이메일 도메인 → 인증 Provider 라우트 매핑 (FR-AU-07 도메인 기반 SSO 라우팅)

CREATE EXTENSION IF NOT EXISTS pgcrypto;  -- gen_random_uuid() 안전망 (V001 미적용 환경 대비)

-- ── domain_provider_routes ───────────────────────────────────────────────────
-- 사용자가 입력한 이메일의 도메인(예: partner.com)을 인증 Provider 로 라우팅한다.
-- 로그인 화면에서 도메인이 매칭되면 해당 SAML/OIDC SSO 로 자동 안내한다(FR-AU-07).
-- 도메인 정규화(소문자/공백 제거)는 Controller 책임 — 이 테이블은 정규화된 값을 저장한다.
-- 라우트 행은 환경 의존(고객사/파트너사별)이라 시드 행을 두지 않는다.
-- ON DELETE CASCADE — Provider 삭제 시 라우트도 함께 삭제(미아 라우트로 fail-safe 우회 방지).
CREATE TABLE domain_provider_routes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    domain      VARCHAR(253) NOT NULL UNIQUE,                          -- 이메일 도메인 (RFC 1035 최대 253자)
    provider_id UUID         NOT NULL REFERENCES authn_providers (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- FK(provider_id) 조회용 인덱스 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다 (DATA.md §4)
CREATE INDEX idx_domain_provider_routes_provider_id
    ON domain_provider_routes (provider_id);

COMMENT ON TABLE  domain_provider_routes             IS '이메일 도메인 → 인증 Provider 라우트 매핑 — FR-AU-07 도메인 기반 SSO 라우팅';
COMMENT ON COLUMN domain_provider_routes.domain      IS '정규화(소문자/공백제거)된 이메일 도메인 (UNIQUE, 최대 253자)';
COMMENT ON COLUMN domain_provider_routes.provider_id IS '라우팅 대상 Provider — authn_providers(id) FK (ON DELETE CASCADE)';
