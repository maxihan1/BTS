<!-- FR-IM-01 CSV/JSON Import의 BC 경계 + cross-BC 이슈 쓰기 포트 + 비동기 job + 트랜잭션 정책 + 사용자 매핑 결정 ADR -->

# ADR — FR-IM-01 CSV/JSON Import (Jira 마이그레이션): IssueImportPort cross-BC 쓰기 + pgmq job + 행별 best-effort

- 날짜: 2026-07-02
- 상태: 제안 (게이트 1 승인 대기)
- 관련 FR: FR-IM-01 (FR-IM-02 매핑 UI가 후속)
- BC: search-export-import
- 선행 ADR: [2026-06-29-fr-ex-02-async-export-jobs.md](2026-06-29-fr-ex-02-async-export-jobs.md), [2026-06-29-fr-ex-01-csv-xlsx-export.md](2026-06-29-fr-ex-01-csv-xlsx-export.md), [../adr/2026-06-01-issue-assignee-user-lookup-port.md](../adr/2026-06-01-issue-assignee-user-lookup-port.md), [../adr/2026-06-02-bulk-operation-async-architecture.md](../adr/2026-06-02-bulk-operation-async-architecture.md)

## 맥락 (Context)

product §4.1은 FR-IM-01을 ImportJob 도메인 · CSV/JSON 파싱 + 트랜잭션 정책 + dry-run · `import_jobs(status, error_log_minio_key)` · `POST /api/v1/imports` + 백그라운드 worker · Jira CSV 샘플 테스트로 명세한다. 선행은 issue-tracking §2~§3(이슈/컴포넌트/버전 작성 API) — 완료.

FR-IM-01은 FR-EX(Export)의 **역방향**이다. Export는 이슈를 **읽어** 파일로 내보내고, Import는 파일을 **파싱해 이슈를 생성**한다. 코드베이스 조사 결과.

1. **비동기 job 패턴 완성형 — FR-EX-02(search-export-import) / BulkOperation(FR-IS-05).** `export/job/{domain,repository,application,worker,storage,serialize,event}` 구조. `ExportJobWorker`(@Scheduled 폴링 + `pgmq.read` + 작업레벨 CAS `claimForRun` + dead-letter archive + VT 300 / stale 600 정합 + `@Transactional` 밖 process()), `ExportJobEnqueuePublisher`(outbox — 영속과 동일 트랜잭션 enqueue), `ExportJobCleanupWorker`(TTL 하드삭제 + Clock 주입).
2. **cross-BC 읽기 포트 선례 — `IssueSearchPort`(shared-kernel).** search-export-import → shared-kernel 포트 ◀ issue-tracking 어댑터. default fail-safe 구현(어댑터 부재 시 빈 결과). visibility 보안 술어를 구현체 SQL이 강제, 소비자는 알지 못함. ArchUnit이 BC 경계 강제.
3. **이슈 생성 진입점 — `IssueApplicationService.createIssue(actor, request)`.** CREATE_ISSUE 권한 게이트 · 이슈키 발급(`incrementKeySequence`) · 워크플로우 시작상태 해석 · 컴포넌트/보안등급/커스텀필드 검증 · auto-watch · `IssueCreated` 이벤트 발행을 캡슐화. `CreateIssueRequest(projectKey, typeId?, summary, description?, componentIds, securityLevelId?, customFields?, reporterId)`.
4. **사용자 조회 포트 — `UserLookupPort`(shared-kernel, identity-access 구현).** 현재 `exists(userId)` + username 일괄 조회(LOWER 매칭)만 보유. **이메일 조회 메서드 없음.**
5. **MinIO는 BC별 자체 클라이언트.** FR-EX-02가 search 모듈에 `exportMinioClient` 빈 분리(io.minio 기승인). cross-BC 배포 조립 없음(no-cross-bc-deployment-assembly).
6. **search 모듈 마이그레이션 대역 V600~V699.** 현재 V600~V603. 다음 V604. `db/codegen/init_codegen.sql` jOOQ codegen 미러 필수(jooq-init_codegen-mirror). 머지 직전 V번호 재확인(동시 브랜치 충돌).

