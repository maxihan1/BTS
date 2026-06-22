-- boards.swimlane_field CHECK 제약에 EPIC 추가 (FR-EP-01 완료로 이연 해제)
-- V501 에서 NONE/ASSIGNEE/PRIORITY 만 허용하던 제약을 EPIC 포함 4종으로 재생성한다.

ALTER TABLE boards
    DROP CONSTRAINT boards_swimlane_field_allowed;

ALTER TABLE boards
    ADD CONSTRAINT boards_swimlane_field_allowed
        CHECK (swimlane_field IN ('NONE', 'ASSIGNEE', 'PRIORITY', 'EPIC'));

COMMENT ON COLUMN boards.swimlane_field IS
    '스윔레인 기준 — NONE/ASSIGNEE/PRIORITY/EPIC (그룹핑은 프론트, EPIC 은 FR-EP-01 완료로 활성화)';
