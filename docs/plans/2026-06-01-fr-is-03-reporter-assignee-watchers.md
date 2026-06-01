# FR-IS-03 — 담당자 (Reporter 1 / Assignee 1 / Watchers N)

> slug: fr-is-03-reporter-assignee-watchers
> type: backend (풀스택 D1~D7)
> agent: backend-engineer (+ db / frontend / qa)
> BC: issue-tracking
> 생성: 2026-06-01

## Brief

이슈에 담당자 역할을 분리한다. Reporter(보고자) 1명, Assignee(담당자) 1명, Watchers(관찰자) N명.
원문: FR-IS-03 담당자 — 이슈에 보고자/담당자/관찰자 역할 분리. issue-tracking BC. D1~D7 풀스택.
선행: §2.1.1(이슈 생성), §4.3.1(Watcher). Plan slug: issue/assignees.

classify: type=backend, agent=backend-engineer, slug=fr-is-03-reporter-assignee-watchers, BC=issue-tracking

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: Issue (assigneeId 신규 필드 추가). Reporter 는 기존 `Issue.reporterId: ActorId` 재사용.
- **신규 cross-BC 포트**: `UserLookupPort` (assignee 사용자 존재 검증). stub = `AlwaysExistsUserLookup` (`@Profile("!prod")`). 실 사용자 디렉터리 연동 시 identity-access adapter 로 교체.
- **데이터 모델**: `issues.assignee_id UUID NULL` 추가 (reporter_id 와 동일하게 FK 미적용, BC 격리). `init_codegen.sql` 미러 필수(jOOQ 상수 생성, V005/V006 선례).
- **사용자 ID 타입**: UUID (SDD 05 의 BIGINT 표기는 구버전 — 코드가 정본).

### 범위 결정 (Maxi)

- **Watcher 분리** — FR-IS-03 의 "Watchers N" 은 별도 FR-WT-01(§4.3.1)과 `issue_watchers` 테이블·자동 Watcher 를 공유. 이번 PR 은 **Reporter/Assignee 만** 다루고 Watcher 전체(테이블·POST/DELETE·자동등록)는 FR-WT-01 로 분리. FR-IS-03 의 D3/D6 표기상 `issue_watchers` 는 FR-WT-01 로 이관.
- **Assignee 사용자 검증** — 검증 없이 저장(trust) 대신 **UserLookupPort 신규**(cross-BC port + stub). reporter 는 본인이라 검증 불요, assignee 는 타인이라 실재 검증 필요. 인증 배선 미완(SYSTEM_ACTOR_UUID 스텁)이라 stub 은 AlwaysExists, 실 검증은 후속.

### 신규 용어 (glossary 추가 대기 — Maxi 승인 필요)

| 용어 | 정의 |
|---|---|
| 보고자 | Reporter. 이슈를 생성한 사용자(1명). 생성 후 불변(또는 재지정 규칙은 spec 결정). |
| 담당자 | Assignee. 이슈 해결을 책임지는 사용자(0~1명). 미할당(null) 허용. |

(워처(Watcher)는 이미 glossary 등록됨 — 이번 PR 범위 밖.)

### 기존 결정 충돌

- 없음. cross-BC 포트는 `IssuePermissionResolver`(2026-05-22) / `IssueTypeUsagePort`(2026-05-29) 와 동일 패턴 확장.

### 관련 ADR

- [docs/adr/2026-06-01-issue-assignee-user-lookup-port.md](../adr/2026-06-01-issue-assignee-user-lookup-port.md) (생성됨) — UserLookupPort 도입
- docs/adr/2026-05-22-issue-permission-resolver-port.md — port-adapter 원형

## 스펙

전체 스펙. [docs/specs/2026-06-01-fr-is-03-reporter-assignee-watchers.md](../specs/2026-06-01-fr-is-03-reporter-assignee-watchers.md)

