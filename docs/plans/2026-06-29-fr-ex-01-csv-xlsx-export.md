# FR-EX-01 — 필터 결과 CSV/XLSX Export

> slug: fr-ex-01-csv-xlsx-export
> type: api
> agent: backend-engineer
> 생성: 2026-06-29

## Brief

FR-EX-01 (search-export-import BC, SDD §3.1, 필수). 필터(FR-SR-01)/AQL(FR-SR-02) 검색 결과를
CSV(UTF-8 BOM 한글 인코딩) + XLSX(Apache POI)로 **동기** 다운로드.
`POST /api/v1/search/export`. 선행 §2.1 FR-SR-01 + §2.2 FR-SR-02 모두 완료.
대용량(>1만건) 비동기 Export는 FR-EX-02로 분리 (이번 범위 아님).

product D단계.
- D1. 도메인 — ExportRequest (backend-engineer)
- D2. 명세 — 필드 선택 + 한글 인코딩 (UTF-8 BOM) (backend-engineer)
- D3. 데이터 모델 — (활용) (db-engineer)
- D4. 백엔드 — POST /api/v1/search/export (CSV + Apache POI XLSX) (backend-engineer)
- D5. 백엔드 테스트 — Excel 검증 (backend-engineer)
- D6. 프론트 UI — Export 다이얼로그 + 진행률 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리 (← /bts-domain 채움)

- **BC**: search-export-import (물리·논리 모두). FR-SR-02 ADR D2가 "search-export-import = Export/Import/REST API FR의 본거지"로 예고 → BC 신설 없음, 기존 모듈 확장.
- **데이터 접근**: `IssueSearchPort`(shared-kernel) → issue-tracking `IssueSearchAdapter`. search 모듈은 jOOQ 직접 접근 불가(ArchUnit BC 격리). visibility 보안 술어 + BROWSE 권한은 어댑터가 이미 자동 적용 → export 경로도 우회 불가 자동 상속.
- **영향 엔티티/타입**:
  - 신규(search-export-import): `ExportRequest`(VO — projectKey·AQL query·format·선택 컬럼), `ExportFormat`(enum CSV/XLSX), `ExportController`(`POST /api/v1/search/export`), CSV writer + XLSX writer(Apache POI).
  - 재사용: `IssueSearchPort`/`IssueSearchQuery`/`IssueSearchHit`(9필드, **무변경**), `AqlParser`/`AqlLexer`(AQL→AST). issue-tracking·shared-kernel **변경 0**(cross-BC 안 건드림).
- **새 용어**: Export(검색 결과를 파일로 내보내기), ExportFormat(CSV/XLSX). glossary 추가 후보.
- **Maxi 결정(2026-06-29 도메인 게이트, 4개 갈림길)**:
  1. **입력 경로** = AQL 쿼리 기반만. `POST /api/v1/search/export` body `{projectKey, query(AQL), format, columns?}`. 필터(GET /issues?filter=)는 미지원(assignee/component는 AQL MVP 필드 부재 → FR-SR 후속 확장으로 해결).
  2. **포맷** = CSV + XLSX 둘 다. **Apache POI 신규 의존성 도입 승인**(XLSX는 손수 구현 비현실적, POI가 표준). CSV는 라이브러리 없이 손수(UTF-8 BOM).
  3. **컬럼** = `IssueSearchHit` 9필드 고정(key, summary, typeKey, currentStateKey, assigneeId, priority, priorityName, projectKey, updatedAt). 포트 무변경. product D2 "필드 선택" = 이 9개 중 부분 선택으로 해석. assigneeId는 UUID(이름 해석은 후속).
  4. **행 상한** = 1만건(동기). 초과 시 명시 에러 + FR-EX-02 비동기 export 안내. IssueSearchPort 페이지 순회로 전체 fetch.
