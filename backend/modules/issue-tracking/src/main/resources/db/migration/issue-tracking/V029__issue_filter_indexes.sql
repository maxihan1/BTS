-- 이슈 목록 status/assignee 필터 가속 복합 부분 인덱스 — FR-SR-01. project_id 범위 내 추가 술어용.

-- 배경: GET /api/v1/issues 목록은 항상 project_id 단위(buildActiveSecureWhere — PROJECTS.KEY 조인 + ISSUES.DELETED_AT IS NULL)로
--   좁혀진 뒤, FR-SR-01 이 status(current_state_key) / assignee(assignee_id) 필터를 추가 술어(AND)로 붙인다.
-- 기존 인덱스: idx_issues_project_id_deleted_at (project_id, deleted_at WHERE deleted_at IS NULL) — project 범위 좁히기만 커버.
--   status/assignee 필터 컬럼은 인덱스 미커버 → 프로젝트 내 잔여 행 필터링이 힙 스캔이 된다.
--
-- 설계(DATA.md §7): 복합 인덱스는 카디널리티 높은 컬럼 먼저 → project_id 선두. 그 뒤 필터 컬럼.
--   부분 인덱스(WHERE deleted_at IS NULL): 목록 쿼리가 항상 활성 이슈만 보므로 idx_issues_project_id_deleted_at 선례와
--   동형으로 활성 행만 커버 → 인덱스 크기 절감 + 술어 정확 일치.
-- plain CREATE INDEX (CONCURRENTLY 미사용): 초기 단계 중소 테이블 + Flyway 기본 트랜잭션 내 CONCURRENTLY 비호환.
--   V026/V028 형제 선례와 일관 (짧은 락).

-- (project_id, current_state_key): status 필터 — current_state_key 정확 매칭/IN(소문자 컨벤션 V004).
CREATE INDEX idx_issues_project_state_active
    ON issues (project_id, current_state_key)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX idx_issues_project_state_active
    IS 'project_id 범위 내 status(current_state_key) 필터 가속 복합 부분 인덱스 — 활성 이슈 기준. FR-SR-01.';

-- (project_id, assignee_id): assignee 필터 — assignee_id IN/IS NULL(미할당). assignee_id NULL 행도 부분 인덱스에 포함되어
--   includeUnassigned(IS NULL) 술어도 인덱스로 커버된다.
CREATE INDEX idx_issues_project_assignee_active
    ON issues (project_id, assignee_id)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX idx_issues_project_assignee_active
    IS 'project_id 범위 내 assignee(assignee_id) 필터 가속 복합 부분 인덱스 — 활성 이슈 기준(미할당 NULL 포함). FR-SR-01.';
