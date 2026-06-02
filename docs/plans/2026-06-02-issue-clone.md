# FR-IS-06 — 이슈 클론 (옵션: 첨부/Watcher/댓글 포함)

> slug: issue/clone
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-02

## Brief

FR-IS-06 이슈 클론. `POST /api/v1/issues/{key}/clone`. 옵션으로 첨부/Watcher/댓글 포함 여부 선택.
무엇이 복사되고 무엇이 새로 시작되는지 명세 필요 (CloneOptions).
선행: §2.1.1(FR-IS-01 CRUD), §4.2.1, §4.3.1.

## 도메인 정리

- **BC**: issue-tracking (단일 BC, 격리 유지)
- **영향 엔티티**: Issue 애그리거트 **재사용**. 신규 엔티티/용어 없음 (`CloneOptions`는 application 계층 입력 DTO이지 도메인 엔티티 아님).
- **복사 대상 (원본에서 그대로 carry-over)**: `summary`, `description`, `typeId`, `priority`, `labels`, `environment`, `impact`, `assigneeId`(옵션).
- **새로 시작 (복사 안 함)**:
  - `id` — 새 UUID
  - `key` — `incrementKeySequence(projectKey)`로 새 키 발급 (원본 key는 영구 불변)
  - `reporterId` — 클론을 수행한 actor
  - `currentStateKey` — 워크플로우 초기 상태 재결정 (원본 상태 복사 안 함)
  - `version` = 1, `createdAt`/`updatedAt` = now
- **미구현이라 복사 불가**: 첨부(Attachment) / Watcher / 댓글(IssueComment). 도메인·테이블·리포지토리 모두 미존재.
  → FR 제목의 "옵션: 첨부/Watcher/댓글 포함"은 **해당 하위 시스템 도입 후로 이연**. 지금 placeholder 옵션을 만들지 않음 (CLAUDE.md PoC 금지 / 추측 구현 금지).
- **CloneOptions (현 범위에서 의미 있는 옵션)**:
  - `includeAssignee: Boolean` (기본 true) — 담당자 carry-over 여부. false면 미할당으로 클론.
  - (선택) `summaryOverride` — 클론본 제목 덮어쓰기. 미지정 시 원본 summary 그대로.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: [docs/decisions/2026-06-02-issue-clone-semantics.md](../decisions/2026-06-02-issue-clone-semantics.md) (생성됨)
- **기존 코드 재사용 단서**: `Issue.create(...)` 팩토리가 모든 복사 필드 파라미터 지원, `repo.insert()`의 `toInsertRecord()`가 전 컬럼 영속. createIssue 흐름(권한→키발급→projectId조회→typeId해소→startState→create→insert→publish)을 그대로 모사하되 필드를 원본에서 채움.

## 스펙

전체 스펙. [docs/specs/2026-06-02-issue-clone.md](../specs/2026-06-02-issue-clone.md)

핵심 시나리오 요약.
- `POST /api/v1/issues/{key}/clone` — 원본 필드(summary/description/type/priority/labels/env/impact/assignee) 복사한 새 이슈를 같은 프로젝트에 생성.
- 새로 시작: id/key/reporter/상태/version/시각. CloneOptions = includeAssignee(기본 true) + summaryOverride(선택).
- 신규 테이블 없음. 기존 Issue.create + insert + IssueCreated 이벤트 재사용. 첨부/Watcher/댓글은 이연.
- 범위: 백엔드 API(D1~D5). 프론트(D6)·E2E(D7) 후속.

## Brainstorming Check

✅ 통과 (1회, BLOCKER 없음). EC-8 비활성타입=원본 보존, 이벤트=IssueCreated 재사용, 권한=VIEW+CREATE.

## Plan

### Task 1. application 계층 — CloneIssueRequest DTO + cloneIssue 서비스 메서드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceCloneTest.kt`]
- depends-on: []

**RED**:
- 파일: `IssueApplicationServiceCloneTest.kt` (mockk 기반, 기존 IssueApplicationServiceCreateTest 패턴 따름)
- 테스트:
  - `clone copies summary,description,typeId,priority,labels,environment,impact from source`
  - `clone with includeAssignee=true copies assigneeId`
  - `clone with includeAssignee=false sets assigneeId=null`
  - `clone with summaryOverride uses overridden summary`
  - `clone with blank summaryOverride falls back to source summary` (EC-3)
  - `clone resets key(new seq), reporter(actor), currentStateKey(start), version=1`
  - `clone publishes IssueCreated event`
  - `clone of missing source throws IssueNotFoundException` (404)
  - `clone copies source typeId as-is without active re-validation` (EC-8)
- 실패 메시지 (예상): `cloneIssue` 메서드 없음 / `CloneIssueRequest` 없음

