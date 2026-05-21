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

## 스펙

전체 스펙. [`docs/specs/2026-05-22-issue-tracking-bc-fr-is-01-crud.md`](../specs/2026-05-22-issue-tracking-bc-fr-is-01-crud.md)

### 핵심 시나리오 6줄 요약

- S1. 사용자가 `POST /api/v1/issues { projectKey: "ATLAS", summary: "..." }` 호출 → `key_sequence` 증가 + `ATLAS-1` 발급 + pgmq `IssueCreated` (같은 트랜잭션)
- S2~S3. 단건 조회 / 부분 수정 (낙관락 version 검증) — 표준 REST
- S4. 상태 전이 — `WorkflowTransitionPort.plan()` 호출 (Propagation.MANDATORY) → 반환된 TransitionPlan 을 issue-tracking 이 적용 + pgmq `IssueTransitioned`
- S5. 소프트 삭제 — `deleted_at=NOW()` + 키 영구 보존. 새 이슈는 `ATLAS-2` 발급 (`ATLAS-1` 재발급 절대 없음)
- S6. 목록 조회 — Page<IssueResponse> + 페이지네이션 + 프로젝트 필터 + 정렬

### 7 엣지 케이스 (FR-IS-01 D2)

EC-1 키 race condition (advisory_xact_lock) / EC-2 권한 부재 (403 ProblemDetail) / EC-3 전이 위반 (409) / EC-4 대용량 목록 (인덱스) / EC-5 동시 편집 (낙관락 409) / EC-6 소프트 삭제 조회 (404) / EC-7 키 영속성 (재발급 차단).

### 추가 결정 (spec §6.1, §7.1, §7.2)

- §6.1. **RFC 7807 ProblemDetail** 표준 에러 형식 — issue-tracking BC 부터 BTS 전체 적용
- §7.1. `IssueApplicationService.transition()` 의 workflow.plan() 호출 + TransitionPlan 적용 + pgmq enqueue **단일 `@Transactional`** 흐름 명시 (Propagation.MANDATORY)
- §7.2. Testcontainers **singleton pattern** + workflow Bean wiring 격리 (PR #8/#10 learning 적용)

### 데이터 모델 (V007 + V008)

- `projects(id, key UNIQUE, name, key_sequence, ...)` 신규 + dev seed 1건 (`ATLAS`)
- `issues(id, key UNIQUE, project_id, summary, reporter_id, current_state_key, version, deleted_at, ...)` 신규
- `issue_key_redirects(old_key PK, new_key, redirected_at)` 신규 — **스키마만**, FR-MV-01 도입 시 사용
- pgmq 큐 `q_issue_events` 신규

### 본 PR 스코프 외 (명시 제외)

타입/담당자/본문/Resolution/첨부/멘션/Watcher/링크/히스토리/템플릿/이동 — 모두 후속 FR.

## Brainstorming Check

✅ 통과 (self-conducted, office-hours 우회). 4건 gap 발견 후 spec 보강 완료. 자세히 spec §Brainstorming Check.

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan

> **참고**. 본 plan 은 writing-plans 우회 (Maxi 의 "게이트 1까지 자동 진행" 의도 + 도메인/spec 단계 충분 도출). TDD red→green→refactor 강제 + wave 당 5~7 task (PR #10 learning) + task 메타 블록 (agent / files / depends-on) 필수 명시.

### Wave 0 — Domain VO 4종 (병렬, files 겹침 0)

#### Task 1. `ActorId` VO

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/ActorId.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/ActorIdTest.kt]` / depends-on: `[]`

**RED**. `ActorIdTest`. `@Test fun "ActorId requires non-nil UUID"()` → `ActorId(UUID.fromString("00000000-..."))` 호출 시 `IllegalArgumentException` 기대. 클래스 미존재로 fail.

**GREEN**. `data class ActorId(val value: UUID) { init { require(value != ZERO_UUID) } }`. ZERO_UUID 상수.

**REFACTOR**. KDoc 명시 — "identity-access UserId 와 동일 UUID 값. BC 격리로 별도 VO".

**검증**. `./gradlew :backend:issue-tracking:test --tests ActorIdTest`

#### Task 2. `IssueKey` VO

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueKey.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueKeyTest.kt]` / depends-on: `[]`

**RED**. `IssueKeyTest`. 형식 검증 (`ATLAS-1` accepted / `atlas-1` rejected / `ATLAS-0` rejected / `-1` rejected). 분리 메서드 `projectPrefix`, `number`.

**GREEN**. `@JvmInline value class IssueKey(val value: String) { init { require(REGEX.matches(value)) } }` + 정규식 `^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$`.

**REFACTOR**. `companion object { fun of(prefix: String, number: Long): IssueKey }` 헬퍼 + KDoc.

#### Task 3. `IssuePermission` enum + `IssueScope` sealed

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/port/outbound/IssuePermission.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/port/outbound/IssueScopeTest.kt]` / depends-on: `[]`

**RED**. `IssueScopeTest`. `Global`/`Project("ATLAS")`/`Issue("ATLAS-1")` 인스턴스화 + equals 검증.

**GREEN**. `enum class IssuePermission { VIEW, CREATE, UPDATE, TRANSITION, SOFT_DELETE, HARD_DELETE }` + `sealed interface IssueScope { object Global; data class Project(val key: String); data class Issue(val key: String) }`.

**REFACTOR**. KDoc — 각 권한이 어느 엔드포인트에서 검증되는지 명시.

#### Task 4. `IssueKeyPrefixReservedWords` 상수 + 검증 함수

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueKeyPrefixReservedWords.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueKeyPrefixReservedWordsTest.kt]` / depends-on: `[]`

