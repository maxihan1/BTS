<!-- 스펙: FR-IS-01 이슈 CRUD — issue-tracking BC 진입. 7 엣지 케이스 + REST API + NFR + 측정 기준 -->

# FR-IS-01 이슈 CRUD — 스펙

> slug. `issue-tracking-bc-fr-is-01-crud`
> 대상 FR. SDD `docs/sdd/02-requirements.md §2.1.1` + `docs/plan/product/issue-tracking.md §2.1.1`
> 일자. 2026-05-22
> 작성. backend-engineer agent (Maxi 승인)

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 이슈 생성 — 정상 흐름

```
Given. 로그인한 사용자가 ATLAS 프로젝트 멤버이고 CREATE 권한 보유
When.  POST /api/v1/issues { projectKey: "ATLAS", summary: "버그 수정" }
Then.  201 Created + 본문 { key: "ATLAS-1", id: <uuid>, currentStateKey: "TO_DO", version: 1 }
       AND  ATLAS 프로젝트의 key_sequence 가 0 → 1
       AND  pgmq outbox 에 IssueCreated 이벤트 enqueue (같은 트랜잭션)
```

### S2. 이슈 단건 조회 — 정상 흐름

```
Given. ATLAS-1 이슈 존재 (deleted_at IS NULL), 호출자가 VIEW 권한 보유
When.  GET /api/v1/issues/ATLAS-1
Then.  200 OK + 이슈 본문 (key, project, summary, currentStateKey, reporterId, version, createdAt, updatedAt)
```

### S3. 이슈 부분 수정 — 정상 흐름

```
Given. ATLAS-1 (version=1) 존재, 호출자가 UPDATE 권한 보유
When.  PATCH /api/v1/issues/ATLAS-1 { summary: "긴급 버그 수정", version: 1 }
Then.  200 OK + 본문 { summary: "긴급 버그 수정", version: 2 }
       AND  pgmq outbox 에 IssueUpdated { fields: ["summary"] } enqueue
```

### S4. 상태 전환 — workflow.plan() 호출 후 적용

```
Given. ATLAS-1 (currentStateKey="TO_DO", version=2), 호출자가 TRANSITION 권한 보유
       AND  WorkflowTransitionPort.plan(ATLAS-1, TO_DO → IN_PROGRESS) 가 TransitionPlan { toState: IN_PROGRESS, fieldChanges: [], emitEvents: [IssueTransitioned] } 반환
When.  POST /api/v1/issues/ATLAS-1/transition { transitionKey: "start-progress", version: 2 }
Then.  200 OK + 본문 { currentStateKey: "IN_PROGRESS", version: 3 }
       AND  pgmq outbox 에 IssueTransitioned 이벤트 enqueue (같은 트랜잭션)
       AND  WorkflowEngine 이 plan() 만 반환, 이슈 영속화는 issue-tracking 책임 (workflow-bc-cross-bc-port ADR §결정 준수)
```

### S5. 소프트 삭제 — 키 영구 보존

```
Given. ATLAS-1 존재, 호출자가 SOFT_DELETE 권한 보유
When.  DELETE /api/v1/issues/ATLAS-1
Then.  204 No Content
       AND  issues.deleted_at = NOW() (소프트 삭제, row 자체는 보존)
       AND  GET /api/v1/issues/ATLAS-1 → 404 Not Found
       AND  ATLAS 프로젝트 다시 이슈 생성 시 key_sequence 가 1 → 2 → 새 이슈는 ATLAS-2 (ATLAS-1 키 재발급 절대 없음)
       AND  pgmq outbox 에 IssueSoftDeleted 이벤트 enqueue
```

### S6. 목록 조회 — 페이지네이션 + 필터

