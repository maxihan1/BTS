-- FR-PR-02 사용자 상태 메시지 (이모지/텍스트/만료). users 1:1 확장. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE user_statuses (
    user_id    UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    emoji      VARCHAR(32),
    text       VARCHAR(100),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT user_statuses_emoji_or_text CHECK (emoji IS NOT NULL OR text IS NOT NULL)
);

-- user_id 는 PRIMARY KEY 이자 FK(users.id) — PK 인덱스가 곧 FK 조회/CASCADE 인덱스라 별도 FK 인덱스 불요.

COMMENT ON TABLE  user_statuses            IS 'FR-PR-02 사용자 상태 메시지 — 이모지/텍스트/만료 (users 1:1 확장). 해제는 행 DELETE. identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN user_statuses.user_id    IS 'BTS 사용자 FK(V001 users.id) 겸 PK. ON DELETE CASCADE — 사용자 삭제 시 상태 연쇄 삭제';
COMMENT ON COLUMN user_statuses.emoji      IS '상태 이모지 문자열 (없으면 NULL). emoji 또는 text 중 최소 하나는 non-null (CHECK)';
COMMENT ON COLUMN user_statuses.text       IS '상태 텍스트 (없으면 NULL). emoji 또는 text 중 최소 하나는 non-null (CHECK)';
COMMENT ON COLUMN user_statuses.expires_at IS '만료 시각(절대, NULL=만료 없음). 조회 시 과거면 만료 처리(lazy 필터)';
COMMENT ON COLUMN user_statuses.created_at IS '상태 최초 생성 시각';
COMMENT ON COLUMN user_statuses.updated_at IS '상태 최종 수정 시각 (repository가 write마다 NOW() 세팅)';
