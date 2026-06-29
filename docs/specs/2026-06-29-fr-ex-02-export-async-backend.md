<!-- FR-EX-02 대용량 비동기 Export 백엔드 명세 — 사용자 시나리오·FR/NFR·API·데이터 모델·엣지 케이스 -->

# FR-EX-02 — 대용량(>1만건) 비동기 Export (백엔드 D1~D5) — 스펙

> slug: fr-ex-02-export-async-backend
> 관련 ADR: [docs/decisions/2026-06-29-fr-ex-02-async-export-jobs.md](../decisions/2026-06-29-fr-ex-02-async-export-jobs.md)
> 범위: 백엔드 D1~D5 (프론트 D6/D7 별도 PR)

## 결정 요약 (Maxi 게이트, 2026-06-29)

| 결정 항목 | 채택 | 근거 |
|---|---|---|
| 직렬화 전략 | **스트리밍** (CSV=OutputStream 직접 / XLSX=SXSSFWorkbook) | 대용량 OOM 방지, 메모리 상수 |
| 행 상한 | **10만행** | 사내 1,000명 규모 충분. 초과 시 FAILED |
| 다운로드 | **자체 엔드포인트(프록시)** | 권한 일관(본인만) + MinIO 비노출 |
| TTL | **24시간** | 결과 파일 빠른 정리, 즉시 다운 가정 |
| 진행률 | 페이지(100행)마다 `progress` 갱신 | 폴링 노출 |

## 사용자 시나리오 (Given-When-Then)

**S1. 대용량 Export 접수**
- Given: 사용자가 AQL 쿼리로 5만 건 매칭되는 검색을 했고, 동기 Export(1만 상한)로는 거부됨
- When: `POST /api/v1/search/export-jobs`로 같은 AQL + format을 제출
- Then: 즉시 202 + `{ jobId, status: PENDING }` 수신 (대기 없음). 백그라운드 worker가 처리 시작

**S2. 진행률 폴링**
- Given: 접수된 jobId가 RUNNING 상태로 처리 중
- When: 사용자가 `GET /api/v1/search/export-jobs/{jobId}`를 주기 폴링
- Then: `{ status: RUNNING, progress: 32, rowCount: 50000, downloadReady: false }` — progress가 페이지 처리마다 증가

**S3. 완료 후 다운로드**
- Given: worker가 결과 파일을 MinIO에 저장하고 job을 COMPLETED로 마킹
- When: 폴링이 `{ status: COMPLETED, downloadReady: true }`를 반환 → 사용자가 `GET /api/v1/search/export-jobs/{jobId}/download` 호출
- Then: Content-Disposition 헤더 + 파일 바이트 스트리밍 (MinIO에서 프록시)

**S4. 상한 초과**
- Given: AQL이 12만 건(>10만 상한) 매칭
- When: worker가 count-first로 총 건수 확인
- Then: 추가 처리 없이 job을 FAILED + `errorCode: SEARCH_EXPORT_LIMIT_EXCEEDED`로 마킹. 폴링이 error 노출

**S5. 타인 job 접근 차단**
- Given: 사용자 A가 만든 jobId를 사용자 B가 추측
- When: B가 `GET .../export-jobs/{A의 jobId}` 또는 `/download` 호출
- Then: 404 Not Found (존재 은닉 — 소유권 위반을 403이 아닌 404로 숨김)

**S6. TTL 만료 정리**
- Given: COMPLETED job의 `expires_at`(완료 +24h)이 경과
- When: `ExportJobCleanupWorker` 일배치 실행
- Then: export_jobs 레코드 + MinIO 객체 하드 삭제. 이후 다운로드 시 404

## 기능 요구사항 (FR)