- **새 외부 의존성**: `org.apache.poi:poi-ooxml` (DEVELOPMENT.md §외부 의존성 — Maxi 승인 완료). CSV는 zero-dep.
- **기존 결정 충돌**: 없음. FR-SR-02 ADR이 본 FR을 예고. BC 격리·포트 패턴 그대로 계승.
- **관련 ADR**: [docs/decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md](../decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md) (생성됨). 선행 [2026-06-25-fr-sr-02-aql-parser-and-bc.md](../decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md).

## 스펙 (← /bts-spec Phase A 채움)

전체 스펙. [docs/specs/2026-06-29-fr-ex-01-csv-xlsx-export.md](../specs/2026-06-29-fr-ex-01-csv-xlsx-export.md)

핵심 시나리오 요약.
- `POST /api/v1/search/export` body `{projectKey, query(AQL), format(CSV|XLSX), columns?}` → 파일 다운로드(Content-Disposition attachment).
- 데이터는 `IssueSearchPort` 재사용 → BROWSE 권한 + visibility 보안 술어 자동 상속(우회 없음). 컬럼 = IssueSearchHit 9필드(영문 표준 라벨).
- CSV = UTF-8 BOM + RFC 4180 이스케이프(zero-dep), XLSX = Apache POI(신규 의존성). 둘 다 formula injection 방어(`'` prefix).
- 동기 상한 1만 행: count-first(IssueSearchPage.total) 확인 → 초과면 400 `SEARCH_EXPORT_LIMIT_EXCEEDED`, 통과면 페이지 순회 전체 수집(best-effort 스냅샷).
- issue-tracking·shared-kernel 무변경. 신규 테이블 0(D3 활용). D6 /search Export 다이얼로그 + D7 E2E.

## Brainstorming Check (← /bts-spec Phase B 채움)

✅ 통과 (1회 — self-sanity-check). gap 3건 해소: G1 상한초과=400+errorCode / G2 헤더라벨=영문 표준(Maxi) / G3 순회일관성=best-effort+count-first. 보강: Clock 주입, formula injection security codereview 점검.

## Plan

> 모듈: search-export-import (`com.bts.search`), export 패키지 `com.bts.search.export` 신설. 엔드포인트 `POST /api/v1/search/export`.
> 전 백엔드 task는 같은 모듈이라 컴파일 단위 공유([[bts-plan-wave-gradle-module-compile]]) — 파일은 분리하되 wave는 사실상 직렬. frontend(T7)는 별도 모듈(apps/web)이라 T6와 진짜 병렬.
> 검증 공통: `./gradlew :modules:search-export-import:test ktlintCheck detekt` (sub-agent 보고 불신, controller 직접 재실행 — [[subagent-ktlint-false-green-controller-verify]] / [[backend-detekt-lint-debt-unmasked]]).
> **리뷰 반영(2026-06-29 eng+devex 독립 리뷰)**: BLOCKER 5 + 주요 CONCERN 전부 task에 반영(아래 각 task 주석 [R:..]).

### Task 1. Export 도메인 타입 + 공용 Sanitizer + Apache POI 의존성

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/build.gradle.kts`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportFormat.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportColumn.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportCellSanitizer.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/ExportColumnTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/ExportCellSanitizerTest.kt`]
- depends-on: []

**RED**.
- `ExportColumnTest`: (1) 9 enum ↔ IssueSearchHit 9필드 1:1, (2) 영문 헤더 라벨(Key/Summary/Type/Status/Assignee ID/Priority/Priority Name/Project/Updated At), (3) `extract(hit)`(assigneeId null→"", updatedAt→ISO-8601 UTC, priority→문자열), (4) `parse(names)`: null/빈→전체(표준 순서), 부분집합→**표준 순서로 정렬 반환**(요청 순서 무관, 결정성 — [R:devex-N3]), **미지원 필드명→`SearchValidationException`**(IAE 금지 — catch-all 500 회피 [R:B1/B2]).
- `ExportCellSanitizerTest`: `=`,`+`,`-`,`@`,탭(\t),CR(\r) 시작 셀 → `'` prefix. 일반 셀 무변경. [R:C4 — 공용 추출을 Task1로 끌어올림]

