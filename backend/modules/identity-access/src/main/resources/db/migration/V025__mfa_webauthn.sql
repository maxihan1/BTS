-- FR-MF-03 WebAuthn(Passkey) 2차 인증 — 검증 후 INSERT=즉시 활성(status 컬럼 없음). credential_id 전역 UNIQUE. SDD §19.8

CREATE TABLE webauthn_credentials (
    id                       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id            TEXT        NOT NULL,          -- base64url(rawId)
    attested_credential_data TEXT        NOT NULL,          -- base64(AttestedCredentialDataConverter 직렬화)
    sign_count               BIGINT      NOT NULL DEFAULT 0,
    name                     VARCHAR(100),
    aaguid                   TEXT,
    last_used_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- credential_id 는 WebAuthn 명세상 전역 고유(복합 아님) — 다른 사용자라도 같은 자격증명 ID 차단.
CREATE UNIQUE INDEX uq_webauthn_credential_id ON webauthn_credentials(credential_id);
-- FK 인덱스 — 사용자별 자격증명 조회/CASCADE 삭제 성능(PostgreSQL은 FK 인덱스 자동 생성 안 함).
CREATE INDEX idx_webauthn_credentials_user ON webauthn_credentials(user_id);

COMMENT ON TABLE  webauthn_credentials                          IS 'FR-MF-03 WebAuthn(Passkey) 자격증명(사용자당 N건). 검증 통과 후 INSERT=즉시 활성(status 컬럼 없음). SDD §19.8. identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN webauthn_credentials.id                       IS '자격증명 행 PK(gen_random_uuid). pgcrypto 확장 필요(V001에서 활성화)';
COMMENT ON COLUMN webauthn_credentials.user_id                  IS 'BTS 사용자 FK(V001 users.id). ON DELETE CASCADE — 사용자 삭제 시 자격증명 연쇄 삭제';
COMMENT ON COLUMN webauthn_credentials.credential_id            IS 'base64url(rawId). WebAuthn 명세상 전역 고유 — uq_webauthn_credential_id UNIQUE(복합 아님)';
COMMENT ON COLUMN webauthn_credentials.attested_credential_data IS 'base64(AttestedCredentialDataConverter 직렬화). 공개키/AAGUID/credentialId 포함 — assertion 검증 시 복원';
COMMENT ON COLUMN webauthn_credentials.sign_count               IS '인증기 서명 카운터. assertion마다 단조 증가 — 역행/정체 시 clone(복제 인증기) 의심해 거부(clone 방어). DEFAULT 0';
COMMENT ON COLUMN webauthn_credentials.name                     IS '사용자 지정 별칭(예: "회사 노트북 Touch ID"). 다중 자격증명 식별용. NULL 허용';
COMMENT ON COLUMN webauthn_credentials.aaguid                   IS '인증기 모델 식별자(Authenticator Attestation GUID). 기기 종류 표시/관리용. NULL 허용';
COMMENT ON COLUMN webauthn_credentials.last_used_at             IS '마지막 assertion(로그인) 성공 시각. NULL=등록 후 미사용';
COMMENT ON COLUMN webauthn_credentials.created_at               IS '자격증명 등록(registration) 시각';
COMMENT ON COLUMN webauthn_credentials.updated_at               IS '마지막 갱신 시각(sign_count/last_used_at 변경 등). 애플리케이션이 명시 갱신';
