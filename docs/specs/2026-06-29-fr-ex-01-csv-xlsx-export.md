# FR-EX-01 필터 결과 CSV/XLSX Export — 스펙

> FR: FR-EX-01 | BC: search-export-import | SDD §3.1 | 우선순위: 필수
> 도메인 결정: ADR `docs/decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md`
> 선행: FR-SR-01(필터)·FR-SR-02(AQL) 완료. 후속: FR-EX-02(대용량 비동기)

## 개요

AQL 검색 결과를 CSV 또는 XLSX 파일로 **동기** 다운로드한다. 입력은 AQL 쿼리(FR-SR-02와 동일 파서),
데이터 접근은 `IssueSearchPort` 재사용(검색의 BROWSE 권한 + visibility 보안 술어 그대로 상속).
컬럼은 `IssueSearchHit` 9필드. 동기 상한 1만 행. issue-tracking·shared-kernel 무변경.

## 사용자 시나리오 (Given-When-Then)

### S1. CSV Export (정상)
- **Given** /search 페이지에서 AQL 쿼리로 검색해 결과가 보이는 상태
- **When** "Export" 버튼 → 다이얼로그에서 형식 CSV 선택 → 내보내기
- **Then** 현재 AQL 쿼리의 전체 결과(검색 페이지네이션과 무관, 최대 1만 행)가 CSV로 다운로드된다. 한글이 Excel에서 깨지지 않는다(UTF-8 BOM).

### S2. XLSX Export (정상)
- **Given** S1과 동일
- **When** 형식 XLSX 선택 → 내보내기
- **Then** `.xlsx` 파일이 다운로드되고 Excel/Numbers/LibreOffice에서 정상 열린다. 헤더 행 + 데이터 행 구조.

### S3. 컬럼 부분 선택
- **Given** 다이얼로그에서 컬럼 체크박스(9개) 노출
- **When** 일부만 체크(예: key, summary, currentStateKey) 후 내보내기
- **Then** 선택한 컬럼만, 선택 순서(또는 고정 표준 순서)대로 출력된다. 미선택 시 전체 9컬럼.

### S4. 빈 결과
- **Given** 결과 0건인 AQL 쿼리
- **When** 내보내기
- **Then** 헤더 행만 있는 빈 파일이 정상 다운로드된다(에러 아님).

### S5. 상한 초과
- **Given** 결과가 1만 행을 초과하는 쿼리
- **When** 내보내기
- **Then** 명시적 에러(결과 수 + "대용량은 비동기 Export(FR-EX-02) 사용")로 거부되고 파일은 생성되지 않는다.

### S6. 권한 없는 이슈 제외
- **Given** viewer가 볼 수 없는 보안 등급 이슈가 쿼리 결과에 포함될 조건
- **When** 내보내기
- **Then** 검색과 동일하게 보안 술어가 적용되어 볼 수 없는 이슈는 파일에도 나오지 않는다. (export 전용 우회 없음)

## 기능 요구사항 (FR)

- **FR-1.** `POST /api/v1/exports`로 AQL 쿼리 기반 동기 export를 제공한다.
- **FR-2.** 형식 CSV / XLSX 두 가지를 지원한다.
- **FR-3.** CSV는 UTF-8 BOM(`EF BB BF`)을 선두에 붙여 Excel 한글 호환을 보장한다.
- **FR-4.** CSV는 RFC 4180 이스케이프(쉼표/따옴표/개행 포함 셀은 `"`로 감싸고 내부 `"`는 `""`)를 적용한다.
- **FR-5.** XLSX는 Apache POI로 생성한다(헤더 1행 + 데이터 N행, 단일 시트).
- **FR-6.** 컬럼은 `IssueSearchHit` 9필드(key, summary, typeKey, currentStateKey, assigneeId, priority, priorityName, projectKey, updatedAt)에서 부분 선택 가능. 미지정 시 전체.
- **FR-7.** 동기 export는 최대 1만 행. 초과 시 거부(FR-NFR/엣지 참조).
- **FR-8.** 검색과 동일한 BROWSE 권한 + visibility 보안 술어를 `IssueSearchPort` 경유로 상속한다.
- **FR-9.** AQL 문법 오류는 검색(`POST /search/aql`)과 동일한 400 에러 계약으로 응답한다.
- **FR-10.** 응답에 `Content-Disposition: attachment; filename="..."` + 적절한 `Content-Type`을 설정한다.

## 비기능 요구사항 (NFR)

- **NFR-1 (보안 — CSV Injection 방어).** 셀 값이 `=` `+` `-` `@` (및 탭/CR)로 시작하면 Excel/스프레드시트가 수식으로 해석한다(formula injection). 위험 시작 문자에 대해 앞에 작은따옴표(`'`) prefix를 붙여 무력화한다. CSV·XLSX 모두 적용.
- **NFR-2 (성능).** 1만 행 export가 합리적 시간(목표 p95 < 5s) 내 완료. `IssueSearchPort` 페이지 순회 횟수 최소화.
- **NFR-3 (메모리).** 1만 행 × 9컬럼은 일반 `XSSFWorkbook` 메모리 처리로 충분(스트리밍 SXSSF는 FR-EX-02 범위).
- **NFR-4 (인코딩).** 모든 텍스트는 UTF-8. XLSX는 내부적으로 UTF-8(POI 기본).