**GREEN**.
- `ExportFormat`(CSV/XLSX + contentType, fileExtension). `ExportColumn`(9개, headerLabel, extract, companion parse→SearchValidationException). `ExportCellSanitizer.sanitize(String):String`.
- build.gradle.kts: `implementation("org.apache.poi:poi-ooxml:5.4.0")`. **추가 직후 `./gradlew :modules:search-export-import:dependencies`로 commons-compress/commons-io/xmlbeans 해석 버전이 POI 5.4 요구 충족하는지 확인 + log4j-api 처리(무로깅 또는 log4j-to-slf4j 브리지) 결정** [R:C5]. 해석 결과 plan/PR에 인용.

**REFACTOR**. updatedAt 직렬화는 검색 응답과 동일 포맷 재사용. 위험 시작문자 상수화. KDoc.

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportColumnTest" --tests "*ExportCellSanitizerTest"`

### Task 2. CSV writer (UTF-8 BOM + RFC 4180 + Sanitizer)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/CsvExportWriter.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/CsvExportWriterTest.kt`]
- depends-on: [1]

**RED**.
- (1) 선두 `EF BB BF`, (2) 헤더=선택 컬럼 라벨, (3) 쉼표/따옴표/개행 RFC 4180 이스케이프, (4) formula injection 셀 `'` prefix(**ExportCellSanitizer 호출** — Task1 제공), (5) 빈 결과→헤더만, (6) 한글 round-trip, (7) assigneeId null→빈 셀.

**GREEN**. `CsvExportWriter.write(out, columns, rows)`: BOM→헤더→행. 셀 = sanitize 후 RFC 4180 이스케이프. UTF-8.

**REFACTOR**. 이스케이프 private 분리.

**검증**: `./gradlew :modules:search-export-import:test --tests "*CsvExportWriterTest"`

### Task 3. XLSX writer (Apache POI + Sanitizer)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/XlsxExportWriter.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/XlsxExportWriterTest.kt`]
- depends-on: [1]

**RED**(POI round-trip 재읽기). (1) 시트 1개, 헤더=라벨, (2) 데이터 셀 일치, (3) formula injection 셀 `'` prefix(**ExportCellSanitizer 호출**), (4) 빈 결과→헤더만, (5) 한글, (6) **모든 셀 STRING 타입**(숫자도 문자열 — 일관성+injection 안전, XLSX 숫자정렬 trade-off는 spec E12 수용).

**GREEN**. `XlsxExportWriter.write(out, columns, rows)`: `XSSFWorkbook` 1 시트. `setCellValue(String)` + sanitize. try-with-resources(close).

**REFACTOR**. 헤더/행 작성 분리.

**검증**: `./gradlew :modules:search-export-import:test --tests "*XlsxExportWriterTest"`

### Task 4. ExportService (count-first 경계값 + @Service + Clock)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportService.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportLimitExceededException.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportResult.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/ExportServiceTest.kt`]
- depends-on: [1, 2, 3]

**RED**(IssueSearchPort mock).
- (1) query→AqlParser→IssueSearchQuery(viewerUserId, sort 반영), (2) count-first: total>10000 → `ExportLimitExceededException(resultCount, limit)` 즉시(**추가 순회 없음** — mock 호출 1회 검증), (3) **경계 쌍**: total=10000 → 통과(100페이지 순회), total=10001 → 거부 [R:C6], (4) total=250,size=100 → 3페이지 수집, (5) format CSV/XLSX 디스패치, (6) 파일명=`{projectKey}-issues-{Clock 고정시각}.{ext}`(Clock 주입 결정성), (7) AQL 문법오류→AqlSyntaxException 전파.

