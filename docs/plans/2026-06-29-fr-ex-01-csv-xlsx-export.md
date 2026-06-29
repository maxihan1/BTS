# FR-EX-01 — 필터 결과 CSV/XLSX Export

> slug: fr-ex-01-csv-xlsx-export
> type: api
> agent: backend-engineer
> 생성: 2026-06-29

## Brief

FR-EX-01 (search-export-import BC, SDD §3.1, 필수). 필터(FR-SR-01)/AQL(FR-SR-02) 검색 결과를
CSV(UTF-8 BOM 한글 인코딩) + XLSX(Apache POI)로 **동기** 다운로드.
`POST /api/v1/exports`. 선행 §2.1 FR-SR-01 + §2.2 FR-SR-02 모두 완료.
대용량(>1만건) 비동기 Export는 FR-EX-02로 분리 (이번 범위 아님).

product D단계.
- D1. 도메인 — ExportRequest (backend-engineer)
- D2. 명세 — 필드 선택 + 한글 인코딩 (UTF-8 BOM) (backend-engineer)
- D3. 데이터 모델 — (활용) (db-engineer)
- D4. 백엔드 — POST /api/v1/exports (CSV + Apache POI XLSX) (backend-engineer)
- D5. 백엔드 테스트 — Excel 검증 (backend-engineer)
- D6. 프론트 UI — Export 다이얼로그 + 진행률 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리 (← /bts-domain 채움)

- **BC**: search-export-import (물리·논리 모두). FR-SR-02 ADR D2가 "search-export-import = Export/Import/REST API FR의 본거지"로 예고 → BC 신설 없음, 기존 모듈 확장.
- **데이터 접근**: `IssueSearchPort`(shared-kernel) → issue-tracking `IssueSearchAdapter`. search 모듈은 jOOQ 직접 접근 불가(ArchUnit BC 격리). visibility 보안 술어 + BROWSE 권한은 어댑터가 이미 자동 적용 → export 경로도 우회 불가 자동 상속.
- **영향 엔티티/타입**:
  - 신규(search-export-import): `ExportRequest`(VO — projectKey·AQL query·format·선택 컬럼), `ExportFormat`(enum CSV/XLSX), `ExportController`(`POST /api/v1/exports`), CSV writer + XLSX writer(Apache POI).
  - 재사용: `IssueSearchPort`/`IssueSearchQuery`/`IssueSearchHit`(9필드, **무변경**), `AqlParser`/`AqlLexer`(AQL→AST). issue-tracking·shared-kernel **변경 0**(cross-BC 안 건드림).
- **새 용어**: Export(검색 결과를 파일로 내보내기), ExportFormat(CSV/XLSX). glossary 추가 후보.
- **Maxi 결정(2026-06-29 도메인 게이트, 4개 갈림길)**:
  1. **입력 경로** = AQL 쿼리 기반만. `POST /api/v1/exports` body `{projectKey, query(AQL), format, columns?}`. 필터(GET /issues?filter=)는 미지원(assignee/component는 AQL MVP 필드 부재 → FR-SR 후속 확장으로 해결).
  2. **포맷** = CSV + XLSX 둘 다. **Apache POI 신규 의존성 도입 승인**(XLSX는 손수 구현 비현실적, POI가 표준). CSV는 라이브러리 없이 손수(UTF-8 BOM).
  3. **컬럼** = `IssueSearchHit` 9필드 고정(key, summary, typeKey, currentStateKey, assigneeId, priority, priorityName, projectKey, updatedAt). 포트 무변경. product D2 "필드 선택" = 이 9개 중 부분 선택으로 해석. assigneeId는 UUID(이름 해석은 후속).
  4. **행 상한** = 1만건(동기). 초과 시 명시 에러 + FR-EX-02 비동기 export 안내. IssueSearchPort 페이지 순회로 전체 fetch.
