-- FR-MF-01 TOTP(다중 요소 인증) — totp_secrets 테이블(사용자당 1건) + sessions.mfa_verified 전파 컬럼. SDD §19.7

CREATE TABLE totp_secrets (
    user_id            UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_cipher      TEXT        NOT NULL,
    status             VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE')),
    last_verified_step BIGINT,
    confirmed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  totp_secrets                    IS 'FR-MF-01 TOTP secret — 사용자당 1건(user_id PK). SDD §19.7. identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN totp_secrets.user_id            IS 'BTS 사용자 FK(V001 users.id). PK 겸용 — 사용자당 TOTP 1개. ON DELETE CASCADE';
COMMENT ON COLUMN totp_secrets.secret_cipher      IS 'TOTP secret 암호문(AES-256-GCM, MfaSecretEncryptor). 평문 저장 금지(DEVELOPMENT.md §1.1.1)';
COMMENT ON COLUMN totp_secrets.status             IS '설정 상태. PENDING=secret 생성 후 enable 확인 전, ACTIVE=확인 완료(2단계 로그인 적용)';
COMMENT ON COLUMN totp_secrets.last_verified_step IS '마지막 검증 성공 time-step(RFC 6238). 코드 replay 차단(WHERE last_verified_step < :step 조건부 UPDATE). NULL=미검증';
COMMENT ON COLUMN totp_secrets.confirmed_at       IS 'enable(ACTIVE 전이) 시각. PENDING 상태에서는 NULL';
COMMENT ON COLUMN totp_secrets.created_at         IS 'secret 최초 생성 시각(setup 시점)';
COMMENT ON COLUMN totp_secrets.updated_at         IS '마지막 변경 시각(재setup/activate/step 갱신)';

-- GAP-1: refresh 회전 시 MFA 통과 상태가 소실되지 않도록 세션에 mfa_verified 전파.
-- 기존 row 무회귀를 위해 NOT NULL DEFAULT false(MFA 미통과 = 기본값).
ALTER TABLE sessions ADD COLUMN mfa_verified BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN sessions.mfa_verified IS 'FR-MF-01 — 이 세션이 2차 요소(TOTP)까지 통과했는지. JWT mfa_verified 클레임 원천. refresh 회전 시 전파(GAP-1). 기본 false';