## 결정 (Decision)

### D1. 구현 BC — search-export-import 모듈 (BC 신설 없음, FR-EX 동일 본거지)

ImportJob 영속 · pgmq worker · 업로드 파일/에러 로그 저장을 모두 **search-export-import 모듈** `com.bts.search.imports` 패키지에 둔다(Kotlin `import` 예약어 → 복수형). issue-tracking은 아래 D2의 어댑터/포트 구현만 추가한다.

### D2. cross-BC 이슈 쓰기 — `IssueImportPort` (shared-kernel) — **BTS 최초 쓰기 포트**

`IssueSearchPort` 패턴을 1:1 미러한 쓰기 포트를 신설한다.

```
search-export-import ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
```

- shared-kernel `com.bts.shared.issue.IssueImportPort` 인터페이스 + `IssueImportCommand`(projectKey, typeName?, summary, description?, reporterUserId?, assigneeUserId?, componentNames?, ...) + `IssueImportResult`(생성 issueKey 또는 실패 사유 코드).
- issue-tracking `IssueImportAdapter`가 `IssueApplicationService.createIssue(actor=requester, ...)` 위임. **CREATE_ISSUE 권한·이슈키·워크플로우·이벤트 전부 기존 경로 재사용** — search BC는 권한을 알지 못하고 우회할 수 없다.
- default fail-safe 구현(어댑터 부재 시 실패 결과 반환) — 단위 테스트/단계 배포 대비. **단, 쓰기이므로 "생성 성공 위장" 금지** — default는 명시적 실패(fail-closed)로 둔다(읽기 포트의 빈-결과 fail-safe와 방향이 다른 것은 의도적).

### D3. 트랜잭션 정책 — 행별 best-effort (all-or-nothing 아님)

각 행의 createIssue는 issue-tracking 안에서 **자체 트랜잭션**으로 처리한다. 한 행 실패가 다른 행을 롤백하지 않는다. 실패 행은 `error_log`(MinIO)에 행 번호·사유와 함께 기록하고 계속 진행한다. `import_jobs`는 total/succeeded/failed 카운트를 집계한다.

- **근거**. 10만 행 마이그레이션에서 1행 오류가 전체를 되돌리면 실무 불가. FR-IS-05 BulkOperation의 항목별 성공/실패(best-effort) 선례 동형.
- **트레이드오프**. 부분 완료 상태 발생 가능. 재실행 시 중복 생성 위험 — MVP는 재실행 방지/멱등을 요구하지 않음(사용자가 실패 행만 정정 후 재업로드). dry-run으로 사전 검증 유도.

### D4. 사용자 매핑 — 이메일 자동매칭 + 폴백 (`UserLookupPort` default 메서드 확장)

- Jira CSV의 reporter/assignee(이메일)를 `UserLookupPort.resolveByEmails(emails): Map<String, UUID>`로 해석.
- 미매칭: reporter = import 실행자(requester), assignee = 미할당(null).
- **`UserLookupPort` 확장은 default 메서드로 추가**(`= emptyMap()`) — 기존 ~35개 인라인 구현이 깨지지 않음(interface-extension-default-method). identity-access `UserLookupAdapter`가 `WHERE LOWER(email) IN (:emails)` 실제 구현.
- 명시적 필드/사용자 매핑은 **FR-IM-02**. FR-IM-01은 고정 기본 매핑(Jira 표준 컬럼 → 이슈 필드).

### D5. dry-run — 선택 옵션

`POST /api/v1/imports`의 `dryRun=true`(또는 별도 필드) 시 파싱 + 검증만 수행하고 이슈를 생성하지 않는다. 실제 import와 **같은 파싱/검증 경로**를 태워 검증 리포트(총행/유효/오류행+사유)를 반환한다. 미리보기 없이 바로 실행도 허용.

### D6. 접수/저장 — multipart 업로드 → MinIO → 202 → pgmq