- **새 외부 의존성**: `org.apache.poi:poi-ooxml` (DEVELOPMENT.md §외부 의존성 — Maxi 승인 완료). CSV는 zero-dep.
- **기존 결정 충돌**: 없음. FR-SR-02 ADR이 본 FR을 예고. BC 격리·포트 패턴 그대로 계승.
- **관련 ADR**: [docs/decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md](../decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md) (생성됨). 선행 [2026-06-25-fr-sr-02-aql-parser-and-bc.md](../decisions/2026-06-25-fr-sr-02-aql-parser-and-bc.md).

## 스펙 (← /bts-spec Phase A 채움)

전체 스펙. [docs/specs/2026-06-29-fr-ex-01-csv-xlsx-export.md](../specs/2026-06-29-fr-ex-01-csv-xlsx-export.md)

핵심 시나리오 요약.
- `POST /api/v1/exports` body `{projectKey, query(AQL), format(CSV|XLSX), columns?}` → 파일 다운로드(Content-Disposition attachment).
- 데이터는 `IssueSearchPort` 재사용 → BROWSE 권한 + visibility 보안 술어 자동 상속(우회 없음). 컬럼 = IssueSearchHit 9필드(영문 표준 라벨).
- CSV = UTF-8 BOM + RFC 4180 이스케이프(zero-dep), XLSX = Apache POI(신규 의존성). 둘 다 formula injection 방어(`'` prefix).
- 동기 상한 1만 행: count-first(IssueSearchPage.total) 확인 → 초과면 400 `EXPORT_LIMIT_EXCEEDED`, 통과면 페이지 순회 전체 수집(best-effort 스냅샷).
- issue-tracking·shared-kernel 무변경. 신규 테이블 0(D3 활용). D6 /search Export 다이얼로그 + D7 E2E.

## Brainstorming Check (← /bts-spec Phase B 채움)

✅ 통과 (1회 — self-sanity-check). gap 3건 해소: G1 상한초과=400+errorCode / G2 헤더라벨=영문 표준(Maxi) / G3 순회일관성=best-effort+count-first. 보강: Clock 주입, formula injection security codereview 점검.

## Plan

> 모듈: search-export-import (`com.bts.search`), export 패키지 `com.bts.search.export` 신설.
> 전 백엔드 task는 같은 모듈이라 컴파일 단위 공유([[bts-plan-wave-gradle-module-compile]]) — 파일은 분리하되 wave는 사실상 직렬에 가까움. frontend(T7)는 별도 모듈(apps/web)이라 T6와 진짜 병렬 가능.
> 검증 공통: `./gradlew :modules:search-export-import:test ktlintCheck detekt` (sub-agent 보고 불신, controller가 직접 재실행 — [[subagent-ktlint-false-green-controller-verify]] / [[backend-detekt-lint-debt-unmasked]]).

### Task 1. Export 도메인 타입 + Apache POI 의존성

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/build.gradle.kts`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportFormat.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportColumn.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/ExportColumnTest.kt`]
- depends-on: []

**RED**.
- `ExportColumnTest`: (1) `ExportColumn` 9개 enum이 IssueSearchHit 9필드와 1:1, (2) 각 컬럼의 영문 헤더 라벨 정확(Key/Summary/Type/Status/Assignee ID/Priority/Priority Name/Project/Updated At), (3) `extract(hit)`가 각 필드를 문자열로 반환(assigneeId null → "", updatedAt → ISO-8601 UTC), (4) `ExportColumn.parse(listOf("key","summary"))`가 화이트리스트 검증(미지원 필드명 → 예외).
- 실패: `ExportColumn` 없음.