핵심 요약.
- Reporter 는 이미 구현됨(생성 시 고정). **이번 PR 신규 = Assignee**(0~1명, 미할당 허용).
- `PATCH /api/v1/issues/{key}/assignee` `{assigneeId: UUID|null, expectedVersion}` — 전용 엔드포인트로 merge-patch 3-state 모호성 회피.
- assignee 지정 시 `UserLookupPort.exists`(shared-kernel 포트, identity-access 구현)로 실재 검증. 없으면 422 `ASSIGNEE_NOT_FOUND`.
- 담당자 셀렉터 재료로 identity-access `GET /api/v1/users`(활성 목록/검색) 추가 — 2-BC PR(정당한 cross-BC 예외).
- `IssueResponse.assigneeId` 노출 + `issues.assignee_id UUID NULL`(V007 + init_codegen 미러).

## Brainstorming Check

✅ 통과 (1회 iteration). 핵심 gap "담당자 셀렉터 사용자 목록 출처 부재" → Maxi 결정으로 identity-access `GET /api/v1/users` 추가 보강. 부수 gap 2건(프론트 invalidate-only, Zod mock 파급) spec 반영.

## Plan

> **PR 범위 = 백엔드 D1~D5만**(Maxi 결정). 프론트(D6)·E2E(D7)는 별 워크플로우 후속.
> 모든 사용자 ID = UUID. users 테이블에 active/deleted 컬럼 없음 → "사용자 실재" = 행 존재.
> **같은 Gradle 모듈 test 컴파일 직렬화 주의**(메모리 `bts-plan-wave-gradle-module-compile`): issue-tracking task 들(T2/T5/T6/T7/T8)은 파일이 안 겹쳐도 RED 병렬 dispatch 시 같은 test source set 공유로 서로 컴파일 차단 가능. identity-access task(T3/T4)는 별 모듈이라 issue-tracking 과 병렬 가능.

### Task 1. V007 마이그레이션 — issues.assignee_id + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V007__issue_assignee.sql`, `backend/modules/issue-tracking/src/main/resources/db/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/db/V007MigrationIntegrationTest.kt`]
- depends-on: []

**RED**: Testcontainers 통합 테스트 — 마이그레이션 후 `issues.assignee_id` 컬럼이 UUID NULL 로 존재하는지 information_schema 조회. 컬럼 없음 → fail.

**GREEN**:
- `V007__issue_assignee.sql` — `ALTER TABLE issues ADD COLUMN assignee_id UUID NULL;` + `COMMENT ON COLUMN issues.assignee_id IS 'identity-access BC users.id 대응. BC 격리로 FK 미적용 — ApplicationService 가 UserLookupPort 로 존재 guard. null=미할당.';`
- **`init_codegen.sql` 에 동일 컬럼 미러**(jOOQ ISSUES.ASSIGNEE_ID 상수 생성, V005/V006 선례 — 메모리 `jooq-init_codegen-미러`. 누락 시 T6 repository 컴파일 불가).

**REFACTOR**: 없음(스키마 변경).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*V007MigrationIntegrationTest" --rerun-tasks`

### Task 2. Issue 도메인 — assigneeId 필드 + assignTo/unassign

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/Issue.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/domain/IssueTest.kt`]
- depends-on: []

**RED**: `IssueTest` — (a) `Issue.create(...)` 의 assigneeId 기본값 null, (b) `issue.assignTo(ActorId)` → assigneeId 설정 + version 동일(도메인은 version 안 올림, 영속 계층 책임), (c) `issue.unassign()` → assigneeId=null. 메서드 없음 → 컴파일 fail.

**GREEN**:
- `Issue` data class 에 `val assigneeId: ActorId? = null` 추가(생성자 끝, 기본 null).
- `Issue.create` 시그니처에 `assigneeId: ActorId? = null` 추가(기본 null).
- `fun assignTo(assignee: ActorId): Issue = copy(assigneeId = assignee)`, `fun unassign(): Issue = copy(assigneeId = null)`.

**REFACTOR**: KDoc 에 assigneeId(0~1명, null=미할당) + 두 메서드 문서화.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueTest" --rerun-tasks`

### Task 3. shared-kernel UserLookupPort + identity-access 어댑터

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/user/UserLookupPort.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/user/UserLookupAdapter.kt`, `backend/modules/identity-access/build.gradle.kts`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/user/UserLookupAdapterIntegrationTest.kt`]
- depends-on: []