**GREEN**.
- `@Service class ExportService(searchPort, aqlParser, clock)` [R:C8 — @Service 명시]. parse→count-first→순회 수집(PAGE_SIZE=100, MAX_ROWS=10000 companion)→writer 디스패치→`ExportResult(filename, contentType, bytes)`.
- **`@Transactional` 의도적 생략**: 직접 DB 접근 없는 read-only 오케스트레이터, 일관성은 best-effort(ADR C6). 주석으로 명시(codereview rule 9 오탐 방지 [R:C8]).

**REFACTOR**. 순회 루프 가독성.

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportServiceTest"`

### Task 5. ExportController + DTO + 전용 ExportExceptionHandler

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/ExportController.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/dto/ExportRequest.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/ExportExceptionHandler.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/SearchErrorCodes.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/web/ExportControllerTest.kt`]
- depends-on: [4]

**RED**(MockMvc 슬라이스).
- (1) `POST /api/v1/search/export` CSV→200 + `Content-Type: text/csv; charset=UTF-8` + `Content-Disposition: attachment; filename=...csv` + body BOM, (2) XLSX→200 + xlsx contentType(`.contentType()` 동적), (3) 상한초과→400 `SEARCH_EXPORT_LIMIT_EXCEEDED` + **ProblemDetail 봉투(detail 메시지 + resultCount/limit property 단언)** [R:C1/C2/C3], (4) AQL 문법오류→400, (5) **format 오타("PDF")→400 + 허용값 메시지** [R:devex-C4], (6) **columns 미지원→400 SEARCH_VALIDATION_FAILED**(SearchValidationException 경유) [R:B1/B2], (7) projectKey/query 누락→400(**수동 검증** [R:devex-C3]), (8) **포트 SecurityException→403 SEARCH_ACCESS_DENIED**(비-vacuous 매핑 검증) [R:B1/C7], (9) **projectKey에 CRLF→400**(헤더 인젝션 방어) [R:B3/N1].

**GREEN**.
- `ExportRequest`(`@field:NotBlank` projectKey + 패턴 검증, `@field:NotBlank @field:Size(max=2000)` query, `format: String?`, `columns: List<String>?`) [R:devex-N1].
- `ExportController.export()`: **수동 `validateRequest()`**(SearchController 선례 — Hibernate Validator 부재 false-green 회피 [R:devex-C3]) + projectKey sanitize(영숫자+하이픈) + format parse + actor 추출(SearchController 동일 패턴) → ExportService → `ResponseEntity<ByteArray>` + 헤더.
- **전용 `ExportExceptionHandler`**(`@RestControllerAdvice(assignableTypes=[ExportController::class])`) [R:B2/C1]: ExportLimitExceededException→400 + property, AqlSyntaxException→400, SecurityException→403, ResponseStatusException→401, SearchValidationException→400. **`problem()` 헬퍼/ProblemDetail 봉투 패턴 재사용**(신규 봉투 금지 [R:C2]).
- `SearchErrorCodes`에 `SEARCH_EXPORT_LIMIT_EXCEEDED` 추가(SEARCH_ prefix 유지 §6 [R:C3]).

**REFACTOR**. 헤더 조립 헬퍼. 검증/actor 추출 SearchController 패턴 재사용.

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportControllerTest"`

### Task 6. 백엔드 통합 테스트 (D5 — 매핑층 end-to-end)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/test/kotlin/com/bts/search/integration/ExportIntegrationTest.kt`]
- depends-on: [5]