```
Given. ATLAS 프로젝트에 이슈 75건 (deleted_at IS NULL), 호출자가 프로젝트 VIEW 권한 보유
When.  GET /api/v1/issues?projectKey=ATLAS&page=1&size=50&sort=createdAt,desc
Then.  200 OK + Page { content: [50건], pageNumber: 1, pageSize: 50, totalElements: 75, totalPages: 2 }
       AND  소프트 삭제된 이슈는 결과에 미포함 (jOOQ SoftDeleteFilter 자동 적용)
```

## 2. 기능 요구사항 (FR)

`docs/plan/product/issue-tracking.md §2.1.1 FR-IS-01` 기반.

| FR ID (sub) | 한 줄 | 본 PR scope |
|---|---|---|
| FR-IS-01.a | 이슈 생성 + 키 자동 발급 (`<PROJECT_KEY>-<NUMBER>`) | ✅ |
| FR-IS-01.b | 이슈 단건 조회 | ✅ |
| FR-IS-01.c | 이슈 부분 수정 (낙관락 version 검증) | ✅ |
| FR-IS-01.d | 이슈 상태 전환 (WorkflowTransitionPort.plan() 호출 + pgmq 이벤트) | ✅ |
| FR-IS-01.e | 이슈 소프트 삭제 + 키 영구 보존 | ✅ |
| FR-IS-01.f | 이슈 목록 조회 (페이지네이션 + 프로젝트 필터 + 정렬) | ✅ |
| FR-IS-01.g | pgmq outbox 패턴으로 이벤트 발행 (동일 트랜잭션) | ✅ |
| FR-IS-01.h | 권한 가드 (`IssuePermissionResolver` port, stub) | ✅ (FR-AU-12 시 adapter 교체) |
| FR-IS-01.i | `issue_key_redirects` 스키마만 도입 (사용은 FR-MV-01) | ✅ 스키마만 |

## 3. 비기능 요구사항 (NFR)

`docs/plan/product/issue-tracking.md §2.1.1` 측정 기준 + §NFR 표 기반.

| 항목 | 임계 (p95) | 측정 도구 | 검증 시점 |
|---|---|---|---|
| `GET /issues/{key}` 단건 조회 | 200 ms | k6 (Testcontainers PG) | D7 NFR 측정 |
| `GET /issues?...` 목록 50건 | 500 ms | k6 | D7 NFR 측정 |
| `POST /issues` 생성 (pgmq 이벤트 포함) | 300 ms | k6 | D7 NFR 측정 |
| `POST /issues/{key}/transition` 전환 | 400 ms | k6 (workflow.plan() 포함) | D7 NFR 측정 |
| `DELETE /issues/{key}` 소프트 삭제 | 200 ms | k6 | D7 NFR 측정 |
| 동시 PATCH 충돌 처리 (낙관락) | 409 응답 | Testcontainers 통합 테스트 (20 thread) | D5 |
| pgmq 이벤트 순서 보존 | 동일 트랜잭션 commit 순서 | Testcontainers (pgmq 도구) | D5 |
| 단위 테스트 커버리지 | line 80%+, branch 70%+ | jacoco | D5 |
| ArchUnit 룰 (BC 격리, @Transactional Bean) | 0 위반 | ArchUnit | D5 |

## 4. API 인터페이스 (REST)

OpenAPI 3.1 스타일 요약. 본 PR에서 `apps/web/openapi` 자동 생성 + `springdoc-openapi` 통합.

### 4.1 `POST /api/v1/issues` — 이슈 생성

| 항목 | 값 |
|---|---|
| Auth | `Authorization: Bearer <session-jwt>` (FR-AU-09 세션 토큰) |
| Body | `{ projectKey: string, summary: string }` |
| Response 201 | `{ key: string, id: uuid, projectKey: string, summary: string, currentStateKey: string, reporterId: uuid, version: int, createdAt: iso8601, updatedAt: iso8601 }` |
| Response 400 | `summary` 빈 문자열, 길이 초과 (255+), `projectKey` 형식 위반 |
| Response 401 | 세션 만료 |
| Response 403 | CREATE 권한 부재 (`IssuePermissionResolver.hasPermission`이 false) |
| Response 404 | `projectKey` 존재하지 않음 |
| Response 500 | pgmq 발행 실패 (rollback) |

