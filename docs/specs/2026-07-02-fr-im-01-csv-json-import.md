<!-- FR-IM-01 PR1(기반+코어) 백엔드 스펙 — CSV/JSON Import 비동기 job + IssueImportPort cross-BC 쓰기 -->

# FR-IM-01 CSV/JSON Import (Jira 마이그레이션) — PR1 기반+코어 스펙 (백엔드)

> 범위: **PR1만** (기반 + 코어 이슈 필드). 컴포넌트/버전 자동생성·상태전환=PR2, 댓글/Worklog=PR3, 첨부(zip)/이력=PR4, 프론트 UI=후속.
> 도메인/결정 정본: [../plans/2026-07-02-fr-im-01-csv-json-import.md](../plans/2026-07-02-fr-im-01-csv-json-import.md), [../decisions/2026-07-02-fr-im-01-csv-json-import.md](../decisions/2026-07-02-fr-im-01-csv-json-import.md)

## 사용자 시나리오 (Given-When-Then)

1. **정상 임포트**. Given 사용자가 프로젝트 KEY에 CREATE_ISSUE 권한 보유, When Jira CSV(100행)를 `POST /api/v1/imports`로 업로드, Then 202 + jobId 반환 · worker가 100행을 새 이슈로 생성 · `GET /imports/{jobId}` progress 100 · succeededRows=100.
2. **부분 실패(best-effort)**. Given 100행 중 3행이 summary 누락, When 임포트, Then 97행 생성 성공 · 3행은 에러 로그(MinIO)에 행번호+사유 기록 · status=COMPLETED · succeededRows=97 · failedRows=3.
3. **dry-run**. Given `dryRun=true`로 업로드, When 처리, Then 이슈 0건 생성 · 검증 리포트(총행/유효/오류행+사유) 반환 · status=COMPLETED.
4. **사용자 매핑**. Given CSV reporter="bob@corp.com"(존재)·assignee="ghost@x.com"(미존재), When 임포트, Then reporter=bob(이메일 매칭)·assignee=null(미매칭 폴백, 실행자 아님). Given reporter도 미존재, Then reporter=import 실행자.
5. **권한 거부**. Given 사용자가 대상 프로젝트 CREATE_ISSUE 미보유, When 임포트, Then 각 행이 FORBIDDEN으로 실패(에러 로그) — 권한은 issue-tracking createIssue가 판정(우회 불가).
6. **행 상한 초과**. Given 파일 행 수 > MAX_ROWS(10만), When 처리 중 10만 번째 행을 넘는 순간, Then 즉시 중단 · status=FAILED · errorCode=IMPORT_ROW_LIMIT_EXCEEDED. **단일패스 스트리밍이라 중단 전까지 생성된 최대 10만 행은 잔존(best-effort — "생성 0" 아님)**. 사전 카운트 패스(파일 2회 읽기)를 피하려는 의도적 설계. 대량 파일은 dry-run으로 사전 검증 권장.
7. **파싱 실패**. Given 깨진 CSV/JSON(헤더 없음/파싱 불가), When 처리, Then status=FAILED · errorCode=IMPORT_PARSE_FAILED.

## 기능 요구사항 (FR)