**RED→GREEN**(기존 search 통합테스트 부팅 레시피 + IssueSearchPort fake).
- (1) fake 포트 시드 → CSV export → BOM + 행 파싱, (2) XLSX → POI 재읽기, (3) 상한초과 → 400 SEARCH_EXPORT_LIMIT_EXCEEDED(봉투 property), (4) formula injection 셀 e2e `'` prefix, (5) **fake 포트 SecurityException → 403**(매핑층), (6) **export가 검색과 동일 viewerUserId/AST로 포트 호출**(slot capture — 보안 구조적 상속 증명).
- **데이터 제외(미가시 이슈 빠짐)는 search 모듈에서 재증명하지 않음** — issue-tracking gradle 의존 부재라 mock 재증명은 vacuous [R:B1]. FR-SR-02의 `IssueSearchAdapterTest`(BROWSE→SecurityException) + 실DB visibility IT 인용(plan/PR 주석).
- ArchUnit: export가 jOOQ/issue-tracking 직접 접근 0(IssueSearchPort만) — 기존 BC 격리 룰 통과(vacuous 아닌지 [R:archunit] 확인).

**검증**: `./gradlew :modules:search-export-import:test`

### Task 7. D6 프론트 — Export 다이얼로그 + blob 다운로드 (기존 패턴 재사용)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/search/ExportDialog.tsx`, `apps/web/src/components/search/ExportDialog.test.tsx`, `apps/web/src/api/search.ts`, `apps/web/src/routes/search.tsx`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [5]

**RED→GREEN**(vitest + RTL).
- (1) /search 결과 있을 때 Export 버튼 → 다이얼로그(형식 CSV/XLSX 라디오 + 9 컬럼 체크박스), (2) 내보내기 → `POST /api/v1/search/export`(현재 AQL query + projectKey + format + 선택 columns).
- **blob 다운로드 — 기존 패턴 재사용(재발명 금지 [R:B4/devex-B2])**: `apiFetch`는 raw `Response` 반환(JSON 파싱 안 함, `client.ts`). `exportIssues(...)=apiFetch('/api/v1/search/export',{method:'POST',body}).blob()` (non-ok→ApiError, `attachments.ts:downloadAttachment` 패턴 복제). 다운로드는 **이미 존재하는 `apps/web/src/lib/download.ts:triggerBlobDownload(blob, filename)` 재사용**. **신규 download 헬퍼 추출 금지.** CSRF/credentials/401-refresh는 apiFetch가 자동 상속 [R:C10].
- filename은 응답 `Content-Disposition` 헤더에서 파싱(서버 생성 timestamp라 클라가 모름 [R:devex-B2]).
- (3) 상한초과 400 → ProblemDetail `detail` 메시지 표시(자연어, FR-EX-02 코드 노출 안 함). (4) Zod 불요(파일 응답, 계약은 요청 측만).
- MSW: `/api/v1/search/export` 핸들러는 작은 고정 바이트(BOM 포함) + Content-Disposition 반환.

**REFACTOR**. 다이얼로그는 기존 `SaveFilterDialog` radix 패턴 답습.

**검증**: `pnpm --filter web test ExportDialog && pnpm --filter web typecheck`(CI tsconfig.app — [[ci-typecheck-tsconfig-app-vs-local]]).

### Task 8. D7 E2E (Playwright)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/export.spec.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [7]

**시나리오**.
- (1) /search 검색 → Export → CSV → 내보내기 → `page.waitForEvent('download')` + 파일명 패턴, (2) XLSX 전환 후 다운로드, (3) 컬럼 부분선택 후 다운로드. 텍스트 중복 버튼 컨테이너 한정([[playwright-getbyrole-exact-strict-mode]]).
- 기존 E2E 무회귀([[ui-pr-defer-e2e-regression-latent]]). PRE_EXISTING 17(FR-AU-07)은 본 작업 무관.

**검증**: `pnpm --filter web test:e2e export`

## Plan 메타

