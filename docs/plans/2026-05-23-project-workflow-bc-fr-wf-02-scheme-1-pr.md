<!-- FR-WF-02 project-workflow BC WorkflowScheme — D1~D5 backend plan stub (워크플로우 단계별 채워짐) -->

# FR-WF-02 project-workflow BC — WorkflowScheme + 프로젝트별 스킴 + 타입별 워크플로우 매핑 (D1~D5, 1-PR)

> slug. `project-workflow-bc-fr-wf-02-scheme-1-pr`
> type. backend (classify-task 원본 `qa` override — D1~D5 token 오분류, learnings L1 누적 패턴)
> primary agent. backend-engineer (+ D4 security-engineer 검토)
> primary BC. project-workflow
> 의존 PR. #10 (FR-WF-01 backend, 머지됨), #13 (FR-WF-01 frontend, 머지됨)
> 생성. 2026-05-23

## Brief

**사용자 원문**. "FR-WF-02 backend 구현 진행하자. WorkflowScheme 도메인 + workflow_schemes / project_workflow_scheme_map / scheme_issue_type_workflow 테이블 3건 + Scheme 관리 API. D1~D5 한 PR, frontend (D6) 와 E2E (D7) 는 후속 PR. Plan slug `workflow/scheme`. FR-WF-01 PR #10 패턴 참고."

**SDD/plan 출처**.
- `docs/plan/product/project-workflow.md §2.2 FR-WF-02` — D1~D7 7단계 정의
- `docs/sdd/` — 워크플로우 도메인 챕터 (도메인 단계에서 정확 발췌)

**scope (본 PR)**.
- D1 도메인. WorkflowScheme 책임 (backend-engineer)
- D2 명세 (backend-engineer)
- D3 데이터 모델. `workflow_schemes` + `project_workflow_scheme_map` + `scheme_issue_type_workflow` 3 테이블 (db-engineer)
- D4 backend. Scheme 관리 API (backend-engineer + security-engineer 검토)
- D5 backend 테스트 (backend-engineer)

**scope 외 (후속 PR)**.
- D6 frontend UI. 프로젝트 설정 → 워크플로우 (designer → frontend-engineer)
- D7 E2E (qa-engineer)

## 충돌 분석 (worktree 진입 시점)

| 후보 worktree | 충돌 영역 | 영향 |
|---|---|---|
| PR #16 (ui/workflow-diagram-c2-c3-followup) | frontend cosmetic (MSW fixture + classDef) | 0 — 본 PR backend 도메인, 영역 분리 |
| PR #17 (backend/issue-tracking-bc-fr-is-01-business-logic) | issue-tracking BC | 0 — 본 PR project-workflow BC, BC 격리 |

본 worktree 진입 안전.

## 도메인 정리 (`/bts-domain` 채움 — 2026-05-24)

### BC 책임 분리

- **주 BC. project-workflow** (본 PR scope 본업). FR-WF-02 — WorkflowScheme + 프로젝트별 스킴 적용 + 이슈 타입별 워크플로우 매핑.
- **부 BC. issue-tracking** (D6/D7 결정으로 scope 확장). IssueType entity + `issue_types` 테이블 + 5 표준 seed 도입. FR-IS-02 본업의 일부 사전 도입 (커스텀 IssueType + IssueType CRUD API + Issue.type FK 는 후속 PR).

**BC 격리 예외 정당화**. FR-WF-02 의 "타입별 매핑" 키로 IssueType 필요. IssueType 본 정의가 issue-tracking BC (SDD §05.2) 영역이라 직접 의존. learnings PR #13 옵션 C 패턴의 inverse 사례 (frontend PR 안에 same BC backend view layer patch ↔ project-workflow PR 안에 different BC IssueType entity 도입). 본 PR 머지 후 후속 FR-IS-02 PR 이 커스텀 IssueType + CRUD API + IssueTypeScheme 진척.

### 신규 엔티티 / VO

#### A. project-workflow BC (주) — WorkflowScheme 도입

| 이름 | 종류 | 책임 |
|---|---|---|
| `WorkflowScheme` | Aggregate Root | 워크플로우 묶음. key(UNIQUE) + name + description + isDefault. 4 표준 seed (`software-scheme` / `bug-tracking-scheme` / `simple-scheme` / `kanban-scheme`) — 후속 결정 (D8). |
| `WorkflowSchemeId` | Value Object | UUID 내부 식별자 |
| `WorkflowSchemeKey` | Value Object | `@JvmInline` + REGEX `^[a-z][a-z0-9-]{1,29}$` (key 안정성, FR-WF-01 의 workflow.key 패턴 일치) |
| `ProjectWorkflowSchemeAssignment` | Aggregate Root | 프로젝트 ↔ 스킴 N:1 (한 프로젝트 = 한 스킴). **별도 매핑 테이블 (Jira high-level 모델 align — D9 옵션 C 채택)**. domain/project-workflow.md 의 stale `WorkflowAssignment` 를 정정 대체. |
| `SchemeIssueTypeWorkflowMapping` | Entity (하위) | 스킴 ↔ {issue_type_id → workflow_id} 1:N 매핑. nullable issue_type_id (NULL = default workflow, 매핑되지 않은 type 대상). **Jira workflowschemeentity 패턴 일치**. |

**카디널리티 결정** (spec §5 본문 inline, D9 옵션 C Jira align 모델).
- Project N — 1 WorkflowScheme (별도 매핑 테이블 `project_workflow_scheme_assignments` 경유 — Jira align, projects 직접 컬럼 FK 아님)
- WorkflowScheme 1 — N WorkflowSchemeIssueTypeMapping
- WorkflowScheme N — N Workflow (mapping 테이블 경유, 같은 워크플로우 여러 스킴에서 재사용 가능)
- WorkflowSchemeIssueTypeMapping 1 — 1 IssueType (nullable, NULL = default mapping — Jira workflowschemeentity 의 issuetype=NULL 패턴 일치)

#### B. issue-tracking BC (부) — IssueType 사전 도입

