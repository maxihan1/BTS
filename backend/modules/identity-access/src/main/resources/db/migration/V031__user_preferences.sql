-- FR-PF-01 사용자 환경설정 (테마/로케일/날짜형식). users 1:1 확장. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE user_preferences (
    user_id      UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme        VARCHAR(16)  NOT NULL DEFAULT 'system',   -- light | dark | system
    locale       VARCHAR(16)  NOT NULL DEFAULT 'ko',       -- ko | en (저장만, UI 번역은 후속)
    date_format  VARCHAR(16)  NOT NULL DEFAULT 'iso',      -- iso | kr | us | eu
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- user_id 는 PRIMARY KEY 이자 FK(users.id) — PK 인덱스가 곧 FK 조회/CASCADE 인덱스라 별도 FK 인덱스 불요.

COMMENT ON TABLE  user_preferences             IS 'FR-PF-01 사용자 환경설정 — 테마/로케일/날짜형식 (users 1:1 확장). identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN user_preferences.user_id     IS 'BTS 사용자 FK(V001 users.id) 겸 PK. ON DELETE CASCADE — 사용자 삭제 시 환경설정 연쇄 삭제';
COMMENT ON COLUMN user_preferences.theme       IS 'UI 테마 — light | dark | system (기본 system)';
COMMENT ON COLUMN user_preferences.locale      IS '로케일 — ko | en (기본 ko). 저장만, UI 번역은 후속';
COMMENT ON COLUMN user_preferences.date_format IS '날짜 표시 형식 — iso | kr | us | eu (기본 iso)';
COMMENT ON COLUMN user_preferences.created_at  IS '환경설정 생성 시각';
COMMENT ON COLUMN user_preferences.updated_at  IS '환경설정 최종 수정 시각 (repository가 write마다 NOW() 세팅)';