**RED**: identity-access Testcontainers 통합 테스트 — 시드 사용자 UUID 로 `adapter.exists(uuid)` = true, 랜덤 UUID = false. 어댑터 없음 → fail.

**GREEN**:
- shared-kernel `interface UserLookupPort { fun exists(userId: UUID): Boolean }` (WorkflowTransitionPort 선례, UUID 사용 — issue-tracking VO 비의존).
- identity-access build.gradle 에 `implementation(project(":modules:shared-kernel"))` 추가.
- `@Component class UserLookupAdapter(...) : UserLookupPort` — `users` 테이블 `SELECT EXISTS(... WHERE id=?)` (jOOQ 또는 JdbcTemplate, identity-access 기존 조회 패턴 따름).

**REFACTOR**: KDoc — 실 검증 어댑터, ADR 2026-06-01 링크.

**검증**: `./gradlew :modules:identity-access:test --tests "*UserLookupAdapterIntegrationTest" --rerun-tasks`

### Task 4. GET /api/v1/users — 활성 사용자 목록/검색

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/UsersController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/dto/UserSummaryResponse.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/UsersControllerIntegrationTest.kt`]
- depends-on: []

**RED**: 통합 테스트 — (a) 인증 사용자가 `GET /api/v1/users` 호출 → 200 + 시드 사용자 목록(id/username/displayName/email), (b) `?query=al` → username/displayName 부분일치 필터, (c) 미인증 → 401. 엔드포인트 없음 → fail.

**GREEN**:
- `UserSummaryResponse(id: UUID, username: String, displayName: String?, email: String?)`.
- `@GetMapping("/api/v1/users")` — 선택적 `query` 파라미터(username/display_name ILIKE), 결과 상한(예: 50, typeahead 용도). 인증 필수(기존 SecurityFilterChain 가드).
- 사용자 조회 read 서비스/리포지토리(기존 identity-access 사용자 조회 패턴 재사용).

**REFACTOR**: KDoc — PII 노출 엔드포인트, 인증 가드 명시. 페이지네이션은 후속(상한+검색으로 충분).

**검증**: `./gradlew :modules:identity-access:test --tests "*UsersControllerIntegrationTest" --rerun-tasks`

### Task 5. AssigneeNotFoundException + ASSIGNEE_NOT_FOUND (422)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueExceptions.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandlerTest.kt`]
- depends-on: []

**RED**: 핸들러 테스트 — `AssigneeNotFoundException` → 422 ProblemDetail + errorCode `ASSIGNEE_NOT_FOUND`. 예외/핸들러 없음 → fail.

**GREEN**:
- `class AssigneeNotFoundException(assigneeId: UUID) : IssueDomainException(...)`.
- `IssueErrorCodes` 에 `ASSIGNEE_NOT_FOUND` 상수(handler 동일 패키지).
- `@ExceptionHandler(AssigneeNotFoundException::class)` → `HttpStatus.UNPROCESSABLE_ENTITY`(422, WORKFLOW_NOT_CONFIGURED 선례).

**REFACTOR**: 핸들러 KDoc 목록에 422 ASSIGNEE_NOT_FOUND 추가. `@Suppress("TooManyFunctions")` 유지.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueExceptionHandlerTest" --rerun-tasks`

### Task 6. 리포지토리 updateAssignee(OCC) + IssueResponse.assigneeId

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryIntegrationTest.kt`]
- depends-on: [1]

**RED**: 리포지토리 Testcontainers 통합 테스트 — (a) `updateAssignee(key, assigneeId, expectedVersion)` 가 assignee_id 갱신 + version+1, (b) expectedVersion 불일치 → 0 row(서비스가 충돌 판정), (c) 조회 시 assignee_id 매핑(toIssue). jOOQ ASSIGNEE_ID 상수는 T1 init_codegen 으로 생성됨.

**GREEN**:
- `IssueRepository.updateAssignee(key, assigneeId: UUID?, expectedVersion): Int` — `UPDATE issues SET assignee_id=?, version=version+1, updated_at=now() WHERE key=? AND version=? AND deleted_at IS NULL` (updateFields OCC 패턴 미러).
- toIssue row mapper 에 `assigneeId = record.assigneeId?.let { ActorId(it) }`.
- `IssueResponse.assigneeId: UUID? = null` + `from(...)` 에 `assigneeId = issue.assigneeId?.value`.

**REFACTOR**: KDoc — assignee_id OCC UPDATE.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueRepositoryIntegrationTest" --rerun-tasks`