| 이름 | 종류 | 책임 |
|---|---|---|
| `IssueType` | Aggregate Root | 이슈 타입 정의. key + name + description + iconName + isStandard BOOLEAN. 5 표준 seed (Epic/Story/Task/Subtask/Bug, `is_standard = true`). |
| `IssueTypeId` | Value Object | BIGINT 내부 식별자 (SDD §05 line 10 의 `issue_type_id BIGINT FK` 일치) |
| `IssueTypeKey` | Value Object | `@JvmInline` + REGEX `^[a-z][a-z0-9-]{1,29}$` (PR #14 의 IssueKey 패턴 일치) |

**본 PR scope 외 (후속 FR-IS-02 PR)**.
- 커스텀 IssueType 생성/수정/삭제 API
- IssueTypeScheme (프로젝트별 타입 묶음 — SDD §05 line 65 의 `issue_type_scheme_id`)
- Issue.type FK 추가 + Issue Aggregate 통합 (PR #17 후속, Issue Aggregate 안정화 후)

### DB 마이그레이션 (Wave 1 — db-engineer)

- **V003** (project-workflow 모듈 namespace). 3 테이블 — `workflow_schemes` (id, key UNIQUE, name, description, is_default BOOLEAN, deleted_at) + `project_workflow_scheme_assignments` (project_id PK, workflow_scheme_id FK, assigned_at, assigned_by) + `workflow_scheme_issue_type_mappings` (id, scheme_id FK, issue_type_id FK nullable, workflow_id FK, UNIQUE(scheme_id, issue_type_id)). 4 표준 스킴 SQL INSERT seed (D8 결정 후 확정).
- **V003** (issue-tracking 모듈 namespace). `issue_types` 테이블 (id, key UNIQUE, name, description, icon_name, is_standard BOOLEAN, deleted_at) + 5 표준 타입 SQL INSERT seed (`Epic/Story/Task/Subtask/Bug`, `is_standard = true`).
- **FK 결정**. (1) `workflow_scheme_issue_type_mappings.issue_type_id BIGINT REFERENCES issue_types(id)` — cross-BC FK 허용 (두 BC 모두 본 PR scope, 같은 DB 인스턴스). (2) `project_workflow_scheme_assignments.project_id BIGINT REFERENCES projects(id)` — cross-BC FK 동일 정당화.
- **Jira align 모델**. nodeassociation 의 다목적 통합 패턴은 BTS 1K 사용자 규모에 over-engineered 라 미도입 (Jira legacy 부담, ADR 본문 정당화). high-level concept (별도 매핑 테이블 + 1:N IssueType→Workflow 매핑) 만 align.

### 다른 BC 와의 관계

- **inbound (호출 in)**. `IssuePermissionResolver` (PR #14 outbound port, AlwaysAllow stub 사용 — admin 권한 검증) — Scheme 생성/수정/삭제 시 `IssuePermission.MANAGE_WORKFLOW` 권한 필요. (FR-PM-04 진척 시 정식 resolver 교체)
- **outbound (호출 out)**. 본 PR 에서 신규 outbound port 없음 — IssueType 도 본 PR 안에서 직접 import (same DB schema 공유).
- **이벤트 발행**. `q_workflow_scheme_events` (V003 with pgmq) — `WorkflowSchemeAssignedEvent` / `WorkflowSchemeUpdatedEvent` 발행. issue-tracking BC 의 후속 PR 에서 consume 가능 (Issue.type 변경 시 스킴 매핑 재해석).

### 기존 결정 충돌 / 정정 대상

| 항목 | 출처 | 정정 방향 |
|---|---|---|
| `WorkflowAssignment (프로젝트 → 워크플로우 바인딩)` (stale) | `Maxi_wiki/BTS/domain/project-workflow.md` line 18 | `WorkflowScheme` + `ProjectWorkflowSchemeAssignment` (스킴 컨테이너 + 별도 매핑 테이블) 으로 정정. SDD §7.1 정식 용어 일치. domain 노트는 수동 영역이라 Maxi 직접 갱신. |
| **SDD §05.3 projects 테이블 의 `workflow_scheme_id BIGINT FK` 컬럼 직접 모델 → 별도 매핑 테이블로 정정** (D9 옵션 C 채택) | `docs/sdd/05-data-model.md` line 62 | **본 PR scope 포함** — SDD §05.3 의 projects 컬럼 직접 FK 모델은 Jira align 모델 (별도 매핑 테이블 + admin UI 가 만들 수 있는 모델) 과 모순. SDD v0.5.0 봉인 변경 + 정정 사유 본문 inline + ADR `project-scheme-mapping-jira-align` 연결. 비슷한 4 scheme (workflow / permission / notification / issue_type) 의 `*_scheme_id` 컬럼 4건 일괄 정정 — 본 PR 은 `workflow_scheme_id` 만 정정, 나머지 3건은 후속 PR (FR-PM-04 / 알림 BC / FR-IS-02). |
| ADR 디렉토리 이중화 (`docs/decisions/` 14건 + `docs/adr/` 4건) | worktree 코드 잔재 | **본 PR scope 외** — 후속 chore PR 후보. /bts-codereview 단계에서 별도 학습 항목 등록. |

### 신규 ADR 후보 (impl 단계 작성)

1. **`2026-05-24-workflow-scheme-storage`** (PR #10 `workflow-yaml-vs-db-storage` 연장 결정).
   - **결정 포인트**. WorkflowScheme 정의를 (a) YAML seed 일관 (FR-WF-01 의 4 표준 yaml seed 패턴) — Git 버전 관리 + GitOps 일관성 vs (b) DB-only + SQL INSERT seed (admin UI 편집 가능 — D9 옵션 C 의 자연 연장) vs (c) hybrid (표준 4 스킴은 YAML seed + 커스텀 스킴은 DB-only).
   - **trade-off**. (a) workflow 와 동일 패턴 + 일관성 vs (b) admin 편집 UX + Jira align 모델 일관성 vs (c) 복잡도 ↑.
   - **추천**. **(b) DB-only + V003 SQL INSERT seed** — D9 옵션 C 채택 (별도 매핑 테이블 + admin UI 가 만들 수 있는 모델) 의 자연 연장. spec 단계 D8 에서 확정.

2. **`2026-05-24-issue-type-cross-bc-introduction`** (BC 격리 예외 패턴 기록).
   - **결정 포인트**. project-workflow PR 안에서 issue-tracking 의 IssueType entity + 테이블 사전 도입. CLAUDE.md §컨텍스트 효율 의 "한 번에 한 BC만 작업" 룰의 의식적 예외.
   - **정당화**. (a) FR-WF-02 의 "타입별 매핑" 키로 IssueType 필요, (b) IssueType 본 정의 = FR-IS-02 영역인데 본 PR 안에서 작은 도입만 (CRUD API 없음 + 커스텀 없음), (c) PR #17 (issue-tracking 비즈니스 로직 WIP) 와 영역 분리 (Issue Aggregate 미터치).
   - **예방**. 본 ADR 가 향후 동일 패턴 (한 PR 안 두 BC 영역) 의 의식적 도입 기준 제공.

3. **`2026-05-24-project-scheme-mapping-jira-align`** (D9 옵션 C 채택 결정 — 신규).
   - **결정 포인트**. Project ↔ WorkflowScheme 매핑 모델 — (A) SDD §05.3 의 projects 컬럼 직접 FK (단순) vs (B) 별도 매핑 테이블 (Jira high-level 모델 align) vs (D) Jira nodeassociation 다목적 통합 (over-engineered).
   - **선택**. (B) 별도 매핑 테이블 + SDD §05.3 의 `workflow_scheme_id` 컬럼 정의 정정. (D) nodeassociation 의 다목적 association_type string typing + 다형성 FK 는 Kotlin/jOOQ 환경 + BTS 1K 사용자 규모에 over-engineered 라 비도입.
   - **정당화**. (a) Jira high-level concept (별도 매핑 테이블 + 1:N IssueType→Workflow) align — 검증된 패턴, (b) BC 격리 룰 준수 — project-workflow 모듈 내 schema 만 수정 (단, IssueType 의 cross-BC FK 는 ADR 2 의 예외 정당화 별개), (c) 미래 확장성 — 매핑 history 추적 (assigned_at / assigned_by 컬럼) admin UI 감사 로그 용이, (d) SDD §05.3 정정 본 PR scope 포함 — 후속 chore PR 분리 부담 0.
   - **유사 4 scheme 정정**. SDD §05.3 의 `permission_scheme_id` / `notification_scheme_id` / `issue_type_scheme_id` 컬럼 3건 도 동일 정정 필요 — 본 PR 은 workflow_scheme_id 만, 나머지 3건은 후속 PR (FR-PM-04 / 알림 BC / FR-IS-02) 에서 같은 ADR 패턴 적용 권장.

### glossary 등록 후보 (Maxi 승인 필요)

| 용어 (영문) | 한글 | 정의 |
|---|---|---|
| WorkflowScheme | 워크플로우 스킴 | 워크플로우 묶음 + 이슈 타입별 매핑 규칙. 프로젝트에 1:1 적용. |
| WorkflowSchemeAssignment | 워크플로우 스킴 적용 | 프로젝트 ↔ 스킴 매핑. 한 프로젝트 = 한 스킴. |
| WorkflowSchemeIssueTypeMapping | 워크플로우 스킴 매핑 | 스킴 ↔ {이슈 타입 → 워크플로우} 1:N 규칙. 매핑되지 않은 타입은 default workflow. |
| IssueType | 이슈 타입 | 이슈의 분류 (Epic/Story/Task/Subtask/Bug + 커스텀). FR-IS-02 본업. 본 PR 은 5 표준 entity + seed 만 사전 도입. |
| IssueTypeScheme | 이슈 타입 스킴 | 프로젝트별 이슈 타입 묶음 (어떤 타입을 허용하는지). **본 PR scope 외** — FR-IS-02 후속 PR. |

### 관련 ADR / 참고

- PR #10 ADR. [docs/adr/2026-05-21-workflow-validator-terminology.md](../adr/2026-05-21-workflow-validator-terminology.md) (Validator 명명 — 본 PR 영향 0)
- PR #10 ADR. [docs/adr/2026-05-21-workflow-bc-cross-bc-port.md](../adr/2026-05-21-workflow-bc-cross-bc-port.md) (BC 간 통신 port-adapter 패턴 — 본 PR 의 cross-BC IssueType 도입은 ADR 2 정당화 필요)
- PR #14 ADR. [docs/adr/2026-05-22-issue-permission-resolver-port.md](../adr/2026-05-22-issue-permission-resolver-port.md) (issue-tracking outbound port 패턴 — 본 PR 의 IssueType 도입 시 IssuePermissionResolver 활용)
- SDD §7.1. WorkflowScheme 정의 — "프로젝트 + 이슈 타입별 매핑"
- SDD §05.2. IssueType 정식 챕터 (본 PR 는 일부 사전 도입)
- SDD §02 FR-IS-02. "이슈 타입: Epic/Story/Task/Subtask/Bug + 커스텀" 필수 FR

## 스펙 (`/bts-spec` Phase A — 2026-05-24)

전체 스펙. [docs/specs/2026-05-24-project-workflow-bc-fr-wf-02-scheme-1-pr.md](../specs/2026-05-24-project-workflow-bc-fr-wf-02-scheme-1-pr.md) — S1~S8 시나리오 + 11 FR + 8 NFR + 10 REST endpoint + V003/V004 schema + 10 EC + 9 결정 표.

### 핵심 시나리오 3줄 요약
- admin alice 가 워크플로우 스킴 만들고 이슈 타입별 워크플로우 매핑 → 프로젝트에 적용 → Issue transition 시 자기 타입의 워크플로우 자동 결정 (`WorkflowResolver.resolveFor`).
- 4 표준 스킴 (software-scheme / bug-tracking-scheme / simple-scheme / kanban-scheme) V004 SQL INSERT seed → 모든 프로젝트가 즉시 사용 가능 (default = software-scheme auto-assign, D10 채택).
- 5 표준 IssueType (Epic / Story / Task / Subtask / Bug) V003 seed (issue-tracking 모듈) — 본 PR 은 entity + read-only API 만, 커스텀 IssueType + Issue.type FK 는 FR-IS-02 후속 PR.

### scope 외 명시
- D6 frontend admin UI / D7 E2E → 후속 PR
- Issue.type FK + Issue Aggregate 통합 → PR #17 후속
- 커스텀 IssueType CRUD API + IssueTypeScheme → FR-IS-02 본업 후속 PR
- audit log 정책 → FR-HS 후속 PR
- bulk assignment admin UX → admin UX 후속 PR
- 정식 RBAC resolver → FR-PM-04 후속 PR (현재 AlwaysAllowWorkflowSchemePermissionResolver stub)
- pgmq `q_workflow_scheme_events` consumer → 후속 BC 작업 (notification / audit-log / issue-tracking)

## Brainstorming Check (`/bts-spec` Phase B — 2026-05-24)

✅ **통과** (1 iteration, sanity check 한 번에 gap 16건 종합 발견 → Maxi 결정 4건 받음 + 나머지 12건 implementer/후속 PR 분류 + spec inline 보강 완료)

### Maxi 결정 4건 (spec §6 EC + §9 결정 표 inline 반영 완료)
- **D10 (a)**. EC-1 assignment 없는 프로젝트 — software-scheme auto-assign + UPSERT 1회 (Jira default scheme 패턴 align)
- **D11 (a)**. EC-4 표준 스킴 mapping — 변경 가능 (default 추가/변경 + 추가 mapping admin OK), key/name/description/is_default 4 필드만 lock
- **D12 (a)**. MANAGE_WORKFLOW 권한 위치 — project-workflow BC 자체 `WorkflowSchemePermission` enum + `WorkflowSchemePermissionResolver` outbound port (PR #14 IssuePermissionResolver 패턴 그대로 복제, BC 격리 OK). 신규 FR-WF-02-11 추가.
- **D13 (a)**. Flyway V-number 충돌 — issue-tracking V003 + project-workflow **V004** (단일 SchemaHistory 통합, BTS single DB instance 모델 일치)

### implementer 결정 (impl 단계 자연 해소, spec 일부 보강 완료)
- **G1**. WorkflowResolver port 시그니처 — spec §FR-WF-02-06 보강 (`@Transactional(readOnly = true)` + `Propagation.MANDATORY` + `ProjectNotFoundException` / `WorkflowSchemeNoDefaultException`)
- **G6**. Mapping CRUD UPDATE endpoint — DELETE+POST 패턴 (Jira align), spec §4 후미 명시
- **G7**. is_default 의미 — Jira workflowscheme.is_default 와 동일 (시스템 표준), spec §5.1.1 KDoc impl 단계 권장
- **G8**. mapping_id BIGINT REST path 노출 — spec §4 명시 OK
- **G11**. SDD §05.3 정정 commit 시점 — implementer 결정 (별도 wave 마지막 docs cleanup 권장)
- **G14**. WorkflowResolver 트랜잭션 boundary — G1 과 함께 spec §FR-WF-02-06 inline

### 후속 PR 위임 (본 PR scope 외, spec §7 명시)
- **G2**. WorkflowResolver consumer wiring (어느 application service 가 호출) — PR #17 후속 또는 별도 PR
- **G3**. pgmq `q_workflow_scheme_events` consumer — 본 PR 발행만, consumer 후속 BC 작업
- **G5**. audit log 정책 — FR-HS 후속 PR 위임
- **G15**. bulk assignment — admin UX 후속 PR

### 자동 처리 / 스킵
- **G4**. IssuePermission.MANAGE_WORKFLOW 미존재 → D12 (a) 채택으로 자연 해소 (project-workflow BC 자체 enum 정의)
- **G9**. assigned_by UUID 형식 — 이미 명확 (사용자 UUID, system actor 의 경우 D10 EC-1 처리)
- **G12**. Scheme soft-delete + 사용 중 → spec §EC-9 신규 추가
- **G13**. race Mapping POST + Scheme DELETE → spec §EC-10 신규 추가 (ON DELETE CASCADE 자동 정리)

## Plan (`/bts-plan` 채움 — 2026-05-24)

> **35 task / 7 wave / depth 6**. PR #10 (36 task) 규모와 유사. wave 당 task 수 max 6 (learnings — wave 3 = 13 task 의 Mac 과부하 회피).
> **TDD 강제** — 각 task RED→GREEN→REFACTOR. `test:` 커밋이 `feat:` 보다 먼저 (bts-impl 자동 검증).
> **메타 블록** — agent / files / depends-on 필수. bts-impl wave 계산에 사용.

### Wave 0 — VO 4종 + Custom Exceptions sealed (5 task, 병렬)

#### Task 1. WorkflowSchemeId VO

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/domain/WorkflowSchemeId.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/domain/WorkflowSchemeIdTest.kt`]
- depends-on. []

**RED**. `WorkflowSchemeIdTest` — `WorkflowSchemeId(42L)` 인스턴스화 + `value` 접근 + equals/hashCode (Long 기반).
**GREEN**. `@JvmInline value class WorkflowSchemeId(val value: Long)` (PR #14 IssueId 패턴).
**REFACTOR**. KDoc + 도메인 의미 (workflow_schemes.id BIGINT 매핑).
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeIdTest`

#### Task 2. WorkflowSchemeKey VO + REGEX

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/domain/WorkflowSchemeKey.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/domain/WorkflowSchemeKeyTest.kt`]
- depends-on. []

**RED**. `WorkflowSchemeKeyTest` — 유효 key (`software-scheme`) 통과 + REGEX 위반 거부 (대문자 `Software`, 0-시작 `0scheme`, 30자 초과). `IllegalArgumentException` 검증.
**GREEN**. `@JvmInline value class WorkflowSchemeKey(val value: String) { init { require(REGEX.matches(value)) } }` + `companion object { val REGEX = Regex("^[a-z][a-z0-9-]{1,29}$") }` (PR #10 workflow.key 패턴 일치).
**REFACTOR**. KDoc + REGEX 상수 추출 + 의미 명시 (URL-safe + 30자컷).
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeKeyTest`

#### Task 3. IssueTypeId VO (issue-tracking 모듈)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/domain/IssueTypeId.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/domain/IssueTypeIdTest.kt`]
- depends-on. []

**RED**. `IssueTypeIdTest` — 인스턴스화 + value + equals/hashCode.
**GREEN**. `@JvmInline value class IssueTypeId(val value: Long)` (PR #14 IssueKey 패턴).
**REFACTOR**. KDoc + issue_types.id BIGINT 매핑 명시.
**검증**. `./gradlew :backend:issue-tracking:test --tests IssueTypeIdTest`

#### Task 4. IssueTypeKey VO + REGEX

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/domain/IssueTypeKey.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/domain/IssueTypeKeyTest.kt`]
- depends-on. []

**RED**. `IssueTypeKeyTest` — 유효 (`bug`, `subtask`) + 위반 (`Bug`, `1bug`, 30자 초과).
**GREEN**. `@JvmInline value class IssueTypeKey(val value: String) { init { require(REGEX.matches(value)) } }` + REGEX 동일 (PR #14 IssueKey 패턴 일치).
**REFACTOR**. KDoc + REGEX companion (WorkflowSchemeKey 와 동일 패턴).
**검증**. `./gradlew :backend:issue-tracking:test --tests IssueTypeKeyTest`

#### Task 5. WorkflowSchemeExceptions sealed (7 sub-class)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/exception/WorkflowSchemeExceptions.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/exception/WorkflowSchemeExceptionsTest.kt`]
- depends-on. []

**RED**. `WorkflowSchemeExceptionsTest` — 7 예외 각각 인스턴스화 + message 검증.
**GREEN**. `sealed class WorkflowSchemeDomainException(message: String)` + 7 sub-class — `WorkflowSchemeNotFoundException(key)` / `SchemeInUseException(usedByProjects)` / `SchemeStandardNotDeletableException(key)` / `SchemeStandardFieldLockedException(key, field)` / `MappingDuplicateException(schemeKey, issueTypeKey)` / `MappingDefaultDuplicateException(schemeKey)` / `WorkflowSchemeNoDefaultException(schemeKey)`.
**REFACTOR**. KDoc + spec §4.5 ProblemDetail errorCode 매핑 명시 (각 예외 → errorCode).
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeExceptionsTest`

### Wave 1 — DB 마이그레이션 (2 task, db-engineer, V003 → V004 직렬)

#### Task 6. issue-tracking V003 issue_types 마이그레이션 + 5 표준 seed

**메타**.
- agent. `db-engineer`
- files. [`backend/modules/issue-tracking/src/main/resources/db/migration/V003__issue_types.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/migration/IssueTypesMigrationIntegrationTest.kt`]
- depends-on. []

**RED**. `IssueTypesMigrationIntegrationTest` — Flyway migrate 후 `issue_types` 5 row 존재 (`epic / story / task / subtask / bug`) + `is_standard = true`. Testcontainers PostgreSQL.
**GREEN**. `V003__issue_types.sql` — CREATE TABLE (spec §5.2.1) + 5 INSERT seed (spec §5.2.2) + `CREATE INDEX ix_issue_types_key_active`.
**REFACTOR**. SQL 형식 + 주석 (spec §5.2 인용) + Flyway placeholder 치환 비활성화 검증 (PR #10 learning).
**검증**. `./gradlew :backend:issue-tracking:test --tests IssueTypesMigrationIntegrationTest`

#### Task 7. project-workflow V004 workflow_schemes/assignments/mappings + pgmq queue + 4 표준 seed

**메타**.
- agent. `db-engineer`
- files. [`backend/modules/project-workflow/src/main/resources/db/migration/V004__workflow_schemes.sql`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/migration/WorkflowSchemesMigrationIntegrationTest.kt`]
- depends-on. [6] (issue-tracking V003 의 issue_types 가 cross-BC FK 필요)

**RED**. `WorkflowSchemesMigrationIntegrationTest` — Flyway migrate 후 (a) `workflow_schemes` 4 row + `is_default=true`, (b) `project_workflow_scheme_assignments` 빈, (c) `workflow_scheme_issue_type_mappings` 4 row (default mapping, `issue_type_id IS NULL`), (d) pgmq `q_workflow_scheme_events` 존재.
**GREEN**. `V004__workflow_schemes.sql` — 3 CREATE TABLE + pgmq queue + 4 스킴 INSERT seed + 4 default mapping INSERT (workflows JOIN, spec §5.1.5/6). partial UNIQUE INDEX `ix_scheme_default_mapping`.
**REFACTOR**. SQL 형식 + 주석 + ADR `project-scheme-mapping-jira-align` 참조 inline 주석.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemesMigrationIntegrationTest`

### Wave 2 — 도메인 entity (5 task, 병렬)

#### Task 8. WorkflowScheme Aggregate Root

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/domain/WorkflowScheme.kt`, test]
- depends-on. [1, 2, 5]

**RED**. `WorkflowSchemeTest` — factory `WorkflowScheme.create(key, name, description, isDefault)` + invariants (name 빈 문자열 거부, description null 허용, isDefault default false).
**GREEN**. `data class WorkflowScheme(id, key, name, description, isDefault, createdAt, updatedAt, deletedAt)` + `companion object { fun create(...) }`.
**REFACTOR**. KDoc + spec §5.1.1 일치 + `is_default = true 는 시스템 표준 스킴 (Jira workflowscheme.is_default 패턴)` KDoc 명시 (G7).
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeTest`

#### Task 9. ProjectWorkflowSchemeAssignment Aggregate Root

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/domain/ProjectWorkflowSchemeAssignment.kt`, test]
- depends-on. [1]

**RED**. assignedAt + assignedBy + projectId + workflowSchemeId 인스턴스화 + equals.
**GREEN**. `data class ProjectWorkflowSchemeAssignment(projectId: Long, workflowSchemeId: WorkflowSchemeId, assignedAt: Instant, assignedBy: UUID)`.
**REFACTOR**. KDoc + Jira nodeassociation 미도입 정당화 명시 (ADR `project-scheme-mapping-jira-align` 참조).
**검증**. `./gradlew :backend:project-workflow:test --tests ProjectWorkflowSchemeAssignmentTest`

#### Task 10. SchemeIssueTypeMapping Entity

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/domain/SchemeIssueTypeMapping.kt`, test]
- depends-on. [1, 3]

**RED**. nullable issueTypeId (NULL = default mapping) + workflowId NOT NULL + scheme/issue_type/workflow id 참조.
**GREEN**. `data class SchemeIssueTypeMapping(id: Long, schemeId: WorkflowSchemeId, issueTypeId: IssueTypeId?, workflowId: Long, createdAt: Instant)`.
**REFACTOR**. KDoc + Jira `workflowschemeentity` 패턴 일치 + issueTypeId NULL 의미 (default mapping for unmatched types).
**검증**. `./gradlew :backend:project-workflow:test --tests SchemeIssueTypeMappingTest`

#### Task 11. IssueType Aggregate Root (issue-tracking 모듈)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/domain/IssueType.kt`, test]
- depends-on. [3, 4]

**RED**. `IssueTypeTest` — factory + name 빈 문자열 거부 + isStandard default false + 5 표준 정의 (key 일치 확인).
**GREEN**. `data class IssueType(id, key, name, description, iconName, isStandard, createdAt, updatedAt, deletedAt)` + `companion object { fun create(...) }`.
**REFACTOR**. KDoc + 본 PR scope (5 표준만, CRUD 후속 FR-IS-02) 명시 + ADR `issue-type-cross-bc-introduction` 참조.
**검증**. `./gradlew :backend:issue-tracking:test --tests IssueTypeTest`

#### Task 12. WorkflowSchemeDomainEvent sealed (assigned / updated / deleted)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/event/WorkflowSchemeDomainEvent.kt`, test]
- depends-on. [1]

**RED**. Jackson JSON round-trip 검증 (`@JsonTypeInfo(use=NAME, property="type")` 적용) + 3 data class 각각 직렬화.
**GREEN**. `sealed interface WorkflowSchemeDomainEvent` + `WorkflowSchemeAssignedEvent(schemeId, projectId, assignedBy, occurredAt)` / `WorkflowSchemeUpdatedEvent(schemeId, field, occurredAt)` / `WorkflowSchemeDeletedEvent(schemeId, occurredAt)`.
**REFACTOR**. KDoc + PR #17 IssueDomainEvent 패턴 일치 + occurredAt timestamp.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeDomainEventTest`

### Wave 3 — Repository + Outbound port + EventPublisher (7 task, 병렬, E-2 재조정 적용)

#### Task 13. WorkflowSchemeRepository (jOOQ)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/repository/WorkflowSchemeRepository.kt`, integration test]
- depends-on. [7, 8]

**RED**. `WorkflowSchemeRepositoryIntegrationTest` (Testcontainers) — save + findByKey + softDelete (deleted_at SET) + 4 표준 seed 조회.
**GREEN**. `@Repository class WorkflowSchemeRepository(private val dsl: DSLContext)` + 4 메서드.
**REFACTOR**. KDoc + PR #10 WorkflowRepository 패턴 일치 + jOOQ codegen 자동 트리거 (build.gradle.kts module generateJooq) + ktlint generated 제외 검증 (PR #14 #5 learning).
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeRepositoryIntegrationTest`

#### Task 14. ProjectWorkflowSchemeAssignmentRepository

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/repository/ProjectWorkflowSchemeAssignmentRepository.kt`, integration test]
- depends-on. [7, 9]