### 4.2 `GET /api/v1/issues/{key}` — 단건 조회

| 항목 | 값 |
|---|---|
| Auth | `Authorization: Bearer <session-jwt>` |
| Path | `key: string (PREFIX-NUMBER 형식)` |
| Response 200 | 이슈 본문 (위와 동일 스키마) |
| Response 308 | 이슈 이동됨 — `Location: /api/v1/issues/<new_key>` 헤더 (FR-MV-01 활성화 후) — 본 PR 은 308 로직 미구현 (스키마만), 항상 404 또는 200 |
| Response 401 / 403 / 404 | 인증 / 권한 / 미존재 또는 소프트 삭제 |

### 4.3 `PATCH /api/v1/issues/{key}` — 부분 수정

| 항목 | 값 |
|---|---|
| Auth | `Authorization: Bearer <session-jwt>` |
| Body | `{ summary?: string, version: int }` |
| Response 200 | 갱신된 이슈 본문 (version 증가) |
| Response 400 | body 빈 객체 (version 만), 형식 위반 |
| Response 409 | version 충돌 (낙관락 실패) |

### 4.4 `POST /api/v1/issues/{key}/transition` — 상태 전환

| 항목 | 값 |
|---|---|
| Auth | `Authorization: Bearer <session-jwt>` |
| Body | `{ transitionKey: string, version: int }` |
| Response 200 | 갱신된 이슈 본문 (currentStateKey 변경 + version 증가) |
| Response 400 | `transitionKey` 형식 위반 |
| Response 409 | version 충돌 OR workflow 가 plan() 에서 전환 거부 (`WorkflowTransitionException`) |

### 4.5 `DELETE /api/v1/issues/{key}` — 소프트 삭제

| 항목 | 값 |
|---|---|
| Auth | `Authorization: Bearer <session-jwt>` |
| Response 204 | 성공 (body 없음) |
| Response 401 / 403 / 404 | 표준 |

### 4.6 `GET /api/v1/issues` — 목록 조회

| 항목 | 값 |
|---|---|
| Auth | `Authorization: Bearer <session-jwt>` |
| Query | `projectKey: string (필수), page?: int (기본 0), size?: int (기본 20, 최대 100), sort?: string (기본 createdAt,desc)` |
| Response 200 | Spring `Page<IssueResponse>` 형식 |

## 5. 데이터 모델 변경

`backend/modules/issue-tracking/src/main/resources/db/migration/V001__issues_initial.sql` (모듈별 Flyway namespace, identity-access V001~V006 / project-workflow V001 과 별개) (단일 마이그레이션).

### 5.1 `projects` (신규)

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | UUID | PK, DEFAULT gen_random_uuid() |
| `key` | VARCHAR(10) | NOT NULL UNIQUE, CHECK 정규식 `^[A-Z][A-Z0-9]{1,9}$` |
| `name` | VARCHAR(255) | NOT NULL |
| `key_sequence` | BIGINT | NOT NULL DEFAULT 0 |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |
| `deleted_at` | TIMESTAMPTZ | NULL |

### 5.2 `issues` (신규)

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `id` | UUID | PK, DEFAULT gen_random_uuid() |
| `key` | VARCHAR(20) | NOT NULL UNIQUE, CHECK 정규식 `^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$` |
| `project_id` | UUID | NOT NULL FK → projects(id) |
| `summary` | VARCHAR(255) | NOT NULL |
| `reporter_id` | UUID | NOT NULL (identity-access users(id) 참조 — FK 는 BC 격리 위해 미적용, ApplicationService 가드) |
| `current_state_key` | VARCHAR(50) | NOT NULL (workflow state key, FR-WF-01 발급) |
| `version` | BIGINT | NOT NULL DEFAULT 1 (낙관락) |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |
| `deleted_at` | TIMESTAMPTZ | NULL |

