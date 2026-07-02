# FR-IM-01 — CSV/JSON Import (Jira 마이그레이션)

> slug: fr-im-01-csv-json-import
> type: feature
> agent: backend-engineer
> 생성: 2026-07-02
> BC: search-export-import (§4.1)

## Brief

FR-IM-01 CSV/JSON Import (Jira 마이그레이션). search-export-import BC §4.1, 우선순위 필수.
선행: issue-tracking §2~§3 (이슈/컴포넌트/버전 작성 API) — 완료.
작업 범위 D1~D7:
- D1. 도메인 — ImportJob (backend-engineer)
- D2. 명세 — CSV/JSON 파싱 + 트랜잭션 정책 + dry-run (backend-engineer)
- D3. 데이터 모델 — import_jobs(status, error_log_minio_key) (db-engineer)
- D4. 백엔드 — POST /api/v1/imports + 백그라운드 worker (backend-engineer)
- D5. 백엔드 테스트 — Jira CSV 샘플 (backend-engineer)
- D6. 프론트 UI — 파일 업로드 + 진행률 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

강력한 선례: FR-EX-02(비동기 Export) — pgmq job + MinIO + 폴링 worker 패턴의 역방향.

classify 정정: 'Jira 마이그레이션' → DB migration 오인(type=migration/db-engineer)을 feature/backend-engineer로 정정.

## 도메인 정리

- **BC**. search-export-import (물리 모듈 동일, 새 BC 신설 0). FR-EX-02(비동기 Export)의 역방향 미러.
- **패키지**. `com.bts.search.imports` (Kotlin `import`는 예약어 → 복수형. 엔드포인트 `POST /api/v1/imports`와 정합).
- **신규 엔티티**. `ImportJob` (Aggregate Root, `ExportJob` 거울상) — status(PENDING/RUNNING/COMPLETED/FAILED)·progress(0~100)·sourceObjectKey(업로드 원본 MinIO 키)·errorLogObjectKey(실패행 로그)·totalRows/succeededRows/failedRows·requesterUserId·projectKey·format(CSV/JSON)·dryRun·expiresAt(TTL)·created/started/completedAt.

### cross-BC write 포트 (핵심 결정 — ADR)

- Export는 이슈를 **읽음**(`IssueSearchPort`, shared-kernel, issue-tracking 어댑터). Import는 이슈를 **써야** 함.
- **BTS 최초의 search→issue-tracking write 경로**. 기존 shared-kernel 포트는 전부 read/lookup.
- shared-kernel 신규 `IssueImportPort` 인터페이스 → issue-tracking `IssueImportAdapter` 구현. `IssueSearchPort` 패턴 1:1 미러(default fail-safe 구현 포함).
- **권한 게이트는 issue-tracking 안에 유지**. 어댑터가 `IssueApplicationService.createIssue(actor=requester, ...)`를 호출 → CREATE_ISSUE 권한·워크플로우 시작상태·이슈키 발급·이벤트 발행 전부 기존 경로 재사용. search BC는 권한을 알지 못함(우회 불가).
- **트랜잭션 경계**. 행별 best-effort — 각 createIssue가 issue-tracking 안에서 자체 @Transactional. 한 행 실패가 다른 행에 영향 없음(FR-IS-05 BulkOperation 선례 동형).

### 사용자 매핑 (Maxi 결정 — 이메일 자동매칭+폴백)

- Jira CSV의 reporter/assignee(이메일) → `UserLookupPort` 이메일 조회로 userId 해석.
- 미매칭 시 reporter=import 실행자(requester), assignee=미할당(null).
- **`UserLookupPort` 확장 필요** — 현재 `exists`/username 조회만 있음. 신규 `resolveByEmails(emails): Map<String,UUID>`를 **default 메서드**로 추가(기존 ~35개 인라인 구현 안 깨짐 — memory: 공유 인터페이스 확장은 default fail-safe). identity-access `UserLookupAdapter`가 실제 구현.
- 명시적 필드/사용자 매핑 UI는 **FR-IM-02** 범위. FR-IM-01은 고정 기본 매핑.

### Maxi 결정 요약 (게이트 전 확정)

