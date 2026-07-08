-- FR-PF-02 user_preferences 에 시작 페이지(start_page) 컬럼 추가. 로그인 후 자동 이동 대상. identity-access raw SQL(init_codegen 미러 불요 — jdbc-only)

ALTER TABLE user_preferences
    ADD COLUMN start_page VARCHAR(16) NOT NULL DEFAULT 'dashboards'
        CHECK (start_page IN ('dashboards', 'my_issues', 'issues', 'inbox'));

-- VARCHAR(16): 형제 컬럼(theme/locale/date_format) 과 길이 일치, 최장값 'dashboards'(10자) 수용.
-- NOT NULL + DEFAULT 'dashboards' — 기존 행은 'dashboards' 로 자동 백필.
-- CHECK 제약 — 값 화이트리스트(dashboards/my_issues/issues/inbox)를 DB 최후 방어선으로 강제. 앱 우회 raw SQL 쓰기도 차단.
COMMENT ON COLUMN user_preferences.start_page IS 'FR-PF-02 로그인 후 시작 페이지 논리 키 — dashboards(기본) | my_issues | issues | inbox (CHECK 제약으로 화이트리스트 강제)';