- FR1. `POST /api/v1/imports` — multipart(file + projectKey + format + dryRun). 원본 파일을 MinIO `bts-imports`에 저장 후 ImportJob PENDING 접수. **202 + { jobId, status }**.
- FR2. CSV(Jira 호환, RFC 4180) + JSON(Jira REST export 호환, 이슈 배열) 파싱. 헤더/키로 컬럼 인식.
- FR3. **코어 필드 매핑**(고정) — Summary→summary(필수), Description→description, Issue Type→type(이름→typeId, 미매칭 시 기본 Task 폴백), Priority→priority(1..5 매핑, 범위밖 무시), Reporter→reporterId(이메일), Assignee→assigneeId(이메일), Labels→labels(콤마/세미콜론 분리). Components는 이름 연결만(없으면 스킵+경고).
- FR4. 이슈 생성은 **`IssueImportPort`(shared-kernel) → issue-tracking `IssueImportAdapter`** 위임. 어댑터가 `createIssue(actor=requester)` + priority/labels/assignee update를 **행별 1 트랜잭션**(행 원자성)으로 실행. CREATE_ISSUE 권한·키발급·워크플로우 시작상태·이벤트는 기존 경로 재사용.
- FR5. **행별 best-effort** — 한 행 실패가 다른 행 롤백 안 함. 실패행은 에러 로그(행번호+사유코드) MinIO 기록. total/succeeded/failedRows 집계.
- FR6. **dry-run**(`dryRun=true`) — 같은 파싱/검증 경로, 이슈 생성 0, 리포트 반환.
- FR7. **사용자 매핑** — `UserLookupPort.resolveByEmails`(신규 default 메서드, identity-access 구현: `WHERE LOWER(email) IN (:emails)`). 미매칭 reporter→실행자, assignee→null.
- FR8. `GET /api/v1/imports/{jobId}` — 소유자만(404 은닉). `{ status, progress, totalRows, succeededRows, failedRows, errorCode?, errorLogReady }`.
- FR9. `GET /api/v1/imports/{jobId}/errors` — 에러 로그 CSV 다운로드(MinIO 프록시 스트리밍, 완료 후).
- FR10. `ImportJobWorker`(@Scheduled `q_import_jobs` 폴링) — CAS claim·dead-letter·outbox enqueue·VT/stale 정합. process()는 `@Transactional` 밖.
- FR11. TTL — 완료 +24h 후 `import_jobs` 행 + MinIO 오브젝트(원본·에러로그) 하드삭제(`ImportJobCleanupWorker`, Clock 주입).
- FR12. **접수 시점 권한 fail-fast** (Brainstorming 보강) — `POST /imports` 접수 시 요청자가 projectKey에 CREATE_ISSUE 권한이 없으면 **403 즉시 거부**(파일 저장·job 생성 전). 행별 권한 판정은 방어선으로 유지(이중). cross-BC 권한은 resolver/권한코드 경유(crossbc-permission-resolver 원칙), search BC가 role 직접조회 금지.

### 상세 보강 (Brainstorming Check)

- **JSON 구조**. Jira REST export 호환 = `{ "issues": [ { "key"?, "fields": { "summary", "description", "issuetype":{"name"}, "priority":{"name"}, "reporter":{"emailAddress"}, "assignee":{"emailAddress"}, "labels":[], "components":[{"name"}] } } ] }`. 파서가 `fields` 하위에서 코어 필드 추출. 정확 스키마는 impl에서 Jira 샘플로 고정.
- **이메일 다중매칭**. `users.email`은 **nullable + UNIQUE 아님**(V001 확인). 따라서 `resolveByEmails`(`WHERE LOWER(email) IN (:emails)`)가 한 이메일에 **다중 행 반환 가능** → 해당 이메일은 **미매칭 취급(폴백)** 필수(잘못된 배정보다 안전, fail-safe). NULL email은 IN 매칭에서 자연 제외. username 과다매칭 트레이드오프(UserLookupPort KDoc) 선례 동형.

## 비기능 요구사항 (NFR)

- Import 1만건 120s 이내(product §NFR). 스트리밍 파싱(전체 메모리 적재 금지).
- 업로드 파일 크기 상한(예 50MB) — 초과 413. 행 상한 MAX_ROWS=10만(초과 FAILED).
- 원본 파일·에러 로그는 요청자 소유. 타 사용자 접근 404 은닉.
- 파일은 신뢰 경계 — 필드 길이 상한(summary 200 등 기존 검증 상속), NUL/제어문자 정화.

## API 인터페이스 (REST)

