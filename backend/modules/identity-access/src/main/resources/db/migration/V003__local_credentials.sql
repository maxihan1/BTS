-- Local Provider 사용자의 Argon2id 해시 영속 저장 (PoC #2 spec §5 미구현 완성)

-- 비밀번호 영속 저장 (Local Provider 한정)
-- PoC #2 spec §5 가 명세했으나 미구현 — 본 마이그레이션이 완성
-- 외부 IdP (Keycloak/LDAP/SAML/OIDC) 인증은 해당 외부 시스템이 저장, 본 테이블 미사용
CREATE TABLE local_credentials (
    user_id        UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash  TEXT         NOT NULL,
    algo_version   TEXT         NOT NULL DEFAULT 'argon2id-v1',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  local_credentials               IS 'Local Provider 사용자의 Argon2id 해시 저장 (Keycloak/LDAP 인증과 무관)';
COMMENT ON COLUMN local_credentials.password_hash IS 'Argon2id 인코딩 문자열 ($argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>)';
COMMENT ON COLUMN local_credentials.algo_version  IS '알고리즘 버전 (마이그레이션 추적용, 현재 argon2id-v1 단일)';

-- 인덱스. PK 단일로 충분 (user_id 만 조회). FK = users.id 가 CASCADE 처리