| 결정 | 채택 |
|---|---|
| 트랜잭션 정책 | 행별 best-effort (실패행→에러 로그 MinIO, 나머지 계속) |
| 사용자 매핑 | 이메일 자동매칭 → 폴백(reporter=실행자, assignee=null) |
| dry-run | 선택 옵션(`dryRun=true` → 파싱+검증만, 생성 0, 리포트 반환) |
| PR 분할 | 백엔드(D1~D5) 먼저 → **이번 PR은 백엔드 전용**, D6/D7 후속 PR |

### 확장 범위 결정 (Maxi — FR-IM-01은 SDD 10.6.3 풀 마이그레이션 에픽)

Maxi가 확장 범위를 택함 → FR-IM-01을 순차 PR 에픽으로 분할. **이번 PR = PR1(기반+코어)**.

| 결정 | 채택 |
|---|---|
| 이슈 키 | 새 키 자동생성(incrementKeySequence). Jira 키 보존 안 함(키 재사용 금지 원칙) |
| 임포트 범위 | 확장 포함(첨부·댓글·이력·Worklog) — 순차 PR로 분할 |
| 컴포넌트/버전 | 없으면 자동 생성 (PR2) |
| Status | 소스 상태로 전이 시도 (PR2). 도달불가/게이트 폴백은 PR2 스펙 |
| 첨부 출처 | zip 아카이브 업로드(서버 fetch 없음=SSRF 없음) (PR4) |
| 이력 주입 | append-only 이력에 조작 타임스탬프/주체 주입 — 메커니즘 결정은 PR4 스펙 |

**에픽 PR 순서**.
- **PR1 (이번)**. ImportJob·`import_jobs`(V604)·`q_import_jobs`·multipart 업로드→MinIO·`POST/GET /api/v1/imports`·`ImportJobWorker`·CSV/JSON 파싱·**코어 이슈 필드** 생성(`IssueImportPort`)·이메일 매핑(`UserLookupPort.resolveByEmails`)·dry-run·에러 로그(MinIO)·행 상한(MAX_ROWS)·행별 best-effort.
- PR2. 컴포넌트/버전 자동생성 + 소스 상태 전이.
- PR3. 댓글 + Worklog.
- PR4. 첨부(zip) + 이력.
- (D6/D7 프론트 업로드/진행률 UI는 별도 PR — FR-IM-02 매핑 UI와 조율)

### 재사용 자산 (FR-EX-02 1:1 미러)

- pgmq worker(`ExportJobWorker` → `ImportJobWorker`, CAS claim·dead-letter·outbox enqueue·VT/stale 정합)
- MinIO 자체 클라이언트 빈 분리(`exportMinioClient` → `importMinioClient`, io.minio 기승인, BC 격리). 버킷 `bts-imports`(업로드 원본 + 에러 로그).
- `@Transactional` 밖 process()(분 단위 I/O), CSV 파서(RFC 4180 — FR-EX-01 자산 참고), POI/CSV 의존성 이미 모듈 존재.
- **차이점**. Export=파일 생성/다운로드. Import=multipart 파일 업로드→MinIO 저장→enqueue→worker 파싱→행별 createIssue.

### 데이터 모델

- `import_jobs` 테이블(V604 예정 — V600~603 사용, 다음 번호. **머지 직전 재확인** — memory: V번호 동시 브랜치 충돌) + pgmq 큐 `q_import_jobs`.
- 소프트 삭제 없음(TTL 하드삭제, export_jobs 동형).

### 신규 용어 (glossary 추가 후보 — Maxi 승인 후)

- **Import** — CSV/JSON 파일을 파싱해 이슈/컴포넌트/버전을 대량 생성(Jira 마이그레이션). Export의 역방향.
- **ImportJob** — 비동기 Import 작업 단위. pgmq worker가 PENDING→RUNNING→COMPLETED/FAILED 처리. 행별 best-effort(부분 실패 허용).
- **dry-run** — 실제 생성 없이 파싱+검증만 수행해 결과를 미리 보는 검증 실행 모드.

- **기존 결정 충돌**. 없음.
- **관련 ADR**. `docs/decisions/2026-07-02-fr-im-01-csv-json-import.md` (생성). FR-EX-02 ADR·IssueSearchPort ADR(2026-06-01) 계승.
- **선행 조건**. issue-tracking §2~§3(이슈/컴포넌트/버전 작성 API) 완료 확인 ✓.