**RED**. saveAssignment (UPSERT project_id PK) + findByProjectId + deleteByProjectId.
**GREEN**. jOOQ Repository + `ON CONFLICT (project_id) DO UPDATE`.
**REFACTOR**. KDoc + PR #14 ExternalAccountRepository 책임 분리 learning 검증 (cross-table UPSERT 금지 — projects 테이블 미터치).
**검증**. `./gradlew :backend:project-workflow:test --tests ProjectWorkflowSchemeAssignmentRepositoryIntegrationTest`

#### Task 15. SchemeIssueTypeMappingRepository

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/repository/SchemeIssueTypeMappingRepository.kt`, integration test]
- depends-on. [7, 10]

**RED**. addMapping (UNIQUE 위반 차단) + findBySchemeId + findDefaultMapping (issue_type_id IS NULL) + findByIssueType + deleteMapping.
**GREEN**. jOOQ Repository + `DataIntegrityViolationException → MappingDuplicateException` 변환.
**REFACTOR**. KDoc + partial UNIQUE INDEX `ix_scheme_default_mapping` 동작 검증.
**검증**. `./gradlew :backend:project-workflow:test --tests SchemeIssueTypeMappingRepositoryIntegrationTest`

#### Task 16. IssueTypeRepository (issue-tracking 모듈)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/repository/IssueTypeRepository.kt`, integration test]
- depends-on. [6, 11]

