<!-- FR-EX-02 대용량 비동기 Export의 BC 경계 + 비동기 job 아키텍처 + MinIO 결과 저장 + TTL 결정 ADR -->

# ADR — FR-EX-02 대용량(>1만건) 비동기 Export: pgmq job + ExportJob 영속 + MinIO 결과 저장

- 날짜: 2026-06-29
- 상태: 제안 (게이트 1 승인 대기)
- 관련 FR: FR-EX-02
- BC: search-export-import
- 선행 ADR: [2026-06-29-fr-ex-01-csv-xlsx-export.md](2026-06-29-fr-ex-01-csv-xlsx-export.md), [../adr/2026-06-02-bulk-operation-async-architecture.md](../adr/2026-06-02-bulk-operation-async-architecture.md), [2026-06-15-clamav-attachment-virus-scan.md](2026-06-15-clamav-attachment-virus-scan.md)

## 맥락 (Context)

FR-EX-01(PR #203)이 동기 Export(`POST /api/v1/search/export`, 1만 행 상한, 200-파일 즉시 응답)를 구현했다.
FR-EX-01 ADR §D5는 1만건 초과를 FR-EX-02 비동기로 분리하고, 엔드포인트는 `POST /api/v1/search/export-jobs`(202 + job 리소스),
영속 테이블 `export_jobs`를 예고했다. product §3.2 D단계는 ExportJob 도메인, 큐 + 진행률 + 결과 URL TTL,
`export_jobs(status, progress, result_minio_key, expires_at)`, pgmq job + 백그라운드 worker, 1만건 시나리오 테스트를 명세한다.

코드베이스 조사 결과.

1. **비동기 job 패턴 선례 — BulkOperation(FR-IS-05, issue-tracking).** `bulk/{domain,repository,application,worker,event,web}` 구조가
   pgmq consumer 패턴을 완성형으로 보유한다. `BulkOperationWorker`(@Scheduled 폴링 + `pgmq.read` + 작업레벨 CAS `claimForRun` +
   dead-letter archive + outbox), `BulkOperationEnqueuePublisher`(Propagation.MANDATORY로 영속과 동일 트랜잭션 enqueue),
   `BulkOperationCleanupWorker`(완료 N일 후 하드삭제 + Clock 주입), `BulkOperationStatus`(PENDING→RUNNING→COMPLETED/FAILED 종단).
2. **Export 직렬화 자산 — FR-EX-01(search-export-import).** `ExportService`(count-first 페이지 순회 + writer 디스패치),
   `CsvExportWriter`/`XlsxExportWriter`(`OutputStream`에 write), `ExportColumn`/`ExportFormat`, `ExportCellSanitizer`(formula injection 방어 공용),
   `IssueSearchPort` 재사용(BROWSE 권한 게이트 + visibility 보안 술어 구조적 상속).
3. **MinIO는 issue-tracking 전용.** `MinioStorageConfig`(MinioClient 빈 + `bts.minio.*` + bucket 자동 생성 + best-effort 기동) /
   `MinioStorageAdapter`가 issue-tracking에만 있다. search-export-import의 build.gradle.kts에는 `io.minio` 의존성이 **없다**.
   프로젝트는 cross-BC 배포 조립이 없고 test-assembled가 표준(no-cross-bc-deployment-assembly)이라 모듈 간 빈 공유가 불가하다.
4. **search 모듈 마이그레이션 대역 V600~V699.** 현재 V600(saved_filters)/V601(saved_filter_shares). 다음 V602.
   `db/codegen/init_codegen.sql`이 jOOQ codegen 입력이므로 신규 테이블 컬럼/제약을 여기에 미러해야 한다(jooq-init_codegen-mirror).

## 결정 (Decision)

### D1. 구현 BC — search-export-import 모듈 (BC 신설 없음, FR-EX-01 동일 본거지)

ExportJob 영속·pgmq worker·결과 저장을 모두 **search-export-import 모듈**에 둔다. FR-EX-01 export 패키지와 같은 본거지.
issue-tracking·shared-kernel은 변경하지 않는다(cross-BC 변경 0, FR-EX-01 §D1과 동일).

### D2. 접수/조회 계약 — 202 + job 리소스 폴링

- `POST /api/v1/search/export-jobs` — 요청 바디 = FR-EX-01 `ExportRequest`와 동형(`{ projectKey, query(AQL), format, columns? }`).
  **202 Accepted** + `{ jobId, status: PENDING }` 반환(동기 200-파일과 의미 분리, FR-EX-01 §D2 예고대로).
- `GET /api/v1/search/export-jobs/{jobId}` — 진행률 폴링. `{ jobId, status, progress, rowCount?, error?, downloadReady }` 반환.
- 결과 다운로드 엔드포인트는 D6에서 결정.
- 접수 시 ExportJob을 PENDING으로 영속 + `ExportJobEnqueuePublisher.enqueue`(MANDATORY, outbox)를 **단일 트랜잭션**으로 묶는다.

### D3. ExportJob 영속 + pgmq 큐 — BulkOperation 패턴 복제

- `export_jobs(id, project_key, query, format, columns, requester_user_id, status, progress, row_count, result_object_key, error_code, expires_at, created_at, started_at, completed_at)` — V602. init_codegen.sql 미러.
- pgmq 큐 `q_export_jobs` — V602에서 `pgmq.create`. 페이로드 `{"exportJobId":"<UUID>"}`.
- `ExportJobWorker`(@Scheduled 폴링) — `pgmq.read` → 작업레벨 CAS `claimForRun`(TOCTOU 회피, status='PENDING' OR stale RUNNING) →
  처리 → 종단 markCompleted/markFailed CAS → `pgmq.delete`. 예외 시 delete 생략(at-least-once 재전달). dead-letter는 `read_ct > MAX_RECEIVE_COUNT` 시 `pgmq.archive`.
- 워커에 `@Transactional` 없음(BulkOperationWorker 교훈 — REQUIRES_NEW 전파 충돌 회피).

### D4. 결과 저장 — search 모듈 자체 MinIO 클라이언트 (BC 격리)

search-export-import 모듈에 `io.minio` 의존성을 추가하고 **자체 `MinioClient` 빈 + 설정**을 둔다(issue-tracking `MinioStorageConfig` 패턴 복제).

- bucket은 첨부와 분리한 **`bts-exports`**(기본값). prefix는 `{projectKey}/{jobId}.{ext}`.
- best-effort 기동(MinIO 미가용 시 앱 부팅 차단 금지, 업로드 시점 예외) — `MinioStorageConfig` 동형.
- **shared-kernel `ObjectStoragePort` 추출은 기각.** MinIO는 이미 공유 인프라(객체 저장소)라 각 BC가 자기 버킷으로 자체 클라이언트를 갖는 것이 자연스럽다.
  포트 추출은 issue-tracking 어댑터를 cross-BC로 호출하게 만들어 BC 격리를 깨고 배포 조립(부재)을 강제한다.
- `io.minio`는 FR-AC-01에서 이미 도입·승인된 의존성이다(신규 라이브러리 아님 — 신규 모듈에 추가만).

### D5. 결과 보존 TTL — 하드 삭제 (BulkOperation 하드삭제 근거 동형)

`export_jobs.expires_at` 경과 시 `ExportJobCleanupWorker`(@Scheduled 일배치, Clock 주입)가 **DB 레코드 + MinIO 객체를 하드 삭제**한다.

- Export 결과는 영구 이력이 아니라 일시적 운영 부산물(다운로드 후 폐기)이므로 소프트 삭제(`deleted_at`) 불요(bulk-operation ADR §하드삭제 동형, DATA.md §3 TTL 만료 GC).
- TTL 기본 기간(예: 24시간/7일)은 spec D2에서 확정.

### D6. 권한/보안 — 검색 불변식 상속 + job 소유권

- 처리 worker가 FR-EX-01 `ExportService`(또는 그 writer 경로)를 재사용하므로 `IssueSearchPort`의 BROWSE 권한 게이트 + visibility 술어가 그대로 적용된다(export 전용 우회 경로 없음, FR-EX-01 §D6).
- 접수 시점의 `requester_user_id`를 영속하고, **조회/다운로드는 본인 job만 허용**(타인 jobId 추측 차단 — 비소유 404로 존재 은닉). 다운로드 방식(presigned URL vs 자체 스트리밍 엔드포인트)은 spec D2에서 확정.
- Content-Disposition 파일명/projectKey 인젝션 방어는 FR-EX-01 패턴 재사용.

### D7. 직렬화/상한 — 비동기 행 상한 + 스트리밍 전략은 spec D2

- 비동기 행 상한(>1만의 상위 경계, 예: 10만/50만)과 메모리 적재(현 `ExportService`는 `ByteArrayOutputStream` 전체 적재) vs 스트리밍(XLSX `SXSSFWorkbook` + MinIO 스트리밍 업로드) 전략은 **spec D2에서 확정**한다.
  FR-EX-01 ADR §D4가 "스트리밍 SXSSF는 대용량 FR-EX-02에서 검토"로 명시했다.
- 진행률(`progress`)은 페이지 순회 중 `처리행/총행`으로 갱신(폴링 노출).

## 결과 (Consequences)

- search-export-import 모듈에 `export/job` 하위 패키지 신설(ExportJob 도메인/Status/Repository, ExportJobService 접수, ExportJobWorker, ExportJobEnqueuePublisher, ExportJobCleanupWorker, ExportJobController + 전용 ExceptionHandler).
- build.gradle.kts에 `io.minio` 추가(search 모듈 첫 MinIO). V602 마이그레이션(export_jobs + q_export_jobs) + init_codegen.sql 미러 + jOOQ codegen 재생성.
- issue-tracking·shared-kernel 무변경. `IssueSearchPort`·FR-EX-01 writer 재사용.
- fr-index/SDD의 FR-EX-02 BC 매핑(search-export-import)·카운트 무변경.
- **범위 분리**: 이번 PR = 백엔드 D1~D5. 프론트 D6/D7(진행률/다운로드 UI + E2E)은 별도 PR(Maxi 게이트 확정 2026-06-29).
- glossary에 "ExportJob(Export 작업)" 추가.

## 대안 (Rejected)

- **shared-kernel ObjectStoragePort 추출 후 issue-tracking MinioStorageAdapter 재사용.** 기각 — cross-BC 호출 + 배포 조립(부재) 강제 + BC 격리 위반. MinIO는 공유 인프라라 각 BC 자체 클라이언트가 자연스럽다.
- **결과를 DB(bytea)에 저장.** 기각 — 대용량(>1만건) 파일을 RDB에 적재하면 테이블 비대·메모리 압박. 객체 저장소가 정석.
- **동기 export 상한만 올림(비동기 미도입).** 기각 — product가 비동기를 명시. 대용량은 요청 타임아웃·메모리 위험(bulk-operation ADR §기각 A 동형).
- **별도 export 큐 BC/모듈 신설.** 기각 — search-export-import가 Export의 의도된 본거지(FR-SR-02/FR-EX-01 ADR).
- **grill-with-docs 대화형 도메인 검증.** 생략 — BulkOperation 선례와 1:1 매핑, 새 용어 1개(ExportJob)로 도메인이 명확. 명확한 백엔드 작업엔 대화형 스킬이 과함(메모리: bts-spec office-hours mismatch, bts-review-plan autoplan overkill). 코드 근거 직접 정리로 대체하고 게이트 1에서 일괄 검토.