인덱스. `idx_issues_project_id` (project_id), `idx_issues_deleted_at` (`WHERE deleted_at IS NULL`, partial).

### 5.3 `issue_key_redirects` (신규, 스키마만)

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `old_key` | VARCHAR(20) | PK |
| `new_key` | VARCHAR(20) | NOT NULL |
| `redirected_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() |

본 PR 은 INSERT 없음. FR-MV-01 (이슈 이동) 도입 시 사용.

### 5.4 dev seed (`backend/modules/issue-tracking/src/main/resources/data-dev.sql` — 모듈별 separate)

```sql
INSERT INTO projects (id, key, name, key_sequence) VALUES
  ('00000000-0000-0000-0000-000000000001', 'ATLAS', 'Atlas Issues', 0)
ON CONFLICT (key) DO NOTHING;
```

PR #11 의 `data-dev.sql` (Alice 사용자) 와 **분리** — 모듈별 separate 파일 (identity-access 와 issue-tracking 각자 보유). Spring Boot `spring.sql.init.data-locations` 가 두 파일 모두 로드.

### 5.5 pgmq 큐 — `q_issue_events`

`backend/modules/issue-tracking/src/main/resources/db/migration/V002__pgmq_queue_issue_events.sql`. workflow 가 PR #10 에서 도입한 pgmq 확장 (`CREATE EXTENSION pgmq`) 활용.

```sql
SELECT pgmq.create('q_issue_events');
```

이벤트 페이로드는 JSON. 본 PR 도입 이벤트: `IssueCreated`, `IssueUpdated`, `IssueTransitioned`, `IssueSoftDeleted`.

## 6. 엣지 케이스 (FR-IS-01 D2 7 케이스)

| # | 케이스 | 동작 | 검증 |
|---|---|---|---|
| EC-1 | 중복 키 race condition | **선택. `pg_advisory_xact_lock(hash('project:' || project_id))` + UPDATE projects.key_sequence + INSERT issues**. advisory lock 이 project 단위로 직렬화 → 동시 POST 시 한 트랜잭션씩 처리. UNIQUE 위반 자체는 발생 안 함. lock 보유 시간 < 10 ms (단순 UPDATE + INSERT). PR #10 의 `pg_advisory_xact_lock` 패턴 (200 ms 워크플로우 캐시) 일관 적용 | Testcontainers 20 thread 동시 POST — 모두 200 + 순차 key 발급 |
| EC-2 | 권한 부재 | `IssuePermissionResolver.hasPermission(actor, IssuePermission.X, scope) == false` → `IssueAccessDeniedException` → 403 + 본문 `{ error: "ACCESS_DENIED", required: "CREATE" }` | MockK 단위 + 통합 (stub 의 거짓 응답 시나리오) |
| EC-3 | 전환 위반 | `WorkflowTransitionPort.plan()` 이 `WorkflowTransitionException("transition not allowed")` 던짐 → 409 + 본문 `{ error: "TRANSITION_NOT_ALLOWED" }` | 통합 (project-workflow 통합 — 미허용 전환 호출) |
| EC-4 | 대용량 (목록 1000 + 페이지 50건) | 인덱스 사용 (`idx_issues_project_id`) + EXPLAIN 분석. p95 < 500 ms 유지 | k6 + EXPLAIN ANALYZE |
| EC-5 | 동시 편집 | `version` 낙관락. 두 클라이언트가 version=2 로 PATCH → 한쪽 200, 다른 쪽 409 + 본문 `{ error: "VERSION_CONFLICT", currentVersion: 3 }` | Testcontainers 2 thread 동시 PATCH |
| EC-6 | 소프트 삭제된 이슈 조회 | `deleted_at IS NOT NULL` → 404 (200 + body 마스킹 아님, 완전 미존재 시뮬레이션) | 통합 (DELETE 후 GET) |
| EC-7 | 키 보존 (재발급 금지) | 소프트 삭제 후 같은 프로젝트에 새 이슈 생성 → `key_sequence` 증가 → 새 키. 옛 키는 issues 테이블에 deleted_at 으로 잔존. 재발급 시도 = 자동 차단 (UNIQUE 제약) | 통합 (5-회 create/delete/create cycle) |

### 6.1 표준 에러 응답 형식 — RFC 7807 ProblemDetail

본 PR 부터 issue-tracking BC 의 모든 에러 응답은 Spring 6 의 `ProblemDetail` (RFC 7807 — application/problem+json) 표준 형식 채택. 이후 BC 도 동일.

```json
{
  "type": "https://bts.example.com/errors/version-conflict",
  "title": "Version Conflict",
  "status": 409,
  "detail": "Issue ATLAS-1 was modified by another transaction. Current version is 3, you sent version 2.",
  "instance": "/api/v1/issues/ATLAS-1",
  "errorCode": "VERSION_CONFLICT",
  "currentVersion": 3
}
```

| HTTP | errorCode | type suffix | 시나리오 |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | `validation-failed` | summary 빈 문자열, 길이 초과 등 |
| 401 | `UNAUTHENTICATED` | `unauthenticated` | 세션 만료 |
| 403 | `ACCESS_DENIED` | `access-denied` | `IssuePermissionResolver` false |
| 404 | `ISSUE_NOT_FOUND` | `issue-not-found` | 미존재 또는 소프트 삭제됨 |
| 404 | `PROJECT_NOT_FOUND` | `project-not-found` | projectKey 미존재 |
| 409 | `KEY_PREFIX_RESERVED` | `key-prefix-reserved` | 예약어 prefix 사용 시도 |
| 409 | `VERSION_CONFLICT` | `version-conflict` | 낙관락 충돌 |
| 409 | `TRANSITION_NOT_ALLOWED` | `transition-not-allowed` | workflow 거부 |
| 500 | `INTERNAL_ERROR` | `internal-error` | 미분류 — pgmq 발행 실패 포함 (rollback 후) |

`@RestControllerAdvice` 의 `IssueExceptionHandler` 가 `IssueAccessDeniedException`, `IssueVersionConflictException`, `WorkflowTransitionException` 등을 ProblemDetail 로 변환.

## 7. 제약 조건 (DEVELOPMENT.md 절대 규칙 19개 대조)

| 규칙 | 본 PR 적용 |
|---|---|
| #1 `@Transactional` 명시 | `IssueApplicationService` 의 모든 mutation 메서드 + ArchUnit 룰 검증 |
| #4 BC 격리 (직접 import 금지) | `IssueApplicationService` 가 `WorkflowTransitionPort` interface 만 의존. ArchUnit 룰 검증 |
| #7 모든 public service 가 `@Service` 부착 | `IssueApplicationService`, `IssueRepository`, `IssueEventPublisher` 등. ArchUnit 룰 |
| #12 nullable 회피 — 명시적 sealed result | `WorkflowTransitionResult sealed interface (Success / TransitionNotAllowed / VersionConflict)` |
| #15 logger 만 사용, `println` / `System.out` 금지 | `slf4j` (`LoggerFactory.getLogger`) |
| #16 PoC 금지 — 완제품 기준 | 모든 코드 production-ready. AlwaysAllowIssuePermissionResolver 도 stub 이지만 production-ready 패턴 (`@Profile("!prod")` 로 운영 차단) |
| 절대 규칙 19개 전부 | `/bts-codereview` 가 PR 단위 자동 검증 |

DATA.md 5원칙.

| 원칙 | 본 PR 적용 |
|---|---|
| 1. 이슈 키 영구 보존 | `issues.key UNIQUE` + 소프트 삭제 후 row 잔존 + `IssueKeyRedirect` 스키마 도입 |
| 2. DELETE 는 WHERE + 소프트 우선 | `DELETE` 엔드포인트는 `UPDATE issues SET deleted_at=NOW() WHERE key=? AND deleted_at IS NULL` |
| 3. Flyway 만 | `V007__issues_initial.sql` + `V008__pgmq_queue_issue_events.sql` |
| 4. `@Transactional` 명시 + ArchUnit | 위 §7 동일 |
| 5. 인증/CSRF 우회 불가 | 모든 엔드포인트가 SecurityFilterChain 통과 (PR #8 자산 활용) |

### 7.1 IssueApplicationService — workflow.plan() 호출 매핑 책임

`POST /api/v1/issues/{key}/transition` 처리 흐름. workflow ADR (`workflow-bc-cross-bc-port`) §결정 준수.

```kotlin
@Service
class IssueApplicationService(
    private val issueRepo: IssueRepository,
    private val workflowPort: WorkflowTransitionPort,
    private val permissionResolver: IssuePermissionResolver,
    private val eventPublisher: IssueEventPublisher,
) {
    @Transactional
    fun transition(actor: ActorId, key: IssueKey, transitionKey: String, version: Long): IssueResponse {
        // 1. 이슈 조회 + 권한 가드
        val issue = issueRepo.findByKeyForUpdate(key)
            ?: throw IssueNotFoundException(key)
        if (!permissionResolver.hasPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(key.value))) {
            throw IssueAccessDeniedException(actor, IssuePermission.TRANSITION, IssueScope.Issue(key.value))
        }
        if (issue.version != version) {
            throw IssueVersionConflictException(key, issue.version)
        }

        // 2. workflow.plan() 호출 — TransitionRequest 매핑
        val req = TransitionRequest(
            issueKey = key.value,
            fromStateKey = issue.currentStateKey,
            transitionKey = transitionKey,
            actorId = actor.value,
            version = version,
            issueFields = mapOf("summary" to issue.summary)  // 현재 본 PR 필드만, FR-IS-02~07 확장
        )
        val plan: TransitionPlan = workflowPort.plan(req)  // Propagation.MANDATORY → 같은 트랜잭션

        // 3. 이슈 영속화 (issue-tracking BC 책임)
        val updated = issueRepo.applyTransition(key, plan.toState.stateKey, plan.fieldChanges, version)

        // 4. pgmq outbox enqueue (같은 트랜잭션)
        plan.emitEvents.forEach { event ->
            eventPublisher.enqueue(event)
        }

        return IssueResponse.from(updated)
    }
}
```

### 7.2 통합 테스트 인프라 — Testcontainers + workflow Bean 격리

PR #8 learning ("Testcontainers 클래스 라이프사이클 함정") + PR #10 learning ("wave 당 5~7 task max") 적용.

| 항목 | 규칙 |
|---|---|
| Postgres container | `IssueTestcontainersBase` abstract class. **singleton pattern (`.apply { start() }`)** — `@Container` 어노테이션 미사용 |
| workflow Bean wiring | `@Import(WorkflowAutoConfiguration::class)` 또는 `@SpringBootTest` 의 component scan 으로 자동 — issue-tracking 통합 테스트는 workflow stub (`AlwaysAllowPermissionResolver`) Bean 도 함께 wire 됨 |
| ApplicationContext cache | 같은 cache key (`@SpringBootTest @ActiveProfiles("test")`) 면 issue-tracking 통합 테스트끼리 공유 OK. workflow 통합 테스트와는 분리 (`@ActiveProfiles` 차별) — cache pollution 회피 |
| 가짜 workflow 시나리오 | `MockK` 로 `WorkflowTransitionPort` mock — 단위 테스트는 mock, 통합 테스트는 실제 workflow Bean (1 모듈 안에서 가능) |

## 8. 측정 가능한 완료 기준

다음이 **전부** 충족되어야 `/bts-codereview` 통과.

- [ ] §3 NFR 표의 모든 임계값 달성 (k6 자동화)
- [ ] §6 EC-1~EC-7 통합 테스트 7건 모두 통과 (Testcontainers)
- [ ] ArchUnit 룰 0 위반 (BC 격리, @Transactional Bean, port-adapter 일관성)
- [ ] 단위 테스트 line coverage 80%+, branch 70%+
- [ ] ktlint / detekt 0 issue
- [ ] OpenAPI 자동 생성 — `apps/web/src/api/openapi-issue.d.ts` 갱신 + frontend client zod schema 동기화
- [ ] Playwright E2E 시나리오 S1~S6 통과 (D6 wave 완료 후 D7 에서)
- [ ] PR diff size — D5 종료 시점 < 5000 lines (D6/D7 추가 후 PR #10/#11 수준 monster PR 예상)

## 9. 본 PR 스코프 외 (명시적 제외)

다음은 본 PR scope **아님**. 후속 PR.

- 이슈 타입 (Epic/Story/Task/Subtask/Bug + 커스텀) — FR-IS-02
- 담당자 (Assignee) — FR-IS-03
- 본문 (Markdown body) + 우선순위/라벨 — FR-IS-04
- Resolution (Fixed/Won't Fix) — FR-IS-07
- 첨부 / 멘션 / Watcher — FR-AC, FR-MN, FR-WT
- 링크 / 히스토리 / 템플릿 — FR-LK, FR-HS, FR-TM
- 이슈 이동 (옛 키 redirect) — FR-MV-01 (스키마만 본 PR)
- 일괄 편집 / 클론 / PDF — FR-IS-05, FR-IS-06, FR-IS-08
- 프로젝트 관리 API (생성/수정/삭제) — Project Management 후속 PR (본 PR 은 dev seed 1건)
- 실제 PERMISSION 가드 평가 — FR-AU-12 (stub 만 본 PR)

## 10. 참고

- 도메인 정리. `docs/plans/2026-05-22-issue-tracking-bc-fr-is-01-crud.md` §도메인 정리
- ADR. `docs/adr/2026-05-22-issue-key-prefix-policy.md`, `docs/adr/2026-05-22-issue-permission-resolver-port.md`
- 호출 대상 ADR. `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md`
- 절대 규칙. `DEVELOPMENT.md §1`
- 데이터 규칙. `DATA.md`

## Brainstorming Check

✅ 통과 (self-conducted sanity check, 4건 gap 발견 후 보강 — 2026-05-22).

**1회차 (직접 spec 작성)**. office-hours 우회 (Maxi 의 "게이트 1까지 자동 진행" 의도 반영). 도메인 정리 §ADR-1/ADR-2 + workflow-bc-cross-bc-port ADR + plan/product/issue-tracking.md §2.1.1 의 7 엣지 케이스 기반으로 직접 초안 작성.

**2회차 (self sanity check)**. 본 spec 의 핵심 책임 영역에서 4건 gap 발견 → 본 문서 인라인 보강:
- EC-1 race condition 해결책 구체화 — `pg_advisory_xact_lock(hash('project:' || project_id))` 명시 (PR #10 패턴 일관)
- 표준 에러 응답 형식 — `§6.1 RFC 7807 ProblemDetail` 신설 + errorCode 표
- `IssueApplicationService.transition()` workflow.plan() 호출 매핑 — `§7.1` 신설, Kotlin 의사코드로 명시
- 통합 테스트 인프라 — `§7.2` 신설, Testcontainers singleton + workflow Bean 격리 규칙 명시

추가 검토 사항 (gap 아님, 향후 plan/impl 단계에서 다룸):
- jakarta.validation `@Valid` 사용 — DEVELOPMENT.md 기본값으로 충분
- jOOQ 코드젠 — `V007__issues_initial.sql` 추가 후 `./gradlew generateJooq` (CI 자동, plan task 명시)
- OpenAPI 도구 — `springdoc-openapi` (PR #8 자산 활용)

