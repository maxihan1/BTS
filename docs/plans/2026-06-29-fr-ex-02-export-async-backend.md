# FR-EX-02 — 대용량(>1만건) 비동기 Export (백엔드 D1~D5)

> slug: fr-ex-02-export-async-backend
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import
> 생성: 2026-06-29

## Brief

대용량(>1만건) 비동기 Export. FR-EX-01(동기, PR #203)의 진화판.
사용자 입력: "FR-EX-02 대용량(>1만건) 비동기 Export — pgmq job + 백그라운드 worker + export_jobs 테이블 + 진행률/결과URL TTL + 프론트 진행률/다운로드 UI"

**범위 결정 (Maxi 게이트, 2026-06-29)**: 이번 PR = 백엔드 D1~D5만. 프론트 D6/D7은 별도 PR.
- D1. ExportJob 도메인
- D2. 명세 — 큐 + 진행률 + 결과 URL TTL
- D3. 데이터 모델 — export_jobs(status, progress, result_minio_key, expires_at)
- D4. 백엔드 — pgmq job + 백그라운드 worker
- D5. 백엔드 테스트 — 1만건 시나리오

**classify 교정 메모**: 원판 type=ui(프론트 키워드 끌림) → feature/backend-engineer 교정.

## 도메인 정리

- **BC**: search-export-import (FR-EX-01 동일 본거지, BC 신설 없음)
- **영향 엔티티**: ExportJob (신규 aggregate), export_jobs 테이블 (신규, V602)
- **새 용어**: **ExportJob (Export 작업)** — 대용량 비동기 Export의 영속 aggregate. `status`(PENDING→RUNNING→COMPLETED/FAILED 종단) + `progress` + `result_object_key`(MinIO) + `expires_at`(TTL). pgmq `q_export_jobs`로 백그라운드 처리. (glossary 추가 대기 — Maxi 승인 필요)
- **재사용 자산**:
  - BulkOperation(FR-IS-05, issue-tracking) — pgmq consumer 패턴 1:1 복제 (CAS claim·dead-letter·outbox MANDATORY enqueue·@Transactional 없음·하드삭제 cleanup+Clock 주입)
  - FR-EX-01 export 패키지 — ExportService/Csv·XlsxExportWriter(OutputStream write)/ExportColumn/ExportFormat/ExportCellSanitizer/IssueSearchPort(BROWSE+visibility 구조적 상속)
  - issue-tracking MinioStorageConfig 패턴 — search 모듈 자체 MinioClient 복제 (BC 격리)
- **핵심 아키텍처 결정** (ADR 상세):
  - D4. **MinIO 접근 = search 모듈 자체 클라이언트** (io.minio 의존성 추가, bucket `bts-exports`). shared-kernel 포트 추출 기각 — MinIO는 공유 인프라라 각 BC 자체 클라이언트가 자연스럽고, 포트화는 cross-BC 호출+배포조립(부재) 강제.
  - D5. 결과 TTL = 하드삭제 (DB + MinIO 객체). 일시적 운영 부산물 (bulk-operation 하드삭제 근거 동형).
  - D6. job 소유권 — 본인 job만 조회/다운로드 (비소유 404 존재 은닉).
  - D7. 비동기 행 상한 + 스트리밍(SXSSF) 전략 → spec D2 이연.
- **기존 결정 충돌**: 없음 (FR-EX-01 ADR §D5가 `/search/export-jobs`·export_jobs·SXSSF를 명시적으로 예고).
- **관련 ADR**: [docs/decisions/2026-06-29-fr-ex-02-async-export-jobs.md](../decisions/2026-06-29-fr-ex-02-async-export-jobs.md) (생성됨)
- **grill-with-docs 생략 근거**: BulkOperation 선례 1:1 매핑 + 새 용어 1개로 도메인 명확. 명확한 백엔드 작업에 대화형 스킬 과함 (메모리: bts-spec office-hours mismatch / bts-review-plan autoplan overkill). 게이트 1에서 일괄 검토.

## 스펙

전체 스펙. [docs/specs/2026-06-29-fr-ex-02-export-async-backend.md](../specs/2026-06-29-fr-ex-02-export-async-backend.md)

핵심 결정 (Maxi 게이트, 2026-06-29).
- 직렬화 = **스트리밍** (CSV OutputStream 직접 / XLSX SXSSFWorkbook) + **10만행 상한** (초과 시 FAILED)
- 다운로드 = **자체 엔드포인트 프록시** (`GET .../export-jobs/{id}/download`, 본인 job만, MinIO 비노출)
- TTL = **24시간** (만료 후 DB+MinIO 하드삭제)
- 진행률 = 페이지(100행)마다 progress(%) 갱신, 빈 결과는 progress=100 즉시

API 3종. `POST /search/export-jobs`(202+jobId) · `GET /search/export-jobs/{id}`(폴링) · `GET .../{id}/download`(스트리밍).
데이터 모델. V602 export_jobs + pgmq `q_export_jobs` 큐 + init_codegen 미러.

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check, gap 4건 — G1 division-by-zero / G3 ExportService 메모리경로 재사용불가 보강, G2 동시job제한 후속이연 / G4 신규에러코드 plan이연). Maxi 결정 필요 잔여 gap 없음.