- **FR-1. 접수**: `POST /api/v1/search/export-jobs`는 FR-EX-01 `ExportRequest`와 동형 입력(`{ projectKey, query, format, columns? }`)을 받아 ExportJob을 PENDING으로 영속하고 202 + jobId를 반환한다. ExportJob 영속과 `pgmq.send`(enqueue)는 **단일 트랜잭션**(outbox).
- **FR-2. AQL 선검증**: 접수 시점에 AQL을 파싱해 문법 오류면 **400으로 즉시 거부**(job 미생성). UX상 빠른 피드백 + worker 무익한 실패 방지.
- **FR-3. 워커 처리**: `ExportJobWorker`가 `q_export_jobs`를 @Scheduled 폴링 → 작업레벨 CAS `claimForRun`(PENDING 또는 stale RUNNING) → count-first로 총 건수 확인 → 페이지 순회 스트리밍 직렬화 → MinIO 업로드 → COMPLETED 마킹 → `pgmq.delete`.
- **FR-4. 스트리밍 직렬화**: CSV는 페이지별 행을 `OutputStream`에 직접 append(헤더 1회). XLSX는 `SXSSFWorkbook`(rowAccessWindow=100)으로 윈도우 행만 메모리 유지. 직렬화 결과는 **임시 파일** 경유로 MinIO `putObject`(파일 크기 확정 → OOM 0). 업로드 후 임시 파일 정리(finally). **FR-EX-01 `ExportService.export()`는 전체 결과를 `ByteArrayOutputStream`에 적재하는 메모리 경로라 스트리밍에 재사용 불가** — 신규 스트리밍 직렬화 컴포넌트를 만들고, FR-EX-01의 `CsvExportWriter`/`XlsxExportWriter`(행 단위 write 진입점 필요 시 분리)·`ExportColumn`·`ExportCellSanitizer`·`IssueSearchPort`만 재사용한다.
- **FR-5. 진행률**: count-first로 확정한 `row_count`를 기준으로 페이지(100행) 처리마다 `progress`(0~100 %)를 UPDATE. 폴링으로 노출. **빈 결과(row_count=0)는 `progress=100` 즉시 설정**(0/0 division 회피) + 헤더만 있는 파일 생성.
- **FR-6. 상한**: 총 건수 > 10만이면 직렬화 없이 FAILED + `SEARCH_EXPORT_LIMIT_EXCEEDED`. (동기 1만 → 비동기 10만, 두 FR의 자연 경계)
- **FR-7. 폴링 조회**: `GET .../export-jobs/{jobId}`는 `{ jobId, status, progress, rowCount?, format, errorCode?, downloadReady }` 반환. **본인 job만**(비소유 404).
- **FR-8. 다운로드**: `GET .../export-jobs/{jobId}/download`는 COMPLETED + 본인 job일 때만 MinIO에서 읽어 Content-Disposition + 스트리밍. 미완료=409, 비소유/없음=404.
- **FR-9. TTL 정리**: `ExportJobCleanupWorker`(@Scheduled 일배치, Clock 주입)가 `expires_at < now`인 job의 DB 레코드 + MinIO 객체를 하드 삭제.
- **FR-10. 보안 상속**: worker가 `IssueSearchPort`를 재사용하므로 BROWSE 권한 게이트 + visibility 술어가 자동 적용(export 전용 우회 경로 없음). 접수자 `requester_user_id`를 viewer로 검색.

## 비기능 요구사항 (NFR)

- **NFR-1 메모리**: 직렬화 메모리는 행 수와 무관한 상수(스트리밍 윈도우 + 임시 파일). 10만행에서도 OOM 없음.
- **NFR-2 멱등**: at-least-once 재전달에 안전. `claimForRun` CAS + 종단 상태 CAS + 같은 `result_object_key` 덮어쓰기로 재처리 무해.
- **NFR-3 격리**: 워커에 `@Transactional` 없음(REQUIRES_NEW 전파 충돌 회피). 항목 처리는 검색 포트가 자체 트랜잭션 관리.
- **NFR-4 dead-letter**: poison 메시지(파싱 불가/job 없음)는 `read_ct > MAX_RECEIVE_COUNT` 시 `pgmq.archive`.
- **NFR-5 보안**: job 소유권 강제(본인만 조회/다운로드, 비소유 404). formula injection 방어(`ExportCellSanitizer` writer 상속). Content-Disposition projectKey/파일명 인젝션 방어(FR-EX-01 패턴). MinIO 외부 비노출(자체 프록시 다운로드).
- **NFR-6 best-effort 기동**: MinIO 미가용 시 앱 부팅 차단 금지(업로드 시점 예외 → 해당 job FAILED).
- **NFR-7 vt 산정**: visibility timeout = 10만행 처리 예산 기반 산정(plan에서 확정). 처리 중 크래시 시 재전달 윈도우 확보.

