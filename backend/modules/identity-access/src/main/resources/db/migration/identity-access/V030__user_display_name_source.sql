-- FR-PR-04 display_name 출처 추적 컬럼. LDAP=디렉터리 동기화 대상, USER=사용자 편집(재로그인 보존). identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

ALTER TABLE users
    ADD COLUMN display_name_source VARCHAR(8) NOT NULL DEFAULT 'LDAP'
        CHECK (display_name_source IN ('LDAP', 'USER'));

COMMENT ON COLUMN users.display_name_source IS 'FR-PR-04 display_name 값 출처. LDAP=디렉터리 동기화 대상, USER=사용자 편집(재로그인 보존). 기본 LDAP';
