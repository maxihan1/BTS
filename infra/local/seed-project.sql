-- 로컬 테스트용 ATLAS 프로젝트 시드 + admin 멤버십 (이슈 생성 가능 상태). prod 절대 사용 금지.
--
-- 배경. 앱에 프로젝트 '생성' 기능이 아직 없다(Project Management BC 미구현). 그래서 테스트용
--   프로젝트는 이 스크립트로 시드한다. 이슈를 만들 수 있으려면 프로젝트 row 만으론 부족하고,
--   원래 프로젝트 생성 기능이 해줄 세팅이 함께 필요하다:
--     1) 프로젝트 row
--     2) 기본 권한 스킴 배정(project_permission_scheme) — 없으면 CREATE_ISSUE 판정 불가
--     3) 관리자를 프로젝트 멤버(PROJECT_ADMIN)로 등록 — prod 권한 리졸버는 SYSTEM_ADMIN 자동
--        우회가 없다(ADR §결정5). 멤버십 + 역할이 있어야 CREATE_ISSUE 매트릭스를 통과한다.
--     4) 워크플로우 스킴 명시 배정(project_workflow_scheme_assignments) — 없으면 첫 이슈 생성 시
--        resolveStart 가 auto-assign 을 시도하는데, 그 경로가 ASSIGN_SCHEME 권한을 nil actor 로
--        검사해 WorkflowSchemeAccessDeniedException(500)으로 실패한다. 미리 배정해 auto-assign 을
--        건너뛴다. 이슈 타입 5종은 부팅 시 이미 시드되어 있어 추가 시드 불필요.
--
-- 전제. seed-admin.sql 을 먼저 실행해 admin@bts.com(user_id=...0002)이 존재해야 한다.
-- 실행:
--   docker exec -i bts-postgres psql -U bts -d bts < infra/local/seed-project.sql
-- 반복 실행 안전(ON CONFLICT DO NOTHING).

-- 1. 프로젝트 (key 규칙 CHECK ^[A-Z][A-Z0-9]{1,9}$).
INSERT INTO projects (id, key, name, key_sequence, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'ATLAS', 'Atlas Issues', 0, NOW(), NOW())
ON CONFLICT (key) DO NOTHING;

-- 2. 기본 권한 스킴 배정 — 부팅 시 시드된 Default Permission Scheme(...0001) 연결.
INSERT INTO project_permission_scheme (project_id, scheme_id, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    NOW(),
    NOW()
) ON CONFLICT (project_id) DO NOTHING;

-- 3. admin@bts.com 을 ATLAS 의 PROJECT_ADMIN 멤버로 (CREATE_ISSUE 등 12권한 보유 역할).
INSERT INTO project_memberships (project_id, user_id, role, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000002',
    'PROJECT_ADMIN',
    NOW(),
    NOW()
) ON CONFLICT (project_id, user_id) DO NOTHING;

-- 4. 워크플로우 스킴 명시 배정 — software-scheme. assigned_by 는 admin(...0002).
--    scheme id 는 재시드(down -v) 시 값이 바뀔 수 있으므로 key 로 조회한다.
INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
SELECT
    '00000000-0000-0000-0000-000000000001',
    (SELECT id FROM workflow_schemes WHERE key = 'software-scheme'),
    NOW(),
    '00000000-0000-0000-0000-000000000002'
ON CONFLICT (project_id) DO NOTHING;

-- 5. 스킴의 기본 워크플로우 매핑 — software-scheme → software-default (issue_type_id NULL = default).
--    부팅 시드는 스킴/워크플로우만 만들고 이 매핑은 안 만든다(원래 워크플로우 스킴 설정 UI 의 몫).
--    없으면 resolveStart(null) 가 기본 매핑을 못 찾아 422 workflow_not_configured.
--    workflow_id 는 재시드 시 UUID 가 바뀌므로 key 로 조회. 기본 매핑은 스킴당 1개(부분 UNIQUE).
INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
SELECT
    (SELECT id FROM workflow_schemes WHERE key = 'software-scheme'),
    NULL,
    (SELECT id FROM workflows WHERE key = 'software-default')
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_scheme_issue_type_mappings m
    WHERE m.scheme_id = (SELECT id FROM workflow_schemes WHERE key = 'software-scheme')
      AND m.issue_type_id IS NULL
);