**RED**. findAll (5 표준 V003 seed) + findByKey + findById + V003 seed idempotent 검증.
**GREEN**. jOOQ Repository.
**REFACTOR**. KDoc + 본 PR scope (read-only, CRUD 후속 FR-IS-02) 명시.
**검증**. `./gradlew :backend:issue-tracking:test --tests IssueTypeRepositoryIntegrationTest`

#### Task 17. WorkflowResolver outbound port + KDoc 시그니처

**메타**.
- agent. `backend-engineer` (+ `security-engineer` review)
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/port/outbound/WorkflowResolver.kt`, contract test]
- depends-on. [8, 9, 10, 11]

**RED**. `WorkflowResolverContractTest` — interface 메서드 시그니처 검증 (`fun resolveFor(projectKey: ProjectKey, issueTypeKey: IssueTypeKey?): Workflow`).
**GREEN**. `interface WorkflowResolver { @Transactional(readOnly = true, propagation = Propagation.MANDATORY) fun resolveFor(projectKey: ProjectKey, issueTypeKey: IssueTypeKey?): Workflow }`.
**REFACTOR**. KDoc + Propagation.MANDATORY 정책 (PR #10 WorkflowEngine.plan() 패턴 일치) + 예외 명시 (`ProjectNotFoundException` / `WorkflowSchemeNoDefaultException`) + spec §FR-WF-02-06 일치.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowResolverContractTest`

