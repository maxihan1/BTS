<!-- FR-EX-01 CSV/XLSX Export의 BC 경계 + 입력 계약 + 의존성 + 동기 상한 결정 ADR -->

# ADR — FR-EX-01 필터 결과 CSV/XLSX Export: 입력 계약 + 데이터 접근 + 의존성

- 날짜: 2026-06-29
- 상태: 채택 (Accepted)
- 관련 FR: FR-EX-01
- 관련 PR: #203
- 선행 ADR: [2026-06-25-fr-sr-02-aql-parser-and-bc.md](2026-06-25-fr-sr-02-aql-parser-and-bc.md), [2026-06-23-fr-sr-01-issue-filter-bc.md](2026-06-23-fr-sr-01-issue-filter-bc.md)

## 맥락 (Context)

FR-EX-01(`docs/plan/product/search-export-import.md §3.1`)은 "필터 결과 CSV/XLSX Export"다.
선행은 §2.1(FR-SR-01 필터) + §2.2(FR-SR-02 AQL)로 둘 다 완료됐다. product D단계는 ExportRequest 도메인,
필드 선택 + 한글 인코딩(UTF-8 BOM), `POST /api/v1/exports`(CSV + Apache POI XLSX), Excel 검증을 명세한다.

코드베이스 조사 결과:

