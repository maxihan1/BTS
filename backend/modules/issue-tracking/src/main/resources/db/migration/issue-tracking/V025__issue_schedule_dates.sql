-- 이슈 일정 필드 3컬럼(시작일/마감일/목표일) 추가 — FR-PL-01. 날짜 단위라 DATE NULL (시각/타임존 성분 없음)

-- 시작일/마감일/목표일은 모두 선택(NULL=미지정). 교차 검증(start ≤ due 등) 없음 — Jira 정석 (versions.start_date/release_date 동형).
ALTER TABLE issues
    ADD COLUMN start_date  DATE NULL,
    ADD COLUMN due_date    DATE NULL,
    ADD COLUMN target_date DATE NULL;

COMMENT ON COLUMN issues.start_date  IS '이슈 시작 예정일 (선택). 날짜 단위라 DATE.';
COMMENT ON COLUMN issues.due_date    IS '이슈 마감 예정일 (선택). 날짜 단위라 DATE.';
COMMENT ON COLUMN issues.target_date IS '이슈 목표일 (선택). 날짜 단위라 DATE.';
