<!-- FR-WF-02 project-workflow BC WorkflowScheme + Jira align 모델 — D1~D5 backend spec -->

# FR-WF-02 — WorkflowScheme + 프로젝트별 스킴 + 타입별 워크플로우 매핑 (D1~D5, 1-PR)

> **출처**. SDD §02 FR-WF-02 / SDD §07.1 WorkflowScheme / docs/plan/product/project-workflow.md §2.2
> **본 PR scope**. D1~D5 backend (도메인 + 스펙 + DB + Scheme 관리 API + 백엔드 테스트)
> **후속 PR**. D6 frontend admin UI / D7 E2E / FR-IS-02 커스텀 IssueType + CRUD API + IssueTypeScheme
> **의존 PR**. #10 (FR-WF-01 backend, 머지됨) / #14 (issue-tracking 부트스트랩, 머지됨) / #13 (FR-WF-01 frontend, 머지됨)
> **plan**. `docs/plans/2026-05-23-project-workflow-bc-fr-wf-02-scheme-1-pr.md`
> **결정 4건**. D6 옵션 A (issue-tracking IssueType 사전 도입) / D7 옵션 B (entity + 5 표준 seed) / D8 옵션 B (DB-only + V003 SQL seed) / D9 옵션 C (별도 매핑 + Jira align + SDD §05.3 정정)

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 프로젝트 admin 이 스킴 생성
- **Given**. 프로젝트 ATLAS, admin 권한 사용자 alice
- **When**. `POST /api/v1/workflow-schemes` body `{ key: "team-a-scheme", name: "팀 A 스킴", description: "..." }`
- **Then**. 201 Created, `workflow_schemes` 행 1건 추가, `is_default = false`, response body `{ id, key, name, description, isDefault: false, mappings: [] }`