1. **search-export-import 모듈이 이미 부트스트랩됨.** FR-SR-02(PR #189)에서 7번째 BC로 신설. AQL 렉서/파서
   (`com.bts.search.aql.AqlLexer`/`AqlParser`) + `SearchController.POST /api/v1/search/aql` + `SavedFilter` 도메인 보유.
   FR-SR-02 ADR D2 결과가 이 모듈을 "향후 Export/Import/REST API FR의 본거지"로 명시했다.
2. **검색 데이터 접근은 `IssueSearchPort`(shared-kernel) 경유.** search 모듈은 jOOQ 생성 코드(issue-tracking 전용)에
   직접 접근할 수 없다(ArchUnit BC 격리). issue-tracking `IssueSearchAdapter`가 포트를 구현하며
   BROWSE 권한 게이트 + visibility 보안 술어(AND 자동 결합, 우회 불가)를 이미 적용한다.
3. **포트 입출력은 AQL AST 기반.** `IssueSearchQuery(projectKey, ast, sort, viewerUserId, page, size)` →
   `IssueSearchPage(items: List<IssueSearchHit>, total, page, size)`. `IssueSearchHit`은 9필드
   (key, summary, typeKey, currentStateKey, assigneeId, priority, priorityName, projectKey, updatedAt). 페이지 size 최대 100.
4. **필터 경로(FR-SR-01)는 별도.** `GET /api/v1/issues?filter=`(issue-tracking `IssueController`)가
   status/assignee/label/component(`BoardCardFilter`)를 받아 `IssueResponse`(30+필드)를 돌려준다. AQL 경로와 다른 모듈·다른 DTO.
5. **CSV/XLSX 라이브러리·Export 코드 전무.** 어느 모듈에도 Apache POI/OpenCSV 의존성이 없고 export 구현도 없다.

## 결정 (Decision)

### D1. 구현 BC — search-export-import 모듈 (BC 신설 없음)

Export 엔드포인트(`POST /api/v1/exports`) + CSV/XLSX 직렬화를 **search-export-import 모듈**에 둔다.
FR-SR-02 ADR이 예고한 본거지이며, AQL 파서가 이 모듈에 있어 입력 파싱이 자족적이다.
issue-tracking·shared-kernel은 **변경하지 않는다**(cross-BC 변경 0).

### D2. 입력 계약 — AQL 쿼리 기반 단일 경로

`POST /api/v1/search/export` 요청 바디 = `{ projectKey, query(AQL 문자열), format(CSV|XLSX), columns?(선택 컬럼) }`.
서버는 query를 `AqlParser`로 파싱 → AST → `IssueSearchPort.search()`로 결과 수집 → 직렬화.

**엔드포인트 URL(Maxi 2026-06-29, devex 리뷰).** `/api/v1/search/export` — 검색 하위 네임스페이스(`POST /api/v1/search/aql`과 같은 계층, SearchController 일관·발견성). FR-EX-02 비동기는 `POST /api/v1/search/export-jobs`(202 + job 리소스)로 분리 진화 → 동기 200-파일과 비동기 202-job의 URL 의미 충돌 회피.

**필터(GET /issues?filter=) 경로는 이번 범위에서 미지원.** 근거: (a) AQL이 검색의 정본 입력이고 `IssueSearchPort` 재사용으로
BC 격리가 깔끔하다. (b) /search 페이지(AQL + 저장필터 UI, FR-SR-03)가 export 진입점으로 자연스럽다. (c) 필터 경로를 export하려면
issue-tracking 필터 로직을 cross-BC로 호출해야 해 경계가 복잡해진다. assignee/component 기반 export는 AQL MVP 필드 확장(FR-SR 후속)으로 자연 해결된다.

product 제목의 "필터 결과"는 "검색/조회한 결과"의 일반 의미로 해석하며, 본 ADR이 그 입력을 AQL로 확정한다.

### D3. Export 컬럼 — IssueSearchHit 9필드 고정 (포트 무변경)

export 컬럼 후보는 `IssueSearchHit`의 9필드로 고정한다. product D2 "필드 선택"은 이 9개 중 사용자가 부분 선택하는 것으로 구현한다
(미지정 시 전체). 포트/어댑터/`IssueSearchHit`을 확장하지 않는다 → issue-tracking·shared-kernel 무변경, cross-BC 파급 0.

- `assigneeId`는 UUID 그대로 출력한다(사람 이름 해석은 cross-BC 사용자 조회가 필요 → 후속 범위).
- dates(start/due/target)·labels·description·reporter 등 풍부한 필드는 포트 확장이 필요하므로 본 FR 범위에서 제외(후속 검토).

### D4. 직렬화 — CSV 손수 작성 + XLSX Apache POI

- **CSV**: 외부 라이브러리 없이 손수 작성. **UTF-8 BOM** 선두 바이트(`EF BB BF`)로 Excel 한글 깨짐 방지. RFC 4180 따옴표 이스케이프
  (쉼표/따옴표/개행 포함 셀은 `"`로 감싸고 내부 `"`는 `""`). BTS 미니멀 인프라 철학(AQL 파서 손수 작성, Kafka→pgmq, OpenSearch→FTS)과 일관.
- **XLSX**: **Apache POI(`org.apache.poi:poi-ooxml`) 신규 의존성 도입**(Maxi 승인, DEVELOPMENT.md §외부 의존성).
  XLSX는 ZIP+XML 복합 포맷이라 손수 구현이 비현실적이고 POI가 사실상 표준이다. 행 상한이 1만건(D5)이라 일반 `XSSFWorkbook`로 충분
  (스트리밍 `SXSSF`는 대용량 FR-EX-02에서 검토).

### D5. 동기 Export 상한 — 1만건

동기 export는 최대 1만 행. 초과 시 명시적 에러(결과 수 + "대용량은 FR-EX-02 비동기 export 사용" 안내)로 거부한다.
근거: product가 ">1만건"을 FR-EX-02(비동기)로 명시 → 1만건이 두 FR의 자연 경계.
`IssueSearchPort`가 페이지(max size 100) 기반이므로 export는 페이지를 순회해 최대 1만 행까지 수집한다.

### D6. 권한/보안 — 검색 경로의 불변식 그대로 상속

export는 `IssueSearchPort.search()`를 그대로 호출하므로 BROWSE 권한 게이트 + visibility 보안 술어(AND 자동 결합)가
검색과 동일하게 적용된다. export 전용 우회 경로를 만들지 않는다 → 사용자가 볼 수 없는 이슈는 export에도 나오지 않는다.

**검증 분리(리뷰 B1 — vacuous 회피).** search-export-import 모듈은 issue-tracking에 gradle 의존이 없어 실 `IssueSearchAdapter`(BROWSE 게이트 + visibility SQL)가 테스트 클래스패스에 없다. 따라서 search 모듈 테스트는 (a) 매핑층(포트 SecurityException → 403, export가 검색과 동일 viewerUserId/AST로 포트 호출하는지 slot capture)만 검증하고, (b) 데이터 제외(미가시 이슈 빠짐)는 FR-SR-02의 기존 실증 테스트(issue-tracking)를 인용한다. mock 포트로 데이터 제외를 재증명하면 가짜 그린이 된다.

### D7. 에러 처리 — 전용 ExportExceptionHandler + ProblemDetail 봉투 재사용 (리뷰 B2/C1/C2)

- 기존 `SearchExceptionHandler`는 `@RestControllerAdvice(assignableTypes=[SearchController])`로 한정 → ExportController에 적용 안 됨. **전용 `ExportExceptionHandler`(assignableTypes=[ExportController])를 신설**한다(SearchController 에러 계약 오염 방지). limit(400)·AQL syntax(400)·SecurityException(403)·ResponseStatusException(401)·validation(400)을 모두 매핑.
- 에러 봉투는 기존 RFC 7807 ProblemDetail + `problem()` 헬퍼 패턴 재사용(신규 봉투 발명 금지). 사람 메시지는 `detail`, 커스텀 값(`resultCount`/`limit`)은 `setProperty`.
- 에러 코드는 `SEARCH_` prefix 유지(`SEARCH_EXPORT_LIMIT_EXCEEDED` 등) — 같은 BC, SearchErrorCodes §6 규칙. 컬럼/포맷 검증 실패는 기존 `SearchValidationException`(400) 재사용(`IllegalArgumentException`은 catch-all로 500이 되므로 금지).

### D8. 입력 검증 — 수동 검증 병행 + 헤더 인젝션 방어 (리뷰 devex-C3/B3)

- `@Valid`만 의존하지 않고 SearchController `validateRequest()` 선례처럼 컨트롤러 수동 검증 병행(Hibernate Validator 부재 환경 false-green 회피).
- `projectKey`는 영숫자+하이픈 패턴 검증(Content-Disposition 파일명 삽입 전 — 헤더 인젝션 방어, IssueController PDF export 선례 동형).
- `format`은 nullable String 수신 후 parse(오타 시 허용값 노출 메시지).

## 결과 (Consequences)

- search-export-import 모듈에 `ExportController`(`POST /api/v1/search/export`) + `ExportExceptionHandler`(전용) + `ExportService` + CSV/XLSX writer + `ExportCellSanitizer`(공용) + `ExportRequest`/`ExportFormat`/`ExportColumn` 추가.
- build.gradle.kts에 `org.apache.poi:poi-ooxml` 추가(첫 POI 도입). 버전은 BOM/최신 안정 버전으로 spec/plan에서 고정.
- issue-tracking·shared-kernel **무변경**. `IssueSearchPort`/`IssueSearchHit` 재사용.
- fr-index/SDD의 FR-EX-01 BC 매핑(search-export-import)·카운트 무변경(카운트 영향 0).
- product §3.1 D단계: D3(데이터 모델)은 "활용"이라 신규 테이블/마이그레이션 없음(동기 export는 영속 상태 불요). FR-EX-02에서 export_jobs 도입.
- 후속: assignee 이름 해석, 풍부한 컬럼(dates/labels), 필터 경로 export, 대용량 비동기(FR-EX-02), Import(FR-IM).

## 대안 (Rejected)

- **필터(GET /issues?filter=) 경로도 export 지원.** 기각(이번 범위) — cross-BC 호출 + 포트 2개 + DTO 2종 복잡도. AQL 단일 입력으로 충분하고 /search가 자연 진입점.
- **IssueSearchHit을 풍부한 필드로 확장.** 기각(이번 범위) — shared-kernel 포트 + issue-tracking 어댑터 cross-BC 변경 동반. 9필드로 MVP 충족, 후속 확장.
- **CSV도 라이브러리(OpenCSV) 도입.** 기각 — CSV는 손수 작성으로 충분(RFC 4180 + BOM). 의존성 최소화.
- **XLSX 손수 구현(POI 미도입).** 기각 — ZIP+XML 복합 포맷 손수 구현은 비현실적·고위험. POI가 표준.
- **별도 export BC/모듈 신설.** 기각 — search-export-import가 이미 Export의 의도된 본거지(FR-SR-02 ADR D2).