## API 인터페이스 (REST)

### `POST /api/v1/exports`

요청 바디(JSON).
```json
{
  "projectKey": "PROJ",
  "query": "status = open AND priority = 1 ORDER BY updated_at DESC",
  "format": "CSV",
  "columns": ["key", "summary", "currentStateKey"]
}
```
- `projectKey` (필수, NotBlank) — 검색 대상 단일 프로젝트.
- `query` (필수, NotBlank, max 2000) — AQL. FR-SR-02 파서와 동일.
- `format` (필수) — `CSV` | `XLSX`. 그 외 값 400.
- `columns` (선택) — 9필드 화이트리스트 부분집합. 빈 배열/미지정 시 전체 9컬럼. 미지원 필드명 포함 시 400.

응답.
- 200 — 파일 스트림.
  - CSV: `Content-Type: text/csv; charset=UTF-8`, body 선두 UTF-8 BOM.
  - XLSX: `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.
  - `Content-Disposition: attachment; filename="{projectKey}-issues-{yyyyMMdd-HHmmss}.{csv|xlsx}"`.
- 400 — AQL 문법 오류 / 잘못된 format / 잘못된 columns / projectKey·query 누락(검색과 동일 에러 봉투).
- 403 — BROWSE 권한 없음(어댑터가 SecurityException → 403).
- 422(또는 400, plan에서 확정) — 결과 1만 행 초과. 본문에 `{ errorCode, message, resultCount, limit }`.

> 헤더 라벨 정책(컬럼 표시명): **Maxi 결정 필요** — 영문 필드명(`key`,`summary`,...) vs 사람친화 라벨(한글/영문). 기본 제안: 영문 표준 라벨(Key, Summary, Type, Status, Assignee, Priority, Priority Name, Project, Updated At). → Brainstorming gap.

## 데이터 모델 변경

- **없음.** 동기 export는 영속 상태가 불필요(D3 "활용"). 신규 테이블/마이그레이션 0. export_jobs는 FR-EX-02.

## 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| E1 | 결과 0건 | 헤더만 있는 빈 파일 200 |
| E2 | 결과 = 정확히 1만 행 | 정상 export(상한 포함 허용) |
| E3 | 결과 > 1만 행 | 거부(상한 초과 에러). 부분 export 안 함 |
| E4 | AQL 문법 오류 | 400(검색과 동일 SearchSyntaxException 경로) |
| E5 | BROWSE 권한 없음 | 403 |
| E6 | format 누락/오타 | 400 |
| E7 | columns에 미지원 필드 | 400(화이트리스트 검증) |
| E8 | assigneeId = null(미배정) | 빈 셀 |
| E9 | summary에 쉼표/따옴표/개행 | CSV RFC 4180 이스케이프, XLSX는 셀 문자열 그대로 |
| E10 | summary에 한글 | UTF-8 BOM(CSV)·POI UTF-8(XLSX)로 정상 |
| E11 | summary가 `=SUM(...)` 등 수식 시작 | NFR-1 formula injection 방어(`'` prefix) |
| E12 | priority 등 숫자 컬럼 | CSV는 문자열화, XLSX는 숫자 셀 또는 문자열(plan 확정) |
| E13 | updatedAt(Instant) | ISO-8601 UTC 문자열(검색 응답과 동일 직렬화) |

## 제약 조건

- **C1.** search-export-import 모듈 내부 구현. issue-tracking·shared-kernel 변경 0.
- **C2.** `IssueSearchPort`/`IssueSearchHit` 무변경 재사용(컬럼 9필드 고정).
- **C3.** 새 외부 의존성은 `org.apache.poi:poi-ooxml`만(Maxi 승인 완료). CSV는 zero-dep.
- **C4.** export는 검색의 보안 불변식을 우회하지 않는다(전용 쿼리 경로 금지).
- **C5.** 단일 프로젝트 스코프(MVP) — cross-project export는 범위 외.

## 측정 가능한 완료 기준

- [ ] `POST /api/v1/exports` CSV/XLSX 둘 다 200 다운로드(통합 테스트).
- [ ] CSV 선두 바이트 `EF BB BF` 검증(바이트 단언).
- [ ] CSV RFC 4180 이스케이프 검증(쉼표/따옴표/개행 셀).
- [ ] XLSX를 POI로 다시 읽어 헤더 + 셀 값 검증(round-trip).
- [ ] formula injection 셀(`=`,`+`,`-`,`@`)에 `'` prefix 적용 검증(CSV·XLSX).
- [ ] 컬럼 부분 선택 시 해당 컬럼만 출력 검증.
- [ ] 빈 결과 → 헤더만 파일 검증.
- [ ] 1만 행 초과 → 거부 검증.
- [ ] BROWSE 권한 없음 → 403, 미가시 이슈 제외 검증(보안 술어 상속).
- [ ] AQL 문법 오류 → 400.
- [ ] D6: /search 페이지 Export 다이얼로그(형식·컬럼 선택) + 다운로드 동작.
- [ ] D7: E2E — CSV export happy path + 형식 전환.

## Brainstorming Check