- task 수: 8 (백엔드 6 + 프론트 1 + E2E 1)
- depends-on 그래프: T1→{T2,T3}→T4→T5→{T6, T7}→T8. (T2/T3·T6/T7 병렬, 단 백엔드는 모듈 컴파일 직렬화)
- 예상 wave: 6
- TDD 강제: yes (test 커밋 먼저). T6/T7은 결합형 RED→GREEN 허용(통합/프론트 — 변형 사유 커밋 명시 [R:N4]).
- 신규 외부 의존성: `org.apache.poi:poi-ooxml:5.4.0` (Maxi 승인, Task 1) — transitive/log4j 해석 검증 포함 [R:C5]
- cross-BC 변경: **0** (issue-tracking·shared-kernel 무변경, IssueSearchPort 재사용)
- **리뷰 BLOCKER 해소**: B1(보안 vacuous→2층 분리 T6) / B2(컬럼검증 500→SearchValidationException T1·T5) / B3(헤더 인젝션→projectKey sanitize T5) / B4(프론트 raw fetch 오류→apiFetch().blob()+기존 헬퍼 T7) / 핸들러 스코프(→전용 ExportExceptionHandler T5).
- 보안 점검(codereview): formula injection(T1·T2·T3), 보안 매핑층 403 + 구조적 상속(T6), 에러 핸들러 스코프(T5), 헤더 인젝션(T5).
- **머지 전 동기화 체크리스트** [R:C11] (bts-merge가 수행, 여기 명시): product `search-export-import.md` §3.1 D1~D7 체크박스 / glossary `Export`·`ExportFormat` 추가 / fr-index 카운트 영향 0 확인 / dashboard 재생성 / Obsidian 미러 / `bash scripts/verify-master-plan.sh` 통과.
- 추가 검증: ktlint/detekt(모듈 직접 재실행), pnpm typecheck(tsconfig.app), playwright.

## 리뷰 결과

### eng 독립 리뷰 (2026-06-29, Plan 에이전트 — 엔지니어링 매니저 관점)
- BLOCKER 2: **B1 보안 술어 vacuous**(search 모듈 issue-tracking 의존 부재 → mock 재증명 가짜그린) / **B2 컬럼검증 IAE→500**(spec 400 모순).
- CONCERN 11: C1 핸들러 스코프 / C2 봉투 resultCount·limit 누락+detail / C3 SEARCH_ prefix / C4 Sanitizer 파일 누락+T2·T3 race / C5 POI transitive·log4j / C6 경계값 1만 / C7 컨트롤러 403 / C8 @Transactional 정당화·@Service / C9 count-first 성능 / C10 프론트 CSRF / C11 문서 동기화.
- NIT 5: 파일명 인젝션 / 인증 matcher / XLSX 숫자정렬 / TDD 결합형 / format 역직렬화.
- **해소**: B1·B2 + C1~C8·C11 전부 task 반영. C9(성능)는 NFR-2 통합 실측으로 위임. 잔여 NIT 반영.

### devex 독립 리뷰 (2026-06-29, Plan 에이전트 — REST API/DevEx 관점)
- BLOCKER 4: **B1 컬럼검증 500** / **B2 프론트 raw fetch 전제 오류**(apiFetch=raw Response, triggerBlobDownload 기존 존재) / **B3 Content-Disposition 헤더 인젝션** / **B4 핸들러 스코프**.
- CONCERN 6: C1 prefix / C2 ProblemDetail 봉투 / C3 수동 validateRequest / C4 format 피드백 / C5 URL 진화(동기 200 vs 비동기 202) / C6 FR-EX-02 코드 노출.
- NIT 4: @field: prefix / 엔드포인트 네이밍 / 컬럼 토큰 직관성·순서 / produces 동적.
- **해소**: 전 BLOCKER + CONCERN task 반영. 엔드포인트 URL `/api/v1/search/export`(Maxi 확정), FR-EX-02는 `/search/export-jobs`(ADR D2). C6 메시지 자연어화.

### 종합
두 리뷰 모두 **핵심 설계(BC 격리·count-first·formula injection·Clock·9필드 재사용)는 건전** 판정. 발견은 전부 에러 계약/검증/프론트 패턴의 구체 결함으로, plan/spec/ADR 개정으로 해소. **BLOCKER 잔존 0.** 게이트 1 진입 가능.