## Plan

> 패키지 미러: BulkOperation `bulk/{domain,repository,application,event,worker}` → `com.bts.search.export.job.{domain,repository,application,event,worker,storage,serialize}`.
> 베이스: `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/job/`, 테스트는 대응 `src/test/`.
> 모듈 검증 경로: `:modules:search-export-import`.

### Task 1. V602 마이그레이션 + pgmq 큐 + jOOQ codegen

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/search-export-import/src/main/resources/db/migration/search-export-import/V602__export_jobs.sql`, `backend/modules/search-export-import/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/job/ExportJobsSchemaMigrationTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/persistence/SearchPersistenceTestBase.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/SavedFilterIntegrationTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/savedfilter/SavedFilterShareIntegrationTest.kt`]
- depends-on: []

**⚠️ BLOCKER-1 (eng 리뷰) — pgmq Docker 이미지 불일치**: search 모듈 통합테스트는 `postgres:16-alpine`을 쓰는데 pgmq extension 바이너리가 이 이미지에 **없다**. issue-tracking은 그래서 `quay.io/tembo/pg16-pgmq:latest`(ADR 2026-05-22-pgmq-postgres-image)를 쓴다. V602가 `pgmq.create`를 만나면 alpine 컨테이너에서 마이그레이션이 죽는다(`could not open extension control file`). `CREATE EXTENSION IF NOT EXISTS`로 방어 불가 — extension 바이너리 부재면 `IF NOT EXISTS`도 동일 오류.

**RED**: `ExportJobsSchemaMigrationTest`(Testcontainers, **Tembo 이미지**) — 마이그레이션 적용 후 `export_jobs` 테이블 + 13컬럼 + status/format CHECK 제약 + 2 인덱스 존재 검증. 큐 `q_export_jobs`가 `pgmq.list_queues()`에 존재 검증. → 테이블 없음 fail.

**GREEN**:
- **통합테스트 컨테이너 이미지 변경** — `SearchPersistenceTestBase` + `SavedFilterIntegrationTest` + `SavedFilterShareIntegrationTest` 3곳의 `postgres:16-alpine` → `quay.io/tembo/pg16-pgmq:latest`(`asCompatibleSubstituteFor("postgres")`, issue-tracking build.gradle.kts 패턴). search 모듈 Flyway가 V602를 적용하므로 기존 통합테스트도 pgmq 이미지 필요.
- **V602__export_jobs.sql** — `CREATE EXTENSION IF NOT EXISTS pgmq CASCADE`(V002 패턴) + `SELECT pgmq.create('q_export_jobs')` + spec §데이터 모델 `CREATE TABLE` + 인덱스.
- **init_codegen.sql** — `CREATE TABLE export_jobs` + 인덱스**만** 미러. **`pgmq.create`/`CREATE EXTENSION` 제외**(CONCERN-1) — jOOQ codegen은 `public` 스키마만 introspect(`inputSchema="public"`)하므로 큐 메타 불필요. codegen은 alpine 유지(build.gradle codegen url 무변경).
- `./gradlew :modules:search-export-import:generateJooq`로 jOOQ 코드 재생성.

**REFACTOR**: SQL 주석(컬럼 의미) + 인덱스 명명 일관성 + 이미지 변경 KDoc(ADR 2026-05-22 인용).

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobsSchemaMigrationTest"` + `:modules:search-export-import:generateJooq`

