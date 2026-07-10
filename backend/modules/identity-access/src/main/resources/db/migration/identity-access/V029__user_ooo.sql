-- FR-PR-03 부재중(Out of Office) — 기간/대체담당자/안내메시지. users 1:1 확장. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE user_ooo (
    user_id          UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    starts_at        TIMESTAMPTZ  NOT NULL,
    ends_at          TIMESTAMPTZ  NOT NULL,
    delegate_user_id UUID         REFERENCES users(id) ON DELETE SET NULL,
    message          VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT user_ooo_ends_after_starts CHECK (ends_at > starts_at)
);

-- user_id 는 PRIMARY KEY 이자 FK(users.id) — PK 인덱스가 곧 FK 조회/CASCADE 인덱스라 별도 FK 인덱스 불요.
-- delegate_user_id 는 조회 빈도가 낮고(단건 LEFT JOIN) 카디널리티도 낮아 별도 인덱스는 불요.

COMMENT ON TABLE  user_ooo                   IS 'FR-PR-03 부재중(Out of Office) — 기간/대체담당자/안내메시지 (users 1:1 확장). 해제는 행 DELETE. identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN user_ooo.user_id           IS 'BTS 사용자 FK(V001 users.id) 겸 PK. ON DELETE CASCADE — 사용자 삭제 시 OOO 연쇄 삭제';
COMMENT ON COLUMN user_ooo.starts_at         IS '부재 시작 시각(절대). 미래 허용(예약)';
COMMENT ON COLUMN user_ooo.ends_at           IS '부재 종료 시각(절대). starts_at 보다 이후여야 함 (CHECK)';
COMMENT ON COLUMN user_ooo.delegate_user_id  IS '대체 담당자 FK(V001 users.id). 없어도 OOO 성립. ON DELETE SET NULL — 대리자 삭제 시 OOO 자체는 유지';
COMMENT ON COLUMN user_ooo.message           IS '안내 메시지(표시용, 없으면 NULL)';
COMMENT ON COLUMN user_ooo.created_at        IS 'OOO 최초 생성 시각';
COMMENT ON COLUMN user_ooo.updated_at        IS 'OOO 최종 수정 시각 (repository가 write마다 NOW() 세팅)';
