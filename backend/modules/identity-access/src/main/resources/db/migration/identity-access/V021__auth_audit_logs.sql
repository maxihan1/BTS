-- 인증 감사 로그 — append-only 포렌식 기록(로그인/로그아웃/리프레시/프로비저닝/LDAP 등) — FR-AU-10
-- 파티셔닝 없는 단순 테이블 + 인덱스 3종(1K 규모, SDD §5.14 정합 / §19.9 파티셔닝 일탈 — ADR 2026-06-10-auth-audit-log-persistence).
-- FK 없음: 삭제된 사용자의 과거 행위 기록도 보존(사용자 생명주기 비결합), FK-check 쓰기 오버헤드 회피.
-- DATA.md §3: 감사 로그 절대 삭제 금지(append-only 영구 보존). 따라서 soft-delete 컬럼/삭제 로직 없음.
-- identity-access raw SQL(jOOQ/init_codegen 미러 불요, V016 선례).

CREATE TABLE auth_audit_logs (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            UUID,
    event_type         VARCHAR(40)  NOT NULL,
    provider_id        VARCHAR(50)  NOT NULL,
    ip_address         VARCHAR(45),
    user_agent         TEXT,
    device_fingerprint VARCHAR(255),
    metadata           JSONB        NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- findRecent(userId) 조회 — 특정 사용자의 최근 인증 이벤트(최신순).
CREATE INDEX idx_auth_audit_logs_user_created ON auth_audit_logs(user_id, created_at DESC);

-- 이벤트 유형 필터(후속 admin 조회 — 예: LOGIN_FAILURE만).
CREATE INDEX idx_auth_audit_logs_event_type ON auth_audit_logs(event_type);

-- 시간 범위 조회(후속 admin 감사 기간 검색). 삭제 sweep 아님 — append-only.
CREATE INDEX idx_auth_audit_logs_created_at ON auth_audit_logs(created_at);

COMMENT ON TABLE  auth_audit_logs                    IS '인증 감사 로그 — append-only 포렌식 기록(FR-AU-10, FK 없음, 영구 보존)';
COMMENT ON COLUMN auth_audit_logs.id                 IS '내부 PK(append-only 단조 증가, 정렬 tiebreaker)';
COMMENT ON COLUMN auth_audit_logs.user_id            IS '행위 주체. LOGIN_FAILURE/LDAP_UNAVAILABLE은 null(사용자 미상). FK 없음';
COMMENT ON COLUMN auth_audit_logs.event_type         IS 'AuthEventType enum name(LOGIN_SUCCESS/LOGIN_FAILURE/LOGOUT 등)';
COMMENT ON COLUMN auth_audit_logs.provider_id        IS '인증 제공자(local/ldap/oidc/saml/pat/project-membership 등)';
COMMENT ON COLUMN auth_audit_logs.ip_address         IS '요청 IP(IPv6 최대 45자). web 레이어 emit만 채움';
COMMENT ON COLUMN auth_audit_logs.user_agent         IS '요청 User-Agent. web 레이어 emit만 채움';
COMMENT ON COLUMN auth_audit_logs.device_fingerprint IS '디바이스 지문 — 현재 미사용(FR-MF-05 대비 컬럼만)';
COMMENT ON COLUMN auth_audit_logs.metadata           IS 'Map<String,String> 부가정보(sid/username/reason 등). 빈 map은 ''{}''';
COMMENT ON COLUMN auth_audit_logs.created_at         IS '발생 시각(TIMESTAMPTZ)';