**RED**. `IssueKeyPrefixReservedWordsTest`. `isReserved("API")` true / `isReserved("api")` true (대소문자 무관) / `isReserved("ATLAS")` true (BTS 자체명) / `isReserved("BUG")` false.

**GREEN**. `object IssueKeyPrefixReservedWords { val WORDS = setOf("API", "WWW", "ADMIN", "ROOT", "SYSTEM", "BTS", "ATLAS", "NULL", "UNDEFINED", "TEST", "DEBUG", "GET", "POST", ...); fun isReserved(word: String): Boolean = WORDS.contains(word.uppercase()) }`. 단, dev seed 의 `ATLAS` 는 본 PR 한정 예외 — ADR 에서 명시한 대로 본 PR 은 dev seed 1건만, 사용자 입력 API 는 후속 PR. 따라서 `WORDS` 에는 `ATLAS` 포함하되 본 PR 의 dev seed INSERT 는 검증 우회 (직접 SQL).

**REFACTOR**. 카테고리별 주석 (시스템 / 보안 / HTTP / SQL).

### Wave 1 — DB migration + jOOQ codegen + Permission stub (5 task, 일부 직렬)

#### Task 5. Flyway `V007__issues_initial.sql`

**메타**. agent: `db-engineer` / files: `[backend/db/migration/V007__issues_initial.sql]` / depends-on: `[]`

**RED**. Testcontainers Postgres 부팅 + `flywayMigrate` 실행 후 `psql -c "\d issues"` 가 4컬럼 이상 보유 검증 (현재는 테이블 미존재). 실패.

**GREEN**. spec §5 의 `projects`, `issues`, `issue_key_redirects` 3 테이블 + 인덱스 + CHECK 제약 작성. `CREATE EXTENSION IF NOT EXISTS "pgcrypto"` (gen_random_uuid).

**REFACTOR**. SQL 코멘트 — 각 테이블 책임 + DATA.md §1.1 인용.

**검증**. `./gradlew :backend:flywayMigrate -Pflyway.url=jdbc:postgresql://<testcontainer>` + jOOQ codegen `./gradlew generateJooq` 성공.

#### Task 6. Flyway `V008__pgmq_queue_issue_events.sql`

**메타**. agent: `db-engineer` / files: `[backend/db/migration/V008__pgmq_queue_issue_events.sql]` / depends-on: `[5]` (V007 마이그레이션 후 V008)

**RED**. `pgmq.q_q_issue_events` 큐 미존재 검증 → fail.

**GREEN**. `SELECT pgmq.create('q_issue_events');` (PR #10 의 pgmq 확장 활용).

**REFACTOR**. SQL 코멘트 — 발행되는 이벤트 4종 (`IssueCreated`, `IssueUpdated`, `IssueTransitioned`, `IssueSoftDeleted`).

#### Task 7. `IssuePermissionResolver` interface (outbound port)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/port/outbound/IssuePermissionResolver.kt]` / depends-on: `[1, 3]` (ActorId, IssuePermission/Scope)

**RED**. (interface 라 직접 테스트 불가. 대신 stub Bean 등록 검증을 T8 에서) — depends-on T1/T3 코드 컴파일 검증.

**GREEN**. `interface IssuePermissionResolver { fun hasPermission(actorId: ActorId, permission: IssuePermission, scope: IssueScope): Boolean }`.

**REFACTOR**. KDoc — ADR `issue-permission-resolver-port` 인용.

#### Task 8. `AlwaysAllowIssuePermissionResolver` stub

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/AlwaysAllowIssuePermissionResolver.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/AlwaysAllowIssuePermissionResolverTest.kt]` / depends-on: `[7]`

**RED**. `AlwaysAllowIssuePermissionResolverTest`. `hasPermission(...)` 무엇이든 true 반환 검증. WARN 로그 1건 검증 (logback test appender 또는 `mockk verify`). 클래스 미존재로 fail.

**GREEN**. `@Component @Profile("!prod") class AlwaysAllowIssuePermissionResolver : IssuePermissionResolver { override fun hasPermission(...) = true.also { log.warn(...) } }`.

**REFACTOR**. KDoc — FR-AU-12 시 교체 명시 + ADR 인용.

#### Task 9. `data-dev.sql` 의 `projects` seed 추가

**메타**. agent: `db-engineer` / files: `[backend/db/seed/data-dev.sql]` / depends-on: `[5]`

**RED**. dev profile 부팅 + `psql -c "SELECT count(*) FROM projects WHERE key='ATLAS'"` = 0 (seed 미적용). fail.

**GREEN**. spec §5.4 의 `INSERT INTO projects ... ON CONFLICT (key) DO NOTHING` 추가. PR #11 의 alice seed 와 동일 파일 (`data-dev.sql`) append.

**REFACTOR**. 주석 — "dev/staging 전용. Project Management 후속 PR 도입 시 제거 검토".

### Wave 2 — Domain entity + Exceptions + Outbox event types (4 task, 병렬)

#### Task 10. `Issue` Aggregate Root + `IssueId` VO

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt, backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueId.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueTest.kt]` / depends-on: `[1, 2]`