### Task 2. ExportJob 도메인 + Status

**메타**.
- agent: `backend-engineer`
- files: [`backend/.../export/job/domain/ExportJobId.kt`, `backend/.../export/job/domain/ExportJobStatus.kt`, `backend/.../export/job/domain/ExportJob.kt`, `backend/.../export/job/domain/ExportJobTest.kt`]
- depends-on: []

**RED**: `ExportJobTest` — (a) `ExportJobStatus` PENDING/RUNNING/COMPLETED/FAILED 종단 전이 규칙, (b) `ExportJob.progressPercent(processed, total)` 계산(total=0 → 100, 그 외 처리/총*100 내림), (c) `downloadReady` = COMPLETED && resultObjectKey != null. → 클래스 없음 fail.

**GREEN**: `ExportJobId`(value class UUID), `ExportJobStatus`(enum, BulkOperationStatus 미러), `ExportJob`(data class + progress/downloadReady 순수 함수).

**REFACTOR**: KDoc(상태 머신 전이 다이어그램) + 상한 상수 `MAX_ROWS=100_000L`를 도메인 companion에.

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobTest"`

### Task 3. ExportJobRepository (jOOQ)

**메타**.
- agent: `backend-engineer`
- files: [`backend/.../export/job/repository/ExportJobRepository.kt`, `backend/.../export/job/repository/ExportJobRepositoryTest.kt`]
- depends-on: [1, 2]

**RED**: `ExportJobRepositoryTest`(Testcontainers) — insert(PENDING) / `claimForRun`(CAS: PENDING 또는 stale RUNNING만 1행, 동시 2호출 시 1성공) / `findStatus` / `updateProgress(id, percent, rowCount)` / `markCompleted(id, objectKey, expiresAt)` CAS / `markFailed(id, errorCode)` CAS / `findByIdForRequester(id, userId)` 소유권(타인 null) / `findExpired(now)` / `deleteById`. → 메서드 없음 fail.

**GREEN**: jOOQ DSL 구현. `claimForRun`은 단일 UPDATE affected-rows(advisory-lock-bigint-TOCTOU 교훈 — 조회후UPDATE 금지). markCompleted/Failed는 `WHERE status='RUNNING'` 조건부 CAS. Clock 주입(시각 의존).

**REFACTOR**: SQL 상수 추출 + KDoc(CAS 단일성 근거).

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobRepositoryTest"`

### Task 4. ExportJobEnqueuePublisher (pgmq outbox)

**메타**.
- agent: `backend-engineer`
- files: [`backend/.../export/job/event/ExportJobEnqueuePublisher.kt`, `backend/.../export/job/event/ExportJobEnqueuePublisherTest.kt`]
- depends-on: [1, 2]

**RED**: `ExportJobEnqueuePublisherTest`(Testcontainers) — (a) 트랜잭션 안에서 `enqueue(id)` 호출 시 `q_export_jobs`에 `{"exportJobId":"<UUID>"}` 메시지 1건, (b) 트랜잭션 없이 호출 시 `IllegalTransactionStateException`(MANDATORY). → 클래스 없음 fail.

**GREEN**: `BulkOperationEnqueuePublisher` 미러 — `@Transactional(propagation=MANDATORY)` + `pgmq.send(?, ?::jsonb)` 바인딩(문자열 결합 금지). QUEUE_NAME="q_export_jobs".

**REFACTOR**: KDoc(outbox 패턴 + MANDATORY 근거 DATA.md §7.2).

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobEnqueuePublisherTest"`

### Task 5. MinIO 저장 어댑터 (search 모듈 자체)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/build.gradle.kts`, `backend/.../export/job/storage/ExportObjectStoragePort.kt`, `backend/.../export/job/storage/MinioExportStorageConfig.kt`, `backend/.../export/job/storage/MinioExportStorageAdapter.kt`, `backend/.../export/job/storage/MinioExportStorageException.kt`, `backend/.../export/job/storage/MinioExportStorageAdapterTest.kt`]
- depends-on: []

