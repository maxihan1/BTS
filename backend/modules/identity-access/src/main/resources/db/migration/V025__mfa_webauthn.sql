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
