<!-- FR-IS-01 이슈 CRUD 풀스택 — issue-tracking BC 진입 1-PR plan stub -->

# FR-IS-01 이슈 CRUD — issue-tracking BC 진입

> slug: issue-tracking-bc-fr-is-01-crud
> type: api (feature 수준 풀스택)
> primary agent: backend-engineer
> 보조 agent: db-engineer, security-engineer, frontend-engineer, designer, qa-engineer
> BC: issue-tracking
> 생성: 2026-05-22

## Brief

이슈 CRUD (Create / Read / Update / Delete) + 상태 변경 시 워크플로우 검증 + 알림 이벤트 발행을 D1~D7 풀스택으로 구현하는 issue-tracking BC의 진입 작업.

### 사용자 원문

> "issue tracking 작업 진행하자" → AskUserQuestion 결과. FR-IS-01 D1~D7 풀스택 monster PR 선택.

### classify-task 결과

```json
{
  "type": "api",
  "agent": "backend-engineer",
  "primary_bc": "issue-tracking",
  "slug": "issue-tracking-bc-fr-is-01-crud"
}
```

### 작업 범위 (D1~D7 풀스택)

`docs/plan/product/issue-tracking.md §2.1.1` 기준.

- D1. 도메인 — Issue Aggregate Root, IssueKey VO (backend-engineer + Maxi)
- D2. 명세 — Given/When/Then. 7 엣지 케이스 (중복 키 / 권한 / 전이 위반 / 대용량 / 동시 편집 / 소프트 삭제 / 키 보존) (backend-engineer)
- D3. 데이터 모델 — Flyway. `issues`, `issue_key_redirects`. DATA.md 영속성 (db-engineer)
- D4. 백엔드 — `POST/GET/PATCH/DELETE /api/v1/issues`. `@Transactional`. pgmq 이벤트 발행 동일 트랜잭션 (backend-engineer + security-engineer 가드)
- D5. 백엔드 테스트 — MockK 단위 + Testcontainers 통합. TDD red→green→refactor (backend-engineer)
- D6. 프론트 UI — `IssueDetail.tsx`. TanStack Query 캐싱 + 낙관적 업데이트 (designer → frontend-engineer)
- D7. E2E + NFR — 생성→조회→수정→상태 전이→소프트 삭제→키 영속성 (qa-engineer)

### 진입 조건 점검 (`§0` 대조)

