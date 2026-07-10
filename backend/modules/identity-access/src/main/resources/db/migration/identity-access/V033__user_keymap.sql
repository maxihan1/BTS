-- FR-PF-03 사용자별 단축키 커스터마이즈(override) 저장 테이블. 기본 단축키를 사용자가 재지정한 항목만 행으로 보관. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

CREATE TABLE user_keymap (
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action     VARCHAR(32) NOT NULL,
    key_combo  VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, action),
    CONSTRAINT user_keymap_action_chk CHECK (action IN
        ('help', 'create-issue', 'search', 'goto-my-issues', 'goto-dashboard'))
);

-- 복합 PK (user_id, action) — 사용자당 action 하나만 override. PK 선두 컬럼이 user_id 라 FK 조회/CASCADE 인덱스도 겸함(별도 FK 인덱스 불요).
-- action CHECK — override 가능한 action 화이트리스트(help/create-issue/search/goto-my-issues/goto-dashboard)를 DB 최후 방어선으로 강제. 앱 우회 raw SQL 쓰기도 차단.
-- key_combo — 재지정 키 조합. 신규 테이블이라 backfill 불요(NOT NULL 안전).

COMMENT ON TABLE  user_keymap            IS 'FR-PF-03 사용자별 단축키 override — 기본 단축키를 재지정한 항목만 저장. identity-access raw SQL(init_codegen 미러 불요)';
COMMENT ON COLUMN user_keymap.user_id    IS 'BTS 사용자 FK(V001 users.id). ON DELETE CASCADE — 사용자 삭제 시 단축키 override 연쇄 삭제';
COMMENT ON COLUMN user_keymap.action     IS '단축키 대상 action 논리 키 — help | create-issue | search | goto-my-issues | goto-dashboard (CHECK 제약으로 화이트리스트 강제)';
COMMENT ON COLUMN user_keymap.key_combo  IS '사용자가 재지정한 키 조합(예: ?, c, /, g i, g d)';
COMMENT ON COLUMN user_keymap.created_at IS '단축키 override 생성 시각';
COMMENT ON COLUMN user_keymap.updated_at IS '단축키 override 최종 수정 시각 (repository가 write마다 now() 세팅)';
