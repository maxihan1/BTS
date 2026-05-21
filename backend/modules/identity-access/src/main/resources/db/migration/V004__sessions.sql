-- FR-AU-09 세션 관리 테이블 — 로그인 1회 = row 1건 (device 단위, SDD 19.5)

CREATE TABLE sessions (
    id                 UUID        PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider_id        VARCHAR(64) NOT NULL,
    device_fingerprint VARCHAR(128),
    ip_address         INET,
    user_agent         TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at         TIMESTAMPTZ NOT NULL,
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at         TIMESTAMPTZ,
    revoke_reason      VARCHAR(64)
);

-- active session 조회 (user_id 기준) — revoked_at IS NULL 부분 인덱스
CREATE INDEX idx_sessions_user_active    ON sessions(user_id)    WHERE revoked_at IS NULL;
-- GC / 만료 정리 조회 (expires_at 기준) — revoked_at IS NULL 부분 인덱스
CREATE INDEX idx_sessions_expires_active ON sessions(expires_at) WHERE revoked_at IS NULL;

-- SDD 19.5 명세 인용 컬럼 주석
COMMENT ON TABLE  sessions                    IS 'FR-AU-09 세션 — 로그인 1회(device 단위) = row 1건. SDD 19.5';
COMMENT ON COLUMN sessions.id                 IS 'sid (JWT sid claim 원천). UUID v4 — 호출 측에서 생성하여 INSERT';
COMMENT ON COLUMN sessions.user_id            IS 'BTS 사용자 FK (V001 users.id). ON DELETE CASCADE';
COMMENT ON COLUMN sessions.provider_id        IS '인증에 사용된 Provider ID (예: "local", "ldap-corp"). SDD 19.2';
COMMENT ON COLUMN sessions.device_fingerprint IS 'Server 생성 — SHA-256(User-Agent + IP) hex 12자. Privacy 보호 (FR-AU-09 FR-09-23)';
COMMENT ON COLUMN sessions.ip_address         IS '로그인 시점 클라이언트 IP. INET 타입 — IPv4/IPv6 모두 허용';
COMMENT ON COLUMN sessions.user_agent         IS '클라이언트 User-Agent 전문. VARCHAR(512) 초과 가능성으로 TEXT 채택 (spec §5 V004 주석)';
COMMENT ON COLUMN sessions.created_at         IS '세션 최초 생성 시각 (로그인 시각)';
COMMENT ON COLUMN sessions.expires_at         IS '세션 만료 시각. Refresh Token 14일과 동일 — 로그인 후 now()+14d. SDD 19.5';
COMMENT ON COLUMN sessions.last_seen_at       IS '마지막 API 요청 시각. best-effort 갱신 (lost update 허용 — 통계용). SDD §3';
COMMENT ON COLUMN sessions.revoked_at         IS 'NULL = 활성. NOT NULL = 폐기(로그아웃/replay revoke). lazy revoke 보조 (EC-12)';
COMMENT ON COLUMN sessions.revoke_reason      IS '폐기 사유 코드 (예: "logout", "logout_all", "replay_detected"). EC-05/EC-12';
