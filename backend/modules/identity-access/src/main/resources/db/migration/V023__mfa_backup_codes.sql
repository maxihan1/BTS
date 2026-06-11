-- FR-MF-02 백업 코드(Recovery Codes) — TOTP 분실 시 1회용 복구. SHA-256 해시 저장. SDD §19.7

CREATE TABLE user_mfa_backup_codes (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   TEXT        NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 미사용(used_at IS NULL) 코드만 인덱싱하는 부분 인덱스 — 검증 시 활성 코드만 조회.
CREATE INDEX idx_mfa_backup_codes_user_active ON user_mfa_backup_codes(user_id) WHERE used_at IS NULL;
-- 사용자별 같은 해시 중복 방지 — 코드 재생성 멱등성 보장.
CREATE UNIQUE INDEX uq_mfa_backup_codes_user_hash ON user_mfa_backup_codes(user_id, code_hash);

COMMENT ON TABLE  user_mfa_backup_codes            IS 'FR-MF-02 MFA 백업 코드 — TOTP 분실 시 1회용 복구 코드(사용자당 N건). SHA-256 해시만 저장. SDD §19.7. identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN user_mfa_backup_codes.id         IS '백업 코드 행 PK(gen_random_uuid). pgcrypto 확장 필요(V001에서 활성화)';
COMMENT ON COLUMN user_mfa_backup_codes.user_id    IS 'BTS 사용자 FK(V001 users.id). ON DELETE CASCADE — 사용자 삭제 시 백업 코드 연쇄 삭제';
COMMENT ON COLUMN user_mfa_backup_codes.code_hash  IS '백업 코드 SHA-256 해시. 평문 저장 금지(DEVELOPMENT.md §1.1.1). (user_id, code_hash) UNIQUE';
COMMENT ON COLUMN user_mfa_backup_codes.used_at    IS '코드 사용(소비) 시각. NULL=미사용(활성). 부분 인덱스 idx_mfa_backup_codes_user_active의 조건';
COMMENT ON COLUMN user_mfa_backup_codes.created_at IS '백업 코드 생성 시각(코드 묶음 발급 시점)';
