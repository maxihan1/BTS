# FR-IM-02 PR-B — Import 사용자 매핑 (전 작성자 필드)

> slug: fr-im-02-pr-b-user-mapping
> type: backend
> agent: backend-engineer
> 생성: 2026-07-04

## Brief

FR-IM-02 순차 에픽의 두 번째 PR(PR-B). PR-A(#230, 필드 매핑)에 이어 **사용자 매핑**을 추가.
소스 파일에서 발견되는 **전 작성자 식별자**(reporter/assignee + 댓글/worklog/첨부/changelog 작성자)를
BTS 사용자 UUID로 매핑. `import_user_mappings` 테이블 신규. analyze→validate→confirm 흐름을 사용자 매핑으로 확장.

- BC. search-export-import (`com.bts.search.imports`) — classify가 identity-access로 오분류, domain에서 정정.
- 선행. PR-A(#230, 필드 매핑 완료), FR-IM-01(CSV/JSON Import 전체 완료).
- 후속. PR-C(값 매핑 status/type/priority), 프론트 D6/D7(매핑 마법사).

## 도메인 정리

> 도메인·유비쿼터스 언어·아키텍처는 **승인된 PR-A ADR** `docs/decisions/2026-07-03-fr-im-02-import-mapping.md`가 이미 확정(게이트 1 통과, #230 머지). 여기서는 코드 대조로 검증한 현재 상태 + PR-B 주입점만 정리. 신규 도메인 결정 없음 → 별도 grill-with-docs 불필요(재-결정 회피).

### BC — 정정
- classify가 `identity-access`로 오분류(키워드 "사용자 매핑"). **실제 BC = `search-export-import`** (`com.bts.search.imports`). ADR·PR-A와 동일.

### 영향 모듈 — 3개 (import 에픽의 본질적 cross-BC 성격, FR-IM-01 선례)
1. **search-export-import** — 주 작업. `import_user_mappings` 신규 테이블, distinct 사용자 수집(2차 분석), 사용자 매핑 validate/confirm, 프로세서 매핑 로드.
2. **shared-kernel** (`com.bts.shared.issue.IssueImportCommand`) — 커맨드/VO에 userId 필드 **추가**(포트 시그니처 불변 — 커맨드 객체 설계 의도). `reporterUserId`/`assigneeUserId` + `ImportComment`/`ImportWorklog`/`ImportAttachment`/`ImportChangeGroup`에 `authorUserId`. 모두 nullable(기존 즉시경로=null→이메일 폴백, 하위호환).
3. **issue-tracking** (`IssueImportAdapter.resolveFields`) — userId **우선** 분기 주입. `reporterId = cmd.reporterUserId ?: (email 해석 ?: requester)`. `resolveAuthorId`/`isAuthorUnmatched`도 userId-aware. 어댑터 시그니처(포트) 불변.
- ⚠️ "한 PR = 한 BC" 예외 — ADR가 사전 승인(게이트 1 통과). import 기능은 태생적으로 import BC → shared-kernel 포트 → issue-tracking 어댑터로 걸침(FR-IM-01이 이 포트 도입). plan-review·게이트 1에서 이 경계 명시 재확인.

### 신규 데이터 모델
- `import_user_mappings(import_job_id UUID FK CASCADE, source_identifier TEXT, target_user_id UUID NULL)`, PK `(import_job_id, source_identifier)`. `source_identifier`=파일 내 이메일/이름 원문(정규화형), `target_user_id`=BTS 사용자 UUID(미해석 시 NULL→폴백). V607(PR-A가 V606 사용 → 다음 V607, **머지 직전 재확인**: migration-vnumber-concurrent-branch-collision).
- `import_value_mappings`(PR-C)는 이번 범위 아님.

### 전 작성자 필드 — 코드 대조 확정 (수집 대상)
`ParsedImportRow`/`IssueImportCommand` 상 작성자 식별자 담는 필드 전부.
- **reporter/assignee**: `reporterEmail`/`assigneeEmail` (CSV+JSON).
- **댓글**: `ParsedImportComment.authorEmail` (CSV 다중 `Comment` 컬럼 `date;author;body` + JSON `comment.comments[]`).
- **worklog**: `ParsedImportWorklog.authorEmail` (JSON 전용).
- **첨부**: `ParsedImportAttachment.authorEmail` (JSON 전용).
- **changelog**: `ParsedImportChangeGroup.authorEmail` (JSON 전용).
- CSV는 reporter/assignee/댓글작성자만, JSON은 6종 전부.

### 사용자 매핑 흐름 (ADR D5 순서 의존)
필드 매핑 확정 → (그 매핑 기준) **distinct 작성자 식별자 수집**(2차 분석, 필드매핑 파라미터) → `UserLookupPort.resolveByEmails` 자동해석 제안(이메일형만) → 사용자 확정/재지정 → `import_user_mappings` 저장 → 워커 프로세서가 매핑 1회 로드 → 행별 in-memory 해석 → 커맨드 userId 필드 세팅.

### 기존 해석 모델 (코드 확인)
- `IssueImportAdapter.resolveFields` 가 `resolveByEmails` **1회 배치** → `resolvedEmails: Map<lower(email),UUID>`. reporter/assignee/author 전부 이 맵 + `requesterUserId` 폴백(`resolveAuthorId`). PR-B는 userId 우선 분기만 추가 — 미매핑 사용자는 기존 이메일 폴백 경로 불변(회귀 0).
- `UserLookupPort.resolveByEmails` **이미 존재**(FR-IM-01 Task 4). PR-B는 신규 포트 메서드 불필요 → ~35개 fake 컴파일 파급 없음.

### 회귀 교훈 (PR-A 게이트2 + learnings, plan 반영 필수)
1. **insert 누수(F1)** — 신규 필드는 실 insert 경로 round-trip 테스트. `import_user_mappings.target_user_id`(NULL 포함) 실제 저장·재조회 검증. mock/raw-DSL seed로 갭 은폐 금지.
2. **정규화 비대칭(F2/AMBIGUOUS_SOURCE)** — distinct 수집 정규화 ↔ 매핑 키 저장 ↔ 프로세서 행별 해석 정규화가 **삼자 동일**해야(예: `lower(email)`). 어긋나면 조용한 오배정.
3. **search-export-import 백엔드 CI 없음** — `check`가 detektMain/detektTest 미포함. detekt/ktlint 명시 실행(`--rerun-tasks`). 신규 파일만 국소 `@Suppress("VarCouldBeVal")`, 타 파일 부채 미수정.
4. **생성자 주입 파급** — `ImportJobProcessor`/`ImportMappingService`에 `ImportUserMappingRepository` 주입 시 기존 mockk 테스트도 plan files 포함(plan-files-constructor-injection-existing-tests).

### 관련 ADR
- [docs/decisions/2026-07-03-fr-im-02-import-mapping.md](../decisions/2026-07-03-fr-im-02-import-mapping.md) (PR-A, 승인됨 — PR-B 아키텍처 사전 명세). 신규 ADR 불필요(기존 ADR의 후속 PR 범위).

## 스펙

전체 스펙. [docs/specs/2026-07-04-fr-im-02-pr-b-user-mapping.md](../specs/2026-07-04-fr-im-02-pr-b-user-mapping.md)

핵심 흐름 3줄.
- `POST /{jobId}/mapping/users`(신규) — 필드매핑 받아 원본 전량 스캔 → distinct 작성자 식별자(정규화형) + resolveByEmails 추천 반환.
- `POST /{jobId}/mapping`(confirm 확장) — userMappings 추가. targetUserId 실재검증 후 CAS 트랜잭션에 import_user_mappings 저장.
- 워커 프로세서가 매핑 1회 로드 → 행별 userId 해석 → 커맨드 `reporterUserId`/`authorUserId` 세팅 → 어댑터 userId 우선(미매핑=이메일 폴백 하위호환).

**Maxi 결정(2026-07-04)**. 사용자 매핑 전용 validate 엔드포인트 신설 안 함(confirm 폴딩, YAGNI). 신규 엔드포인트 collect 1개.

## Brainstorming Check

✅ 통과 (자기-비평 1회). 파서 스트리밍 메서드·UserLookupPort 메서드 실재 검증. 수정가능 갭 6건(포트 2회/JSON canonical/방어정규화/생성자주입 파급/검증 트랜잭션 밖/collect idempotent) plan 흡수. fork 1건(validate 엔드포인트) Maxi 결정 확정.

## Plan

> **3 모듈** — shared-kernel(커맨드 userId 필드) → search-export-import(주 작업) + issue-tracking(어댑터). Gradle 모듈 컴파일 순서상 shared-kernel이 소비자보다 먼저(bts-plan-wave-gradle-module-compile). 신규 V번호 V607(전 모듈 최대 V606 = PR-A, **머지 직전 재확인**).
> **F2 정규화 삼자 일치 = 공유 헬퍼 1개로 구조적 차단** — Task 3의 `UserMappingNormalizer`(정규화 + 작성자 식별자 추출)를 collect(Task 5)·프로세서(Task 6)가 **둘 다 재사용**. 규율이 아니라 설계로 drift 차단(fixture 옵션B 선례).

### Task 1. shared-kernel — 커맨드/VO userId 필드 추가

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueImportCommand.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueImportCommandTest.kt`]
- depends-on: []

**RED**. `IssueImportCommandTest` — (1) 커맨드에 `reporterUserId`/`assigneeUserId` 세팅·기본 null 확인. (2) `ImportComment`/`ImportWorklog`/`ImportAttachment`/`ImportChangeGroup`에 `authorUserId` 세팅·기본 null. → 필드 부재 컴파일 FAIL. (기존 파일 없으면 신규 생성.)

**GREEN**. `IssueImportCommand`에 `reporterUserId: UUID? = null`, `assigneeUserId: UUID? = null` 추가(기존 필드 뒤, 기본값 유지 — 기존 named/positional 호출 불변). 4개 VO에 `authorUserId: UUID? = null` 추가.

**REFACTOR**. KDoc §userId 우선 규칙 — "구현체(어댑터)는 [reporterUserId]가 있으면 이메일 해석보다 우선한다. null이면 기존 [reporterEmail] 폴백." 각 VO의 authorUserId KDoc 동일. **기존 exhaustive 호출부 grep**(`IssueImportCommand(` / `ImportComment(` 등) — 기본값이라 파급 0 확인(plan-files-constructor-injection 방어).

**검증**. `./gradlew :backend:modules:shared-kernel:test --tests '*IssueImportCommandTest*'` + shared-kernel 전체 컴파일(소비자 파급 확인).

### Task 2. V607 마이그레이션 + init_codegen 미러 + 스키마 테스트

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/search-export-import/src/main/resources/db/migration/search-export-import/V607__import_user_mappings.sql`, `backend/modules/search-export-import/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/SchemaMigrationImportTest.kt`]
- depends-on: []

**RED**. `SchemaMigrationImportTest`에 추가 — (1) `import_user_mappings` 테이블 존재 + 3컬럼(`import_job_id`/`source_identifier`/`target_user_id`) + PK `(import_job_id, source_identifier)`. (2) `import_job_id` FK `import_jobs(id)` ON DELETE CASCADE. (3) `target_user_id` **nullable + FK 없음**(cross-BC users 미참조 — favorites 선례 확인). → migration 부재 FAIL.

**GREEN**. V607 작성.
```sql
CREATE TABLE import_user_mappings (
  import_job_id     UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
  source_identifier TEXT NOT NULL,
  target_user_id    UUID NULL,
  PRIMARY KEY (import_job_id, source_identifier)
);
```
`init_codegen.sql`에 동일 DDL 미러(jOOQ `ImportUserMappingsRecord` 생성용).

**REFACTOR**. DDL L1 주석(역할 + target_user_id FK 미적용 사유=cross-BC). V606 `import_mappings` 미러 스타일 정합.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*SchemaMigrationImportTest*'`. **머지 직전 V607 번호 재확인**.

### Task 3. UserMappingNormalizer — 정규화 + 작성자 식별자 추출 (공유 순수 헬퍼)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/UserMappingNormalizer.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/UserMappingNormalizerTest.kt`]
- depends-on: []

**RED**. `UserMappingNormalizerTest` — (1) `normalize("  Bob@X.com ") == "bob@x.com"`(trim+lowercase). (2) `collectIdentifiers(row)`가 reporter/assignee/댓글·worklog·첨부·changelog authorEmail 전부 정규화 수집. (3) null/blank 식별자 제외. (4) 대소문자 변형(`bob@x`,`Bob@X`)이 1건으로 dedup. → 클래스 부재 FAIL.

**GREEN**. `object UserMappingNormalizer` — `fun normalize(raw: String): String = raw.trim().lowercase()`; `fun collectIdentifiers(row: ParsedImportRow): Set<String>`(6종 작성자 필드 mapNotNull → filter blank → map normalize → toSet). **이 헬퍼가 F2 삼자 일치의 유일 진실원천** — collect(Task 5)·프로세서(Task 6)가 재사용.

**REFACTOR**. KDoc §F2 삼자 일치 — "수집·저장·행별 해석이 반드시 이 normalize를 거친다. 별도 정규화 금지." 6종 필드 목록 명시.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*UserMappingNormalizerTest*'`.

### Task 4. ImportUserMappingRepository — saveAll + findByJobId

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/repository/ImportUserMappingRepository.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/repository/ImportUserMappingRepositoryTest.kt`]
- depends-on: [2]

**RED**. (1) `saveAll(jobId, Map<String, UUID?>)` + `findByJobId(jobId): Map<String, UUID?>` 라운드트립 — **target_user_id NULL 값 포함 저장·재조회 정확**(F1 누수 방지 — 실 insert 경로, mock/raw-DSL seed 금지). (2) 멱등 — 같은 jobId 재저장 시 기존 삭제 후 삽입(replace-all). (3) 빈 맵 저장 시 0행. → 미구현 FAIL. **(jOOQ codegen: Task2 migration→init_codegen 반영 후 build로 `ImportUserMappingsRecord` 생성 확인. 스테일 캐시 방지 `--rerun-tasks`/clean-codegen — backend-clean-build-broken.)**

**GREEN**. jOOQ 기반 `ImportMappingRepository`(PR-A) 미러 패턴. saveAll = `deleteFrom(where jobId).execute()` 후 batch insert(target_user_id NULL 그대로). findByJobId = select → `Map<source_identifier, target_user_id?>`. ⚠️ **미러 시 target_user_id는 nullable — `ImportMappingRepository.findByJobId`의 non-null 컬럼 `?: error(...)` 패턴(ImportMappingRepository.kt:89,92)을 target_user_id에 복사 금지**(정당한 NULL 크래시, F1 인접 — C2). `record.targetUserId`(nullable) 그대로 맵 값에.

**REFACTOR**. Testcontainers 시드(import_jobs FK 충족). KDoc replace-all 멱등 의도. ArchUnit `com.bts..jooq..` 한정(FR-SR-03 선례).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportUserMappingRepositoryTest*'`.

### Task 5. ImportMappingService — collectUsers + confirm 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/ImportMappingService.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/ImportMappingExceptions.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/mapping/UserCollectionResult.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/ImportMappingServiceTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/ImportMappingServiceUserTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/ImportMappingFlowIntegrationTest.kt`]
- depends-on: [3, 4]

**RED** (`ImportMappingServiceUserTest`, 신규 fake `UserLookupPort`).
- (1) `collectUsers(jobId, actor, fieldMappings)` — 소유+AWAITING 아니면 예외(requireOwnedAwaitingMapping 재사용). **CSV는 전량 스캔 전에 필드매핑을 먼저 검증**(`computeValidationResult` 재사용) — 무효(예: Summary 미매핑)면 422 `ImportMappingInvalidException`(전량 파싱의 `ImportParseException`→500 회피, **C1**). 검증 통과 후 `parseCsv(input, fieldMapping)` 전량 스캔 + `UserMappingNormalizer.collectIdentifiers`로 distinct → `resolveByEmails` 추천 + `findDisplayNamesByIds` display → `UserCollectionResult(users=[{sourceIdentifier, suggestedUserId?, suggestedDisplayName?}])`. JSON은 필드매핑 검증 스킵 + `parseJson` 전량 스캔(fieldMappings 무시).
- (2) `confirm(..., userMappings: List<Pair<String,UUID?>>)` — **List로 받아**(Map 선붕괴로 exact-dup 미검출 회피, **C4**) 정규화 후 검증: 중복 sourceIdentifier(정규화형 기준, exact+대소문자변형 모두)→422(`ImportUserMappingInvalidException`); non-null targetUserId 미실재(`findDisplayNamesByIds`에 없음)→422; null 허용.
- (3) 검증 통과 시 CAS 트랜잭션 안 `importUserMappingRepository.saveAll(jobId, 정규화맵)`이 필드매핑 saveAll 뒤·enqueue 앞에 실행. 반복 confirm 시 단일 저장(CAS 게이트).
- (4) userMappings 비면 사용자매핑 0행(하위호환). → 미구현 FAIL.
- **기존 `ImportMappingServiceTest` + `ImportMappingFlowIntegrationTest`(TestConfig) 생성자 갱신**(UserLookupPort·ImportUserMappingRepository 인자 추가 — **B1**, G4 plan-files-constructor-injection). ⚠️ **`ImportMappingFlowIntegrationTest.kt:181`(`ImportMappingService(...)`)·`:198`(`ImportJobProcessor(...)`) 두 팩토리가 required 신규 인자로 컴파일 깨짐** — TestConfig에 fake `UserLookupPort` 빈 + `ImportUserMappingRepository(dsl)` 빈 배선 추가(모듈 test 소스셋 전체 컴파일 대상이라 Task 5·6 검증 선행 필수).

**GREEN**. 생성자에 `userLookupPort: UserLookupPort`, `importUserMappingRepository: ImportUserMappingRepository` 추가. `collectUsers` 구현(필드매핑 선검증 → storage.get→parser 전량 스캔→collector→lookup). `confirm` 시그니처에 `userMappings: List<Pair<String,UUID?>>` 추가 + 검증(트랜잭션 밖) + CAS 트랜잭션에 saveAll 삽입. `ImportUserMappingInvalidException`(422 코드 `IMPORT_USER_MAPPING_INVALID`)을 `ImportMappingExceptions.kt`에 추가.

**REFACTOR**. 정규화는 `UserMappingNormalizer.normalize`만 사용(자체 lowercase 금지). KDoc §사용자 매핑 흐름·§검증 트랜잭션 밖(I/O)·§collect 필드매핑 선검증. confirm 로그에 userMappingCount 추가.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportMappingServiceTest*' --tests '*ImportMappingServiceUserTest*'`.

### Task 6. ImportJobProcessor — 사용자 매핑 로드 + 커맨드 userId 세팅

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/job/application/ImportJobProcessorTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/ImportMappingFlowIntegrationTest.kt`]
- depends-on: [1, 3, 4]

> **B1** — `ImportMappingFlowIntegrationTest.kt:198`의 `ImportJobProcessor(...)` 팩토리도 신규 required 인자로 컴파일 깨짐. TestConfig에 `ImportUserMappingRepository(dsl)` 빈 배선(Task 5와 동일 파일 — 두 Task가 같은 TestConfig 편집, wave 내 직렬 준수). Task 5가 이 파일을 이미 files에 포함하므로 Task 6은 파일 겹침으로 자동 직렬(depends 명시 불요이나 순서 보장).

**RED**. (1) job에 `import_user_mappings` 있으면 프로세서가 `findByJobId` **1회 로드** → 각 행의 reporter/assignee/댓글·worklog·첨부·changelog 식별자를 `UserMappingNormalizer.normalize`로 정규화 후 매핑 조회 → 커맨드 `reporterUserId`/`assigneeUserId` + VO `authorUserId` 세팅. (2) 매핑 없는 job은 로드 스킵, userId 필드 전부 null(회귀 불변). (3) 매핑에 없는 식별자·null 값은 userId null(폴백). → 미구현 FAIL(mockk `ImportUserMappingRepository.findByJobId`).

**GREEN**. `ImportJobProcessor`에 `ImportUserMappingRepository` 주입. `processRows`(또는 toCommand) 진입 시 매핑 1회 로드(빈 맵 폴백). `toCommand`에서 각 식별자를 `userMappings[normalize(email)]`로 해석해 userId 필드 세팅(email 필드는 그대로 둠 — 어댑터 폴백용). CSV/JSON 공통.

**REFACTOR**. 매핑 로드는 배치 1회(행마다 조회 금지). KDoc §사용자 매핑 로드. `UserMappingNormalizer` 재사용 명시(Task 3과 동일 진실원천).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportJobProcessorTest*'`.

### Task 7. issue-tracking 어댑터 — userId 우선 해석

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterTest.kt`]
- depends-on: [1]

**RED**. (1) `cmd.reporterUserId` 있으면 이메일 해석보다 우선. (2) `cmd.assigneeUserId` 우선(null이면 기존 이메일 해석). (3) 댓글/worklog/첨부 `authorUserId` 우선, 없으면 이메일→requester 폴백. (4) **changelog는 비대칭 유지** — `authorUserId ?: resolvedEmails[email]`(requester 폴백 없이 null). (5) `isAuthorUnmatched`는 userId 있으면 matched(경고 미발생). (6) **미매핑(userId 전부 null) 회귀 — 기존 이메일 해석 동작 완전 불변**. → 어댑터 미수정 FAIL.

**GREEN**. `resolveFields`: `reporterId = cmd.reporterUserId ?: (resolvedEmails[reporterEmail.lower()] ?: requester)`; `assigneeId = cmd.assigneeUserId ?: resolvedEmails[assigneeEmail.lower()]`. `resolveAuthorId(userId, email, resolvedEmails, requester) = userId ?: (resolvedEmails[email?.lower()] ?: requester)`. 각 apply*(comments/worklog/attachment)에서 VO `authorUserId` 전달. `applyChangelogGroup`은 `group.authorUserId ?: resolvedEmails[email?.lower()]`(비대칭). `isAuthorUnmatched(userId, email, resolvedEmails) = userId == null && email != null && resolvedEmails[email.lower()] == null`.

**REFACTOR**. `resolveAuthorId`/`isAuthorUnmatched` 시그니처에 userId 추가 — **호출부 전부 갱신**(컴파일 강제라 조용한 누락은 없으나 명시): `resolveAuthorId` 3곳(comment `:716`·worklog `:776`·attachment `:905`), `isAuthorUnmatched` **4곳**(apply comment `:715`·worklog `:775` + **dry-run preview `warnCommentsPreview:404`·`warnWorklogsPreview:434`** — C3, FR10 경고 정합 위해 preview 2곳도 authorUserId 전달). KDoc §userId 우선(어댑터 계약). resolvedEmails 배치 수집은 불변(userId 있어도 email 폴백 대비 유지).

**검증**. `./gradlew :backend:modules:issue-tracking:test --tests '*IssueImportAdapterTest*'`.

### Task 8. 웹 계층 — collect 엔드포인트 + confirm DTO 확장 + 예외

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/ImportMappingController.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/dto/MappingConfirmRequest.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/dto/UserCollectionResponse.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/imports/web/ImportExceptionHandler.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/web/ImportMappingControllerTest.kt`]
- depends-on: [5]

**RED**. 슬라이스 — (1) `POST /api/v1/imports/{jobId}/mapping/users` 200 + `UserCollectionResponse`(요청 body=fieldMappings, `MappingValidateRequest` 재사용). (2) confirm에 `userMappings` 담아 저장 확인. (3) 미인증 401·타인 404·상태충돌 409·사용자매핑 검증실패 422 `IMPORT_USER_MAPPING_INVALID`·actor 추출 우선. ⚠️ **슬라이스는 `webAppContextSetup`**(standalone 금지 — BLOCKER-2). → 미구현 FAIL.

**GREEN**. 컨트롤러에 `collectUsers` 핸들러(actor 추출 우선 → `importMappingService.collectUsers`). `MappingConfirmRequest`에 `userMappings: List<UserMappingEntry> = emptyList()` + `toUserMappingPairs(): List<Pair<String, UUID?>>` 추가(**Map 아닌 List — exact-dup raw 미검출 회피, C4**; 신규 `UserMappingEntry(sourceIdentifier, targetUserId: UUID?)`). `confirmMapping`이 `toUserMappingPairs()` 전달. `UserCollectionResponse.from`. `ImportExceptionHandler`에 `ImportUserMappingInvalidException`→422 매핑 추가(**assignableTypes는 `ImportMappingController` 이미 포함 — 클래스 불변이라 handler 메서드만 추가**).

**REFACTOR**. DTO from() 팩토리. KDoc 엔드포인트 3→4개 갱신(analyze·mapping/users·mapping/validate·mapping). collect req body는 필드매핑만이라 `MappingValidateRequest` 재사용(신규 req DTO 없음).

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportMappingControllerTest*'`.

### Task 9. 통합테스트 — 사용자 매핑 happy path + 회귀

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/search-export-import/src/test/kotlin/com/bts/search/imports/mapping/ImportUserMappingFlowIntegrationTest.kt`]
- depends-on: [6, 7, 8]

**RED**. 실 DB/MinIO(Testcontainers) — (1) 작성자 이메일이 BTS와 다른 CSV 업로드 → analyze(AWAITING_MAPPING) → collect(distinct 식별자 반환) → confirm(식별자→BTS userId 매핑) → 워커 process → 생성 이슈의 reporter/assignee가 **매핑된 userId**로 배정(stub `IssueImportPort`가 커맨드 userId 필드 capture). (2) 사용자매핑 없는 즉시경로/confirm은 기존 동작 불변(회귀). → FAIL.

**GREEN**. 전 경로 결선. `IssueImportPort`는 test stub(BC 격리 — 커맨드 userId 필드를 capture해 단언, test-assembled). `UserLookupPort` fake 시드(수집·실재확인).

**REFACTOR**. 시나리오 KDoc. PR-A `ImportMappingFlowIntegrationTest` 스타일 정합.

**검증**. `./gradlew :backend:modules:search-export-import:test --tests '*ImportUserMappingFlowIntegrationTest*'` + 모듈 전체 `:search-export-import:test`·`:shared-kernel:test`·`:issue-tracking:test`(회귀 0).

## Plan 메타

- task 수: 9
- 예상 wave: 6 (W1: T1,T2,T3 / W2: T4,T7 / W3: T5 / W4: T6 / W5: T8 / W6: T9). ⚠️ **T5·T6은 같은 `ImportMappingFlowIntegrationTest.kt`(TestConfig) 편집 → 파일 겹침 자동 직렬**(같은 wave 불가). 각 Task가 자기 팩토리 호출(`ImportMappingService(...)`:181 / `ImportJobProcessor(...)`:198)만 갱신 + 필요한 fake 빈 없으면 추가(직렬이라 race 없음).
- 3 모듈 — shared-kernel(T1) 먼저 컴파일, 그 위에 search-export-import(T2~6,8,9)·issue-tracking(T7). Gradle 모듈 컴파일 wave 내 직렬(bts-plan-wave-gradle-module-compile).
- TDD 강제: yes (test: 커밋 먼저)
- 추가 검증: ktlint, **detekt --rerun-tasks**(search-export-import 백엔드 CI 없음, 캐시 false-green 방지), 3 모듈 전체 test 회귀 0, verify-master-plan
- jOOQ codegen: T2 migration→init_codegen 미러 후 build로 `ImportUserMappingsRecord` 생성(T4 선행)
- **F2 삼자 일치 구조적 차단**: T3 `UserMappingNormalizer` 단일 진실원천을 T5(collect)·T6(processor)가 재사용
- **F1 누수 방지**: T4 target_user_id NULL 실 insert round-trip 회귀테스트

### 이월 노트 (후속 PR)
- **프론트 D6/D7**. 매핑 마법사(필드→사용자→값 단계) + collect/confirm 소비 + Zod. `apps/web/src/api/imports.ts` AWAITING_MAPPING enum·userMappings 계약은 프론트 PR에서(PR-B는 백엔드, 기존 클라이언트 미노출 회귀 0).
- **PR-C**. 값 매핑(status/type/priority) `import_value_mappings`. 사용자 매핑과 동형(collect→confirm 확장).

## 리뷰 결과

### eng 독립 리뷰 (2026-07-04, general-purpose 서브에이전트, 코드 대조)

**BLOCKER 1건 (수정 완료 → plan 반영)**.
- **B1** — PR-A `ImportMappingFlowIntegrationTest.kt`의 TestConfig가 `ImportMappingService(...)`(:181)·`ImportJobProcessor(...)`(:198)를 직접 생성. Task 5/6의 생성자 required 인자 추가로 이 파일 컴파일 깨짐 → 모듈 test 소스셋 전체 컴파일 대상이라 Task 5·6 검증 자체 실패. **files 목록 누락**(G4가 인용한 교훈 상황) → Task 5·6 files에 추가 + TestConfig에 fake UserLookupPort·ImportUserMappingRepository 빈 배선 명시. T5·T6 파일 겹침 자동 직렬(wave 6으로 조정).

**CONCERN 4건 (수정 완료 → plan 반영)**.
- **C1** — collect가 재사용하는 `parseCsv(fieldMapping)`이 Summary 미매핑 시 `ImportParseException`→핸들러 없어 500. → collectUsers가 전량 스캔 **전에 필드매핑 선검증**(`computeValidationResult`), 무효면 422(Task 5).
- **C2** — Task 4 findByJobId 미러 시 nullable `target_user_id`에 `?: error` 복사 금지(정당 NULL 크래시, F1 인접). → GREEN 명시(Task 4).
- **C3** — `isAuthorUnmatched`가 dry-run preview 2곳(`warnCommentsPreview:404`·`warnWorklogsPreview:434`)에서도 호출. → Task 7 REFACTOR에 4곳 호출부 전수 명시(FR10 경고 정합).
- **C4** — confirm DTO Map 선붕괴 시 exact-dup raw 미검출(경미, 멱등). → DTO를 List로 전달(`toUserMappingPairs`), 서비스가 정규화 후 dedup 검사(Task 5/8).

**코드 대조 확인된 긍정**. CAS-우선 원자성 보존(사용자매핑 saveAll을 필드 뒤·enqueue 앞 삽입 안전)·검증 트랜잭션 밖(I/O 정책 일관)·커맨드/VO nullable 필드 추가 positional 파급 0(전 호출부 named)·changelog 비대칭 폴백 보존·파서 3 스트리밍 메서드 실재·신규 포트 메서드 0(35 fake 무파급)·V607 공석·init_codegen 미러 스타일·SchemaMigrationImportTest count-coupling 없음·ExceptionHandler assignableTypes에 ImportMappingController 이미 포함·**NFR4 런타임 부팅 위험 없음**(모든 import 테스트가 수동 @ContextConfiguration+명시 @Bean, component-scan 아님 → 위험은 컴파일타임 생성자 파급=B1)·wave 그래프 순환 없음.

**BLOCKER 잔여: 없음** (B1 plan 수정 반영, C1~C4 흡수). 게이트 1 진입 가능.