## API 인터페이스 (REST)

```
POST /api/v1/search/export-jobs
  body: { projectKey, query(AQL), format(CSV|XLSX), columns?(부분선택) }
  202 Accepted: { jobId, status: "PENDING" }
  400: AQL 문법 오류 / format·projectKey 검증 실패 (SEARCH_* errorCode)
  401: 미인증

GET /api/v1/search/export-jobs/{jobId}
  200: { jobId, status, progress, rowCount?, format, errorCode?, downloadReady }
  404: 비소유 또는 없음 (존재 은닉)
  401: 미인증

GET /api/v1/search/export-jobs/{jobId}/download
  200: <파일 바이트 스트리밍> + Content-Disposition: attachment; filename="..."
  404: 비소유 또는 없음
  409: 아직 COMPLETED 아님 (SEARCH_EXPORT_NOT_READY)
  410: TTL 만료 (선택 — 또는 404로 통일)
  401: 미인증
```

전용 `ExportJobExceptionHandler`(`@RestControllerAdvice(assignableTypes=[ExportJobController])`) — FR-EX-01 `ExportExceptionHandler` 패턴. limit(이건 worker라 job FAILED로, 접수 경로는 AQL 400)·not-ready(409)·소유권(404)·validation(400)·ResponseStatusException(401) 매핑. `SEARCH_` prefix 유지. ProblemDetail 봉투 재사용.

## 데이터 모델 변경

**V602__export_jobs.sql** (search-export-import, V600/V601 다음) + `init_codegen.sql` 미러 + jOOQ codegen 재생성.

```sql
-- pgmq 큐 (extension은 issue-tracking V002에서 생성됨 — 통합 순서 제약은 §제약 참조)
SELECT pgmq.create('q_export_jobs');

CREATE TABLE export_jobs (
    id                 UUID PRIMARY KEY,
    project_key        TEXT NOT NULL,
    query              TEXT NOT NULL,                 -- AQL 원문
    format             TEXT NOT NULL,                 -- CSV | XLSX
    columns            TEXT,                          -- 콤마 구분 컬럼 키, NULL=전체
    requester_user_id  UUID NOT NULL,
    status             TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING|RUNNING|COMPLETED|FAILED
    progress           INT  NOT NULL DEFAULT 0,        -- 0~100 (%)
    row_count          BIGINT,                        -- RUNNING에서 count-first로 확정
    result_object_key  TEXT,                          -- MinIO key, COMPLETED에서
    error_code         TEXT,                          -- FAILED에서 (SEARCH_*)
    expires_at         TIMESTAMPTZ,                   -- COMPLETED에서 now()+24h
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,                   -- RUNNING claim 시각
    completed_at       TIMESTAMPTZ,                   -- 종단 시각
    CONSTRAINT chk_export_jobs_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_export_jobs_format CHECK (format IN ('CSV','XLSX'))
);

-- 폴링/소유권 조회 인덱스
CREATE INDEX idx_export_jobs_requester ON export_jobs (requester_user_id, created_at DESC);
-- cleanup 스캔 인덱스
CREATE INDEX idx_export_jobs_expires ON export_jobs (expires_at) WHERE expires_at IS NOT NULL;
```

소프트 삭제(`deleted_at`) 없음 — TTL 하드삭제(ADR §D5, bulk-operation 동형, DATA.md §3).

## 엣지 케이스

