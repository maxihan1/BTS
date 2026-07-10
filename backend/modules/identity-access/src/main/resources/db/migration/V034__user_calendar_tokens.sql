-- FR-CA-02 iCal Export 익명 구독 토큰. 사용자당 1개(재발급=UPSERT rotate), SHA-256 해시만 저장. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE user_calendar_tokens (
    user_id    UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- user_id 는 PRIMARY KEY 이자 FK(users.id) — PK 인덱스가 곧 FK 조회/CASCADE 인덱스라 별도 FK 인덱스 불요.
-- token_hash UNIQUE 제약이 만드는 인덱스가 익명 피드(GET /ical/feed/{token}.ics) 해시 조회 인덱스를 겸한다.

COMMENT ON TABLE  user_calendar_tokens            IS 'FR-CA-02 iCal Export 익명 구독 토큰 — 외부 캘린더 앱이 Authorization 헤더 없이 구독. 사용자당 1개(재발급=UPSERT rotate). 취소=행 DELETE(임시 자격증명, deleted_at 없음, ADR D4). identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN user_calendar_tokens.user_id    IS 'BTS 사용자 FK(V001 users.id) 겸 PK — 사용자당 활성 토큰 1개. ON DELETE CASCADE — 사용자 삭제 시 토큰 연쇄 삭제 → 구독 URL 즉시 404(오프보딩 유출 벡터 없음)';
COMMENT ON COLUMN user_calendar_tokens.token_hash IS '불투명 랜덤 토큰의 SHA-256 hex 해시(64자). 원문 미저장(발급 응답 1회만 노출, ADR D4). UNIQUE 인덱스가 익명 피드 해시 조회 인덱스 겸용';
COMMENT ON COLUMN user_calendar_tokens.created_at IS '토큰 발급(또는 재발급 rotate) 시각. TIMESTAMPTZ(DATA.md — TIMESTAMP without tz 금지)';
