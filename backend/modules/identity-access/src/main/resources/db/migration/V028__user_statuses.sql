-- FR-PR-02 사용자 상태 메시지 (이모지/텍스트/만료). users 1:1 확장. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE user_statuses (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    emoji      VARCHAR(32),
    text       VARCHAR(100),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT user_statuses_emoji_or_text CHECK (emoji IS NOT NULL OR text IS NOT NULL)
);

-- user_id 는 PRIMARY KEY 이자 FK(users.id) — PK 인덱스가 곧 FK 조회/CASCADE 인덱스라 별도 FK 인덱스 불요.
