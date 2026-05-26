<!-- FR-IS-01 issue-tracking BC 비즈니스 로직 (Wave 2~6) — PR #14 부트스트랩 위에서 작성 -->

# FR-IS-01 issue-tracking BC 비즈니스 로직 (Wave 2~6 후속 PR)

> slug. `issue-tracking-bc-fr-is-01-business-logic`
> type. `backend` (api 수준, PR #14 의 부트스트랩 위에 비즈니스 로직)
> primary agent. `backend-engineer`
> 보조 agent. `db-engineer`, `security-engineer` (검토)
> BC. `issue-tracking`
> 생성. 2026-05-23

## Brief

PR #14 (머지 — `333b709`) 가 제공한 issue-tracking BC 부트스트랩 (모듈 + VO 4종 + V001/V002 마이그레이션 + IssuePermissionResolver port + AlwaysAllow stub + dev seed + pgmq 이미지) 위에서 **비즈니스 로직** 구현.

### 사용자 원문

> "wave 2 후속 pr 시작하자" + AskUserQuestion 결정 — "Wave 2~6 한꺼번에 (PR #14 계획 그대로)"

### 작업 범위 (Wave 2~6, 26 task)

`docs/plans/2026-05-22-issue-tracking-bc-fr-is-01-crud.md` (main 머지본) §Plan 의 Wave 2~6 발췌.

- **Wave 2** (4 task, 병렬). Issue Aggregate + IssueId VO / Custom Exceptions / IssueDomainEvent sealed / IssueResponse DTO
- **Wave 3** (2 task, 병렬). IssueRepository (jOOQ) / IssueEventPublisher (pgmq enqueue)
- **Wave 4** (6 task, 같은 파일 직렬). IssueApplicationService — `createIssue` / `findByKey` / `updateIssue` / `transitionIssue` / `softDeleteIssue` / `listIssues`
- **Wave 5** (5 task, 병렬 — files 분리). IssueController 5 엔드포인트 + IssueExceptionHandler (RFC 7807 ProblemDetail)
- **Wave 6** (6 task). ArchUnit (BC 격리 + @Transactional Bean) / Testcontainers `IssueTestcontainersBase` (singleton) / EC-1 race condition 통합 / EC-2~7 통합 6 시나리오 / Kotest property test (키 영속성 invariant)

### 본 PR scope 외 (명시 제외)

- D6 Frontend (Wave 7) — design-shotgun → IssueDetail.tsx + IssueList + TanStack Query — 후속 PR
- D7 E2E + NFR (Wave 8) — Playwright S1~S6 + k6 NFR — 후속 PR

### PR #14 후속 정리 (본 PR 진행 중)

| CONCERN | PR #14 결정 | 본 PR 처리 시점 |
|---|---|---|
| C-1 nu.studer.jooq private 필드 reflection | 후속 정리 | Wave 4 ApplicationService 진입 직전 검토 (jOOQ DSL 활용 시점) |
| C-2 init_codegen.sql 중복 유지 | V003 도입 PR 자동화 | 본 PR 은 V003 미도입, 현행 유지 |
| C-4 WARN 로그 폭증 | Wave 2 ApplicationService 검토 | T16 createIssue 도입 시 로그 sampling 검토 |
| C-5 docker-compose `:latest` 태그 | prod 진입 시 BLOCKER | 본 PR 미터치 |

## 도메인 정리

> **PR #14 의 §도메인 정리 재인용** — 동일 BC, 동일 ADR. 본 PR 은 그 위에서 비즈니스 로직 작성. 신규 ADR 없음 (Wave 2~6 진행 중 발견 시 추가).

### 활용 자산 (PR #14 머지본)

- **Domain VO 4종**. `ActorId`, `IssueKey` (`@JvmInline` + REGEX), `IssuePermission` enum (6종), `IssueScope` sealed (Global/Project/Issue), `IssueKeyPrefixReservedWords` (25 단어)
- **DB 스키마**. `projects(id, key UNIQUE, name, key_sequence, deleted_at)`, `issues(id, key UNIQUE, project_id FK, summary, reporter_id, current_state_key, version, deleted_at)`, `issue_key_redirects(old_key PK, new_key, redirected_at)` (스키마만)
- **pgmq 큐**. `q_issue_events` (V002 도입)
- **Outbound port**. `IssuePermissionResolver` interface + `AlwaysAllowIssuePermissionResolver` stub (`@Profile("!prod")`)
- **dev seed**. `ATLAS` 프로젝트 1건
- **인프라**. `quay.io/tembo/pg16-pgmq:latest` 이미지 + jOOQ codegen Testcontainers 우회 + application-dev.yml/test.yml + ktlint generated 제외 패턴

### 본 PR 신규 엔티티 / VO (Wave 2)

| 이름 | 종류 | 책임 |
|---|---|---|
| `Issue` | Aggregate Root | 이슈 단건 도메인 객체. invariant (summary 빈 문자열/255 초과 거부) + factory `Issue.create()` |
| `IssueId` | Value Object | UUID 내부 식별자. 키 변경되어도 불변 |
| `IssueNotFoundException` 외 4종 | Domain Exception | spec §6.1 ProblemDetail 매핑 대상 |
| `IssueDomainEvent` sealed | Outbox event | `IssueCreated` / `IssueUpdated` / `IssueTransitioned` / `IssueSoftDeleted` |
| `IssueResponse` | REST DTO | `Issue.toResponse()` + `Page<IssueResponse>` |

### 외부 호출 (Cross-BC Port)

- **호출 in**. `IssuePermissionResolver` (PR #14 도입 port + AlwaysAllow stub 사용)
- **호출 out**. `WorkflowTransitionPort.plan()` — project-workflow BC 가 정의 (PR #10), Propagation.MANDATORY. issue-tracking 의 `IssueApplicationService.transition()` 가 호출자.

### 트랜잭션 흐름 (Wave 4 ApplicationService)

```
IssueApplicationService.transition() — @Transactional
  ├─ 권한 가드: issuePermissionResolver.hasPermission(actor, TRANSITION, Issue(key))
  ├─ workflow.plan(TransitionRequest) — Propagation.MANDATORY (같은 트랜잭션)
  │     └─ TransitionPlan (toState, fieldChanges, emitEvents) 반환
  ├─ issueRepository.applyTransition(plan, version) — 낙관락 (version 충돌 시 IssueVersionConflictException)
  ├─ outbox INSERT (pgmq.send) — 같은 트랜잭션 내 enqueue
  └─ 커밋
```

### 관련 ADR (PR #14 머지본, main 에 존재)

- `docs/adr/2026-05-22-issue-key-prefix-policy.md`
- `docs/adr/2026-05-22-issue-permission-resolver-port.md`
- `docs/adr/2026-05-22-pgmq-postgres-image.md`
- `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` (project-workflow BC, 본 PR 의 outbound port 호출 대상)

## 스펙

전체 스펙. `docs/specs/2026-05-22-issue-tracking-bc-fr-is-01-crud.md` (main 머지본, PR #14 작성, **Wave 2~6 영역 그대로 활용**).

### 본 PR 한정 발췌 (spec 의 어느 영역)

| spec §  | 영역 | 본 PR scope |
|---|---|---|
| §1.S1~S6 | 6 사용자 시나리오 (Given-When-Then) | ✅ — Wave 4 ApplicationService 의 단위 테스트 + Wave 6 통합 테스트 검증 |
| §2 FR (a~i) | 9 sub-FR (생성/조회/수정/전이/삭제/목록/이벤트/권한/redirect) | ✅ — redirect 사용은 후속 FR-MV-01 |
| §3 NFR | 9 성능/품질 임계 | 일부만 — k6 측정은 Wave 8 (후속 PR), 본 PR 은 단위/통합 테스트 임계 |
| §4 API | 6 REST 엔드포인트 명세 | ✅ — Wave 5 Controller |
| §5 데이터 모델 | V001/V002 스키마 | (PR #14 머지됨, 본 PR 활용) |
| §6 7 엣지 케이스 (EC-1~EC-7) | 7 통합 시나리오 | ✅ — Wave 6 통합 테스트 (race condition / 권한 / 전이 위반 / 대용량 / 동시 편집 / 소프트 삭제 / 키 보존) |
| §6.1 RFC 7807 ProblemDetail | 9 errorCode 표 | ✅ — Wave 5 IssueExceptionHandler |
| §7.1 IssueApplicationService 의사코드 | Kotlin 코드 가이드 | ✅ — Wave 4 6 메서드 구현 가이드 |
| §7.2 Testcontainers singleton | 통합 테스트 인프라 | ✅ — Wave 6 IssueTestcontainersBase |
| §8 측정 가능한 완료 기준 | 8 항목 | 일부 — D6/D7 (Frontend, E2E, k6) 는 후속 PR |

### 본 PR 의 핵심 시나리오 (spec §1)

- **S1**. 이슈 생성 — `POST /api/v1/issues { projectKey, summary }` → `key_sequence` 증가 + `ATLAS-N` 발급 + pgmq `IssueCreated` (같은 트랜잭션)
- **S4**. 상태 전이 — `WorkflowTransitionPort.plan()` 호출 (Propagation.MANDATORY) → 반환된 TransitionPlan 을 issue-tracking 이 적용 + pgmq `IssueTransitioned`
- **S5**. 소프트 삭제 — `DELETE` → `deleted_at=NOW()` + 키 영구 보존 + pgmq `IssueSoftDeleted`

## Brainstorming Check

✅ 통과 (PR #14 의 brainstorming check 재인용 — 4 gap 발견 후 보강 완료. 본 PR 은 추가 gap 없음, 그 결과를 spec 본문에 그대로 활용).

본 PR 한정 추가 검토 1건. **PR #14 의 C-1 reflection 의존 + C-4 WARN 로그** 후속 정리를 본 PR 진행 중 적정 시점에 처리할지 확인 — Wave 4 진입 시 결정 (controller 게이트).

## Plan

> **PR #14 plan 의 Wave 2~6 발췌 + 본 PR 관점 보강**. 26 task / 5 wave (Wave 2~6).

### Wave 2 — Domain entity + Exceptions + Outbox event types (4 task, 병렬)

#### Task 1. `Issue` Aggregate Root + `IssueId` VO

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt, backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueId.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueTest.kt]` / depends-on: `[]` (PR #14 의 ActorId, IssueKey 는 main 에 머지됨)

**RED**. `IssueTest`. `Issue.create(id, key, projectId, summary, reporterId, currentStateKey)` 호출 + invariants (summary 빈 문자열 거부, length>255 거부, version=1 초기, deletedAt null).

**GREEN**. `data class Issue(...)` + factory `companion object { fun create(...) }` + IssueId VO (`@JvmInline value class IssueId(val value: UUID)`).

**REFACTOR**. domain invariant 메서드 분리 (`validateSummary`).

#### Task 2. Custom exceptions

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueExceptions.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueExceptionsTest.kt]` / depends-on: `[]`

**RED**. 각 예외 인스턴스화 + message 검증.

**GREEN**. sealed base `IssueDomainException` + `IssueNotFoundException(key)`, `IssueAccessDeniedException(actor, permission, scope)`, `IssueVersionConflictException(key, currentVersion)`, `IssueProjectNotFoundException(projectKey)`, `IssueKeyPrefixReservedException(prefix)`.

#### Task 3. Outbox event sealed interface (`IssueDomainEvent`)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueDomainEventTest.kt]` / depends-on: `[]`

**RED**. Jackson JSON 직렬화/역직렬화 round-trip 검증 + `@JsonTypeInfo` 타입 표기.

**GREEN**. `sealed interface IssueDomainEvent` + 4 data class (`IssueCreated`, `IssueUpdated`, `IssueTransitioned`, `IssueSoftDeleted`) + `@JsonTypeInfo(use=NAME, property="type")` + occurredAt timestamp.

#### Task 4. `IssueResponse` DTO + mapper

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponseTest.kt]` / depends-on: `[1]` (Issue 엔티티)

**RED**. `IssueResponse.from(issue, projectKey)` 매핑 검증.

**GREEN**. `data class IssueResponse(key, id, projectKey, summary, currentStateKey, reporterId, version, createdAt, updatedAt)` + factory.

### Wave 3 — Repository + EventPublisher (2 task, 병렬)

#### Task 5. `IssueRepository` (jOOQ DSL)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryTest.kt]` / depends-on: `[1]` (Issue 엔티티)

**RED**. Testcontainers (PR #14 의 IssueTestcontainersBase singleton 사용). `insert / findByKey / findByKeyForUpdate / applyTransition / softDelete / list (page) / incrementKeySequence` 7 메서드 RED.

**GREEN**. jOOQ DSL — `dsl.insertInto(ISSUES)...returning()`, `dsl.selectFrom(ISSUES).where(ISSUES.KEY.eq(key.value).and(ISSUES.DELETED_AT.isNull()))...`. soft delete 필터 명시. advisory_xact_lock 활용 — `incrementKeySequence(projectKey: String): Long` 안에서 `pg_advisory_xact_lock(hash('project:' || project_id))`.

**REFACTOR**. private SQL constant + Kotlin extension `Issue.toRecord()`.

#### Task 6. `IssueEventPublisher` (pgmq enqueue)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueEventPublisher.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueEventPublisherTest.kt]` / depends-on: `[3]` (IssueDomainEvent)

**RED**. Testcontainers 통합. `publisher.publish(IssueCreated(...))` 후 `pgmq.read('q_issue_events', visibility=1, qty=1)` 가 1건 반환 검증.

**GREEN**. `@Component class IssueEventPublisher(private val dsl: DSLContext) { @Transactional(propagation = MANDATORY) fun publish(event: IssueDomainEvent) { dsl.execute("SELECT pgmq.send(?, ?::jsonb)", "q_issue_events", objectMapper.writeValueAsString(event)) } }`.

### Wave 4 — IssueApplicationService 6 메서드 (같은 파일 직렬)

> **결정**. 같은 `IssueApplicationService.kt` 파일이라 files 겹침 → 직렬. depends-on 으로 chain.

#### Task 7. `IssueApplicationService.createIssue`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceCreateTest.kt]` / depends-on: `[5, 6]` (Repo + EventPublisher, PR #14 의 IssuePermissionResolver + AlwaysAllow stub 활용)

**RED**. MockK 단위. `createIssue(actor, request)` 호출 시 (1) 권한 체크, (2) advisory_xact_lock 획득, (3) projects.key_sequence UPDATE, (4) issues INSERT, (5) IssueCreated 이벤트 publish. 권한 거부 시 `IssueAccessDeniedException`.

**GREEN**. `@Service @Transactional` createIssue 메서드. advisory_xact_lock 은 Repository 내부 (T5).

**REFACTOR**. private helper `assertCanCreate()` + KDoc.

#### Task 8. `findByKey` (readOnly)

**메타**. agent: `backend-engineer` / files: `[...IssueApplicationService.kt, ...IssueApplicationServiceFindTest.kt]` / depends-on: `[7]`

**RED**. VIEW 권한 체크 + 미존재 시 `IssueNotFoundException` + 소프트 삭제 시 404 동일.

**GREEN**. `@Transactional(readOnly = true) fun findByKey(actor, key): IssueResponse`.

#### Task 9. `updateIssue` (낙관락)

**메타**. files: 위 동일 / depends-on: `[8]`

**RED**. version 불일치 시 `IssueVersionConflictException` + `IssueUpdated` 이벤트 발행.

**GREEN**. `issueRepo.applyPatch(key, summary, expectedVersion)` → updated row 0 이면 conflict.

**REFACTOR**. 변경 필드 추적 → `IssueUpdated(fields=[...])`.

#### Task 10. `transitionIssue` — workflow.plan() 호출

**메타**. files: 위 동일 / depends-on: `[9]`

**RED**. MockK 로 `WorkflowTransitionPort` mock. `plan(request)` 반환 시 issues.current_state_key 갱신 + `IssueTransitioned`. plan() 이 `WorkflowTransitionException` 던지면 그대로 propagate.

**GREEN**. spec §7.1 의 Kotlin 의사코드 그대로 구현. Propagation.MANDATORY 호출이므로 `@Transactional` 누락 시 즉시 `IllegalTransactionStateException` (통합 테스트에서 보장).

#### Task 11. `softDeleteIssue`

**메타**. files: 위 동일 / depends-on: `[10]`

**RED**. SOFT_DELETE 권한 + `deleted_at=NOW()` + `IssueSoftDeleted`.

**GREEN**. `issueRepo.softDelete(key)` (`UPDATE issues SET deleted_at=NOW() WHERE key=? AND deleted_at IS NULL`). 이중 삭제 idempotent (이미 삭제된 이슈 → 404).

#### Task 12. `listIssues` (Page)

**메타**. files: 위 동일 / depends-on: `[11]`

**RED**. `listIssues(actor, projectKey, pageable)` — VIEW 권한 + Page<IssueResponse> + 소프트 삭제 제외.

**GREEN**. Spring `Pageable` 활용. `Pageable.size > 100` 거부.

### Wave 5 — Controller + ExceptionHandler (5 task, 병렬 — files 분리)

#### Task 13. `IssueController` 골격 + `POST /issues`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt, backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequest.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerCreateTest.kt]` / depends-on: `[7, 4]` (createIssue + IssueResponse)

**RED**. `@WebMvcTest` MockMvc. `POST /api/v1/issues` body 검증 + 201 응답 + Location 헤더.

**GREEN**. `@RestController @RequestMapping("/api/v1/issues")` 골격 + create 엔드포인트. `CreateIssueRequest(projectKey, summary)` + `@Valid` (jakarta.validation).

#### Task 14. `IssueController` — `GET /issues/{key}`, `GET /issues`

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerReadTest.kt]` / depends-on: `[13]` (controller 골격)

**RED**. MockMvc. 단건/목록 응답 + 404 + Page response shape.

**GREEN**. `@GetMapping("/{key}")` + `@GetMapping fun list(...)`. Spring `Page<T>` 직렬화 (PR #10 패턴 활용).

#### Task 15. `IssueController` — `PATCH`, `POST /{key}/transition`

**메타**. files: 위 IssueController.kt + 신규 test / depends-on: `[14]`

**RED**. MockMvc. 정상 200 + version 충돌 409 + transition 거부 409.

**GREEN**. `@PatchMapping("/{key}")` + `@PostMapping("/{key}/transition")` + `UpdateIssueRequest` / `TransitionIssueRequest`.

#### Task 16. `IssueController` — `DELETE /{key}`

**메타**. files: 위 IssueController.kt + 신규 test / depends-on: `[15]`

**RED**. MockMvc. 204 + 404.

**GREEN**. `@DeleteMapping("/{key}")`.

#### Task 17. `IssueExceptionHandler` — RFC 7807 ProblemDetail

**메타**. agent: `backend-engineer` (+ security-engineer 검토) / files: `[backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt, backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandlerTest.kt]` / depends-on: `[2]` (custom exceptions)

**RED**. MockMvc. 각 exception → 해당 HTTP 상태 + ProblemDetail body + errorCode 매핑 (spec §6.1 9 errorCode 표 전수).

**GREEN**. `@RestControllerAdvice class IssueExceptionHandler` + 9 `@ExceptionHandler`. helper `private fun problem(status, type, title, errorCode, detail, ...): ProblemDetail`.

### Wave 6 — ArchUnit + Integration tests + Property test (6 task)

#### Task 18. ArchUnit — BC 격리 룰

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/architecture/IssueBcIsolationArchTest.kt]` / depends-on: `[17]` (모든 코드 작성 완료 후)

**RED**. issue-tracking 클래스가 `com.bts.identity.*` 또는 `com.bts.workflow.adapter.*` 직접 import 시 fail. workflow 의 port (`com.bts.workflow.port.*`) 는 OK.

**GREEN**. ArchUnit 룰 — PR #10 의 `WorkflowBcIsolationArchTest` 패턴 참고.

#### Task 19. ArchUnit — @Transactional Bean 룰

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/architecture/TransactionalServiceArchTest.kt]` / depends-on: `[17]`

**RED**. learning #5 (`@Service` 누락 시 `@Transactional` 무력화) — issue-tracking 모든 `@Transactional` 메서드 클래스가 `@Component` 계열 필수.

**GREEN**. PR #8 의 `TransactionalServiceArchTest` 복사.

#### Task 20. Testcontainers `IssueTestcontainersBase` (singleton)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueTestcontainersBase.kt]` / depends-on: `[]` (PR #14 의 V001/V002 활용)

**RED**. 통합 테스트가 abstract base 상속 시 컨테이너 시작 — singleton 패턴 (PR #8 learning).

**GREEN**. `abstract class IssueTestcontainersBase { companion object { @JvmStatic val postgres = PostgreSQLContainer<Nothing>("quay.io/tembo/pg16-pgmq:latest").apply { ... start() } } }` + Spring `@DynamicPropertySource`.

**REFACTOR**. KDoc — `@Container` 금지 + PR #8 인용. PR #14 의 build.gradle.kts buildscript classpath 의 `PostgreSQLContainer` 와 동일 패턴.

#### Task 21. EC-1 race condition 통합 테스트 (20 thread)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueKeyRaceConditionTest.kt]` / depends-on: `[7, 20]`

**RED**. 20 thread × `POST /api/v1/issues { projectKey: ATLAS, summary: "..." }` 병렬. 기대. 20건 모두 201 + key `ATLAS-1` ~ `ATLAS-20` (순차).

**GREEN**. advisory_xact_lock (T5 incrementKeySequence) 동작 검증. `projects.key_sequence` = 20 + `issues` row count = 20.

#### Task 22. EC-2~EC-7 통합 테스트 (6 시나리오)

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueCrudIntegrationTest.kt]` / depends-on: `[17, 20]`

**RED**. spec §6 의 EC-2~EC-7. 권한 / 전이 위반 / 대용량 (1000건 seed + 페이지 50건 p95 < 500ms) / 동시 편집 (낙관락 409) / 소프트 삭제 / 키 영속성 (5-cycle).

**GREEN**. 6 메서드. workflow Bean 은 stub (workflow 도 `AlwaysAllowPermissionResolver`).

**REFACTOR**. test data builder (`IssueTestDataBuilder`) — 반복 setup 제거.

#### Task 23. Kotest property test — 키 영속성 invariant

**메타**. agent: `backend-engineer` / files: `[backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueKeyPropertyTest.kt]` / depends-on: `[1]` (Issue 엔티티)

**RED**. 1000 random 입력 — `IssueKey.of(prefix, number)` 가 `value` 가 정규식 매치 + idempotent. `IssueKeyPrefixReservedWords.isReserved(prefix)` invariant.

**GREEN**. Kotest `checkAll` (PR #10 패턴 활용).

## Plan 메타

- **task 수**. 23 (Wave 2 = 4 / Wave 3 = 2 / Wave 4 = 6 / Wave 5 = 5 / Wave 6 = 6)
- **wave 수**. 5
- **예상 시간**. monster PR 수준 (PR #14 = 13 task 였고, 본 PR 은 23 task — 약 2배). 컨텍스트 폭증 위험 — 게이트 1 이후 implementation 진입 시점 controller 가 컨텍스트 사용량 측정 후 다음 세션 분리 결정 가능.
- **TDD 강제**. yes
- **wave 당 task 상한**. 7 (PR #10 learning 준수). Wave 4 의 6 task 가 최대 (직렬).
- **병렬 dispatch**. Wave 2 (4 병렬), Wave 3 (2 병렬), Wave 4 (6 직렬 같은 파일), Wave 5 (5 병렬), Wave 6 (6 부분 병렬).
- **agent 분포**. backend-engineer 전수 (23 task). security-engineer 는 IssueExceptionHandler (T17) 검토만.
- **추가 검증**. ktlintCheck (PR #14 의 generated 제외 패턴 활용) / detekt / generateJooq / ArchUnit (T18, T19) / Testcontainers 통합 (T21, T22) / Kotest property (T23).

## 리뷰 결과

> **PR #14 의 4종 self-review (eng/devex/ceo/design + autoplan 8 결정) 재활용**. 본 PR 은 그 위에서 비즈니스 로직 구현 — 추가 리뷰는 본 PR 한정 변경점만.

### light 리뷰 (self-conducted, 2026-05-23)

| 영역 | 평가 | 메모 |
|---|---|---|
| 아키텍처 일관성 | ✅ PASS | port-adapter (PR #14 도입) 활용. WorkflowTransitionPort 호출 패턴 일관 |
| TDD 강제 | ✅ PASS | 23 task 전수 RED → GREEN → REFACTOR 명시 |
| Wave 구조 | ✅ PASS | Wave 4 의 6 task 가 같은 파일 직렬화 — 자동 wave 계산 가능. PR #14 learning (wave 당 7 task max) 준수 |
| BC 격리 | ✅ PASS | ArchUnit (T18) 으로 빌드 시점 검증 |
| 트랜잭션 경계 | ✅ PASS | spec §7.1 의사코드 + Propagation.MANDATORY 일관 |
| 회귀 가드 | ✅ PASS | PR #6/#8/#10/#11/#14 learnings 모두 plan 에 반영 |

### CONCERN (본 PR 한정, 머지 차단 아님)

- **C-A. 26 task monster PR 위험**. PR #14 가 13 task / 29 files / +2760 line 이었고, 본 PR 은 약 2배. /bts-impl 진입 시 컨텍스트 사용량 측정 — wave 4 또는 wave 5 종료 시점에 분리 결정 가능 (옵션 B 일관 패턴).
- **C-B. PR #14 의 4 CONCERN 후속 처리**. C-1 reflection / C-2 init_codegen / C-4 WARN 로그 / C-5 :latest tag — 본 PR 의 진행 중 적정 시점 (Wave 4 진입 직전) 에 controller 가 처리 결정.

### BLOCKER 0건

### 추천 결정 — 게이트 1 진입 가능

## 본 PR 진행 전략 (게이트 1 직전 controller 노트)

본 PR 의 scope (23 task) 는 monster PR 위험이 명백. PR #14 의 패턴 (옵션 B — Wave 1c 종료 시점에 scope 축소 결정) 을 반복할 수 있도록 다음 분기점 명시.

| 분기점 | 진행 가능 조건 | 분리 결정 시 |
|---|---|---|
| Wave 3 완료 (6 task) | 컨텍스트 여유 + agent dispatch 안정 | scope 축소 — Domain + Repo + EventPublisher 만 본 PR. ApplicationService + Controller + Test 후속 PR |
| Wave 4 완료 (12 task) | 같은 위 + ApplicationService 단위 테스트 통과 | scope 축소 — Domain + Repo + ApplicationService 본 PR. Controller + Test 후속 PR |
| Wave 5 완료 (17 task) | REST API 동작 + 단위 테스트 다 통과 | scope 적정 — Controller 까지 본 PR. ArchUnit + 통합/property test 후속 PR |
| Wave 6 완료 (23 task) | 전부 통과 | scope 완전 — 본 PR 일괄 머지 (PR #10 패턴) |

controller 가 wave 종료마다 측정 + Maxi 확인 가능.