**GREEN**:
- `IssueApplicationRequests.kt` — application DTO 추가:
  ```kotlin
  data class CloneIssueRequest(
      val includeAssignee: Boolean = true,
      val summaryOverride: String? = null,
  )
  ```
- `IssueApplicationService.kt` — `cloneIssue(actor, sourceKey, request): Issue`:
  1. 원본 조회 — `repo.findByKey(sourceKey)` 없으면 `IssueNotFoundException`
  2. VIEW 권한 — `assertPermission(actor, VIEW, IssueScope.Issue(sourceKey))` (기존 findByKey 권한과 동일 패턴)
  3. CREATE 권한 — `assertPermission(actor, CREATE, IssueScope.Project(projectKey))`
  4. projectKey 도출 (원본 key prefix). `repo.incrementKeySequence(projectKey)` → 새 IssueKey
  5. startState = `workflowKeyResolver.resolveStart(...)` (createIssue와 동일 try/catch BC 격리)
  6. summary = `request.summaryOverride?.takeIf { it.isNotBlank() } ?: source.summary`
  7. assignee = `if (request.includeAssignee) source.assigneeId else null`
  8. `Issue.create(...)` — 복사 필드 + reporterId=actor + currentStateKey=start + typeId=source.typeId (재검증 없음)
  9. `repo.insert(issue)` → `eventPublisher.publish(IssueCreated(...))`
  10. log + return saved

**REFACTOR**:
- summary/assignee 결정 로직을 private 헬퍼로 추출. KDoc 작성 (복사/리셋 경계 명시).

**검증**: `./gradlew :modules:issue-tracking:test (backend/ 디렉토리에서 ./gradlew) --tests '*IssueApplicationServiceCloneTest'`

### Task 2. 통합 테스트 — 실제 DB 클론 INSERT + key_sequence + 이벤트 enqueue

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueCloneIntegrationTest.kt`]
- depends-on: [1]

**RED**:
- 파일: `IssueCloneIntegrationTest.kt` (Testcontainers, 기존 IssueTransitionRuntimeIntegrationTest / IssueRepositoryIntegrationTest 베이스 재사용)
- 테스트:
  - `clone inserts new issue row in same project with new key and version=1`
  - `clone increments project key_sequence`
  - `clone copies all carry-over fields persisted to DB` (재조회 후 필드 비교)
  - `clone with includeAssignee=false persists null assignee`
  - `clone enqueues IssueCreated into pgmq outbox in same transaction`
- 실패 메시지 (예상): 클론 결과 row 미존재 / 필드 불일치

**GREEN**:
- Task 1의 `cloneIssue` 가 이미 구현됨. 통합 테스트는 실 DB 경로 검증만. 누락 동작 발견 시 service 보완.

**REFACTOR**:
- 테스트 fixture(원본 이슈 seed) 헬퍼 정리.

**검증**: `./gradlew :modules:issue-tracking:test (backend/ 디렉토리에서 ./gradlew) --tests '*IssueCloneIntegrationTest'`

### Task 3. web 계층 — POST /api/v1/issues/{key}/clone 엔드포인트 + 컨트롤러 테스트

**메타**.
- agent: `backend-engineer` (권한 가드 부분은 security-engineer 리뷰 대상)
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/CloneIssueRequest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerCloneTest.kt`]
- depends-on: [1]

**RED**:
- 파일: `IssueControllerCloneTest.kt` (@WebMvcTest, 기존 IssueController 테스트 패턴)
- 테스트:
  - `POST clone returns 201 + Location + DataResponse with descriptionHtml` (S1)
  - `POST clone with body {} uses defaults` (FR-C6)
  - `POST clone with includeAssignee=false reflected` (S2)
  - `POST clone with summaryOverride reflected` (S3)
  - `POST clone of missing source returns 404 ISSUE_NOT_FOUND` (S5)
  - `POST clone without permission returns 403 ACCESS_DENIED` (S6)
  - `POST clone with summaryOverride over 255 chars returns 400 VALIDATION_FAILED` (EC-2)
- 실패 메시지 (예상): clone 핸들러 없음 → 404/405

**GREEN**:
- web DTO `CloneIssueRequest.kt` (rest 패키지):
  ```kotlin
  data class CloneIssueRequest(
      val includeAssignee: Boolean = true,
      @field:Size(max = 255) val summaryOverride: String? = null,
  )
  ```
- `IssueController.kt` — delete 메서드 다음에 추가:
  ```kotlin
  @PostMapping("/{key}/clone")
  fun clone(@PathVariable key: String, @Valid @RequestBody(required = false) request: CloneIssueRequest?): ResponseEntity<DataResponse<IssueResponse>>
  ```
  - body null 시 기본값 `CloneIssueRequest()` 사용
  - actor = SYSTEM_ACTOR_UUID (기존 임시 fallback 동일)
  - `service.cloneIssue(...)` → `service.findByKey(actor, cloned.key)` 재조회(descriptionHtml) → 201 + Location