| 진입 조건 | 상태 | 처리 |
|---|---|---|
| identity-access §2.1 AuthenticationProvider | ✅ 완료 (PR #2~#8) | 활용 |
| identity-access §4.2 PERMISSION 가드 | ❌ 미완료 | 임시 가드 (`@PreAuthorize` 자리만 두고 본 가드는 FR-AU-12 후속) |
| project-workflow §1 FSM + §2.1 FR-WF-01 | ✅ 백엔드 완료 (PR #10) | 상태 전이 호출에 활용 |
| notification §1 STOMP / pgmq 트랜잭션 PoC | ⚠️ pgmq만 (PR #10 도입) / STOMP 미완 | pgmq 이벤트 발행만 본 PR. STOMP 구독은 후속 |
| DATA.md §이슈키 영속성 / §A.3 #5 이슈 키 prefix | ⚠️ DATA.md OK / prefix는 본 PR ADR에서 결정 | ADR 신규 — `<date>-issue-key-prefix-strategy` |

### 충돌 회피

- 진행 중인 PR #13 (`ui/project-workflow-fr-wf-01-frontend-2-pr`) 이 `apps/web/**` 영역 수정 중. **D6 wave는 PR #13 머지 확인 후 시작**.
- 진행 중인 PR #12 (`chore/eslint-no-console-frontend-logging-adr`) 가 frontend ESLint rule 추가 중. D6 frontend wave 시작 시 PR #12 머지 후 rebase 권장.

## 도메인 정리

### BC

- 주 BC. **issue-tracking** (`backend/modules/issue-tracking/`)
- 호출 대상 BC. project-workflow (`WorkflowTransitionPort.plan()`), identity-access (`IssuePermissionResolver` port, 본 PR이 정의)
- 발행 이벤트 (pgmq). `IssueCreated`, `IssueUpdated`, `IssueTransitioned`, `IssueSoftDeleted`

### 핵심 엔티티 / VO (D1)

| 이름 | 종류 | 책임 |
|---|---|---|
| `Issue` | Aggregate Root | 이슈 단건. 키/프로젝트/요약/리포터/현재 상태/version/deleted_at |
| `IssueKey` | Value Object | `<PROJECT_KEY>-<NUMBER>` 형식. 영구 보존 (DATA.md §1.1) |
| `IssueId` | Value Object | UUID 내부 식별자. 키 변경되어도 불변 |
| `Project` (참조) | Aggregate Root (별도 BC 후속 또는 본 PR에 최소 도입) | `key_sequence` 보유 — 시퀀스 증가 발급 |
| `IssueKeyRedirect` | 보조 엔티티 | `old_key → new_key` 영구 매핑. 본 PR은 **스키마만** 도입, 사용은 FR-MV-01 |

### Maxi 결정 사항 — ADR 후보 2건

**ADR-1. 이슈 키 prefix 결정 정책 — 사용자 직접 입력 (Jira 동일)**
- 프로젝트 생성 시 사용자가 영문 대문자 2~10자 prefix 직접 입력
- 검증. `^[A-Z][A-Z0-9]{1,9}$` + 중복 차단 + 예약어 차단 (e.g. `API`, `WWW`, `ADMIN`, `NULL`)
- 본 PR에서는 프로젝트 생성 API가 없으므로 dev seed (`data-dev.sql`) 로 단일 프로젝트 1개 (`ATLAS`) 삽입 — Project Management 후속 PR에서 사용자 입력 API 도입
- 위치. `docs/adr/2026-05-22-issue-key-prefix-policy.md`

**ADR-2. 임시 PERMISSION 가드 — IssuePermissionResolver port + stub**
- project-workflow ADR (`workflow-bc-cross-bc-port`) 의 port-adapter 패턴 일관 적용
- 인터페이스. `IssuePermissionResolver { fun hasPermission(actorId, permission, scope): Boolean }` — issue-tracking BC가 정의
- stub 구현. `AlwaysAllowIssuePermissionResolver` (`@Profile("!prod")`)
- 운영 차단. profile 미설정 시 부팅 실패 — workflow의 `AlwaysAllowPermissionResolver` 패턴 그대로
- FR-AU-12 시 identity-access BC가 실제 adapter (`IdentityAccessIssuePermissionResolver`) 제공
- 위치. `docs/adr/2026-05-22-issue-permission-resolver-port.md`

### 트랜잭션 흐름 (workflow + 알림 + 영속화)

```
IssueApplicationService.transition() — @Transactional
  ├─ 권한 가드: issuePermissionResolver.hasPermission(actor, "TRANSITION", Issue(key))
  ├─ workflow.plan(TransitionRequest) — port @Transactional(Propagation.MANDATORY)
  │     └─ TransitionPlan (toState, fieldChanges, emitEvents) 반환
  ├─ issueRepository.applyTransition(plan, version) — 낙관락 (version 충돌 시 409)
  ├─ outbox INSERT (pgmq.send_with_delay) — 같은 트랜잭션 내 enqueue (DATA.md §6 / §7.2)
  └─ 커밋
```

- Propagation.MANDATORY 가 누락 시 즉시 `IllegalTransactionStateException`. `@Transactional` 빠진 호출자 차단 (워크플로우 ADR §결정).
- pgmq enqueue 는 호출자(issue-tracking) 책임. `IssueEventPublisher` 신규 컴포넌트 (자체 BC 내).

### 본 PR scope 한정 (필드 / 테이블)

| 필드/테이블 | 본 PR | 후속 PR |
|---|---|---|
| `issues.key, project_id, summary, reporter_id, current_state_key, version, deleted_at, created_at, updated_at` | ✅ | — |
| `issues.type_id` | — | FR-IS-02 |
| `issues.assignee_id` | — | FR-IS-03 |
| `issues.body, priority, labels, environment, impact` | — | FR-IS-04 |
| `issues.resolution_id` | — | FR-IS-07 |
| `issue_key_redirects` 테이블 | ✅ 스키마만 | FR-MV-01 (사용) |
| `projects(key, key_sequence)` | ✅ 최소 (seed 1건) | Project Management 후속 |

### 글로서리 / domain note 갱신

- **글로서리 (`Maxi_wiki/BTS/glossary.md`)**. 신규 용어 없음. 기존 "이슈/이슈 키/IssueKeyRedirect/pgmq/포트" 그대로 활용
- **domain note (`Maxi_wiki/BTS/domain/issue-tracking.md`)**. `## 관련 ADR / Learnings` 섹션에 본 PR 두 ADR 추가 (PR 머지 시 sync-obsidian이 미러)

### ADR 디렉토리 분산 관찰 (본 PR scope 아님)

- `docs/decisions/` (auth 13개) + `docs/adr/` (workflow 5개) 양립. 본 PR은 `docs/adr/` 사용 (최신 컨벤션). 후속 일관성 정리 별도 PR 권장

### 관련 ADR (기존)

- `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` — port-adapter 패턴 + plan() + Propagation.MANDATORY (본 PR이 호출자 측 구현)
- `docs/adr/2026-05-21-v001-initial-schema-non-concurrent.md` — V001 스키마 작성 시 CONCURRENTLY 안 씀 (본 PR `V007__issues_initial.sql` 동일 정책)
- `docs/decisions/2026-05-20-session-pat-schema.md` — `actor_id` UUID 컬럼 명명 일관성

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