## 스펙

전체 스펙(PR1 백엔드). [docs/specs/2026-07-02-fr-im-01-csv-json-import.md](../specs/2026-07-02-fr-im-01-csv-json-import.md)

핵심 시나리오 3줄 요약.
- multipart로 Jira CSV/JSON 업로드 → MinIO 저장 → 202 jobId → worker가 행별로 코어 이슈 필드 생성(`IssueImportPort`)
- 행별 best-effort — 실패행은 에러 로그(MinIO), 성공행은 유지. dry-run은 생성 없이 검증만
- 사용자는 이메일로 매핑(미매칭 폴백), 접수 시 CREATE_ISSUE fail-fast + 행별 권한 이중 방어

## Brainstorming Check

✅ 통과 (1회 iteration). gap 3건 보강 — 접수 권한 fail-fast(FR12)·JSON 구조 명시·이메일 다중매칭 폴백(email nullable+비유니크).

## Plan

> FR-EX-02 `com/bts/search/export/job/*` 구조를 `com/bts/search/imports/job/*`로 1:1 미러.
> 경로 접두사: SEI = `backend/modules/search-export-import/src`, IT = `backend/modules/issue-tracking/src`, SK = `backend/modules/shared-kernel/src`, IA = `backend/modules/identity-access/src`.

### Task 1. import_jobs 마이그레이션 + q_import_jobs 큐 (V604)

**메타**.
- agent: `db-engineer`
- files: [`SEI/main/resources/db/migration/search-export-import/V604__import_jobs.sql`, `SEI/main/resources/db/codegen/init_codegen.sql`, `SEI/test/kotlin/com/bts/search/imports/job/SchemaMigrationImportTest.kt`]
- depends-on: []

**RED**: Testcontainers로 마이그레이션 적용 후 `import_jobs` 존재·컬럼·CHECK·인덱스 + `pgmq.q_import_jobs` 존재 검증 테스트(실패 = 테이블 없음).
**GREEN**: `V604` — `CREATE EXTENSION IF NOT EXISTS pgmq CASCADE` + `pgmq.create('q_import_jobs')` + `import_jobs`(id·project_key·format·source_object_key·dry_run·requester_user_id·status·progress·total_rows·succeeded_rows·failed_rows·error_code·error_log_object_key·expires_at·created_at·started_at·completed_at) + CHECK(status)/CHECK(format) + idx (requester_user_id, created_at DESC) + partial (expires_at) WHERE NOT NULL. V602 export_jobs 미러.
**REFACTOR**: init_codegen.sql에 동일 DDL 미러(jOOQ codegen 입력). L1 주석.
**검증**: `./gradlew :backend:search-export-import:test --tests '*SchemaMigrationImportTest'`. **머지 직전 V번호 재확인**(동시 브랜치).

### Task 2. ImportJob 도메인 (Aggregate + Id + Status)

**메타**.
- agent: `backend-engineer`
- files: [`SEI/main/kotlin/com/bts/search/imports/job/domain/ImportJob.kt`, `.../domain/ImportJobId.kt`, `.../domain/ImportJobStatus.kt`, `SEI/test/kotlin/com/bts/search/imports/job/domain/ImportJobTest.kt`]
- depends-on: []

**RED**: `progressPercent(processed,total)`(total 0→100, floor) + `errorLogReady`(COMPLETED && errorLogObjectKey!=null) 순수함수 테스트.
**GREEN**: `data class ImportJob`(불변, ExportJob 미러 + total/succeeded/failedRows + sourceObjectKey + dryRun + format) + `ImportJobId(UUID)` value class + `enum ImportJobStatus{PENDING,RUNNING,COMPLETED,FAILED}` + `companion { const val MAX_ROWS=100_000L }`.
**REFACTOR**: KDoc(중괄호/백틱 평문 — ktlint-kdoc 함정), 상수 추출.
**검증**: `--tests '*ImportJobTest'`.

### Task 3. IssueImportPort 계약 (shared-kernel, cross-BC 쓰기)

**메타**.
- agent: `backend-engineer` (security-engineer 검토 — cross-BC 쓰기·권한 위임)
- files: [`SK/main/kotlin/com/bts/shared/issue/IssueImportPort.kt`, `.../issue/IssueImportCommand.kt`, `.../issue/IssueImportResult.kt`, `SK/test/kotlin/com/bts/shared/issue/IssueImportPortTest.kt`]
- depends-on: []