```
POST /api/v1/imports            multipart(file, projectKey, format=CSV|JSON, dryRun=false) → 202 { jobId, status }
GET  /api/v1/imports/{jobId}    → 200 { jobId, status, progress, totalRows, succeededRows, failedRows, errorCode?, errorLogReady, dryRun }
GET  /api/v1/imports/{jobId}/errors → 200 text/csv (에러 로그) | 404
```

에러 응답 RFC 7807 ProblemDetail, errorCode prefix `IMPORT_` (`IMPORT_ROW_LIMIT_EXCEEDED`/`IMPORT_PARSE_FAILED`/`IMPORT_FILE_TOO_LARGE`/`IMPORT_UNSUPPORTED_FORMAT`). 전용 `ImportExceptionHandler`(assignableTypes=[ImportController], 401→500 변질 차단 — export 선례).

## 데이터 모델 변경

- `import_jobs`(V604 — **머지 직전 V번호 재확인**): id · project_key · format(CSV|JSON) · source_object_key · dry_run · requester_user_id · status(PENDING/RUNNING/COMPLETED/FAILED) · progress · total_rows · succeeded_rows · failed_rows · error_code · error_log_object_key · expires_at · created_at · started_at · completed_at. CHECK(status), CHECK(format). 인덱스 (requester_user_id, created_at DESC) + partial (expires_at) WHERE NOT NULL.
- pgmq 큐 `q_import_jobs`(`SELECT pgmq.create`).
- `init_codegen.sql` 미러(jOOQ codegen).
- 소프트삭제 없음(TTL 하드삭제).

## 엣지 케이스

- 빈 파일/헤더만 → totalRows=0, COMPLETED(생성 0).
- 중복 업로드/재실행 → 중복 이슈 생성(MVP 허용, dry-run으로 사전검증 유도). 멱등 요구 안 함.
- typeId 미매칭 → Task 폴백(경고 로그). projectKey 미존재 → 접수 400(IMPORT_UNSUPPORTED... 아님, 프로젝트 검증).
- create 성공 후 priority/labels update 실패 → 행 전체 롤백(행 원자성) → failed로 기록.
- worker 중복 수신(at-least-once) → CAS claim으로 단일 처리(export 선례).
- dryRun=true인데 파싱 실패 → FAILED + IMPORT_PARSE_FAILED(실 임포트와 동일).

## 제약 조건 (PR1)

- 컴포넌트/버전 자동생성 ✗(이름 연결만, 없으면 스킵+경고) — PR2.
- Status 전환 ✗(워크플로우 시작 상태 고정) — PR2.
- 댓글/Worklog ✗ — PR3. 첨부/이력 ✗ — PR4.
- Jira 원본 키 보존 ✗(새 키 자동생성).
- 프론트 UI ✗(후속 PR).

## 측정 가능한 완료 기준

- [ ] Jira 표준 CSV 샘플 임포트 → 코어 필드 정확 생성(통합 테스트).
- [ ] 부분 실패 시 성공행 생성 + 실패행 에러 로그 기록(best-effort).
- [ ] dry-run 시 생성 0 + 리포트 정확.
- [ ] 이메일 매핑 매칭/폴백 3케이스.
- [ ] 권한 없는 프로젝트 → 행 FORBIDDEN.
- [ ] 행 상한 초과 → FAILED.
- [ ] worker CAS claim/dead-letter/TTL cleanup 통합 테스트.
- [ ] `IssueImportPort` default fail-closed(어댑터 부재 시 성공 위장 안 함) 단위 테스트.
- [ ] ArchUnit — search 모듈이 issue-tracking 직접 의존 안 함.

## Brainstorming Check ✅ 통과 (1회 iteration)

발견 gap 3건 스펙 보강으로 해소.
1. 접수 시점 CREATE_ISSUE 권한 fail-fast(FR12) — 행별 판정만이던 갭.
2. JSON 정확 구조 명시(Jira `fields` 하위 추출).
3. 이메일 다중매칭 폴백 — users.email nullable+비유니크 확인, 다중매칭→미매칭(fail-safe) 필수.
