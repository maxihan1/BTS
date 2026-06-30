# FR-API-02 — AQL 검색 REST API 표준화 — 스펙

> slug: fr-api-02-aql-rest-api · type: api · BC: search-export-import
> 작성: 2026-06-30 · ADR: [2026-06-30-fr-api-02-aql-search-offset-envelope](../decisions/2026-06-30-fr-api-02-aql-search-offset-envelope.md)

## 배경 한 줄

`POST /api/v1/search/aql`(FR-SR-02 구현)는 현재 raw Spring `Page<AqlSearchHit>`를 반환한다.
FR-API-01이 확립한 REST 표준 중 **envelope `{data, meta.page}` + springdoc OpenAPI**를 적용한다.
cursor는 AQL 동적 정렬과 충돌해 후속 FR로 보류한다(ADR §1).

## 사용자 시나리오 (Given-When-Then)

### S1. API 소비자가 표준 envelope로 검색 결과를 받는다
- **Given** 인증된 사용자가 프로젝트 ATLAS에 BROWSE 권한 보유
- **When** `POST /api/v1/search/aql` `{projectKey:"ATLAS", query:"status = open", page:0, size:50}` 호출
- **Then** `200` + `{data:[...히트...], meta:{page:{number:0, size:50, totalElements:N, totalPages:M}}}` 반환

### S2. 다음 페이지를 offset으로 넘긴다
- **Given** 검색 결과가 120건, size=50
- **When** `page:1` 요청
- **Then** `data`에 51~100번째 히트, `meta.page.number=1`, `totalPages=3`

### S3. 결과가 0건이어도 envelope 구조는 유지된다
- **When** 매칭 0건 쿼리
- **Then** `200` + `{data:[], meta:{page:{number:0, size:50, totalElements:0, totalPages:0}}}`

### S4. 사용자 정렬(ORDER BY) + offset 페이지네이션이 함께 동작한다
- **When** `query:"status = open ORDER BY priority DESC", page:1, size:20`
- **Then** priority DESC 정렬된 결과의 2번째 페이지를 envelope로 반환 (offset이므로 동적 정렬과 무충돌)

### S5. API 문서가 OpenAPI/Swagger UI에 노출된다
- **When** `/v3/api-docs` 또는 `/swagger-ui` 조회
- **Then** `POST /api/v1/search/aql`가 `Search` 태그·요청/응답 스키마·bearerAuth 보안요구와 함께 문서화됨

### S6. AQL 문법 오류는 기존 ProblemDetail로 반환된다 (회귀 없음)
- **When** `query:"status = "` (불완전)
- **Then** `400` + RFC 7807 ProblemDetail `{errorCode:"SEARCH_SYNTAX_ERROR", position:..}` (기존 동작 유지)

## 기능 요구사항 (FR)

- **FR-1** `POST /api/v1/search/aql` 응답을 raw Spring `Page` → envelope `{data, meta.page}`로 전환한다.
- **FR-2** `meta.page`는 offset 메타를 담는다: `number`(0-base 현재 페이지), `size`, `totalElements`, `totalPages`.
  - FR-API-01과 동일한 `{data, meta:{page:{...}}}` 외피 구조를 유지하되, 내부 필드는 offset용으로 정의한다.
  - `first`/`last`/`empty`는 derivable(number/totalPages/totalElements에서 계산)하므로 envelope에 포함하지 않는다.
- **FR-3** 요청 DTO `AqlSearchRequest`(projectKey/query/page/size)는 **변경 없이 유지**한다(검증 규칙 동일).
- **FR-4** search-export-import 모듈에 springdoc-openapi 의존성 + `OpenApiConfig`(bearerAuth 스킴)를 신규 도입하고,
  검색 엔드포인트에 `@Tag`/`@Operation`/`@ApiResponses`/`@SecurityRequirement`를 적용한다.
  issue-tracking `OpenApiConfig`의 `bearerAuth` 스킴 규약을 그대로 따른다.
- **FR-5** 프론트(`apps/web/src/api/search.ts`의 `aqlSearchPageSchema` + `routes/search.tsx` 소비처)를
  envelope 파싱으로 동반 수정한다 — 화면 동작 무회귀. (D6 "새 화면 없음"은 유지, 기존 파싱만 어댑테이션)
- **FR-6** 기존 자산은 변경하지 않고 재사용한다: `SearchExceptionHandler`/`SearchErrorCodes`(SEARCH_*),
  `IssueSearchPort`(BROWSE 권한·visibility 보안 술어 구조적 상속), AQL Lexer/Parser/Adapter.

## 비기능 요구사항 (NFR)

- **NFR-1 (무회귀)** 검색 동작·보안 술어·정렬·에러 포맷은 기존과 동일. envelope는 외피 변경만.
- **NFR-2 (BC 격리)** 작업은 search-export-import 모듈 + search 화면 view layer에 한정.
  issue-tracking cursor 인프라를 건드리지 않는다(cross-BC 변경 0).