**RED**: default 구현 호출 시 **fail-closed** 반환(성공 위장 금지) 검증 — `IssueImportResult.failure(ADAPTER_UNAVAILABLE)`.
**GREEN**: `interface IssueImportPort { fun importIssue(cmd: IssueImportCommand): IssueImportResult = IssueImportResult.failure("ADAPTER_UNAVAILABLE") }` + `IssueImportCommand`(projectKey·requesterUserId·typeName?·summary·description?·priority?·reporterEmail?·assigneeEmail?·labels·componentNames·dryRun) + `IssueImportResult`(sealed/데이터: 생성 issueKey 또는 실패 reasonCode). KDoc에 IssueSearchPort와 fail 방향 차이(쓰기=fail-closed) 명시.
**REFACTOR**: 사유 코드 상수(NOT_FOUND/FORBIDDEN/TRANSITION.../VALIDATION/UNKNOWN — BulkOperationItem 코드 참고).
**검증**: `--tests '*IssueImportPortTest'`.

### Task 4. UserLookupPort.resolveByEmails + identity-access 어댑터

**메타**.
- agent: `backend-engineer`
- files: [`SK/main/kotlin/com/bts/shared/user/UserLookupPort.kt`, `IA/main/kotlin/com/atlas/bts/identity/user/UserLookupAdapter.kt`, `IA/test/kotlin/.../user/UserLookupAdapterEmailTest.kt`]
- depends-on: []

**RED**: 어댑터 `resolveByEmails(setOf("Bob@x.com"))` → LOWER 매칭 1:1 · 다중매칭 이메일은 결과 제외(fail-safe) · NULL email 제외 · 빈 입력 빈 맵. Testcontainers 통합.
**GREEN**: `UserLookupPort`에 `fun resolveByEmails(emails: Set<String>): Map<String, UUID> = emptyMap()` **default 메서드**(기존 ~35 인라인 구현 무회귀). 어댑터 `SELECT lower(email), id FROM users WHERE lower(email) IN (:emails)` → 다중행 이메일 드롭.
**REFACTOR**: KDoc에 email nullable+비유니크·다중매칭 드롭 트레이드오프 명시(username 선례 동형).
**검증**: `--tests '*UserLookupAdapterEmailTest'` + 기존 UserLookupPort 소비 모듈 컴파일(default라 무회귀).

### Task 5. CSV/JSON 파서 → ParsedImportRow

**메타**.
- agent: `backend-engineer`
- files: [`SEI/main/kotlin/com/bts/search/imports/parse/ImportRowParser.kt`, `.../parse/ParsedImportRow.kt`, `.../parse/ImportParseException.kt`, `SEI/test/kotlin/com/bts/search/imports/parse/ImportRowParserTest.kt`]
- depends-on: []

**RED**: Jira CSV(헤더+따옴표+콤마 내포, RFC 4180) + Jira JSON(`{issues:[{fields:{...}}]}`) 각각 → `ParsedImportRow`(rowNumber·summary·description·typeName·priorityName·reporterEmail·assigneeEmail·labels·componentNames) 추출. 헤더만/빈파일→0행. 깨진 입력→`ImportParseException`. NUL/제어문자 정화.
**GREEN**: CSV 수동 파서(FR-EX-01 RFC4180 writer 역방향 참고, zero-dep) + JSON Jackson `fields` 추출. Priority 이름→1..5 매핑(범위밖 무시).
**REFACTOR**: 컬럼명 상수·매핑 테이블 추출.
**검증**: `--tests '*ImportRowParserTest'`.

### Task 6. ImportJobRepository (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`SEI/main/kotlin/com/bts/search/imports/job/repository/ImportJobRepository.kt`, `SEI/test/kotlin/com/bts/search/imports/job/repository/ImportJobRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: insert→findById 왕복 · `claimForRun`(PENDING→RUNNING CAS, 1행 true/재청 false) · `findStatus` · `findByIdForRequester`(소유자 아니면 null) · `updateCounts`(progress/total/succeeded/failed) · `markCompleted/markFailed` · `findExpired(now)` (Testcontainers).
**GREEN**: jOOQ 구현 — ExportJobRepository 미러. `STALE_RUNNING_THRESHOLD_SECONDS=600`.
**REFACTOR**: SQL 상수·KDoc. jOOQ repository는 `.repository` 패키지 유지(ArchUnit).
**검증**: `--tests '*ImportJobRepositoryTest'`.

