-- FR-PF-02 user_preferences 에 시작 페이지(start_page) 컬럼 추가. 로그인 후 자동 이동 대상. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

ALTER TABLE user_preferences
    ADD COLUMN start_page VARCHAR(32) NOT NULL DEFAULT 'dashboards';

-- NOT NULL + DEFAULT 'dashboards' — 기존 행은 'dashboards' 로 자동 백필. 값 화이트리스트(dashboards/my_issues/issues/inbox) 검증은 후속 백엔드 task 담당.
COMMENT ON COLUMN user_preferences.start_page IS 'FR-PF-02 로그인 후 시작 페이지 논리 키 — dashboards(기본) | my_issues | issues | inbox';
