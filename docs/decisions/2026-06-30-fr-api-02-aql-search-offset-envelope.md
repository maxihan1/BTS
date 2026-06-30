# ADR: FR-API-02 — AQL 검색 REST API 표준화 (offset envelope, cursor 보류)

> 날짜: 2026-06-30
> 상태: 채택
> 관련 FR: FR-API-02 (search-export-import §5.2)
> 선행 ADR: [2026-06-30-fr-api-01-cursor-pagination-envelope](2026-06-30-fr-api-01-cursor-pagination-envelope.md)

## 맥락

FR-API-02는 `POST /api/v1/search/aql`(FR-SR-02에서 구현)을 FR-API-01이 확립한 REST API
표준에 맞춰 표준화한다. FR-API-01 표준의 구성요소는 (1) 응답 envelope `{data, meta.page}`,
(2) cursor 페이지네이션, (3) RFC 7807 ProblemDetail, (4) springdoc OpenAPI 3.1 이다.

현재 AQL 검색의 상태.
- 응답: raw Spring `Page<AqlSearchHit>` (envelope 없음), offset(page/size) 페이지네이션
- 에러: 자체 `SearchExceptionHandler` + `SEARCH_*` errorCode (이미 RFC 7807 ProblemDetail)
- 보안: `IssueSearchPort`(shared-kernel) 재사용으로 BROWSE 권한·visibility 술어 구조적 상속
- OpenAPI: search-export-import 모듈에 springdoc 의존성 없음 (issue-tracking 모듈에만 존재)

## 결정

### 1. 페이지네이션 — offset 유지 + envelope 정렬, cursor는 후속 FR로 분리

cursor 페이지네이션을 **이번 FR에서 도입하지 않는다**. 이유.

- AQL은 `ORDER BY <field> <dir>` 사용자 지정 정렬을 지원한다(`AqlParser` sort 목록).
  cursor keyset seek는 **고정 정렬 키**(`created_at DESC, id DESC`) 기준 seek가 전제다.
  사용자가 임의 정렬(예: `ORDER BY priority DESC`)을 지정하면 정렬 키가 매번 달라져
  단일 opaque cursor로 안정적 seek가 성립하지 않는다.
- 동적 정렬 키를 cursor에 인코딩하는 "완전 cursor"는 구현·검증 복잡도가 최상이고
  회귀 위험이 크다. "표준화"의 본질(일관 응답 형태·문서화)에 비해 비용 과다.

따라서 **offset(page/size) 페이지네이션을 유지**하되, 응답을 표준 envelope
`{data, meta.page}`로 정렬한다(offset용 meta: number/size/totalElements/totalPages).
프론트(`apps/web/src/api/search.ts`)는 현재 offset 응답을 파싱하므로, offset 유지는
프론트 회귀를 0으로 만든다. cursor 도입은 별도 후속 FR로 둔다(FR-API-01 ADR의
"후속에서 cursor 이전" 방향과 정합).

### 2. 응답 방식 — 동기 검색, 대량 추출은 export job 위임

AQL 검색은 **동기 페이지 응답**으로 고정한다. 1만 행 이상 대량 추출이 필요하면
이미 구현된 비동기 export job(FR-EX-02, `POST /api/v1/search/export-jobs`)을 사용한다.
검색 엔드포인트 자체에 비동기 모드를 추가하지 않는다(export job과 책임 중복 회피).

### 3. OpenAPI — search-export-import 모듈에 springdoc 신규 도입

search 모듈에 springdoc 의존성 + OpenAPI 설정을 추가하고, 검색 엔드포인트에
`@Tag`/`@Operation`/`@ApiResponses`/`@SecurityRequirement`(bearerAuth)를 적용한다.
issue-tracking의 `OpenApiConfig`와 동일한 bearerAuth 스킴 규약을 따른다.

### 4. errorCode — 기존 SEARCH_* 유지, cursor 코드 불필요

cursor를 도입하지 않으므로 `INVALID_CURSOR`/`PAGINATION_MODE_CONFLICT` 류는 추가하지
않는다. 기존 `SEARCH_*` errorCode 체계를 그대로 유지한다.

## 결과

- BC 격리 유지: 작업이 search-export-import 모듈에 한정된다. issue-tracking의 cursor
  인프라(CursorCodec/envelope)를 건드리거나 shared-kernel로 이동하는 cross-BC 변경이 없다.
- 표준화 본질(일관 envelope·ProblemDetail·OpenAPI 문서화) 달성.
- cursor 미도입은 의도적 보류이며, AQL 동적 정렬과 cursor 양립은 후속 FR에서 다룬다.

## 대안 (기각)

- **cursor 하이브리드**(기본 정렬엔 cursor, ORDER BY엔 offset): 두 모드 분기로 복잡도 상승,
  동적 정렬 미지원 cursor의 효용 제한적 → 기각.
- **완전 cursor**(정렬 키 동적 인코딩): 구현·검증 복잡도 최상, 회귀 위험 → 기각.