**REFACTOR**:
- web→app DTO 매핑을 별도 함수로. KDoc.

**검증**: `./gradlew :modules:issue-tracking:test (backend/ 디렉토리에서 ./gradlew) --tests '*IssueControllerCloneTest'`

### Task 4. 문서 — fr-index D 항목 체크 + 첨부/Watcher/댓글 이연 명시

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/issue-tracking.md`, `docs/plan/fr-index.md`]
- depends-on: [3]

**RED**: (문서 task — 코드 테스트 없음. 전체 빌드 green이 검증.)

**GREEN**:
- `docs/plan/product/issue-tracking.md` §2.3.1 — D1·D2·D3·D4·D5 `[x]` 체크 + 완료 메모(첨부/Watcher/댓글 이연, 같은 프로젝트 한정, ADR 링크). D6·D7은 `[ ]` 유지(후속).
- `fr-index.md` FR-IS-06 행 — 필요 시 진척 메모.

**REFACTOR**: 없음.

**검증**: `./gradlew :modules:issue-tracking:test (backend/ 디렉토리에서 ./gradlew) ktlintCheck detekt` 전체 green.

## Plan 메타

- task 수: 4
- 예상 시간: task × 4분 ≈ 16분 (직렬). wave 적용 시 ≈ 10분 (예상 wave 2개: [T1] → [T2, T3] → [T4]).
- TDD 강제: yes (Task 1·3 RED→GREEN→REFACTOR. Task 2는 통합검증, Task 4는 문서)
- 병렬 dispatch: T2·T3은 T1 완료 후 파일 비겹침이라 동일 wave 가능. T4는 T3 후.
- 추가 검증: ktlint, detekt, Testcontainers 통합.

## 리뷰 결과

### plan-eng-review (2026-06-02)

- ✅ **트랜잭션 경계** — `cloneIssue`는 클래스 레벨 `@Transactional` 적용 서비스의 메서드. insert + IssueCreated 발행(Propagation.MANDATORY)이 한 트랜잭션에 묶임 (createIssue와 동일). DEVELOPMENT.md §1.4 충족.
- ✅ **BC 격리** — 워크플로우 초기상태는 `WorkflowKeyResolver` 포트로만 조회, project-workflow 직접 import 없음. createIssue의 try/catch(클래스명 비교) BC 격리 패턴 재사용.
- ✅ **권한 API 정합** — 코드 확인: `IssuePermission.VIEW/CREATE`, `IssueScope.Issue(key.value)/Project(projectKey)`, `assertPermission(actor, perm, scope)` 시그니처 plan과 일치. 클론은 VIEW(원본 Issue) + CREATE(대상 Project) 둘 다 검사.
- ✅ **키 동시성** — `incrementKeySequence`의 `pg_advisory_xact_lock` 재사용으로 동시 클론 key 충돌 없음 (EC-7).
- ✅ **gradle 경로 교정** — 모듈 경로 `:modules:issue-tracking` (backend/gradlew). plan 검증 명령 수정 완료.
- ⚠️ **주의 (non-blocking)** — EC-8(비활성 타입 복사): `Issue.create`는 typeId 활성성을 재검증하지 않으므로 원본 typeId 그대로 통과. 의도된 동작이나 통합 테스트에서 명시 커버 권장 (Task 2에 케이스 추가 고려).
- ⚠️ **주의 (non-blocking)** — S6(403) 컨트롤러 테스트: prod 권한 resolver가 AlwaysAllow이므로, 403 검증은 service가 `IssueAccessDeniedException`을 throw하도록 목킹해 핸들러 매핑만 확인 (FR-IS-01 컨트롤러 테스트와 동일 전략).
- **BLOCKER: 없음**

### plan-devex-review (2026-06-02)

- ✅ **API 일관성** — `POST /api/v1/issues/{key}/clone`는 기존 `/{key}/transition` 하위 리소스 액션 패턴 준수. 응답 `201 + Location + DataResponse<IssueResponse>`로 생성 API와 동일.
- ✅ **하위 호환** — 순수 신규 엔드포인트. 기존 계약 변경 없음. 신규 errorCode 추가 없음(기존 `IssueErrorCodes` 재사용).
- ✅ **DX** — body 선택적(`@RequestBody(required = false)`), `{}`/일부 필드/생략 모두 허용. 옵션 기본값(includeAssignee=true)이 "직관적 클론"과 일치.
- ⚠️ **주의 (non-blocking)** — 첨부/Watcher/댓글 이연 사실을 API 응답이 아니라 문서(fr-index, ADR)로만 노출. 후속 기능 도입 시 옵션 확장 경로 ADR §3에 명시됨. OK.
- **BLOCKER: 없음**