#### Task 18. WorkflowSchemePermission enum + Resolver port + AlwaysAllow stub

**메타**.
- agent. `backend-engineer` (+ `security-engineer` review — 권한 stub 패턴 검증)
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/port/outbound/WorkflowSchemePermission.kt`, `.../WorkflowSchemePermissionResolver.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/outbound/AlwaysAllowWorkflowSchemePermissionResolver.kt`, test]
- depends-on. []

**RED**. `AlwaysAllowWorkflowSchemePermissionResolverTest` — 모든 호출 통과 + `@Profile("!prod")` 검증 + WARN 로그 발행 (logback ListAppender PII regex 검증, PR #14 패턴).
**GREEN**. `enum class WorkflowSchemePermission { MANAGE_SCHEME, ASSIGN_SCHEME }` + `sealed interface WorkflowSchemeScope { data object Global; data class Project(val key: String) }` + `interface WorkflowSchemePermissionResolver { fun requirePermission(actor: ActorId, permission: WorkflowSchemePermission, scope: WorkflowSchemeScope) }` + `@Component @Profile("!prod") class AlwaysAllowWorkflowSchemePermissionResolver : WorkflowSchemePermissionResolver`.
**REFACTOR**. KDoc + PR #14 IssuePermissionResolver 패턴 명시 + FR-PM-04 후속 정식 RBAC 교체 inline + ADR `project-scheme-mapping-jira-align` 의 권한 분리 정당화 참조.
**검증**. `./gradlew :backend:project-workflow:test --tests AlwaysAllowWorkflowSchemePermissionResolverTest`

### Wave 4 — Application Service (4 task, 일부 직렬 — T23 EventPublisher Wave 3 으로 이동, E-2 재조정 적용)

#### Task 19. WorkflowSchemeApplicationService — Scheme CRUD (5 메서드)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/application/WorkflowSchemeApplicationService.kt`, 단위 test]
- depends-on. [13, 18]