- **NFR-3 (문서 정합)** OpenAPI 스펙이 실제 응답/요청 스키마와 일치(contract test로 검증).

## API 인터페이스 (REST)

```
POST /api/v1/search/aql
Authorization: Bearer <JWT>
Content-Type: application/json

요청 (변경 없음):
{ "projectKey": "ATLAS", "query": "status = open ORDER BY priority DESC", "page": 0, "size": 50 }

응답 200 (envelope 신규):
{
  "data": [
    { "key": "ATLAS-1", "summary": "...", "typeKey": "bug", "currentStateKey": "open",
      "assigneeId": "uuid|null", "priority": 1, "priorityName": "Critical",
      "projectKey": "ATLAS", "updatedAt": "2026-06-30T..Z" }
  ],
  "meta": { "page": { "number": 0, "size": 50, "totalElements": 142, "totalPages": 3 } }
}

에러 (변경 없음): RFC 7807 ProblemDetail, errorCode = SEARCH_* (400/401/403/500)
```

## 데이터 모델 변경

- **없음.** 신규 테이블/마이그레이션 0. IssueSearchPort 쿼리 경로 재사용.

## 엣지 케이스

- **EC1** 0건 결과 → `data:[]` + `totalElements:0, totalPages:0` (S3). `data:null` 금지.
- **EC2** `page`가 마지막 페이지 초과(예: totalPages=3인데 page=10) → `data:[]` + meta는 요청 page/size 반영
  (Spring Page 기본 동작 유지, 200).
- **EC3** `size > 100` / `query > 2000자` / 빈 query → 기존 `SEARCH_VALIDATION_FAILED` 400 (회귀 없음).
- **EC4** ORDER BY 지정 검색의 page 넘김에서 정렬 안정성 — offset이므로 동일 page 재요청 시 동일 결과
  (단, 검색 사이 데이터 변경 시 offset 특유의 drift는 cursor 후속 FR에서 해결, 본 FR 범위 밖).
- **EC5** OpenAPI 응답 스키마가 제네릭 envelope를 표현 — `@Schema(implementation = AqlSearchPageResponse::class)`로
  구체 타입 노출(제네릭 erasure로 인한 스키마 누락 방지).

## 제약 조건

- envelope DTO는 search 모듈 자체 정의(`web/dto/`). issue-tracking `CursorPageResponse`를 import하지 않음(BC 격리).
- 프론트 동반 수정은 search 화면 한정. 다른 화면 영향 0.
- cursor 미도입(ADR §1). `INVALID_CURSOR` 류 errorCode 추가 금지.

## 측정 가능한 완료 기준

1. `POST /api/v1/search/aql`가 envelope `{data, meta.page}`를 반환 — 슬라이스 테스트로 구조 단언.
2. offset 페이지 순회(S2)·0건(S3)·ORDER BY+page(S4) 통합 테스트 통과(실 DB).
3. OpenAPI 스펙에 검색 엔드포인트가 Search 태그·bearerAuth·응답 스키마와 함께 노출 — annotation 테스트.
4. contract test: `/v3/api-docs` 응답 스키마 ↔ 실제 응답 형태 일치.
5. 프론트 `search.tsx` E2E/단위 테스트가 envelope 파싱으로 무회귀 통과.
6. 기존 SEARCH_* 에러 테스트(C2~C9) 전부 그린 유지.
7. 모듈 `test` + `ktlintCheck` + `detekt` 그린, 프론트 `pnpm verify` 그린.

## Brainstorming Check (self-review, 직접 스펙)

표준화 FR이므로 office-hours/brainstorming 스킬 대신 직접 작성 후 self sanity-check 수행. 점검 결과.

- ✅ **프론트 회귀 gap 포착** — raw Page→envelope 전환이 `search.ts`/`search.tsx` 파싱을 깨므로
  FR-5로 동반 수정 명시(소비처 1곳, 범위 작음). 도메인 단계에서 "offset 유지=회귀0"은 페이지네이션 방식
  한정이었고, 응답 외피 변경은 별도 회귀임을 분리해 명시.
- ✅ **제네릭 envelope OpenAPI 스키마 누락 위험**(EC5) — 구체 DTO로 노출.
- ✅ **0건/초과 page envelope 일관성**(EC1/EC2) — data:[] 보장.
- ✅ **모듈 springdoc 부재** — search 모듈에 의존성·설정 신규(FR-4)로 명시. (issue-tracking에만 존재 확인)
- ❓ **잔여 결정(게이트1에서 Maxi 확인)**: 프론트 동반 수정(FR-5)이 product D6 "프론트 UI 해당 없음" 표기와
  형식적으로 어긋남. 실질은 "새 화면 없음 + 기존 파싱 어댑테이션"이라 D6 표기는 유지하되, PR 본문에 사유 명시.