**RED**: `MinioExportStorageAdapterTest` — mock `MinioClient`로 (a) `put(key, inputStream, size, contentType)` → `putObject` 호출 인자 capture, (b) `openStream(key)` → `getObject`, (c) `remove(key)` → `removeObject`, (d) 실패 시 `MinioExportStorageException` 변환. → 클래스 없음 fail.

**GREEN**: build.gradle.kts에 `io.minio:minio` 추가(issue-tracking 버전 일치). `ExportObjectStoragePort` 인터페이스 + `MinioExportStorageConfig`(MinioClient 빈, `bts.minio.*` 바인딩 재사용, bucket 기본 `bts-exports`, best-effort ensureBucket) + `MinioExportStorageAdapter`(MinioStorageAdapter 미러). key 패턴 `{projectKey}/{jobId}.{ext}`.

**REFACTOR**: KDoc(BC 격리 — issue-tracking과 별도 클라이언트/버킷, ADR §D4) + endpoint fail-fast.

**검증**: `./gradlew :modules:search-export-import:test --tests "*MinioExportStorageAdapterTest"`

### Task 6. 스트리밍 직렬화 (CSV/XLSX SXSSF → 임시파일)

**메타**.
- agent: `backend-engineer`
- files: [`backend/.../export/job/serialize/StreamingExportSerializer.kt`, `backend/.../export/job/serialize/StreamingExportSerializerTest.kt`]
- depends-on: []

**RED**: `StreamingExportSerializerTest` — (a) CSV: `serialize(format=CSV, columns, hitsBatches)` → 임시파일에 UTF-8 BOM + 헤더 + 전체 행(RFC 4180), (b) XLSX: SXSSFWorkbook로 임시파일, POI로 재로딩해 행 수 검증, (c) `ExportCellSanitizer` 적용(`=`로 시작하는 셀 prefix), (d) 빈 배치(0행) → 헤더만, (e) 윈도우 메모리 상수(여러 배치 append 후에도 SXSSF dispose 호출). → 클래스 없음 fail.

**GREEN**: `StreamingExportSerializer` — 페이지 배치 단위로 받아 `OutputStream`/`SXSSFWorkbook`에 append. FR-EX-01 `ExportColumn`/`ExportCellSanitizer` 재사용(CSV 이스케이프 로직은 CsvExportWriter에서 행 단위 함수 추출 또는 재구현). 결과는 `java.io.File`(createTempFile) 반환. SXSSF는 `dispose()`로 임시 백킹파일 정리.

**REFACTOR**: 헤더/행 write 헬퍼 분리 + KDoc(메모리 상수 보장).

**검증**: `./gradlew :modules:search-export-import:test --tests "*StreamingExportSerializerTest"`

### Task 7. ExportJobService (접수 — AQL 검증 + 영속 + enqueue)

**메타**.
- agent: `backend-engineer`
- files: [`backend/.../export/job/application/ExportJobService.kt`, `backend/.../export/job/application/ExportJobService Test.kt`]
- depends-on: [2, 3, 4]

**RED**: `ExportJobServiceTest`(mockk repo/enqueue) — (a) `submit(request, requesterUserId)`가 AQL 파싱 성공 시 ExportJob PENDING 영속 + `enqueue` 호출 + jobId 반환, (b) AQL 문법 오류 시 `AqlSyntaxException` 전파(job 미생성 — repo.insert 미호출 verify), (c) format/projectKey 수동 검증(FR-EX-01 패턴), (d) 영속+enqueue가 `@Transactional` 단일 경계. → 클래스 없음 fail.

**GREEN**: `ExportJobService` — AqlLexer/AqlParser로 선검증, `ExportColumn.parse`, ExportRequest 수동 검증, `@Transactional`로 insert + enqueue 묶음.

**REFACTOR**: 검증 헬퍼 추출 + KDoc.

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobServiceTest"`

### Task 8. ExportJobProcessor (worker 처리 로직)

**메타**.
- agent: `backend-engineer`
- files: [`backend/.../export/job/application/ExportJobProcessor.kt`, `backend/.../export/job/application/ExportJobProcessorTest.kt`]
- depends-on: [3, 5, 6]

