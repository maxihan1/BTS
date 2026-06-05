-- 전역 사용자 그룹 + 멤버십(N:M, 두 FK 모두 ON DELETE CASCADE) — FR-PM-09

CREATE TABLE user_groups (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE group_memberships (
    group_id   UUID        NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id)       ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (group_id, user_id)
);

-- group_id 는 복합 PK 선두라 인덱스 자동 생성. user_id FK 는 별도 인덱스 필요.
CREATE INDEX ix_group_memberships_user ON group_memberships(user_id);