### S2. 프로젝트 admin 이 스킴 ↔ 이슈 타입 매핑 추가
- **Given**. WorkflowScheme `team-a-scheme` (S1 결과), Workflow `software-default` (PR #10 seed), IssueType `bug` (V003 seed)
- **When**. `POST /api/v1/workflow-schemes/team-a-scheme/mappings` body `{ issueTypeKey: "bug", workflowKey: "software-default" }`
- **Then**. 200 OK, `workflow_scheme_issue_type_mappings` 행 1건 추가, UNIQUE(scheme_id, issue_type_id) constraint 위반 시 409 Conflict

### S3. 프로젝트 admin 이 default 워크플로우 설정 (issue_type_id NULL)
- **Given**. WorkflowScheme `team-a-scheme`
- **When**. `POST /api/v1/workflow-schemes/team-a-scheme/mappings` body `{ issueTypeKey: null, workflowKey: "simple" }`
- **Then**. 200 OK, mapping 행 `issue_type_id IS NULL`, workflow_id = simple. partial UNIQUE INDEX `ix_scheme_default_mapping` 중복 시 409 Conflict

### S4. 프로젝트에 스킴 적용
- **Given**. project ATLAS, scheme `team-a-scheme`
- **When**. `PUT /api/v1/projects/ATLAS/workflow-scheme` body `{ schemeKey: "team-a-scheme" }`
- **Then**. 200 OK, `project_workflow_scheme_assignments` UPSERT (project_id PK), `assigned_at` / `assigned_by` 갱신. pgmq `q_workflow_scheme_events` 에 `WorkflowSchemeAssignedEvent` 발행

### S5. Issue 가 자기 타입의 워크플로우 결정 (inbound resolver)
- **Given**. project ATLAS 가 `team-a-scheme` 적용 + scheme 안에 `(bug → software-default)` + `(null → simple)` 매핑 보유, issue PROJ-1 type=bug
- **When**. `WorkflowResolver.resolveFor(projectKey, issueTypeKey)` 호출 (port — issue-tracking BC consumer)
- **Then**. mappings 에서 bug 매칭 → `software-default` 반환. 매칭 없으면 default (issue_type_id IS NULL) 반환 → `simple`. default 도 없으면 `WorkflowSchemeNoDefaultException` (4 표준 스킴은 default 보장 — V003 seed)

### S6. 표준 스킴 보존 (4 표준)
- **Given**. 4 표준 스킴 (software-scheme / bug-tracking-scheme / simple-scheme / kanban-scheme), V003 SQL INSERT seed (`is_default = true`)
- **When**. `DELETE /api/v1/workflow-schemes/software-scheme`
- **Then**. 403 Forbidden, errorCode `SCHEME_STANDARD_NOT_DELETABLE`. `is_default = true` 스킴 삭제 + key/is_default 수정 모두 불가 (mapping 만 admin 변경 가능 — D11 결정)

### S7. 사용 중인 스킴 삭제 차단
- **Given**. scheme `team-a-scheme` 이 project ATLAS 에 적용 중 (`project_workflow_scheme_assignments` 1 row)
- **When**. `DELETE /api/v1/workflow-schemes/team-a-scheme`
- **Then**. 409 Conflict, errorCode `SCHEME_IN_USE`, body `{ usedByProjects: ["ATLAS"] }`. 다른 프로젝트에 적용 변경 후 재시도 안내

### S8. 5 표준 IssueType 조회 (read-only, 본 PR scope)
- **Given**. V003 seed (Epic/Story/Task/Subtask/Bug)
- **When**. `GET /api/v1/issue-types`
- **Then**. 200 OK, 5건 + `is_standard = true`. 커스텀 IssueType + CRUD API 는 FR-IS-02 후속 PR

## 2. 기능 요구사항 (FR)

| ID | 요구사항 | 우선순위 |
|---|---|---|
| FR-WF-02-01 | WorkflowScheme CRUD (create / read / update / delete / list) | 필수 |
| FR-WF-02-02 | WorkflowSchemeIssueTypeMapping CRUD (1 scheme 안 N mapping) | 필수 |
| FR-WF-02-03 | Project ↔ WorkflowScheme assignment (1 project = 1 scheme, UPSERT) | 필수 |
| FR-WF-02-04 | 4 표준 스킴 V003 SQL INSERT seed (`is_default = true`) — software-scheme / bug-tracking-scheme / simple-scheme / kanban-scheme | 필수 |
| FR-WF-02-05 | 5 표준 IssueType V003 SQL INSERT seed (issue-tracking 모듈, `is_standard = true`) — epic / story / task / subtask / bug | 필수 |
| FR-WF-02-06 | WorkflowResolver outbound port — `fun resolveFor(projectKey: ProjectKey, issueTypeKey: IssueTypeKey?): Workflow`. `@Transactional(readOnly = true)` + `Propagation.MANDATORY` (PR #10 WorkflowEngine.plan() 패턴 일치, G1/G14). 예외. `ProjectNotFoundException` (issue-tracking BC 예외 재사용) / `WorkflowSchemeNoDefaultException` (server invariant 위반, EC-2). consumer wiring 본 PR scope 외 (G2 — PR #17 후속). | 필수 |
| FR-WF-02-07 | 표준 스킴/타입 삭제 차단 (is_default = true / is_standard = true) | 필수 |
| FR-WF-02-08 | 사용 중인 스킴 삭제 차단 (project_workflow_scheme_assignments 존재 시) | 필수 |
| FR-WF-02-09 | WorkflowScheme 변경 이벤트 발행 (pgmq `q_workflow_scheme_events`) — assigned / updated / deleted. 본 PR consumer 0 (G3 — 후속 BC 작업) | 필수 |
| FR-WF-02-10 | IssueType read-only API (`GET /api/v1/issue-types`) — 본 PR scope, CRUD 후속 FR-IS-02 PR | 필수 |
| FR-WF-02-11 | **WorkflowSchemePermission enum + WorkflowSchemePermissionResolver outbound port** (D12 결정 — project-workflow BC 자체 정의, PR #14 IssuePermissionResolver 패턴 그대로 복제). enum 2종 — `MANAGE_SCHEME` (CRUD/매핑 변경) + `ASSIGN_SCHEME` (프로젝트 적용). `WorkflowSchemeScope` sealed — `Global` / `Project(key: String)`. AlwaysAllow stub @Profile("!prod") + FR-PM-04 후속 정식 resolver 교체 | 필수 |

## 3. 비기능 요구사항 (NFR)

| ID | 임계 | 비고 |
|---|---|---|
| NFR-1 | 스킴 매핑 조회 (S5 흐름 `WorkflowResolver.resolveFor`) p95 < **50ms** | k6, projects + assignments + mappings + workflows 4 JOIN (또는 캐시) |
| NFR-2 | Scheme CRUD API p95 < **200ms** | k6 |
| NFR-3 | V003 seed idempotent — dirty diff 0 시 재실행 NOP | Flyway baseline check + 통합 테스트 |
| NFR-4 | DB 무결성. `project_workflow_scheme_assignments.project_id ↔ projects.id` FK, ON DELETE CASCADE | 프로젝트 삭제 시 assignment 자동 정리 |
| NFR-5 | DB 무결성. `workflow_scheme_issue_type_mappings.issue_type_id ↔ issue_types.id` FK, ON DELETE RESTRICT | 타입 삭제 전 매핑 해제 강제 |
| NFR-6 | DB 무결성. `workflow_scheme_issue_type_mappings.workflow_id ↔ workflows.id` FK, ON DELETE RESTRICT | workflow 삭제 전 매핑 해제 강제 |
| NFR-7 | ArchUnit. project-workflow BC ↛ issue-tracking domain 패키지 import 0 (단, SQL 수준 FK 만 cross-BC 허용 — V003 마이그레이션 안) | learnings #BC 격리 |
| NFR-8 | WCAG 2.1 AA. backend 만이라 미적용 (D6 frontend PR 영역) | — |

## 4. API 인터페이스 (REST)

### 4.1 Scheme CRUD (5 endpoint)
- `POST /api/v1/workflow-schemes` — 스킴 생성
- `GET /api/v1/workflow-schemes` — 스킴 목록 (페이지네이션 — page/size query)
- `GET /api/v1/workflow-schemes/{schemeKey}` — 스킴 단건 (mappings 동봉)
- `PUT /api/v1/workflow-schemes/{schemeKey}` — 스킴 수정 (name/description 만, key/is_default 변경 불가)
- `DELETE /api/v1/workflow-schemes/{schemeKey}` — 스킴 삭제 (S6/S7 차단)

### 4.2 Mapping CRUD (2 endpoint)
- `POST /api/v1/workflow-schemes/{schemeKey}/mappings` — 매핑 추가 (`{ issueTypeKey: string | null, workflowKey: string }`)
- `DELETE /api/v1/workflow-schemes/{schemeKey}/mappings/{mappingId}` — 매핑 삭제

### 4.3 Project assignment (2 endpoint)
- `PUT /api/v1/projects/{projectKey}/workflow-scheme` — 프로젝트에 스킴 적용 (UPSERT)
- `GET /api/v1/projects/{projectKey}/workflow-scheme` — 현재 적용 스킴 조회

### 4.4 IssueType (read-only, 본 PR scope)
- `GET /api/v1/issue-types` — 5 표준 타입 목록 (`is_standard = true` 필터 기본). 커스텀 CRUD 는 FR-IS-02 후속

**합계 10 endpoint**. 모든 endpoint 에 **`WorkflowSchemePermissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Project(projectKey))`** 권한 검증 (D12 채택 — project-workflow BC 자체 port, PR #14 IssuePermissionResolver 패턴 그대로). Project assignment (4.3 PUT) 만 `ASSIGN_SCHEME`. `AlwaysAllowWorkflowSchemePermissionResolver` stub `@Profile("!prod")` 우선, FR-PM-04 진척 시 정식 RBAC resolver 교체. Mapping CRUD (4.2) 는 **DELETE + POST 패턴 (Jira align)** — PUT update endpoint 추가 안 함 (G6). mapping_id BIGINT REST path 노출 (G8).

### 4.5 RFC 7807 ProblemDetail 에러 코드 (errorCode)
- `SCHEME_KEY_INVALID` (400 — REGEX 위반)
- `SCHEME_NOT_FOUND` (404)
- `SCHEME_STANDARD_NOT_DELETABLE` (403 — S6)
- `SCHEME_IN_USE` (409 — S7, body `{ usedByProjects: string[] }`)
- `MAPPING_DUPLICATE` (409 — UNIQUE 위반)
- `MAPPING_DEFAULT_DUPLICATE` (409 — partial UNIQUE 위반)
- `WORKFLOW_NOT_FOUND` (404 — workflow_key 무효)
- `ISSUE_TYPE_NOT_FOUND` (404 — issue_type_key 무효)
- `TYPE_STANDARD_NOT_DELETABLE` (403 — EC-5)
- `WORKFLOW_SCHEME_NO_DEFAULT` (500 — S5 default 없음, server invariant 위반)

## 5. 데이터 모델 (DB schema, V003)

### 5.1 V004 (project-workflow 모듈 namespace — D13 단일 sequence 통합)

> **D13 채택**. issue-tracking V003 (5.2) 가 먼저 + project-workflow V004 가 그 다음. 단일 Flyway SchemaHistory 통합 — BTS single DB instance 모델 일치. cross-BC FK (`workflow_scheme_issue_type_mappings.issue_type_id → issue_types.id`) 의 마이그레이션 순서 보장됨 (V003 → V004).

```sql
-- 5.1.1 workflow_schemes
CREATE TABLE workflow_schemes (
  id BIGSERIAL PRIMARY KEY,
  key VARCHAR(30) NOT NULL UNIQUE,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  is_default BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ
);
CREATE INDEX ix_workflow_schemes_key_active ON workflow_schemes (key) WHERE deleted_at IS NULL;

-- 5.1.2 project_workflow_scheme_assignments (Jira align 별도 매핑 테이블)
CREATE TABLE project_workflow_scheme_assignments (
  project_id BIGINT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
  workflow_scheme_id BIGINT NOT NULL REFERENCES workflow_schemes(id) ON DELETE RESTRICT,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  assigned_by UUID NOT NULL
);
CREATE INDEX ix_pwsa_scheme ON project_workflow_scheme_assignments (workflow_scheme_id);

-- 5.1.3 workflow_scheme_issue_type_mappings (Jira workflowschemeentity 패턴)
CREATE TABLE workflow_scheme_issue_type_mappings (
  id BIGSERIAL PRIMARY KEY,
  scheme_id BIGINT NOT NULL REFERENCES workflow_schemes(id) ON DELETE CASCADE,
  issue_type_id BIGINT REFERENCES issue_types(id) ON DELETE RESTRICT,
  workflow_id BIGINT NOT NULL REFERENCES workflows(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_scheme_issue_type UNIQUE (scheme_id, issue_type_id)
);
-- partial UNIQUE INDEX. issue_type_id NULL (default mapping) 도 스킴당 1건만
CREATE UNIQUE INDEX ix_scheme_default_mapping
  ON workflow_scheme_issue_type_mappings (scheme_id)
  WHERE issue_type_id IS NULL;

-- 5.1.4 pgmq 이벤트 큐
SELECT pgmq.create('q_workflow_scheme_events');

-- 5.1.5 4 표준 스킴 V003 SQL INSERT seed
INSERT INTO workflow_schemes (key, name, description, is_default) VALUES
  ('software-scheme', 'Software 표준 스킴', '소프트웨어 개발 팀의 기본 워크플로우 스킴', true),
  ('bug-tracking-scheme', '버그 추적 스킴', '버그 라이프사이클 추적 워크플로우 스킴', true),
  ('simple-scheme', '단순 스킴', 'Open / Closed 2단계 단순 스킴', true),
  ('kanban-scheme', '칸반 기본 스킴', '칸반 방식 작업 흐름 스킴', true);

-- 5.1.6 4 표준 스킴 default mapping seed (issue_type_id NULL = default workflow)
INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
SELECT s.id, NULL, w.id
FROM workflow_schemes s
JOIN workflows w ON
  (s.key = 'software-scheme'     AND w.key = 'software-default') OR
  (s.key = 'bug-tracking-scheme' AND w.key = 'bug-tracking') OR
  (s.key = 'simple-scheme'       AND w.key = 'simple') OR
  (s.key = 'kanban-scheme'       AND w.key = 'kanban-basic');
```

### 5.2 V003 (issue-tracking 모듈 namespace)

```sql
-- 5.2.1 issue_types
CREATE TABLE issue_types (
  id BIGSERIAL PRIMARY KEY,
  key VARCHAR(30) NOT NULL UNIQUE,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  icon_name VARCHAR(50),
  is_standard BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ
);
CREATE INDEX ix_issue_types_key_active ON issue_types (key) WHERE deleted_at IS NULL;

-- 5.2.2 5 표준 IssueType V003 SQL INSERT seed
INSERT INTO issue_types (key, name, description, icon_name, is_standard) VALUES
  ('epic',    'Epic',    '큰 작업 단위 (자식 이슈 보유)', 'epic',    true),
  ('story',   'Story',   '사용자 가치 단위',              'story',   true),
  ('task',    'Task',    '일반 작업',                     'task',    true),
  ('subtask', 'Subtask', '하위 작업',                     'subtask', true),
  ('bug',     'Bug',     '결함',                          'bug',     true);
```

### 5.3 projects 테이블 (PR #14 V001) 정정 결정 (SDD §05.3)

PR #14 V001 의 `projects(id, key UNIQUE, name, key_sequence, deleted_at)` 는 SDD §05.3 의 `workflow_scheme_id BIGINT FK` 컬럼 누락. 본 PR 에서는 컬럼 추가 안 함 — D9 옵션 C (별도 매핑 테이블 + Jira align) 채택. SDD §05.3 의 projects 컬럼 직접 FK 모델 정의 정정 (`docs/sdd/05-data-model.md` 의 line 62 의 `workflow_scheme_id BIGINT (FK)` 행 정정 + 별도 매핑 테이블 모델 본문 추가). ADR `2026-05-24-project-scheme-mapping-jira-align` 연결.

비슷한 SDD §05.3 의 `permission_scheme_id` (line 63) / `notification_scheme_id` (line 64) / `issue_type_scheme_id` (line 65) 컬럼 3건도 동일 정정 후보 — 본 PR 은 `workflow_scheme_id` 만, 나머지 3건은 후속 FR PR (FR-PM-04 / 알림 BC / FR-IS-02) 에서 같은 ADR 패턴 적용.

## 6. 엣지 케이스 (EC)

- **EC-1**. `project_workflow_scheme_assignments` 행 없는 프로젝트의 issue transition → **D10 (a) 채택**. `WorkflowResolver` 가 software-scheme auto-assign 후 resolve (Jira default scheme 패턴 align). UPSERT 로 assignment 행 생성 + assigned_by = system actor UUID. 첫 transition 시 1회만, 이후 admin 이 명시적 변경 가능.
- **EC-2**. `workflow_scheme_issue_type_mappings` 안에 default (issue_type_id NULL) 0 + 매칭 안 된 type → `WorkflowSchemeNoDefaultException` (server invariant 위반). 4 표준 스킴은 V004 seed 로 default 보장. 커스텀 스킴 생성 시 default mapping 강제 검증 (FR-WF-02-02 application layer 검증).
- **EC-3**. 동시 (race) scheme 적용 + 삭제 — `WorkflowApplicationService` 에 `Propagation.REQUIRED` + UPSERT (project_id PK). DELETE 호출은 assignment 존재 시 FR-WF-02-08 차단.
- **EC-4**. `is_default = true` 스킴의 mapping 수정 시도 → **D11 (a) 채택**. mapping 변경 가능 (default mapping 추가/변경 admin OK, 추가 mapping 자유). `key` / `name` / `description` / `is_default` 4 필드만 lock — 시도 시 403 Forbidden errorCode `SCHEME_STANDARD_FIELD_LOCKED`. 4 표준 스킴은 V004 seed 로 default mapping 시작하되 admin 이 confidence 갖고 customization 가능.
- **EC-5**. `issue_types` 의 표준 타입 (is_standard = true) 삭제 시도 → 403 Forbidden, errorCode `TYPE_STANDARD_NOT_DELETABLE`.
- **EC-6**. Workflow 삭제 시 (PR #10 의 Workflow) — `workflow_scheme_issue_type_mappings` 에 사용 중이면 ON DELETE RESTRICT 차단. application layer 에서 친절한 errorCode `WORKFLOW_IN_USE` (mapping list 반환).
- **EC-7**. WorkflowResolver `resolveFor(projectKey, issueTypeKey)` 호출 시 projectKey 무효 → `ProjectNotFoundException` (issue-tracking BC 의 기존 예외).
- **EC-8**. JSON request body 의 `issueTypeKey` 가 표준 타입 외 (커스텀 미지원 본 PR scope) → `ISSUE_TYPE_NOT_FOUND` (404). 후속 FR-IS-02 PR 에서 커스텀 타입 도입 시 자동 해소.
- **EC-9** (G12 신규). `workflow_schemes.deleted_at IS NOT NULL` + 그 scheme 이 `project_workflow_scheme_assignments` 에 사용 중 → soft-delete 갱신 거부 (FR-WF-02-08 의 hard delete RESTRICT 와 동일 동작). errorCode `SCHEME_IN_USE` 동일 반환.
- **EC-10** (G13 신규). 동시 race Mapping POST + Scheme DELETE → `ON DELETE CASCADE` (5.1.3 schema) 로 자동 정리. race window 최소화 — 추가 application 단 lock 불필요.

## 7. 제약 조건

- **본 PR scope D1~D5 backend 만**. D6 frontend admin UI + D7 E2E 후속 PR.
- **Issue.type 컬럼 추가 + Issue Aggregate 통합은 본 PR scope 외** (PR #17 후속, Issue Aggregate 안정화 후 별도 PR).
- **IssueTypeScheme (SDD §05 line 65) + 커스텀 IssueType CRUD API + Issue.type FK 는 FR-IS-02 본업 후속 PR**.
- **IssuePermissionResolver (PR #14 outbound port) 활용** — admin 권한 검증 AlwaysAllow stub 우선. FR-PM-04 진척 시 정식 resolver 교체.
- **SDD §05.3 정정 commit 본 PR scope 포함** — D9 옵션 C 채택 결과, v0.5.0 봉인 변경 + ADR `project-scheme-mapping-jira-align` 정당화.
- **외부 의존성 추가 0** — pgmq / Flyway / jOOQ / Testcontainers 모두 기존. 새 패키지 없음.

## 8. 측정 가능한 완료 기준

- [ ] 4 표준 스킴 V003 SQL seed + 5 표준 IssueType V003 SQL seed 모두 idempotent (Flyway baseline 재실행 NOP)
- [ ] **10 endpoint** 동작 (Testcontainers 통합) — Scheme CRUD 5 + Mapping CRUD 2 + Project assignment 2 + IssueType read 1
- [ ] `WorkflowResolver.resolveFor` port — 4 표준 스킴 모두 default mapping → workflow 정확 결정 (단위 + 통합 8 시나리오)
- [ ] ArchUnit. project-workflow BC ↛ issue-tracking domain import 0 (단, V003 SQL FK 만 cross-BC 허용)
- [ ] NFR-1 (스킴 매핑 조회 p95 < 50ms) 충족 — 통합 테스트 측정
- [ ] **ADR 3건 작성**. `workflow-scheme-storage` (D8) / `issue-type-cross-bc-introduction` (BC 격리 예외) / `project-scheme-mapping-jira-align` (D9)
- [ ] **SDD §05.3 정정 commit** — line 62 의 `workflow_scheme_id BIGINT (FK)` 행 정정 + 별도 매핑 테이블 모델 본문 추가
- [ ] `domain/project-workflow.md` 의 stale `WorkflowAssignment` 표현 → `WorkflowScheme` 정정 후보 명시 (Maxi 수동 영역, 본 PR 머지 후 처리)
- [ ] `glossary.md` 5 용어 등록 후보 명시 (Maxi 수동 영역, 본 PR 머지 후 처리)
- [ ] pgmq `q_workflow_scheme_events` 큐 동작 검증 — `WorkflowSchemeAssignedEvent` send / poll

## 9. 핵심 결정 사항 요약 (D6~D13)

| ID | 결정 | 옵션 | 정당화 |
|---|---|---|---|
| D6 | IssueType 의존성 처리 | A (사전 도입) | FR-WF-02 의 타입별 매핑 키로 IssueType 필요, FR-IS-02 영역 일부 사전 도입 |
| D7 | IssueType 도입 범위 | B (entity + 5 표준 seed) | 본 PR scope 균형, 커스텀/FK/CRUD 후속 |
| D8 | WorkflowScheme storage | B (DB-only + V004 SQL seed) | D9 옵션 C 자연 연장, admin UI 가 만들 수 있는 모델 일관 |
| D9 | Project ↔ Scheme 매핑 | C (별도 매핑 + SDD §05.3 정정) | Jira high-level 모델 align, BC 격리, nodeassociation over-engineered 비도입 |
| **D10** | EC-1 (assignment 없는 프로젝트) | **A 채택** | software-scheme auto-assign + UPSERT 1회 (Jira default scheme 패턴 align) |
| **D11** | EC-4 (표준 스킴 mapping 수정) | **A 채택** | mapping 변경 가능 (default 추가/변경 + 추가 mapping admin OK), key/name/description/is_default lock |
| **D12** | MANAGE_WORKFLOW 권한 위치 | **A 채택** | project-workflow BC 자체 WorkflowSchemePermission enum + WorkflowSchemePermissionResolver outbound port. PR #14 IssuePermissionResolver 패턴 그대로 복제, BC 격리 OK |
| **D13** | Flyway V-number 충돌 | **A 채택** | issue-tracking V003 + project-workflow V004 (단일 SchemaHistory 통합, BTS single DB instance 모델 일치) |