### Task 7. MinIO 저장 (import 전용 클라이언트)

**메타**.
- agent: `backend-engineer`
- files: [`SEI/main/kotlin/com/bts/search/imports/job/storage/ImportObjectStoragePort.kt`, `.../storage/MinioImportStorageAdapter.kt`, `.../storage/MinioImportStorageConfig.kt`, `.../storage/MinioImportStorageException.kt`, `SEI/test/kotlin/com/bts/search/imports/job/storage/MinioImportStorageAdapterTest.kt`]
- depends-on: []

**RED**: put(원본 InputStream)→get 왕복 · delete · 존재하지 않는 key get→예외. MinIO Testcontainers.
**GREEN**: `importMinioClient` 빈 분리(빈이름 충돌 회피 — shared-util 선례) + bucket `bts-imports` 자동생성 + best-effort 기동(권한예외는 누출 금지 — best-effort catch 함정). MinioExportStorage 미러.
**REFACTOR**: 설정 프로퍼티 `bts.minio.*` 재사용·KDoc.
**검증**: `--tests '*MinioImportStorageAdapterTest'`.

### Task 8. IssueImportAdapter (issue-tracking, 행별 1 트랜잭션)

**메타**.
- agent: `backend-engineer` (security-engineer 검토 — createIssue 권한 위임·행 원자성)
- files: [`IT/main/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapter.kt`, `IT/test/kotlin/com/bts/issue/adapter/outbound/imports/IssueImportAdapterTest.kt`]
- depends-on: [3, 4]

**RED**: 통합 테스트 — cmd(코어 필드) → `createIssue(actor=requester)` + priority/labels/assignee update가 **한 @Transactional**로 실행(update 실패 시 create 롤백=행 원자성) · 이메일 매핑(UserLookupPort) 매칭/폴백 · component 이름 연결(없으면 스킵+result 경고) · CREATE_ISSUE 없으면 FORBIDDEN result · dryRun=true면 생성 0 + 검증 result. 실 repo+시드(mockk 금지 — 전이/권한 통합은 실 repo 선례).
**GREEN**: `@Component IssueImportAdapter : IssueImportPort`. `@Transactional` 메서드(REQUIRES_NEW 아님 — 각 행 독립 호출). `IssueApplicationService.createIssue` + update 재사용. 예외→result 코드 변환(도메인 예외 HTTP 누출 방지).
**REFACTOR**: 사유 코드 매핑 함수·KDoc(책임=행 1건 원자 생성).
**검증**: `--tests '*IssueImportAdapterTest'` + ArchUnit(shared-kernel port만 의존).

### Task 9. ImportJobProcessor (application 오케스트레이션 + 에러로그)

**메타**.
- agent: `backend-engineer`
- files: [`SEI/main/kotlin/com/bts/search/imports/job/application/ImportJobProcessor.kt`, `.../application/ImportErrorLogWriter.kt`, `SEI/test/kotlin/com/bts/search/imports/job/application/ImportJobProcessorTest.kt`]
- depends-on: [2, 3, 5, 6, 7]

**RED**: process(job) — MinIO 원본 로드→파서→행수>MAX_ROWS면 markFailed(IMPORT_ROW_LIMIT_EXCEEDED) · 행별 `IssueImportPort.importIssue` 호출→성공/실패 카운트·실패행 CSV 에러로그(MinIO)·progress 갱신 · dryRun=true면 port 호출 0(검증만)·에러로그=검증리포트 · 파싱실패→markFailed(IMPORT_PARSE_FAILED). mockk port로 분기 단위테스트(nullable @Positive 0통과 함정 주의).
**GREEN**: `@Service`(@Transactional 밖 — 분단위 I/O) 오케스트레이션. IssueImportPort 주입(포트 소비, issue-tracking 직접 의존 X). ExportJobProcessor 미러.
**REFACTOR**: 에러로그 컬럼(rowNumber·field·reason·code) 상수·KDoc.
**검증**: `--tests '*ImportJobProcessorTest'`.