**RED**. `IssueTest`. `Issue.create(id, key, projectId, summary, reporterId, currentStateKey)` 호출 + invariants (summary 빈 문자열 거부, length>255 거부, version=1 초기, deletedAt null).

**GREEN**. `data class Issue(val id: IssueId, val key: IssueKey, val projectId: UUID, val summary: String, val reporterId: ActorId, val currentStateKey: String, val version: Long, val deletedAt: Instant?, val createdAt: Instant, val updatedAt: Instant)` + factory `companion object { fun create(...) }`.

**REFACTOR**. domain invariant 메서드 분리 (`validateSummary`).

#### Task 11. Custom exceptions

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueExceptions.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueExceptionsTest.kt]` / depends-on: `[2, 3]`

**RED**. 각 예외 인스턴스화 + message 검증.

**GREEN**. `IssueNotFoundException(val key: IssueKey)`, `IssueAccessDeniedException(val actor: ActorId, val permission: IssuePermission, val scope: IssueScope)`, `IssueVersionConflictException(val key: IssueKey, val currentVersion: Long)`, `IssueProjectNotFoundException(val projectKey: String)`, `IssueKeyPrefixReservedException(val prefix: String)`.

**REFACTOR**. 공통 base `IssueDomainException` sealed class.

#### Task 12. Outbox event sealed interface (`IssueDomainEvent`)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueDomainEventTest.kt]` / depends-on: `[2]`

**RED**. JSON 직렬화/역직렬화 round-trip 검증 (Jackson).

**GREEN**. `sealed interface IssueDomainEvent { data class IssueCreated(val key, val projectKey, val summary, val reporterId, val occurredAt) : IssueDomainEvent; data class IssueUpdated(...); data class IssueTransitioned(...); data class IssueSoftDeleted(...) }`.

**REFACTOR**. `@JsonTypeInfo(use = NAME, property = "type")` + KDoc 페이로드 contract.

#### Task 13. `IssueResponse` DTO + mapper

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponseTest.kt]` / depends-on: `[10]`

**RED**. `IssueResponse.from(issue)` 매핑 검증.

**GREEN**. `data class IssueResponse(val key: String, val id: UUID, val projectKey: String, val summary: String, val currentStateKey: String, val reporterId: UUID, val version: Long, val createdAt: Instant, val updatedAt: Instant)` + `companion object { fun from(issue: Issue, projectKey: String): IssueResponse }`.

### Wave 3 — Repository + EventPublisher (2 task, 병렬 — jOOQ gen 후)

#### Task 14. `IssueRepository` (jOOQ DSL)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt]` / depends-on: `[5, 10]` (V007 jOOQ codegen + Issue 엔티티)

**RED**. Testcontainers 통합. `insert / findByKey / findByKeyForUpdate / applyTransition / softDelete / list (page)` 6 메서드 RED.

**GREEN**. jOOQ DSL — `dsl.insertInto(ISSUES).set(...).returning(...).fetchOne()`, `dsl.selectFrom(ISSUES).where(ISSUES.KEY.eq(key.value).and(ISSUES.DELETED_AT.isNull())).fetchOne()`, etc. `SoftDeleteFilter` 자동 적용 (DATA.md §5).

**REFACTOR**. private SQL constant 추출 + Kotlin extension `Issue.toRecord()`.

**검증**. `./gradlew :backend:issue-tracking:integrationTest --tests IssueRepositoryTest`.

#### Task 15. `IssueEventPublisher` (pgmq enqueue)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueEventPublisher.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueEventPublisherTest.kt]` / depends-on: `[6, 12]` (V008 pgmq + IssueDomainEvent)

**RED**. Testcontainers 통합. `publisher.publish(IssueCreated(...))` 호출 후 `pgmq.read('q_issue_events', visibility=1, qty=1)` 가 1건 반환 검증.

**GREEN**. `@Component class IssueEventPublisher(private val dsl: DSLContext) { @Transactional(propagation = MANDATORY) fun publish(event: IssueDomainEvent) { dsl.execute("SELECT pgmq.send(?, ?::jsonb)", "q_issue_events", objectMapper.writeValueAsString(event)) } }`.

**REFACTOR**. private helper `payloadJson()` + KDoc — "MANDATORY = 호출자 트랜잭션 안에서만. 트랜잭션 commit 시 메시지 visible".

### Wave 4 — ApplicationService 6 메서드 (병렬, 같은 파일 — 하나의 task 또는 분할)

> **결정**. ApplicationService 6 메서드는 같은 파일 (`IssueApplicationService.kt`) 이므로 파일 겹침. bts-impl 자동 직렬화. 하지만 task 단위 TDD 사이클은 별도. 같은 wave 의 6 task 가 직렬 실행되도록 `depends-on` 으로 chain.

