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
