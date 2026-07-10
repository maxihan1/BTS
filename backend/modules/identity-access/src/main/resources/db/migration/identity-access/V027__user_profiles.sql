-- FR-PR-01 사용자 프로필 (아바타/타임존/부서). display_name은 users 테이블에 유지. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE user_profiles (
    user_id           UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    avatar_object_key TEXT,
    timezone          VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    department        VARCHAR(255),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- user_id 는 PRIMARY KEY 이자 FK(users.id) — PK 인덱스가 곧 FK 조회/CASCADE 인덱스라 별도 FK 인덱스 불요.

COMMENT ON TABLE  user_profiles                   IS 'FR-PR-01 사용자 프로필 확장 — 아바타/타임존/부서 (display_name은 users 유지). identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN user_profiles.user_id           IS 'BTS 사용자 FK(V001 users.id) 겸 PK. ON DELETE CASCADE — 사용자 삭제 시 프로필 연쇄 삭제';
COMMENT ON COLUMN user_profiles.avatar_object_key IS 'MinIO 아바타 오브젝트 키 (없으면 NULL). 응답 avatarUrl은 이 값 유무로 파생';
COMMENT ON COLUMN user_profiles.timezone          IS 'IANA 타임존 (기본 UTC)';
COMMENT ON COLUMN user_profiles.department        IS '부서 (nullable)';
COMMENT ON COLUMN user_profiles.created_at        IS '프로필 생성 시각';
COMMENT ON COLUMN user_profiles.updated_at        IS '프로필 최종 수정 시각';