#### Task 16. `IssueApplicationService.createIssue`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceCreateTest.kt]` / depends-on: `[7, 8, 14, 15]`

**RED**. MockK 단위. `createIssue(actor, request)` 호출 시 (1) 권한 체크 호출, (2) advisory_xact_lock 획득, (3) projects.key_sequence UPDATE, (4) issues INSERT, (5) IssueCreated 이벤트 publish. 권한 거부 시 `IssueAccessDeniedException`.

**GREEN**. `@Service class IssueApplicationService(...) { @Transactional fun createIssue(actor: ActorId, request: CreateIssueRequest): IssueResponse { permissionResolver.hasPermission(...); val seq = projectRepo.incrementKeySequence(request.projectKey); val key = IssueKey.of(request.projectKey, seq); val issue = Issue.create(...); issueRepo.insert(issue); eventPublisher.publish(IssueCreated(...)); return IssueResponse.from(...) } }`. advisory_xact_lock 은 `projectRepo.incrementKeySequence` 내부.

**REFACTOR**. private helper `assertCanCreate()` + KDoc.

#### Task 17. `findByKey` + `IssueQueryService` 분리 검토 (단순화: 같은 클래스 readOnly 메서드)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceFindTest.kt]` / depends-on: `[16]`

**RED**. `findByKey(actor, key)` — 권한 VIEW 체크 + 미존재 시 `IssueNotFoundException` + 소프트 삭제 시 404 동일 취급.

**GREEN**. `@Transactional(readOnly = true) fun findByKey(actor: ActorId, key: IssueKey): IssueResponse`.

**REFACTOR**. 권한 가드 inline → `private fun assertCanView(actor, key)`.

#### Task 18. `updateIssue` (낙관락)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceUpdateTest.kt]` / depends-on: `[17]`

**RED**. `updateIssue(actor, key, request)` — version 불일치 시 `IssueVersionConflictException` + `IssueUpdated` 이벤트 발행.

**GREEN**. 낙관락. `issueRepo.applyPatch(key, summary, expectedVersion)` → updated row 0 이면 version conflict.

**REFACTOR**. 변경 필드 추적 → `IssueUpdated(fields=[...])`.

#### Task 19. `transitionIssue` — workflow.plan() 호출

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTransitionTest.kt]` / depends-on: `[18]`

**RED**. MockK 로 `WorkflowTransitionPort` mock. `plan(request)` 가 `TransitionPlan(toState=IN_PROGRESS, ...)` 반환 시 issues.current_state_key 갱신 + `IssueTransitioned` 이벤트. plan() 이 `WorkflowTransitionException` 던지면 그대로 propagate.

**GREEN**. spec §7.1 의 Kotlin 의사코드 그대로 구현. Propagation.MANDATORY 호출이므로 `@Transactional` 누락 시 `IllegalTransactionStateException` 던져짐 — 통합 테스트에서 보장.

**REFACTOR**. `private fun assertCanTransition(actor, key)` 분리.

#### Task 20. `softDeleteIssue`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceDeleteTest.kt]` / depends-on: `[19]`

**RED**. `softDelete(actor, key)` — 권한 체크 + `issues.deleted_at=NOW()` + `IssueSoftDeleted` 이벤트.

**GREEN**. `issueRepo.softDelete(key)` (`UPDATE issues SET deleted_at=NOW() WHERE key=? AND deleted_at IS NULL`).

**REFACTOR**. 이중 삭제 idempotent — 이미 삭제된 이슈에 DELETE 다시 호출 시 404 (관측 불가).

#### Task 21. `listIssues` (Page)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceListTest.kt]` / depends-on: `[20]`

**RED**. `listIssues(actor, projectKey, pageable)` — 권한 VIEW 체크 + Page 반환 + 소프트 삭제 제외.

**GREEN**. Spring `Pageable` 활용. `issueRepo.list(projectKey, pageable)` → `Page<Issue>` → `Page<IssueResponse>`.

**REFACTOR**. `Pageable.size > 100` 거부 (`IllegalArgumentException` → 400 mapping).

### Wave 5 — REST Controller + ExceptionHandler (5 task, 병렬 — files 분리)

#### Task 22. `IssueController` — `POST /issues`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerCreateTest.kt]` / depends-on: `[16, 13]`

**RED**. `@WebMvcTest` MockMvc. `POST /api/v1/issues` body 검증 + 201 응답 + Location 헤더.

**GREEN**. `@RestController @RequestMapping("/api/v1/issues") class IssueController(private val service: IssueApplicationService) { @PostMapping fun create(@Valid @RequestBody request: CreateIssueRequest, @AuthenticationPrincipal actor: AuthenticatedActor): ResponseEntity<IssueResponse> { val response = service.createIssue(actor.toActorId(), request); return ResponseEntity.created(URI("/api/v1/issues/${response.key}")).body(response) } }`.

**REFACTOR**. `CreateIssueRequest` 분리 파일 + `@Valid` annotation 적용.

#### Task 23. `IssueController` — `GET /issues/{key}`, `GET /issues`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerReadTest.kt]` / depends-on: `[22]`

**RED**. MockMvc. 단건/목록 응답 + 404 케이스.

