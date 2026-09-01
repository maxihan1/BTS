-- boards.board_type 추가 (FR-BD-04 V505) — 보드 종류(스크럼/칸반)
-- ⚠ V번호는 머지 직전 origin/main 의 agile-planning 최신 V번호를 재확인할 것 (동시 브랜치 Flyway checksum 충돌 회피, DATA.md §4.1).
--
-- 설계 정본. docs/adr/2026-09-01-board-type-and-active-sprint.md (채택) D1.
-- Jira Cloud 는 보드를 만들 때 "Create a Scrum board" / "Create a Kanban board" 를 **첫 질문**으로 묻는다
-- (support.atlassian.com/jira-software-cloud/docs/create-a-board/ · 2026-09-01 조회).
-- 보드의 의미 자체를 가르는 축이므로 설정이 아니라 정체성으로 본다.

ALTER TABLE boards
    ADD COLUMN board_type VARCHAR(16) NOT NULL DEFAULT 'KANBAN'
        CONSTRAINT boards_board_type_allowed CHECK (board_type IN ('SCRUM', 'KANBAN'));

COMMENT ON COLUMN boards.board_type IS
    'SCRUM/KANBAN. 기본 KANBAN — 이 기본값이 기존 보드 전량을 무변경으로 보존한다(승격하면 카드가 활성 스프린트 것만 남아 사용자가 보던 것이 사라진다). 생성 후 변경 경로는 두지 않는다(ADR X3 — Jira 문서도 변경 경로를 명시하지 않아 근거 없이 만들지 않는다).';

-- 되돌리기. `ALTER TABLE boards DROP COLUMN board_type;` 으로 완전 원복된다(데이터 손실은 종류 정보뿐).