**RED**. `WorkflowSchemeApplicationServiceTest` (Mockito mock Repository + AlwaysAllow stub) — 5 메서드 (create / find / update / softDelete / list) + S6 표준 lock 차단 (`SchemeStandardNotDeletableException`) + S7 사용 중 차단 (`SchemeInUseException`).
**GREEN**. `@Service @Transactional class WorkflowSchemeApplicationService(repo, permissionResolver)` + 5 메서드.
**REFACTOR**. **PR #6 #1 learning 검증** — 클래스 자체 `@Service` 부착 (`@Transactional` 무력화 방지) + Application Service 책임 분리 (PR #10 learning) + EC-4 (D11) `SchemeStandardFieldLockedException` (key/name/description/is_default 4 필드만) inline 검증.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeApplicationServiceTest`

#### Task 20. WorkflowSchemeApplicationService — Mapping CRUD (addMapping / deleteMapping)

**메타**.
- agent. `backend-engineer`
- files. [같은 Service 파일 + 같은 test 파일 (수정)]
- depends-on. [19, 15]

**RED**. addMapping 시나리오 (UNIQUE 위반 → `MappingDuplicateException`, partial UNIQUE 위반 → `MappingDefaultDuplicateException`) + deleteMapping 시나리오.
**GREEN**. 2 메서드 추가 + Repository call.
**REFACTOR**. EC-2 default mapping 강제 검증 (커스텀 스킴 생성 시 default mapping 0 → 강제 추가 유도, application layer 검증).
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeApplicationServiceTest`

#### Task 21. WorkflowSchemeApplicationService — Project assignment (assignToProject / findAssignedScheme)

**메타**.
- agent. `backend-engineer`
- files. [같은 Service 파일 + 같은 test 파일 (수정)]
- depends-on. [19, 14, 23] (EventPublisher 의존 — Wave 4 안 직렬)

**RED**. assignToProject UPSERT + findAssignedScheme + **EC-1 D10** software-scheme auto-assign (assignment 없으면 software-scheme UPSERT + assigned_by = system actor) + event 발행 검증.
**GREEN**. 2 메서드 + EventPublisher 호출 + ASSIGN_SCHEME 권한 검증.
**REFACTOR**. KDoc + D10 EC-1 본문 inline 명시 (assignment 없으면 1회 software-scheme auto-assign).
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeApplicationServiceTest`

#### Task 22. WorkflowResolverImpl (port 구현)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowResolverImpl.kt`, integration test]
- depends-on. [17, 21]

**RED**. `WorkflowResolverImplIntegrationTest` (Testcontainers) — S5 시나리오 (matching mapping) + EC-2 (default mapping fallback) + EC-1 D10 (assignment 없으면 software-scheme auto-assign + resolve) + EC-7 (projectKey 무효 → ProjectNotFoundException).
**GREEN**. `@Service class WorkflowResolverImpl(assignmentRepo, mappingRepo, workflowRepo, schemeAS)` + assignment 조회 → mapping 조회 → workflow 반환. assignment 없으면 schemeAS.assignToProject(software-scheme) 호출 (D10).
**REFACTOR**. KDoc + Propagation.MANDATORY + readOnly + 예외 매핑 + PR #6 #1 learning (@Service 부착) 검증.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowResolverImplIntegrationTest`

#### Task 23. WorkflowSchemeEventPublisher (pgmq adapter)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/outbound/WorkflowSchemeEventPublisher.kt`, integration test]
- depends-on. [12, 7]

**RED**. `WorkflowSchemeEventPublisherIntegrationTest` (Testcontainers) — publish 3 event type → pgmq `q_workflow_scheme_events` send 호출 검증 + Jackson JSON 직렬화 round-trip (PR #14 pgmq 패턴 일치).
**GREEN**. `@Component class WorkflowSchemeEventPublisher(dsl)` + pgmq client wrapper.
**REFACTOR**. KDoc + PR #17 IssueEventPublisher 패턴 일치 + Propagation.MANDATORY (transaction outbox).
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeEventPublisherIntegrationTest`

### Wave 5 — REST Controller (5 task)

#### Task 24. WorkflowSchemeController — Scheme CRUD 5 endpoint

**메타**.
- agent. `backend-engineer` (+ `security-engineer` review — 권한 검증 wiring)
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeController.kt`, DTO `.../dto/WorkflowSchemeDto.kt`, `@WebMvcTest` test]
- depends-on. [19, 28]

**RED**. `@WebMvcTest WorkflowSchemeControllerTest` — 5 endpoint (POST/GET list/GET single/PUT/DELETE) + MANAGE_SCHEME 권한 검증 wiring (`WorkflowSchemePermissionResolver` mock).
**GREEN**. `@RestController class WorkflowSchemeController(applicationService, permissionResolver)` + 5 메서드 + DTO + 권한 검증 inline.
**REFACTOR**. KDoc + Application Service 호출만 (Controller @Transactional 0건, learning #91) + spec §4.1 endpoint 5개 일치.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeControllerTest`

#### Task 25. WorkflowSchemeController — Mapping CRUD 2 endpoint

**메타**.
- agent. `backend-engineer`
- files. [같은 Controller 파일 + 같은 test 파일 (수정)]
- depends-on. [20, 24]

**RED**. POST mapping + DELETE mapping endpoint + DTO `MappingRequestDto(issueTypeKey: String?, workflowKey: String)`.
**GREEN**. 2 메서드 추가.
**REFACTOR**. RESTful URL 검증 (`/api/v1/workflow-schemes/{schemeKey}/mappings`) + Jira align DELETE+POST 패턴 (G6) 명시.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeControllerTest`

#### Task 26. ProjectWorkflowSchemeController — Project assignment 2 endpoint

**메타**.
- agent. `backend-engineer` (+ `security-engineer` review — ASSIGN_SCHEME 권한 검증)
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/ProjectWorkflowSchemeController.kt`, test]
- depends-on. [21, 28]

**RED**. PUT project assignment + GET assigned scheme + ASSIGN_SCHEME 권한 검증.
**GREEN**. `@RestController class ProjectWorkflowSchemeController(applicationService, permissionResolver)` + 2 메서드.
**REFACTOR**. KDoc + spec §4.3 endpoint 일치.
**검증**. `./gradlew :backend:project-workflow:test --tests ProjectWorkflowSchemeControllerTest`

#### Task 27. IssueTypeController — read-only 1 endpoint (issue-tracking 모듈)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/web/IssueTypeController.kt`, DTO, test]
- depends-on. [16]

**RED**. `@WebMvcTest IssueTypeControllerTest` — GET /api/v1/issue-types — 5 표준 IssueType 반환 + JSON schema 검증.
**GREEN**. `@RestController` + 1 메서드 + `IssueTypeResponse(id, key, name, description, iconName, isStandard)` DTO.
**REFACTOR**. KDoc + 본 PR scope (read-only) 명시 + CRUD 후속 FR-IS-02 PR 명시.
**검증**. `./gradlew :backend:issue-tracking:test --tests IssueTypeControllerTest`

#### Task 28. WorkflowSchemeExceptionHandler — RFC 7807 ProblemDetail (10 errorCode)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeExceptionHandler.kt`, test]
- depends-on. [5]

