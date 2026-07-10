-- 신뢰 디바이스(30일 MFA 면제)를 저장하는 테이블 (FR-MF-05). token_hash=SHA-256 해시만 저장(평문 비영속). identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE trusted_devices (
    id           UUID        PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   TEXT        NOT NULL UNIQUE,
    label        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ
);

-- FK 인덱스 — 사용자별 신뢰 디바이스 조회/CASCADE 삭제 성능(PostgreSQL은 FK 인덱스 자동 생성 안 함).
CREATE INDEX idx_trusted_devices_user ON trusted_devices(user_id);
-- token_hash 는 UNIQUE 제약이 곧 조회 인덱스(쿠키 rawToken 해시 → 행 조회).

COMMENT ON TABLE  trusted_devices              IS 'FR-MF-05 신뢰 디바이스(30일 MFA 면제). MFA verify 성공 + trust_device 동의 시 1행. identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN trusted_devices.id           IS '신뢰 디바이스 행 PK(UUID). 애플리케이션이 생성해 INSERT(DB default 없음)';
COMMENT ON COLUMN trusted_devices.user_id      IS 'BTS 사용자 FK(V001 users.id). ON DELETE CASCADE — 사용자 삭제 시 신뢰 디바이스 연쇄 삭제. 우회 판정은 user_id bound(타인 쿠키 차단)';
COMMENT ON COLUMN trusted_devices.token_hash   IS 'SHA-256(rawToken) hex 64자. 평문 rawToken 은 발급 시 쿠키에만 1회 노출(비영속·비로깅, DEVELOPMENT.md §1.1.1). UNIQUE — 재신뢰 충돌 차단';
COMMENT ON COLUMN trusted_devices.label        IS 'User-Agent 파생 표시용 라벨(D6 목록 표시). NULL 허용';
COMMENT ON COLUMN trusted_devices.created_at   IS '신뢰 등록 시각. 애플리케이션이 주입 Clock 기반 INSERT(now() default 는 fallback)';
COMMENT ON COLUMN trusted_devices.expires_at   IS '신뢰 만료 시각(등록 +30일 고정, sliding 아님). 조회 술어 expires_at > now 로 만료 행 무효화(인라인 삭제 안 함)';
COMMENT ON COLUMN trusted_devices.last_used_at IS '마지막 우회 로그인 성공 시각(표시용). NULL=등록 후 미사용. expires_at 는 갱신하지 않음';