### Task 7. IssueApplicationService.changeAssignee 유스케이스

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTest.kt`]
- depends-on: [2, 3, 5, 6]

**RED**: 서비스 단위 테스트(MockK) — (a) assignee non-null → `userLookupPort.exists`=true → 도메인 `assignTo` → repo.updateAssignee, (b) exists=false → `AssigneeNotFoundException`(repo 미호출), (c) assignee=null → exists 미호출 + unassign, (d) repo 0 row → `IssueVersionConflictException`.

**GREEN**:
- `AppChangeAssigneeRequest(assigneeId: UUID?, expectedVersion: Long)`.
- `fun changeAssignee(actor: ActorId, key: IssueKey, request): IssueResponse` — 권한 `IssuePermissionResolver.UPDATE` 가드 → 이슈 조회(미존재 404) → assignee non-null 이면 `userLookupPort.exists` (false→AssigneeNotFound) → 도메인 `assignTo`/`unassign`(도메인 우회 금지, 메모리 `patch-merge-도메인-우회`) → `repo.updateAssignee` (0 row→VersionConflict) → 재조회 응답.
- `IssueApplicationService` 생성자에 `userLookupPort: UserLookupPort` 주입.

**REFACTOR**: KDoc — assignee 변경 유스케이스 흐름.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueApplicationServiceTest" --rerun-tasks`

### Task 8. PATCH /api/v1/issues/{key}/assignee 엔드포인트

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/ChangeAssigneeRequest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerIntegrationTest.kt`]
- depends-on: [7]

**RED**: 컨트롤러 Testcontainers 통합 테스트 — 스펙 S1~S6: S1 지정 200, S2 해제(null) 200, S3 변경 200, S4 미존재 사용자 422 ASSIGNEE_NOT_FOUND, S5 OCC 409 VERSION_CONFLICT, S6 미존재 이슈 404. expectedVersion 누락 400.

**GREEN**:
- `ChangeAssigneeRequest(assigneeId: UUID?, @field:NotNull expectedVersion: Long)`.
- `@PatchMapping("/{key}/assignee")` → `service.changeAssignee(actor, IssueKey.of(key), AppChangeAssigneeRequest(...))` → 200 + IssueResponse. actor 는 기존 SYSTEM_ACTOR_UUID 임시 패턴 따름.

**REFACTOR**: KDoc — 전용 서브리소스 엔드포인트(merge-patch 3-state 회피 사유).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueControllerIntegrationTest" --rerun-tasks`

## Plan 메타

- task 수: 8 (백엔드 D1~D5)
- 모듈 분포: issue-tracking(T1·T2·T5·T6·T7·T8) + shared-kernel/identity-access(T3·T4)
- depends-on 그래프: T1→T6, {T2,T3,T5,T6}→T7→T8. T3→T4 무관(둘 다 독립)이나 같은 identity-access 모듈이라 RED 직렬 가능.
- 예상 wave: ~4 (W1: T1·T2·T3·T4·T5 중 모듈/의존 허용분 → 현실적으로 issue-tracking 직렬 고려 W1=T1,T3,T4 / W2=T2,T5 / W3=T6 / W4=T7 / W5=T8 수준). bts-impl 이 최종 계산.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 추가 검증: ktlint + detekt(--rerun-tasks, 캐시 false-green 회피) + 전체 모듈 test
- cross-BC 가드: ArchUnit — issue-tracking 이 `com.atlas.bts.identity.*` 직접 import 금지(UserLookupPort 는 shared-kernel 경유)

## 리뷰 결과 (← /bts-review-plan 채움)