**GREEN**. `@GetMapping("/{key}")` + `@GetMapping(produces=["application/json"]) fun list(...)`. `IssueKey.fromPath(key)` 변환.

**REFACTOR**. Page response shape — Spring `Page<T>` 직렬화 (PR #10 패턴 활용).

#### Task 24. `IssueController` — `PATCH`, `POST /{key}/transition`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerWriteTest.kt]` / depends-on: `[23]`

**RED**. MockMvc. 정상 200 + version 충돌 409 + transition 거부 409.

**GREEN**. `@PatchMapping("/{key}") fun update(...)` + `@PostMapping("/{key}/transition") fun transition(...)`.

**REFACTOR**. shared private `pathToKey(key: String): IssueKey`.

#### Task 25. `IssueController` — `DELETE /{key}`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerDeleteTest.kt]` / depends-on: `[24]`

**RED**. MockMvc. 204 + 404 케이스.

**GREEN**. `@DeleteMapping("/{key}") fun delete(...): ResponseEntity<Void>`.

#### Task 26. `IssueExceptionHandler` — RFC 7807 ProblemDetail

**메타**. agent: `backend-engineer` + `security-engineer` 검토 / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandlerTest.kt]` / depends-on: `[11]`

**RED**. MockMvc. 각 exception → 해당 HTTP 상태 + ProblemDetail body + errorCode 매핑 (spec §6.1 표).

**GREEN**. `@RestControllerAdvice class IssueExceptionHandler { @ExceptionHandler(IssueAccessDeniedException::class) fun handle(e: ...): ResponseEntity<ProblemDetail> { ... } }` × 7개 (spec §6.1).

**REFACTOR**. helper `private fun problem(status, type, title, errorCode, detail, ...): ProblemDetail`.

### Wave 6 — ArchUnit + Integration tests + Property tests (6 task)

#### Task 27. ArchUnit — BC 격리 룰

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/architecture/IssueBcIsolationArchTest.kt]` / depends-on: `[26]` (모든 코드 작성 완료 후 검증)

**RED**. `IssueBcIsolationArchTest`. issue-tracking 클래스가 `com.bts.identity.*` 또는 `com.bts.workflow.adapter.*` 직접 import 시 fail. workflow 의 port (`com.bts.workflow.port.*`) 는 OK.

**GREEN**. ArchUnit 룰 작성 — PR #10 의 `WorkflowBcIsolationArchTest` 참고.

**REFACTOR**. shared rules → `:backend:common:arch` 모듈로 추출 후속 PR (본 PR 은 모듈별 복사).

#### Task 28. ArchUnit — @Transactional Bean 룰

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/architecture/TransactionalServiceArchTest.kt]` / depends-on: `[26]`

**RED**. 같은 룰 (learning #5 `@Service` 부착 누락 시 @Transactional 무력화) 을 issue-tracking 에 적용.

**GREEN**. PR #8 의 `TransactionalServiceArchTest` 복사.

#### Task 29. Testcontainers `IssueTestcontainersBase` (singleton)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueTestcontainersBase.kt]` / depends-on: `[5, 6]`