**GREEN**.
- `ExportFormat` enum: `CSV`, `XLSX` (+ contentType, fileExtension 속성).
- `ExportColumn` enum: 9개. 각 `headerLabel: String` + `extract(hit: IssueSearchHit): String`. companion `parse(names: List<String>?): List<ExportColumn>` (null/빈 → 전체, 미지원 → IllegalArgumentException).
- build.gradle.kts: `implementation("org.apache.poi:poi-ooxml:5.4.0")` 추가(버전 고정 — 신규 의존성 [[claude 환각 방지]] 검증된 최신 안정).

**REFACTOR**. updatedAt 직렬화는 검색 응답과 동일 포맷 재사용(중복 제거). KDoc 한 줄.

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportColumnTest"`

### Task 2. CSV writer (UTF-8 BOM + RFC 4180 + formula injection 방어)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/CsvExportWriter.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/CsvExportWriterTest.kt`]
- depends-on: [1]

**RED**.
- `CsvExportWriterTest`: (1) 선두 바이트 `EF BB BF`(BOM), (2) 헤더 행 = 선택 컬럼 라벨, (3) 쉼표/따옴표/개행 포함 셀 RFC 4180 이스케이프(`"..."`, 내부 `""`), (4) formula injection: `=`,`+`,`-`,`@`,탭,CR 시작 셀에 `'` prefix, (5) 빈 결과 → 헤더만, (6) 한글 셀 정상(UTF-8 디코딩 round-trip), (7) assigneeId null → 빈 셀.
- 실패: `CsvExportWriter` 없음.

**GREEN**.
- `CsvExportWriter.write(out: OutputStream, columns: List<ExportColumn>, rows: List<IssueSearchHit>)`: BOM 출력 → 헤더 → 행. 셀 처리 = formula 방어 후 RFC 4180 이스케이프. UTF-8.

**REFACTOR**. 이스케이프/방어를 private 함수 분리. 위험 시작문자 상수화.

**검증**: `./gradlew :modules:search-export-import:test --tests "*CsvExportWriterTest"`

### Task 3. XLSX writer (Apache POI + formula injection 방어)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/XlsxExportWriter.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/XlsxExportWriterTest.kt`]
- depends-on: [1]

**RED**.
- `XlsxExportWriterTest`(POI로 round-trip 재읽기): (1) 시트 1개, 헤더 행 = 컬럼 라벨, (2) 데이터 셀 값 일치, (3) formula injection 셀 `'` prefix(POI도 문자열 셀로 강제 — `=`로 시작해도 수식 평가 안 되게), (4) 빈 결과 → 헤더만, (5) 한글 셀 정상, (6) 모든 셀 STRING 타입(숫자도 문자열화 — 일관성·injection 안전).
- 실패: `XlsxExportWriter` 없음.

**GREEN**.
- `XlsxExportWriter.write(out, columns, rows)`: `XSSFWorkbook` 1 시트, 헤더 + 행. 셀은 `setCellValue(String)` + formula 방어. try-with-resources(workbook.close).

**REFACTOR**. formula 방어 로직을 Task 2와 공유(`ExportCellSanitizer` 공용 util) — 중복 제거.

**검증**: `./gradlew :modules:search-export-import:test --tests "*XlsxExportWriterTest"`

### Task 4. ExportService (AQL 파싱 + count-first 순회 + 1만 상한 + writer 디스패치 + Clock)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportService.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/export/ExportLimitExceededException.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/export/ExportServiceTest.kt`]
- depends-on: [1, 2, 3]

**RED**(IssueSearchPort mock).
- `ExportServiceTest`: (1) AQL query → AqlParser → IssueSearchQuery 구성(viewerUserId, sort 반영), (2) **count-first**: 첫 페이지 `total` > 10000 → `ExportLimitExceededException(resultCount, limit)` 즉시(추가 순회 안 함 — mock 호출 횟수 검증), (3) total ≤ 10000 → 페이지 순회로 전체 행 수집(예: total=250, size=100 → 3페이지 호출), (4) format CSV/XLSX 디스패치, (5) 파일명 = `{projectKey}-issues-{Clock 고정시각}.{ext}`(Clock 주입 결정성), (6) AQL 문법오류 → AqlSyntaxException 전파.
- 실패: `ExportService` 없음.

