-- 이슈 소속 Epic 연결 컬럼(epic_id) — FR-EP-01. parent_id(V021)와 별개 자기참조 FK.

-- ── issues.epic_id: 이슈가 소속된 Epic (링크/parent-child 와 별개 메커니즘) ──────────
-- parent_id(V021 — Subtask 구조 계층)와 별개. epic_id 는 일반 이슈가 어느 Epic 에 묶이는지를 표현한다.
-- 한 이슈는 최대 한 Epic 에 소속되므로 issues 자기참조 컬럼으로 표현 (parent_id 동형).
-- nullable: 소속 Epic 없는 이슈는 NULL. 같은 BC 라 issues 자기참조 실 FK.
-- ON DELETE 기본(NO ACTION): Epic 과 자식은 구조적 소속이라 Epic 삭제를 자식 자동 삭제로 전파하지 않는다
--   (prod 는 소프트 삭제라 FK 미발화. 하드 삭제 시도 시 자식이 있으면 거부되어 무결성 보호).
ALTER TABLE issues ADD COLUMN epic_id UUID NULL REFERENCES issues(id);
COMMENT ON COLUMN issues.epic_id IS '소속 Epic (issues.id 자기참조). NULL=소속 없음. parent_id(Subtask 계층)와 별개 — Epic↔자식 (FR-EP-01).';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함). Epic→소속 이슈 조회용.
-- plain CREATE INDEX (CONCURRENTLY 아님 — V021 형제 일관, Testcontainers 트랜잭션 호환).
CREATE INDEX idx_issues_epic_id ON issues(epic_id);