**RED**. 10 errorCode 각각 mapping 검증 (`SCHEME_KEY_INVALID` 400 / `SCHEME_NOT_FOUND` 404 / `SCHEME_STANDARD_NOT_DELETABLE` 403 / `SCHEME_IN_USE` 409 with body / `MAPPING_DUPLICATE` 409 / `MAPPING_DEFAULT_DUPLICATE` 409 / `WORKFLOW_NOT_FOUND` 404 / `ISSUE_TYPE_NOT_FOUND` 404 / `TYPE_STANDARD_NOT_DELETABLE` 403 / `WORKFLOW_SCHEME_NO_DEFAULT` 500 / `SCHEME_STANDARD_FIELD_LOCKED` 403).
**GREEN**. `@RestControllerAdvice class WorkflowSchemeExceptionHandler` + `@ExceptionHandler` 11건.
**REFACTOR**. helper `private fun problem(status, type, title, errorCode, detail, additionalFields: Map): ProblemDetail` + PR #17 IssueExceptionHandler 패턴 일치.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeExceptionHandlerTest`

### Wave 6 — 테스트 + ADR + SDD 정정 (7 task)

#### Task 29. Testcontainers 통합 base class (singleton 패턴)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/integration/WorkflowSchemeTestcontainersBase.kt`]
- depends-on. [7]

**RED**. base class 상속한 더미 통합 테스트 실행 (PostgreSQLContainer + Flyway migrate 자동 + SpringBootTest 빠른 시작).
**GREEN**. **PR #8 learning 패턴** — `companion object { @JvmStatic val container = PostgreSQLContainer(...).apply { start() } }` (singleton, `@Container` annotation 미사용) + Spring `@DynamicPropertySource` 로 `spring.datasource.url` 주입 + Ryuk 자동 정리.
**REFACTOR**. KDoc + PR #8 learning 명시 (왜 `@Container` 가 아닌 singleton 필요한지) + jOOQ codegen Testcontainers JDBC URL 우회 (PR #14 #3 learning) 검증.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeTestcontainersBase`

#### Task 30. S1~S8 시나리오 통합 테스트 (Testcontainers + REST)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/integration/WorkflowSchemeScenarioIntegrationTest.kt`]
- depends-on. [24, 25, 26, 27, 29]

**RED**. S1~S8 (spec §1) 8 시나리오 + MockMvc + 권한 stub. 각 시나리오 1 `@Test` 메서드.
**GREEN**. `@SpringBootTest + WorkflowSchemeTestcontainersBase` 상속 + MockMvc + 8 시나리오 구현.
**REFACTOR**. fixture 정리 + base class 활용 + 8 시나리오 KDoc spec §1 인용.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeScenarioIntegrationTest`

#### Task 31. Kotest property 테스트 — Key REGEX invariant

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/property/WorkflowSchemeKeyPropertyTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/type/property/IssueTypeKeyPropertyTest.kt`]
- depends-on. [2, 4]

**RED**. 1000건 random string 중 REGEX 매칭 case 만 통과 + 비매칭 case 전부 거부.
**GREEN**. `Kotest.forAll(Arb.string())` + REGEX 검증.
**REFACTOR**. KDoc + invariant 명시.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowSchemeKeyPropertyTest && ./gradlew :backend:issue-tracking:test --tests IssueTypeKeyPropertyTest`

#### Task 32. ArchUnit 룰 — TransactionalServiceArchTest + BC 격리 검증

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/architecture/TransactionalServiceArchTest.kt`, `.../architecture/WorkflowSchemeBcIsolationArchTest.kt`]
- depends-on. []