**RED**: `ExportJobProcessorTest`(mockk port/repo/storage/serializer) — (a) count-first total≤10만: 페이지 순회 → serializer → storage.put → `markCompleted`(objectKey + expiresAt=now+24h) + progress 갱신, (b) total>10만: serializer/storage 미호출 + `markFailed(LIMIT_EXCEEDED)`, (c) storage 실패: `markFailed(STORAGE_ERROR)` + 임시파일 정리, (d) viewerUserId = job.requesterUserId로 IssueSearchPort 호출(보안 상속), (e) 빈 결과 progress=100. → 클래스 없음 fail.

**⚠️ BLOCKER-2 (eng 리뷰) — process() @Transactional 경계**: process()는 1000페이지 조회 + MinIO 업로드로 분 단위 소요. `@Transactional`을 붙이면 그동안 DB 커넥션 점유 → 동시 export 5건 시 커넥션 풀 고갈. **process()는 @Transactional 의도적 생략**(FR-AC-01 "100MB I/O @Transactional 밖" + ExportService 선례). 상태 변경(updateProgress/markCompleted/markFailed)은 **Repository 메서드 단독 @Transactional**로 처리.

**CONCERN-2 — Completer Bean 불필요**: BulkOperation은 markCompleted+이벤트발행을 Completer 단일 트랜잭션으로 묶지만, **ExportJob은 완료 이벤트 발행이 없다**. 따라서 Completer Bean 분리 불필요 — markCompleted는 Repository 단독 @Transactional로 충분. (BulkOperation 1:1 복제 시 불필요한 Completer 추가 금지.)

**GREEN**: `ExportJobProcessor.process(jobId)` — repo에서 job 로드 → IssueSearchPort count-first → 상한 체크 → StreamingExportSerializer(페이지 콜백) → storage.put → repo.markCompleted. **process() 자체에 @Transactional 없음**(KDoc로 의도 명시). Clock 주입(expiresAt=now+24h). 예외 분류(limit/storage/generic) → repo.markFailed + errorCode.

**REFACTOR**: 진행률 갱신 throttle(매 페이지) + KDoc(보안 상속 경로 + @Transactional 생략 근거 — codereview rule 9 false-positive).

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobProcessorTest"`

### Task 9. ExportJobWorker (pgmq consumer) + ExportJobCleanupWorker (TTL)

**메타**.
- agent: `backend-engineer`
- files: [`backend/.../export/job/worker/ExportJobWorker.kt`, `backend/.../export/job/worker/ExportJobCleanupWorker.kt`, `backend/.../export/job/worker/ExportJobWorkerTest.kt`, `backend/.../export/job/worker/ExportJobCleanupWorkerTest.kt`, `backend/modules/search-export-import/src/main/resources/application*.yml`(스케줄 프로퍼티)]
- depends-on: [3, 8]

**RED**:
- `ExportJobWorkerTest`(mockk) — `pgmq.read` 결과에 대해 claimForRun true → processor.process → pgmq.delete. claimForRun false + 종단상태 → delete(무한재전달 차단). poison(파싱불가/job없음) read_ct>MAX → archive. 예외 시 delete 생략. `@Transactional` 없음.
- `ExportJobCleanupWorkerTest`(Testcontainers 또는 mockk) — `findExpired(now)` 각 job에 대해 storage.remove(objectKey) + repo.deleteById. Clock.fixed로 time-bomb 방지.

**CONCERN-5 — VT 과소평가 금지**: BulkOperation `VISIBILITY_TIMEOUT_SECONDS=60`을 그대로 미러하면 안 됨. export 10만행 = 1000페이지×~50ms(50초) + 직렬화 + MinIO 업로드 ≈ 2~3분. 60초면 처리 중 job이 재전달된다. **VT 최소 600초(10분)** 산정 + 근거 주석.

**CONCERN-3 — stuck job 누수**: `expires_at`은 COMPLETED에서만 설정되므로 PENDING/RUNNING에서 멈춘 job(worker 크래시 + pgmq 메시지 소실)은 cleanup에 안 걸린다. MVP 허용(엣지 케이스 §에 명시) — 별도 stuck 정리는 후속.