**RED**. 통합 테스트가 abstract base 상속 시 컨테이너 시작 — singleton 패턴 (PR #8 learning).

**GREEN**. `abstract class IssueTestcontainersBase { companion object { @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16").apply { start() } } }` + Spring `@DynamicPropertySource`.

**REFACTOR**. KDoc — `@Container` 금지 (learning #4) + PR #8 인용.

#### Task 30. EC-1 race condition 통합 테스트 (20 thread)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueKeyRaceConditionTest.kt]` / depends-on: `[16, 29]`

**RED**. 20 thread × `POST /api/v1/issues { projectKey: ATLAS, summary: "..." }` 병렬. 기대. 20건 모두 201 + key `ATLAS-1` ~ `ATLAS-20` (순차).

**GREEN**. advisory_xact_lock 동작 검증. `projects.key_sequence` = 20 + `issues` row count = 20 + 키 중복 0건.

**REFACTOR**. helper `private fun concurrentCreates(count: Int): List<HttpResponse>`.

#### Task 31. EC-2~EC-7 통합 테스트 (6 시나리오)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueCrudIntegrationTest.kt]` / depends-on: `[26, 29]`

**RED**. spec §6 의 EC-2~EC-7 각 1 시나리오 — 권한 / 전이 위반 / 대용량 (1000건 seed + 페이지 50건 p95 < 500ms) / 동시 편집 (낙관락 409) / 소프트 삭제 / 키 영속성 (5-cycle).

**GREEN**. 통합 테스트 6 메서드. workflow Bean 은 stub (workflow 도 `AlwaysAllowPermissionResolver` 동작).

**REFACTOR**. test data builder (`IssueTestDataBuilder`) — 반복 setup 제거.

#### Task 32. Kotest property test — 키 영속성 invariant

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueKeyPropertyTest.kt]` / depends-on: `[2, 4]`

**RED**. 1000 random 입력 — `IssueKey.of(prefix, number)` 가 `value` 가 `^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$` 매치 + idempotent `IssueKey(IssueKey.value).value == value`. `IssueKeyPrefixReservedWords.isReserved(prefix)` invariant.

**GREEN**. Kotest `checkAll` (PR #10 패턴 활용).

### Wave 7 — Frontend D6 (PR #13 + PR #12 머지 후 시작. design-shotgun → designer → frontend)

> **조건부 wave**. PR #13 (`ui/project-workflow-fr-wf-01-frontend-2-pr`) + PR #12 (`chore/eslint-no-console-frontend-logging-adr`) 머지 완료 확인 후 wave 시작. 미머지 시 wave skip 후 D7 도 skip — 후속 PR 로 분리. /bts-impl 컨트롤러가 wave 7 진입 직전 `gh pr list --state open` 으로 확인.

#### Task 33. design-shotgun — IssueDetail.tsx 디자인 변형 4종

**메타**. agent: `designer` / files: `[public/mockups/issue-detail-{1,2,3,4}.html]` / depends-on: `[]` (Wave 7 의 첫 task — backend 와 무관)

**산출물**. `public/mockups/issue-detail-{1,2,3,4}.html` 4 변형. DESIGN.md 토큰 (Tailwind v4) 활용. Maxi 가 1개 선택.

**검증**. Playwright 4 mockup 로드 + screenshot 비교. Maxi 선택 후 plan §Frontend §선택 변형 기록.

#### Task 34. `IssueDetail.tsx` — 선택 변형 구현

**메타**. agent: `frontend-engineer` / files: `[apps/web/src/pages/issues/IssueDetail.tsx, apps/web/src/pages/issues/IssueDetail.test.tsx]` / depends-on: `[33]`

**RED**. Vitest. `<IssueDetail issueKey="ATLAS-1" />` 렌더링 + 로딩 / 에러 / 성공 3 상태 + 편집 모드.

**GREEN**. shadcn/ui Form + TanStack Query `useIssue` + `useUpdateIssue`. 낙관적 업데이트 (낙관락 409 시 invalidate).

**REFACTOR**. TipTap 자리만 두기 (FR-IS-04 body 후속), 본 PR 은 summary 만.

#### Task 35. `useIssue` / `useUpdateIssue` / `useTransitionIssue` TanStack Query hooks

**메타**. agent: `frontend-engineer` / files: `[apps/web/src/api/issues.ts, apps/web/src/api/issues.test.ts]` / depends-on: `[34]`

**RED**. msw 로 API mock + Vitest. `useIssue("ATLAS-1")` 가 데이터 반환 / 401 에서 redirect / 404 에서 fallback / 409 에서 toast.

**GREEN**. `@tanstack/react-query` + Zod schema (`IssueResponseSchema`) — apiClient 일관.

**REFACTOR**. shared `IssueQueryKeys` helper.

#### Task 36. `IssueList` 페이지 + 라우팅

**메타**. agent: `frontend-engineer` / files: `[apps/web/src/pages/issues/IssueList.tsx, apps/web/src/router.tsx]` / depends-on: `[35]`

**RED**. Vitest. `/projects/ATLAS/issues` 렌더 + 페이지네이션.

**GREEN**. TanStack Router code-based + `useInfiniteQuery` 또는 `useQuery` Page.

**REFACTOR**. `IssueListRow` 컴포넌트 분리.

### Wave 8 — D7 E2E + NFR (qa-engineer, PR #13 머지 후)

#### Task 37. Playwright E2E S1~S6 시나리오

**메타**. agent: `qa-engineer` / files: `[apps/web/e2e/issue-crud.spec.ts]` / depends-on: `[36]`

**RED**. spec §1 의 S1~S6 시나리오. Local provider 로 로그인 → 이슈 생성 → 조회 → 수정 → 전이 → 소프트 삭제 → 목록 검증. PR #11 의 E2E S1/S2/S8 패턴 일관.

**GREEN**. 6 E2E 시나리오 통과.

**REFACTOR**. shared fixture `loggedInPage`.

#### Task 38. k6 NFR 측정 (단건/목록/POST/전이/DELETE)

**메타**. agent: `qa-engineer` / files: `[backend/perf/k6/issue-crud-nfr.js, docs/perf/2026-05-22-issue-crud-nfr-result.md]` / depends-on: `[36]`

**RED**. 부재.

**GREEN**. k6 스크립트 + Testcontainers + 1000건 seed. spec §3 임계값 5개 측정 + p95 값 결과 docs 기록.

**REFACTOR**. spec §3 NFR 측정값 표 채움 (실측 < 임계 검증).

## Plan 메타

- **task 수**. 38 (Wave 0 = 4 / Wave 1 = 5 / Wave 2 = 4 / Wave 3 = 2 / Wave 4 = 6 / Wave 5 = 5 / Wave 6 = 6 / Wave 7 = 4 / Wave 8 = 2)
- **wave 수**. 9 (Wave 7~8 은 PR #13 머지 조건부)
- **예상 시간**. monster PR 수준 (PR #10 = 36 task, PR #11 = 20 task + 10 controller wave). 약 4~6시간 (병렬 dispatch 가정)
- **TDD 강제**. yes (모든 task RED → GREEN → REFACTOR + spec-compliance-verifier 자동 검증)
- **wave 당 task 상한**. 7 (PR #10 learning 준수, wave 4 의 6 task 가 최대)
- **병렬 dispatch**. bts-impl 이 task 메타(`depends-on` + `files`)로 wave 계산. 같은 파일 겹침은 자동 직렬화.
- **conditional wave**. Wave 7~8 은 PR #13 머지 확인 후 진행. 미머지 시 D5 완료 후 일단 PR 머지 → D6/D7 후속 PR 분리 (옵션 B 패턴 — PR #10 의 결정 일관)
- **agent 분포**. backend-engineer (26 task) / db-engineer (3 task) / designer (1 task) / frontend-engineer (3 task) / qa-engineer (2 task) / security-engineer (검토만, 1 task)
- **추가 검증**. ktlintCheck / detekt / generateJooq / ArchUnit / Playwright / k6 / typecheck / vitest


## 리뷰 결과

> **참고**. type=api → plan-eng-review + plan-devex-review 체인. Maxi 의 "게이트 1까지 자동 진행" 의도 반영 — sub-skill 인터랙티브 우회, 인라인 self-conducted (PR #10 의 plan-eng/ceo-review skip 패턴 일관). 4종 리뷰 (eng / ceo / design / devex) 의 핵심 룰 동시 적용.

### plan-eng-review (self-conducted, 2026-05-22)

| 영역 | 평가 | 메모 |
|---|---|---|
| 아키텍처 일관성 | ✅ PASS | port-adapter 패턴 일관 (workflow `WorkflowTransitionPort` ↔ issue `IssuePermissionResolver`). BC 격리 ArchUnit 룰 명시 |
| 트랜잭션 경계 | ✅ PASS | spec §7.1 IssueApplicationService 의사코드 + Propagation.MANDATORY workflow 호출 + pgmq enqueue 동일 트랜잭션. ArchUnit `@Transactional` 룰 (PR #8 learning) Task 28 에 명시 |
| 동시성 / race condition | ✅ PASS | EC-1 (`pg_advisory_xact_lock(hash('project:' || project_id))` PR #10 패턴) + EC-5 낙관락 version 명시. Task 30 (20 thread 통합 테스트) 검증 |
| 테스트 커버리지 | ✅ PASS | 단위 (MockK) + 통합 (Testcontainers singleton) + property (Kotest 1000건) + ArchUnit. 임계 line 80% / branch 70% 명시 |
| 성능 / NFR | ⚠️ CONCERN-1 | k6 인프라가 본 PR 에서 첫 도입. 인프라 셋업 task (T38) 가 측정 task 와 묶여 있음. 분리 권장 — `T38a: k6 도구 설치 / T38b: 측정` 또는 `qa-engineer agent prompt 에 도구 설치 단계 명시` |
| 회귀 가드 (learning 반영) | ✅ PASS | PR #6 (`@Service` 누락) / PR #8 (Testcontainers singleton) / PR #10 (wave 7 task 상한 + cache stampede) learning 모두 plan 에 반영 |

**eng-review BLOCKER**: 없음
**eng-review CONCERN**: 1건 (k6 인프라 setup 분리)

### plan-devex-review (self-conducted, 2026-05-22)

API 인지 type 이므로 외부 개발자 (API consumer = frontend, 향후 자동화 BC, Slack 통합 BC) 관점 검증.

| 영역 | 평가 | 메모 |
|---|---|---|
| API 설계 일관성 | ✅ PASS | REST 표준 (CRUD + transition action) + Page<T> + RFC 7807 ProblemDetail + Spring `@AuthenticationPrincipal` |
| 에러 응답 표준 | ✅ PASS | spec §6.1 ProblemDetail 9 errorCode 표 + `IssueExceptionHandler` 명시. **다른 BC 도 동일 적용 권장** (CONCERN-2) |
| OpenAPI / 문서 | ⚠️ CONCERN-3 | spec §8 에 "springdoc-openapi" 명시했으나 plan task 에 explicit task 부재. `Wave 5` 끝에 OpenAPI 검증 task 추가 권장 — `T26.5: springdoc-openapi 통합 + apps/web/src/api/openapi-issue.d.ts 생성` |
| 버전 관리 | ✅ PASS | `/api/v1/issues` prefix 명시. v2 분기 후속 결정 |
| Idempotency | ✅ PASS | POST/PATCH/DELETE 모두 명확한 단일 효과. Idempotency-Key 헤더 미사용 — 본 PR scope 외 (후속 자동화 BC 도입 시) |
| frontend client schema | ⚠️ CONCERN-4 | T35 `useIssue` 의 Zod schema 가 plan 에 명시됨. 단, ProblemDetail 의 typed error code 매칭 — `IssueErrorCode` enum 을 frontend 도 공유? `packages/contracts/issue.ts` 같은 shared types 모듈 후속 권장 |

**devex-review BLOCKER**: 없음
**devex-review CONCERN**: 3건 (다른 BC 의 ProblemDetail 적용 권장 / OpenAPI task 명시 / shared types 모듈 후속)

### plan-ceo-review (self-conducted, 2026-05-22) — scope + 가치 판단

| 영역 | 평가 | 메모 |
|---|---|---|
| 사용자 가치 | ✅ PASS | BTS 의 핵심 가치. issue-tracking BC 진입 첫 PR — 27 후속 FR 모두 본 PR 의존 |
| scope 적정성 | ⚠️ CONCERN-5 | 38 task / 9 wave — PR #10 (36) / PR #11 (20+10) 수준 monster PR. Maxi 가 명시적으로 monster PR 선택 (옵션 A 거부) — 정당화 됨. 단, Wave 7~8 conditional 처리가 PR 사이즈 변동성 큼. **PR #13/#12 머지 ETA 확인 권장** — 미머지 시 옵션 B (D5 까지만 머지 / D6/D7 후속 PR 분리) 미리 결정 |
| 시간 비용 | ⚠️ CONCERN-6 | monster PR review burden 누적. `/bts-codereview` 가 code-reviewer agent + /review 2종 다 돌림. PR #10 의 CONCERN 2건 (medium + medium) 머지 직전 fix 패턴 반복 우려 |
| 대체 접근 (10-star product) | ✅ PASS | 더 큰 그림 (자동 분류 / AI 자동 assign / 자연어 검색 등) 은 후속 FR 에서 가능. 본 PR 은 fundamental coverage. CEO 시각 도 정당 |
| 결정 회수성 | ✅ PASS | port-adapter 패턴으로 FR-AU-12 교체 가능. RFC 7807 표준 채택 — 후속 BC 도 일관 가능 |

**ceo-review BLOCKER**: 없음
**ceo-review CONCERN**: 2건 (PR #13/#12 머지 ETA 의존 / monster PR review burden)

### plan-design-review (self-conducted, 2026-05-22) — Wave 7 D6 한정

| 영역 | 평가 | 메모 |
|---|---|---|
| DESIGN.md 일관 | ✅ PASS | PR #11 의 DESIGN.md (21 KB Tailwind v4) 활용. shadcn/ui radix-nova 토큰 활용 |
| design-shotgun 시점 | ⚠️ CONCERN-7 | Task 33 design-shotgun 결과 Maxi 가 1개 선택 요구. 사실상 게이트 1.5 발생 — implementation 중간에 추가 사용자 개입. plan §Plan 메타 에 conditional wave 명시했으나, **Maxi 게이트 1 승인 시점에 "Wave 7 진입 시 추가 Maxi 선택 1회 발생" 명시 권장** |
| IssueDetail.tsx scope | ✅ PASS | 본 PR 은 summary 만 (body/type/assignee 후속). FR-IS-04 body Markdown 도입 시 TipTap 자리만 두기 명시 (Task 34) |
| WCAG / 접근성 | ⚠️ CONCERN-8 | spec §NFR 표에 "WCAG 2.1 AA 0 violations" 명시되어 있으나 D6 task (T34) 에 axe-core 검증 명시 없음. 추가 권장 |

**design-review BLOCKER**: 없음
**design-review CONCERN**: 2건 (Wave 7 추가 게이트 / axe-core 검증)

### autoplan 결정 원칙 적용 (8건)

| # | 결정 영역 | 자동 결정 / taste decision | 결과 |
|---|---|---|---|
| 1 | 이슈 키 prefix 정책 | taste decision | Maxi 결정 완료 (ADR-1, "사용자 직접 입력") |
| 2 | PERMISSION 가드 전략 | taste decision | Maxi 결정 완료 (ADR-2, port-adapter stub) |
| 3 | RFC 7807 ProblemDetail 채택 | 자동 결정 | 표준 + 일관성. 채택 |
| 4 | advisory_xact_lock vs ON CONFLICT | 자동 결정 | PR #10 패턴 일관 + race condition 검증 명확 → advisory_xact_lock |
| 5 | issue_key_redirects 스키마 본 PR 도입 | 자동 결정 | DATA.md §1.1 영속성 보장 일관 + 후속 FR-MV-01 변경 비용 절감 |
| 6 | Wave 7~8 conditional 처리 | 자동 결정 | PR #13/#12 머지 의존 — 미머지 시 옵션 B (D5 머지 + D6/D7 분리) |
| 7 | k6 도입 본 PR vs 후속 | taste decision | 본 PR 도입 (CONCERN-1) — 첫 NFR 측정 BC 라 도입 가치 큼 |
| 8 | Issue Aggregate 단순 data class vs 진짜 DDD Aggregate | 자동 결정 | 본 PR 은 data class. invariant 메서드 분리 (Task 10 refactor). uncommitted events 패턴은 후속 FR 누적 시 도입 검토 |

**taste decision 의 Maxi 검토 결과**. 본 게이트 1 에서 추가 확인.

### 종합 (BLOCKER / CONCERN 카운트)

- **BLOCKER 0건**
- **CONCERN 8건** (eng 1 / devex 3 / ceo 2 / design 2)
  - 핵심 3건. PR #13/#12 머지 ETA / monster PR review burden / Wave 7 추가 게이트
  - 보강 5건. k6 분리 / ProblemDetail 타 BC 적용 / OpenAPI task 명시 / shared types 후속 / axe-core 검증

CONCERN 은 머지 차단 사유 아님 (PR #10 도 머지 직전 fix 패턴). 본 PR 진행 가능하되 implementation 중 보강 권장.