- `POST /api/v1/imports` — multipart 파일 업로드(+ projectKey, format, dryRun). 원본 파일을 MinIO `bts-imports`에 저장 후 ImportJob PENDING 접수. **202 + { jobId, status }**.
- `GET /api/v1/imports/{jobId}` — 진행률 폴링(`{ status, progress, totalRows, succeededRows, failedRows, errorLogReady }`).
- `GET /api/v1/imports/{jobId}/errors` — 에러 로그 다운로드(MinIO 프록시 스트리밍, 완료 후).
- worker(`ImportJobWorker`)가 `q_import_jobs` 폴링 → CAS claim → 파싱 → 행별 `IssueImportPort` 호출 → 카운트/에러 로그 갱신. process()는 `@Transactional` 밖(분 단위 I/O).

### D7. 데이터 모델 — `import_jobs`(V604) + `q_import_jobs`

15컬럼 안팎(export_jobs 동형): id · project_key · format(CSV|JSON) · source_object_key · dry_run · requester_user_id · status · progress · total_rows/succeeded_rows/failed_rows · error_log_object_key · expires_at(TTL 24h) · created/started/completed_at. 소프트 삭제 없음(TTL 하드삭제). init_codegen.sql 미러. **머지 직전 V번호 재확인.**

### D8. 범위 — SDD 10.6.3 풀 마이그레이션을 순차 PR 에픽으로 분할 (Maxi 결정)

FR-IM-01은 SDD 10.6.3의 풀 피델리티 Jira 마이그레이션(이슈 + 컴포넌트/버전 + 상태 + 댓글 + Worklog + 첨부 + 이력)을 목표로 하되, 리뷰 가능 단위로 **순차 PR**로 분할한다.

- **PR1 (본 ADR 범위)**. 기반 + **코어 이슈 필드**(summary·description·type·priority·reporter/assignee(이메일)·labels). 새 키 자동생성(Jira 키 보존 안 함 — "이슈 키 재사용 금지" 원칙). 컴포넌트/버전은 이름 연결만(없으면 스킵+경고), 상태는 워크플로우 시작 상태 고정.
- **PR2**. 컴포넌트/버전 없으면 자동 생성 + 소스 Status로 전이 시도(도달불가/게이트 폴백은 PR2 스펙).
- **PR3**. 댓글 + Worklog.
- **PR4**. 첨부(**zip 아카이브 업로드** — 서버 fetch 없음 = SSRF 없음) + 이력(append-only에 조작 타임스탬프/주체 주입 — 메커니즘은 PR4 스펙에서 결정).

각 PR은 앞선 PR의 자산을 확장한다. 프론트(업로드/진행률 UI)는 별도 PR로 FR-IM-02 매핑 UI와 조율.

## 결과 (Consequences)

- **긍정**. FR-EX-02 자산(pgmq worker·MinIO·CAS·outbox·TTL cleanup)을 최대 재사용. 권한은 기존 issue-tracking 경로에 그대로 위임(우회 0). BC 격리 유지(ArchUnit 강제).
- **부정/위험**. 쓰기 포트는 BTS 최초 — 어댑터 부재 fail-closed·권한 위임 정확성이 codereview 집중 대상. 부분 완료(best-effort)로 재실행 중복 위험(MVP 허용, 문서화). 파일 파싱은 신뢰 경계(악성 CSV/거대 파일) — 크기 상한·행 상한·formula injection은 읽기 아닌 쓰기라 새 관점 필요(스펙에서 확정).
- **후속**. FR-IM-02(매핑 UI) — 필드/사용자 명시 매핑. FR-IM-01의 고정 매핑을 설정형으로 확장.

## 대안 (Alternatives)

- **all-or-nothing 트랜잭션** — 기각(D3). 대규모 마이그레이션 비현실.
- **search BC가 issue-tracking 직접 gradle 의존** — 기각. BC 격리 위반(ArchUnit).
- **모든 이슈 reporter=실행자(매핑 0)** — 기각(D4). 원본 작성자 정보 손실. 이메일 매칭이 저비용(포트 확장 1 메서드).