**GREEN**.
- `ExportService(searchPort, aqlParser, clock)`: parse → count-first total 체크 → 순회 수집(EXPORT_PAGE_SIZE=100, MAX_ROWS=10000) → ExportFormat에 따라 writer 선택 → `ByteArray`/스트림 + 파일명 반환(`ExportResult(filename, contentType, bytes)`).

**REFACTOR**. 상수(MAX_ROWS, PAGE_SIZE) companion. 순회 루프 가독성.

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportServiceTest"`

### Task 5. ExportController + ExportRequest DTO + 에러 매핑

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/ExportController.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/dto/ExportRequest.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/SearchErrorCodes.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/SearchExceptionHandler.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/web/ExportControllerTest.kt`]
- depends-on: [4]

**RED**(MockMvc 또는 슬라이스).
- `ExportControllerTest`: (1) `POST /api/v1/exports` CSV → 200 + `Content-Type: text/csv; charset=UTF-8` + `Content-Disposition: attachment; filename=...csv` + body BOM, (2) XLSX → 200 + xlsx contentType, (3) 상한초과 → 400 `errorCode=EXPORT_LIMIT_EXCEEDED`(에러 봉투), (4) AQL 문법오류 → 400(기존 syntax 에러 계약), (5) format 누락/오타 → 400, (6) columns 미지원 필드 → 400, (7) projectKey/query 누락 → 400(@Valid).
- 실패: `ExportController` 없음.

**GREEN**.
- `ExportRequest(projectKey @NotBlank, query @NotBlank @Size(max=2000), format: ExportFormat, columns: List<String>? )`.
- `ExportController.export()`: actor 추출(SecurityContext) → ExportService 호출 → `ResponseEntity<ByteArray>` + 헤더. SecurityException → 403(기존 패턴).
- `SearchErrorCodes`에 `EXPORT_LIMIT_EXCEEDED` 추가. `SearchExceptionHandler`에 `ExportLimitExceededException` → 400 매핑(**기존 핸들러 basePackages/assignableTypes 스코프 확인** — [[domain-exception-http-handler-basepackage-scope]] / [[catch-all-exceptionhandler-swallows-responsestatusexception]]: 새 컨트롤러 예외가 핸들러에 안 잡히거나 catch-all이 401/403 삼키지 않는지).

**REFACTOR**. 헤더 조립 헬퍼. actor 추출 기존 SearchController와 동일 패턴 재사용.

**검증**: `./gradlew :modules:search-export-import:test --tests "*ExportControllerTest"`

### Task 6. 백엔드 통합 테스트 (D5 — Testcontainers end-to-end)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/test/kotlin/com/bts/search/integration/ExportIntegrationTest.kt`]
- depends-on: [5]

**RED→GREEN**(실 IssueSearchPort 어댑터 + 시드, 기존 search 통합테스트 부팅 레시피 재사용).
- (1) 시드 이슈 → CSV export → 바이트 BOM + 행 파싱 검증, (2) XLSX export → POI 재읽기 검증, (3) **BROWSE 권한 없는 actor → 403**, (4) **미가시 보안등급 이슈는 결과 제외**(보안 술어 상속 실증 — [[crossbc-resolver-nullable-fail-open]] 정신), (5) 상한초과 → 400 EXPORT_LIMIT_EXCEEDED, (6) formula injection 셀 end-to-end `'` prefix.
- ArchUnit: export가 jOOQ 직접 접근 안 함(IssueSearchPort만) — 기존 BC 격리 룰 통과 확인([[archunit-vacuous-rule-silent-pass]]: vacuous 아닌지).

**검증**: `./gradlew :modules:search-export-import:test`