**GREEN**: `ExportJobWorker`(@Scheduled, BulkOperationWorker 1:1 미러 — QUEUE_NAME/POLL_BATCH/MAX_RECEIVE_COUNT, **단 VT=600초**) + `ExportJobCleanupWorker`(@Scheduled 일배치, `findExpired(now)` → storage.remove + repo.deleteById, Clock 주입).

**REFACTOR**: 공통 상수 companion + KDoc(at-least-once 멱등 + dead-letter + VT 600초 산정 근거 + stuck job MVP 미정리 명시).

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobWorkerTest" --tests "*ExportJobCleanupWorkerTest"`

### Task 10. ExportJobController + ExceptionHandler + SearchErrorCodes 확장

**메타**.
- agent: `backend-engineer` (권한 가드는 security-engineer 검토 대상 — codereview에서)
- files: [`backend/.../search/web/ExportJobController.kt`, `backend/.../search/web/ExportJobExceptionHandler.kt`, `backend/.../search/web/SearchErrorCodes.kt`, `backend/.../search/web/dto/ExportJobResponse.kt`, `backend/.../search/web/ExportJobControllerTest.kt`]
- depends-on: [5, 7]

**RED**: `ExportJobControllerTest`(MockMvc 슬라이스) — (a) `POST /search/export-jobs` → 202 + jobId(service mock), (b) AQL 오류 → 400(SEARCH_SYNTAX_ERROR), (c) `GET .../{id}` 본인 → 200 폴링 DTO / 타인 → 404, (d) `GET .../{id}/download` COMPLETED 본인 → 200 + Content-Disposition + storage 스트림 / 미완료 → 409(SEARCH_EXPORT_NOT_READY) / 타인 → 404, (e) actor는 SecurityContext에서 추출(컨트롤러, 위조 차단 — FR-BD-01 교훈), (f) **CONCERN-6 — Content-Disposition 인젝션 방어**: projectKey=`"evil\r\nX-Inject: hdr"` → 400 또는 sanitize(헤더 분리 차단, FR-EX-01 영숫자+하이픈 패턴 검증). → 클래스 없음 fail.

**GREEN**: `ExportJobController`(actor 추출 → service/repo 위임, 소유권 findByIdForRequester로 404 은닉, download는 storage.openStream 프록시 + Content-Disposition 인젝션 방어 projectKey 패턴 검증) + `ExportJobExceptionHandler`(assignableTypes=[ExportJobController], 전용 — ExportController/SearchController 오염 차단) + `SearchErrorCodes`에 `SEARCH_EXPORT_NOT_READY`/`SEARCH_EXPORT_STORAGE_ERROR` 추가(LIMIT은 기존 재사용).

**REFACTOR**: DTO 매핑 헬퍼 + KDoc(소유권 404 은닉 근거).

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobControllerTest"`

### Task 11. end-to-end 통합 테스트 (접수→worker→MinIO→다운로드→cleanup)

**메타**.
- agent: `backend-engineer`
- files: [`backend/.../export/job/ExportJobEndToEndIntegrationTest.kt`]
- depends-on: [9, 10]

**RED**: `ExportJobEndToEndIntegrationTest`(Testcontainers **Tembo PostgreSQL + MinIO 두 컨테이너**, fake IssueSearchPort with 1만+ 시드 hits) — (a) submit → worker poll 처리 → MinIO 객체 존재 → 폴링 COMPLETED → download 바이트 행 수 = 시드 수 → cleanup(Clock.fixed 만료) 후 DB+MinIO 삭제, (b) 상한 초과(>10만) → FAILED, (c) **CONCERN-4 — at-least-once 멱등**: job을 RUNNING으로 강제 update → `worker.pollAndProcess()` 재호출 → `claimForRun`이 stale RUNNING 재진입 → 결과 파일 덮어쓰기 + COMPLETED 마킹(실 DB로 stale RUNNING 분기 검증 — 단위 mock 불가, advisory-lock-bigint-TOCTOU 교훈). → 컴포넌트 미완성 fail.