**RED**. ArchUnit 룰 2건 — (1) `@Transactional` 메서드 보유 클래스는 `@Component` 계열 필수 (PR #8 learning) + (2) project-workflow ↛ issue-tracking `domain` 패키지 import 0 (단, IssueType 사용 시 cross-BC FK 만 허용).
**GREEN**. ArchUnit 룰 2건.
**REFACTOR**. KDoc + PR #6 #1 learning 링크 (왜 이 룰이 있는지 컨텍스트) + PR #14 IssuePermissionResolver port 패턴 학습 인용.
**검증**. `./gradlew :backend:project-workflow:test --tests TransactionalServiceArchTest && ./gradlew :backend:project-workflow:test --tests WorkflowSchemeBcIsolationArchTest`

#### Task 33. NFR-1 측정 통합 테스트 (스킴 매핑 조회 p95 < 50ms)

**메타**.
- agent. `backend-engineer`
- files. [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/performance/WorkflowResolverPerformanceTest.kt`]
- depends-on. [22, 29]

**RED**. 100회 `WorkflowResolver.resolveFor` 호출 (다양한 projectKey + issueTypeKey) → p95 측정 → < 50ms 검증.
**GREEN**. 통합 테스트 측정 (Testcontainers + 4 JOIN 쿼리) + 5 표준 IssueType + 1 표준 스킴 fixture.
**REFACTOR**. WorkflowSchemeTestcontainersBase 활용.
**검증**. `./gradlew :backend:project-workflow:test --tests WorkflowResolverPerformanceTest`

#### Task 34. ADR 3건 본문 작성

**메타**.
- agent. `backend-engineer`
- files. [`docs/adr/2026-05-24-workflow-scheme-storage.md`, `.../2026-05-24-issue-type-cross-bc-introduction.md`, `.../2026-05-24-project-scheme-mapping-jira-align.md`]
- depends-on. []

**RED**. ADR 본문 검증 (linter 또는 markdown 검증 task) — 각 ADR 가 표준 template (Context / Decision / Consequences / Alternatives / Date) 따름 + 본 PR plan 의 §도메인 정리 §신규 ADR 후보 본문 활용.
**GREEN**. 3 ADR 본문 작성.
**REFACTOR**. cross-link — `workflow-scheme-storage` ↔ `workflow-yaml-vs-db-storage` (PR #10) / `issue-type-cross-bc-introduction` ↔ `workflow-bc-cross-bc-port` (PR #10) + `issue-permission-resolver-port` (PR #14) / `project-scheme-mapping-jira-align` 의 nodeassociation 비도입 정당화 + SDD §05.3 정정 link.
**검증**. markdown lint + grep 검증 (예. `grep -l "## Decision" docs/adr/2026-05-24-*.md` = 3 파일).

#### Task 35. SDD §05.3 정정 commit + dev seed 갱신

**메타**.
- agent. `backend-engineer`
- files. [`docs/sdd/05-data-model.md`, `backend/modules/project-workflow/src/main/resources/data-dev.sql`, PR description 갱신]
- depends-on. [7, 34]

**RED**. SDD §05.3 line 62 의 `workflow_scheme_id BIGINT (FK)` 행 정정 — 별도 매핑 테이블 모델 본문 추가 + ADR `project-scheme-mapping-jira-align` 연결 link 검증 (`grep -l "project-scheme-mapping-jira-align" docs/sdd/05-data-model.md`).
**GREEN**. SDD §05.3 본문 정정 (`docs/sdd/05-data-model.md`) + `data-dev.sql` 에 software-scheme assignment seed (ATLAS 프로젝트, PR #14 dev seed 패턴 일치).
**REFACTOR**. 유사 3 scheme (permission_scheme_id / notification_scheme_id / issue_type_scheme_id) 정정 후속 PR 명시 (SDD §05.3 본문 inline 주석) + PR description (head section) 에 SDD 정정 항목 추가 (BLOCKER 후보 인지).
**검증**. `grep -l "project_workflow_scheme_assignments" docs/sdd/05-data-model.md` + `grep -l "atlas.*software-scheme" backend/modules/project-workflow/src/main/resources/data-dev.sql`.

## Plan 메타

- **task 수**. 35
- **wave 수**. 7 (Wave 0~6)
- **wave 별 task 수**. 0=5, 1=2, 2=5, 3=7, 4=4, 5=5, 6=7 (max 7, learnings #wave 5~7 max 준수, E-2 재조정 적용)
- **depth**. 6 (Wave 6 의 Task 30 = depends-on [24, 25, 26, 27, 29] → Wave 5 의 5 task + Wave 6 의 1 task)
- **agent 분배**. backend-engineer 31 task / db-engineer 2 task (T6, T7) / security-engineer review 2 task (T17, T18 권한 port + T24, T26 권한 wiring)
- **TDD 강제**. 모든 35 task RED→GREEN→REFACTOR 사이클
- **예상 시간**. task 평균 5분 + wave 병렬 dispatch = 약 80~100분 (직렬 약 175분)
- **추가 검증**. ktlint + detekt + ArchUnit (T32) + property (T31) + Testcontainers 통합 (T7, T13~T16, T22, T23, T29, T30, T33) + jOOQ codegen 자동 (T13~T15 트리거)
- **신규 ADR**. 3건 (T34)
- **SDD 정정**. §05.3 본 PR scope (T35)
- **dev seed 갱신**. ATLAS 프로젝트 software-scheme assignment 추가 (T35)
- **외부 의존성 추가**. 0건 (Spring/pgmq/Flyway/jOOQ/Testcontainers/Kotest/ArchUnit/Mockito 모두 기존)

## 리뷰 결과 (`/bts-review-plan` 채움 — 2026-05-26)

### plan-eng-review — ✅ PASS with CONCERNS (2026-05-26)

**Section 1 — Architecture**. BLOCKER 0
- ✅ Wave 분해 정합 (T17/T22 port↔impl 분리, T19→T20→T21 직렬 자연 흐름).
- ✅ Cross-BC 책임 분리 (T32 ArchUnit 2건 — TransactionalServiceArch + WorkflowSchemeBcIsolation).
- ✅ DB 마이그레이션 순서 (T6→T7 depends-on cross-BC FK 보장).
- ⚠️ **CONCERN E-1**. T19→T20→T21 직렬 Service 파일 동시 수정 — PR #6 ktlintFormat 부수 변경 learning 적용. implementer prompt 에 "wave 종료 시 통합 ktlintFormat + chore commit" 패턴 명시 위임.
- 💡 **SUGGESTION E-2 적용 완료**. T23 EventPublisher depends-on [12, 7] 기반 자동 wave 계산 = Wave 3. plan 헤더 cosmetic 수정 (Wave 3 = 7 task / Wave 4 = 4 task) + Plan 메타 wave_distribution 갱신.

**Section 2 — Code Quality**. BLOCKER 0
- ✅ DRY (T13~T15 jOOQ Repository 패턴 PR #10 일치).
- ✅ 명시성 (agent / files / depends-on 메타 35 task 모두 inline).
- ✅ learnings 반영 (PR #6 #1 @Service / PR #8 ArchUnit + Testcontainers singleton / PR #14 #3 jOOQ JDBC / PR #14 #5 ktlint generated 모두 task 본문 inline).
- 💡 **SUGGESTION C-1**. T34 ADR 3건 RED phase 가 markdown 검증 — TDD 적합성 약함. PR #10 ADR 5건 도 동일 패턴 (검증됨), 비표준이지만 통과.

**Section 3 — Tests**. BLOCKER 0
- ✅ 100% spec coverage (S1~S8 → T30, 11 FR → T19~T28, 10 EC → T19~T22, 8 NFR → T33 모두 task 매핑).
- ✅ Property test (T31 1000건) + ArchUnit (T32) + Integration (T6/T7/T13~T16/T22/T23/T29/T30/T33).
- ⚠️ **CONCERN T-1**. regression test 명시 0 — 본 PR 신규 기능이라 적용 안 함. T30 의 S5 시나리오가 PR #10 WorkflowEngine.plan() 호출 흐름 회귀 일부 cover.
- 💡 **SUGGESTION T-2**. 11 FR / 10 EC spec coverage matrix 본 plan 에 없음 (PR #10 plan 도 동일) — implementer 의 T30 본문에 inline 작성 가능.

**Section 4 — Performance**. BLOCKER 0
- ✅ NFR-1 (p95 < 50ms) — T33 측정 100회 + 4 JOIN. BTS 1K 사용자 + 1K project 규모 충족.
- ⚠️ **CONCERN P-1**. prod scale (이슈 ~100K) 시 추가 인덱스 필요 가능성 — 본 PR scope 외 (BC-strict 게이트 k6 실측 시점).

**Step 0 scope challenge**. 35 task / 신규 클래스 ~25+ 트리거 형식상 발동. 단 본 PR 은 Maxi 결정 8건 (D6~D13) 누적 + scope 합의 완료 — scope reduction 권장 안 함 (controller 의 sanity check 모드 우선).

**종합**. BLOCKER 0 / CONCERN 3 / SUGGESTION 3. ✅ PASS with CONCERNS. CONCERN 처리 → implementer/후속 PR inline. SUGGESTION E-2 적용 완료 (Wave 재조정).

### plan-ceo-review — ✅ PASS — HOLD SCOPE (2026-05-26)

**7 axes mental review** (compressed mode — controller 의 sanity check 흐름 우선, SKILL 정석 11 section flow 생략).

| Axis | 평가 | 결과 |
|---|---|---|
| 1. 본 PR scope 적정성 (D1~D5, D6/D7 후속) | FR-WF-01 검증 패턴 (PR #10 backend → PR #13 frontend) 일관 | ✅ HOLD SCOPE |
| 2. D6 cross-BC IssueType 사전 도입 | FR-IS-02 본업과 중복 0, ADR 정당화로 후속 reviewer 의문 해소 가능 | ✅ premise 통과 |
| 3. D9 Jira align + SDD §05.3 정정 | nodeassociation over-engineered 부분 명시적 비도입 — BTS 단순화 유지 | ✅ premise 통과 |
| 4. D10 software-scheme auto-assign UX | Jira default scheme 패턴 일치, D6 frontend UX 명시 위임 | ✅ premise 통과 |
| 5. 4 표준 스킴 seed (D8 b) | PR #10 의 4 표준 workflow 결합 자연, 커스텀 admin 확장 가능 | ✅ premise 통과 |
| 6. PR scope 산출 (가시 진척) | backend admin API 만이고 D6 후속 PR 가시화, SDD §17 phased 모델 일치 | ✅ HOLD SCOPE |
| 7. 차별화 / 비전 fit | 4 표준 seed + auto-assign → 작은 팀 onboarding 친절, D6 UX 검증 위임 | ✅ premise 통과 |

**종합**. BLOCKER 0 / SCOPE EXPANSION 0 / SCOPE REDUCTION 0 / premise 7 axes 모두 통과. **✅ PASS — HOLD SCOPE**.

**후속 검증 항목 (본 PR 영향 0)**.
- ADR `issue-type-cross-bc-introduction` 본문 잘 쓰기 (BC 격리 예외 정당화 명확화 — T34 impl 단계)
- D6 frontend admin UI 의 UX 단순성 검증 — 후속 PR 에서 plan-design-review 권장 (작은 팀 적합 커스텀 admin 복잡도 검증)

### 통과 — 게이트 1 진입 권장

| 리뷰 | 결과 | BLOCKER | CONCERN | SUGGESTION |
|---|---|---|---|---|
| plan-eng-review | ✅ PASS with CONCERNS | 0 | 3 (E-1/T-1/P-1) | 3 (E-2 적용 완료 + C-1 + T-2) |
| plan-ceo-review | ✅ PASS — HOLD SCOPE | 0 | 0 | 2 (ADR 본문 + D6 UX 검증 위임) |
| plan-design-review | skip (backend only) | — | — | — |
| plan-devex-review | skip (internal API, FR-PM-04 후속) | — | — | — |

**다음 단계**. 🛑 게이트 1 — Maxi 검토 (도메인 + 스펙 + 계획 + 리뷰 결과 일괄). 승인 시 `/bts-impl` 진입.