- **빈 결과(0행)**: 헤더만 있는 파일 생성 → COMPLETED (정상). row_count=0.
- **AQL 문법 오류**: 접수 시 400 즉시 거부(job 미생성). worker 재파싱 불요.
- **상한 초과(>10만)**: worker count-first → FAILED + SEARCH_EXPORT_LIMIT_EXCEEDED (직렬화 안 함).
- **권한 없는 프로젝트**: IssueSearchPort가 visibility 필터 → 빈 결과 또는 SecurityException. job은 인증 사용자면 생성, 결과는 권한 필터됨.
- **worker 크래시(처리 중)**: vt 만료 후 재전달. claimForRun CAS(stale RUNNING 재진입) + 같은 key 덮어쓰기로 멱등.
- **MinIO 미가용**: 업로드 실패 → 해당 job FAILED + SEARCH_EXPORT_STORAGE_ERROR. 앱 부팅은 영향 없음(best-effort).
- **TTL 만료 후 다운로드**: cleanup이 삭제 → 404(또는 410).
- **poison 메시지**: parseOperationId null 또는 job 없음 → read_ct 초과 시 archive.
- **동시 같은 job 재전달**: claimForRun CAS로 단일 워커만 진입.
- **중복 접수(같은 AQL 연타)**: 각각 독립 job(멱등 키 없음). MVP 허용 — 사용자별 동시 job 제한은 후속.

## 제약 조건

- **BC 격리**: search 모듈 자체 MinioClient(io.minio 의존성 추가, bucket `bts-exports`). issue-tracking·shared-kernel 무변경. `IssueSearchPort`·FR-EX-01 writer 재사용.
- **pgmq extension 통합 순서**: `pgmq.create('q_export_jobs')`는 pgmq extension이 선설치돼야 한다(issue-tracking V002 `CREATE EXTENSION pgmq`). 멀티모듈 Flyway 실행 순서 의존 → plan/db-engineer가 통합 부팅에서 검증(필요 시 `CREATE EXTENSION IF NOT EXISTS pgmq` 방어).
- **신규 의존성**: `io.minio`(FR-AC-01 기승인, 신규 모듈 추가만). `org.apache.poi:poi-ooxml`(FR-EX-01 기도입) — SXSSF는 같은 아티팩트.
- **init_codegen 미러**: V602 컬럼/제약을 `db/codegen/init_codegen.sql`에 미러(jooq-init_codegen-mirror 회귀 방지).
- **완제품 기준**: PoC 금지. 절대 규칙(DEVELOPMENT.md §1) + 테스트 + 보안 + 에러 처리 만족.

## 측정 가능한 완료 기준

- [ ] `POST /search/export-jobs` → 202 + jobId, ExportJob PENDING 영속 + 큐 enqueue(단일 트랜잭션)
- [ ] AQL 문법 오류 → 400 (job 미생성)
- [ ] worker가 10만행 CSV/XLSX를 스트리밍 직렬화해 MinIO `bts-exports`에 저장 → COMPLETED
- [ ] 폴링 `GET .../{jobId}`가 progress 증가 + downloadReady 전이 노출
- [ ] `GET .../{jobId}/download`가 본인 job COMPLETED만 스트리밍, 미완료 409
- [ ] 총 10만 초과 → FAILED + SEARCH_EXPORT_LIMIT_EXCEEDED
- [ ] 비소유 jobId 조회/다운로드 → 404 (존재 은닉)
- [ ] `ExportJobCleanupWorker`가 expires_at 만료분 DB+MinIO 하드삭제
- [ ] 1만건+ 시나리오 통합 테스트(Testcontainers) — 스트리밍 메모리 상수 + 멱등 재처리
- [ ] ktlint + detekt + ArchUnit(BC 격리) 통과

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check, gap 4건 발견).
- G1. 빈 결과 division-by-zero → FR-5에 progress=100 즉시 보강
- G3. ExportService.export() 메모리 경로 재사용 불가 → FR-4에 신규 스트리밍 컴포넌트 명시 보강
- G2. 사용자별 동시 job 제한 없음 → MVP 후속 이연(엣지 케이스 §중복 접수 기록)
- G4. 신규 에러코드 3종(LIMIT_EXCEEDED/STORAGE_ERROR/NOT_READY) → plan에서 SearchErrorCodes 추가
- Maxi 결정 필요한 잔여 gap 없음 (동시 job 제한은 후속 이연이 합리적).