**GREEN**: 위 컴포넌트 조립으로 통과. fake port는 메모리 페이지네이션(visibility 데이터 제외는 FR-SR-02 실증 인용 — search 모듈에 실 IssueSearchAdapter 없음, FR-EX-01 ADR §B1 vacuous 회피). MinIO 컨테이너는 `MINIO_ROOT_USER/PASSWORD` env + 9000 포트 노출, `bts.minio.*` 프로퍼티 동적 주입.

**REFACTOR**: 시드 헬퍼 추출 + KDoc(실 어댑터 부재 → 매핑층 검증 한계 명시) + `SearchBcArchTest.searchProductionClassCountIsAtLeastOne` minimumCount를 ExportJob 클래스 추가 후 실 클래스 수로 상향(vacuous 가드 해소).

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportJobEndToEndIntegrationTest"` + 모듈 전체 `:modules:search-export-import:test :modules:search-export-import:detekt :modules:search-export-import:ktlintCheck --rerun-tasks`(false-green 차단, 교훈 backend-detekt-lint-debt-unmasked)

## Plan 메타

- task 수: 11 (각 TDD 사이클)
- wave 예상 (depends-on + files 교집합 기반):
  - W1: T1(마이그레이션) · T2(도메인) · T5(MinIO) · T6(serializer) — 의존 0, 파일 무겹침
  - W2: T3(repo: 1,2) · T4(enqueue: 1,2)
  - W3: T7(service: 2,3,4) · T8(processor: 3,5,6)
  - W4: T9(worker: 3,8) · T10(controller: 5,7)
  - W5: T11(e2e: 9,10)
- 예상 시간: 직렬 ~40분, 5-wave 병렬 ~12분
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 추가 검증: detekt/ktlint --rerun-tasks (false-green 차단), ArchUnit BC 격리(search 모듈 jOOQ는 com.bts.search..jooq 한정), init_codegen 미러
- 신규 의존성: io.minio (FR-AC-01 기승인, 신규 모듈 추가). poi-ooxml SXSSF (FR-EX-01 기도입 아티팩트)

## 리뷰 결과

### 독립 eng plan 리뷰 (backend-engineer subagent, 2026-06-29)

실제 코드(SearchPersistenceTestBase, issue-tracking build.gradle, V002, ExportService, BulkOperationWorker) 인용 기반 adversarial 리뷰. **BLOCKER 2건 + CONCERN 6건 모두 plan에 반영 완료** (Maxi taste 결정 불요 — 기술적 수정).

**BLOCKER (해소 완료)**.
- **B1. pgmq Docker 이미지 불일치** → Task 1: 통합테스트 3곳 이미지 Tembo로 변경 + init_codegen은 CREATE TABLE만(pgmq 제외) + V602엔 EXTENSION+create. 잘못된 "IF NOT EXISTS 방어" 분석 정정.
- **B2. process() @Transactional 경계** → Task 8: process() 트랜잭션 의도적 생략(분 단위 I/O 커넥션 점유 차단) + 상태변경만 Repository 단독 @Transactional.

**CONCERN (해소 완료)**.
- C1. init_codegen pgmq.create 제외 (→ Task 1)
- C2. Completer Bean 불필요 명시 (→ Task 8, 완료 이벤트 발행 없음)
- C3. stuck PENDING/RUNNING cleanup 누수 MVP 허용 (→ Task 9 + 엣지)
- C4. E2E at-least-once 멱등 재처리 시나리오 추가 (→ Task 11)
- C5. VT 60초 미러 금지, 600초 이상 (→ Task 9)
- C6. Content-Disposition 인젝션 방어 테스트 (→ Task 10)

**주의 (반영)**.
- W1 파일 충돌: T1(테스트베이스 이미지) vs T5(build.gradle io.minio) — **다른 파일이라 충돌 없음**(확인 완료). 단 둘 다 모듈 빌드 영향이라 W2 진입 전 컴파일 1회 확인.
- ArchUnit 카운트 가드 상향 (→ Task 11 REFACTOR)
- MinIO Testcontainer 패턴 명시 (→ Task 11)

**통과 확인**. TDD RED 진짜 실패 · jOOQ ? 바인딩 · MANDATORY outbox(T4) · 비소유 404 은닉(T10) · depends-on 순환 없음 · detekt/ktlint --rerun-tasks.

**BLOCKER: 모두 해소됨 (게이트 1 진입 가능).**