### Task 10. Enqueue publisher + Worker + Cleanup worker

**메타**.
- agent: `backend-engineer`
- files: [`SEI/main/kotlin/com/bts/search/imports/job/event/ImportJobEnqueuePublisher.kt`, `.../job/worker/ImportJobWorker.kt`, `.../job/worker/ImportJobCleanupWorker.kt`, `SEI/test/kotlin/com/bts/search/imports/job/worker/ImportJobWorkerTest.kt`, `.../worker/ImportJobCleanupWorkerTest.kt`]
- depends-on: [1, 6, 9]

**RED**: worker — pgmq.read→CAS claimForRun→process→delete · claim false 분기(terminal→delete/running→skip/unknown→poison) · dead-letter(read_ct>5 archive) · 파싱불가 메시지 poison. cleanup — findExpired→DB+MinIO(원본·에러로그) 하드삭제(Clock 주입). Testcontainers.
**GREEN**: ExportJobWorker/CleanupWorker/EnqueuePublisher 1:1 미러. `QUEUE_NAME="q_import_jobs"`·VT=300·stale=600·MAX_RECEIVE=5. 발행자 `Propagation.MANDATORY`(outbox). **@EnableScheduling 결선 확인**(모듈 첫 @Scheduled면 필수 — 함정).
**REFACTOR**: UUID 패턴·상수·KDoc(VT↔stale 정합 근거).
**검증**: `--tests '*ImportJobWorkerTest' '*ImportJobCleanupWorkerTest'`.

### Task 11. ImportJobService(접수) + Controller + DTO + ExceptionHandler

**메타**.
- agent: `backend-engineer` (security-engineer 검토 — 접수 권한 fail-fast·소유권 404·multipart)
- files: [`SEI/main/kotlin/com/bts/search/imports/job/application/ImportJobService.kt`, `SEI/main/kotlin/com/bts/search/imports/web/ImportController.kt`, `.../web/dto/ImportJobResponse.kt`, `.../web/ImportExceptionHandler.kt`, `SEI/test/kotlin/com/bts/search/imports/web/ImportControllerTest.kt`, `SEI/test/kotlin/com/bts/search/imports/job/application/ImportJobServiceTest.kt`]
- depends-on: [6, 7, 10]

**RED**: 서비스 accept — CREATE_ISSUE 없으면 **403 fail-fast**(파일 저장·job 생성 전, cross-BC resolver 경유) · 파일>50MB→413 · format 미지원→400 · 정상→MinIO 저장+PENDING persist+enqueue(단일 트랜잭션)+202 jobId. 컨트롤러 — POST multipart 202 · GET 소유자만(타인 404 은닉) · GET /errors 완료후 스트리밍 · 401→500 변질 안 됨(ExceptionHandler). MockMvc 슬라이스.
**GREEN**: `@Service ImportJobService`(actor SecurityContext 추출) + `@RestController ImportController`(`/api/v1/imports`) + `ImportJobResponse` + `@RestControllerAdvice(assignableTypes=[ImportController]) ImportExceptionHandler`(IMPORT_ prefix, ProblemDetail). 접수 권한은 IssueVisibilityPort/권한 resolver 경유(role 직접조회 금지). ExportController/Handler 미러.
**REFACTOR**: 에러코드 상수·KDoc·multipart 크기 설정.
**검증**: `--tests '*ImportControllerTest' '*ImportJobServiceTest'` + 모듈 전체 `./gradlew :backend:search-export-import:test`.

## Plan 메타

- task 수: 11 (에픽 PR1 — FR-EX-02 규모 동형)
- 예상 wave: 5 (W1: T1·T2·T3·T4·T5·T7 / W2: T6·T8 / W3: T9 / W4: T10 / W5: T11)
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- cross-BC/보안 검토 task: T3·T8·T11 (security-engineer)
- 추가 검증: ktlint·detekt(--rerun-tasks, 캐시 false-green 함정)·ArchUnit(search→issue-tracking 직접의존 0)·모듈 전체 test
- Gradle 모듈 컴파일 직렬화: shared-kernel(T3,T4)·issue-tracking(T8) 변경은 컴파일 순서 영향 — wave 내 병렬이라도 Gradle 직렬(plan-wave 선례)

## 리뷰 결과 (← /bts-review-plan 채움)