### Task 7. D6 프론트 — Export 다이얼로그 + blob 다운로드

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/search/ExportDialog.tsx`, `apps/web/src/components/search/ExportDialog.test.tsx`, `apps/web/src/api/search.ts`(또는 exports.ts — **같은 BC 선례 grep** [[frontend-api-convention-per-bc]]), `apps/web/src/routes/search.tsx`(Export 버튼 통합), `apps/web/src/mocks/handlers.ts`(또는 search handler)]
- depends-on: [5]

**RED→GREEN**(vitest + RTL).
- (1) /search 결과 있을 때 Export 버튼 → 다이얼로그(형식 CSV/XLSX 라디오 + 9 컬럼 체크박스), (2) 내보내기 → `POST /api/v1/exports` 호출(현재 AQL query + projectKey + format + 선택 columns), (3) **blob 다운로드**: 응답이 JSON 아닌 파일 → **apiFetch가 JSON 기대 시 raw fetch + blob 처리 필요**([[auth-pre-session-401-raw-fetch]]와 동형의 특수 경로 — credentials/CSRF 헤더는 유지), `URL.createObjectURL` + anchor click, (4) 상한초과 400 → 에러 메시지(FR-EX-02 안내) 표시, (5) Zod는 파일 응답이라 불요(계약은 요청 측만).
- MSW: export 핸들러는 작은 고정 바이트(BOM 포함) 반환. **stateful 불요**(다운로드는 부수효과) — 단 핸들러 추가가 정석([[e2e-msw-serviceworker-block]]).

**REFACTOR**. 다운로드 로직 `lib/download.ts` 헬퍼 추출(재사용). 다이얼로그는 기존 `SaveFilterDialog` radix 패턴 답습.

**검증**: `pnpm --filter web test ExportDialog && pnpm --filter web typecheck`(CI는 tsconfig.app — [[ci-typecheck-tsconfig-app-vs-local]]).

### Task 8. D7 E2E (Playwright)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/export.spec.ts`, `apps/web/src/mocks/handlers.ts`(E2E 시나리오 핸들러 보강 시)]
- depends-on: [7]

**시나리오**.
- (1) /search 검색 → Export 버튼 → CSV 선택 → 내보내기 → 다운로드 이벤트 발생(`page.waitForEvent('download')`) + 파일명 패턴 검증, (2) 형식 XLSX 전환 후 다운로드, (3) 컬럼 부분선택 후 다운로드. (브라우저 다운로드는 MSW 통과 — file blob 응답 핸들러 필요. 텍스트 중복 버튼은 컨테이너 한정 [[playwright-getbyrole-exact-strict-mode]].)
- 기존 E2E 무회귀 확인([[ui-pr-defer-e2e-regression-latent]]) — PRE_EXISTING 17(FR-AU-07)은 본 작업 무관([[fr-ux-03-inbox-frontend-done]]).

**검증**: `pnpm --filter web test:e2e export`

## Plan 메타

- task 수: 8 (백엔드 6 + 프론트 1 + E2E 1)
- depends-on 그래프: T1→{T2,T3}→T4→T5→{T6, T7}→T8. (T2/T3 병렬, T6/T7 병렬 — 단 T2/T3는 같은 모듈 컴파일 직렬화)
- 예상 wave: 6 (백엔드 모듈 단일이라 직렬 비중 큼)
- TDD 강제: yes (test 커밋 먼저)
- 신규 외부 의존성: `org.apache.poi:poi-ooxml:5.4.0` (Maxi 승인 완료, Task 1)
- cross-BC 변경: **0** (issue-tracking·shared-kernel 무변경, IssueSearchPort 재사용)
- 보안 점검 포인트(codereview): formula injection 방어(T2/T3), 보안 술어 상속 실증(T6), 에러 핸들러 스코프(T5)
- 추가 검증: ktlint/detekt(모듈 직접 재실행), pnpm typecheck(tsconfig.app), playwright

## 리뷰 결과 (← /bts-review-plan 채움)
