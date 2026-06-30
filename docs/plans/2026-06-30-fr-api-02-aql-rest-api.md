# FR-API-02 — AQL 검색 REST API 표준화

> slug: fr-api-02-aql-rest-api
> type: api
> agent: backend-engineer
> 생성: 2026-06-30

## Brief

FR-API-02 — AQL 검색 REST API 표준화.

`POST /api/v1/search/aql` 엔드포인트를 FR-API-01(#207/#208)이 확립한 REST API 표준
(cursor 페이지네이션 · 응답 envelope `{data, meta.page}` · RFC 7807 ProblemDetail ·
springdoc OpenAPI 3.1 + Swagger UI)에 맞춰 표준화한다.

- 논리 BC: search-export-import (§5.2). 물리 구현은 FR-SR-02에서 신설된 search-export-import 모듈.
- 선행: §2.2 AQL(FR-SR-02에서 구현 완료) · §5.1 FR-API-01(완료, #207/#208)
- Plan slug(product 문서 표기): search/api-aql
- D2 명세 핵심: 동기/비동기 응답 정책
- D6 프론트 UI 없음 / D7 E2E(contract test)

classify 결과: type=api, agent=backend-engineer

## 도메인 정리

- **BC**: search-export-import (검색은 자체 모듈 `backend/modules/search-export-import/`에
  컨트롤러 보유 — issue-tracking에 구현된 FR-API-01과 다름)
- **새 용어**: 없음 (AQL·cursor·envelope·ProblemDetail 모두 기존 용어, glossary §검색/AQL 등재됨)

### 영향 컴포넌트 (모두 search-export-import 모듈, 신규 BC 신설 0)
- `web/SearchController.kt` — `POST /api/v1/search/aql` 응답을 envelope로 + OpenAPI 어노테이션
- `web/dto/AqlSearchResponse.kt` — 응답 envelope DTO 신규(`AqlSearchPageResponse` 등)
- `web/SearchExceptionHandler.kt` / `web/SearchErrorCodes.kt` — 기존 유지(cursor 코드 불필요)
- `OpenApiConfig`(신규) + `build.gradle.kts` springdoc 의존성(신규) — search 모듈
- `shared-kernel/IssueSearchPort` — 재사용(변경 없음, 보안 술어 구조적 상속 유지)

### 핵심 결정 (ADR 생성됨)
1. **페이지네이션 = offset 유지 + envelope 정렬**. cursor는 AQL 사용자 ORDER BY(동적 정렬)와
   keyset seek(고정 정렬 키) 충돌로 **후속 FR 분리**. 프론트(`api/search.ts`) offset 파싱 중 →
   offset 유지로 회귀 0.
2. **응답 = 동기 검색**. 대량(1만행+)은 기존 비동기 export job(FR-EX-02) 위임. 검색 자체 비동기 없음.
3. **OpenAPI = search 모듈에 springdoc 신규 도입**. issue-tracking OpenApiConfig의 bearerAuth 규약 따름.

### 기존 결정 충돌
- 없음. FR-API-01 표준 중 envelope/ProblemDetail/OpenAPI만 채택, cursor는 의도적 보류
  (FR-API-01 ADR "후속에서 cursor 이전" 방향과 정합).

### 관련 ADR
- [docs/decisions/2026-06-30-fr-api-02-aql-search-offset-envelope.md](../decisions/2026-06-30-fr-api-02-aql-search-offset-envelope.md) (생성됨)
- 선행: [docs/decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md](../decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md)

## 스펙

전체 스펙. [docs/specs/2026-06-30-fr-api-02-aql-rest-api.md](../specs/2026-06-30-fr-api-02-aql-rest-api.md)

핵심 요약.
- `POST /api/v1/search/aql` 응답을 raw Spring `Page` → envelope `{data, meta:{page:{number,size,totalElements,totalPages}}}`로 전환 (FR-API-01과 동일 외피, offset 메타)
- search 모듈에 springdoc OpenAPI 신규 도입(@Tag/@Operation/@ApiResponses/@SecurityRequirement, bearerAuth)
- **프론트 동반 수정**(FR-5): `api/search.ts`(aqlSearchPageSchema) + `routes/search.tsx`(소비처) envelope 파싱 — 무회귀, 소비처 1곳
- 보안(IssueSearchPort)·파서·SEARCH_* ProblemDetail·요청 DTO는 변경 없이 재사용

## Brainstorming Check

✅ 통과 (직접 스펙 self sanity-check, 1회).
- 포착한 gap: 응답 외피 변경 → 프론트 파싱 회귀 → FR-5 동반 수정으로 해소. 제네릭 envelope OpenAPI 스키마 누락(EC5), 0건/초과 page 일관성(EC1/EC2) 명시.
- ❓ 게이트1 Maxi 확인 항목: 프론트 동반 수정이 product D6 "프론트 UI 해당 없음" 표기와 형식상 어긋남(실질은 기존 파싱 어댑테이션). D6 표기 유지 + PR 본문 사유 명시 방침.

## Plan

> 표준 envelope (모든 task 계약 단일 출처 — drift 방지):
> ```json
> { "data": [ <AqlSearchHit>, ... ],
>   "meta": { "page": { "number": 0, "size": 50, "totalElements": 142, "totalPages": 3 } } }
> ```
> AqlSearchHit 필드(기존 유지): key, summary, typeKey, currentStateKey, assigneeId(nullable),
> priority, priorityName, projectKey, updatedAt.

### Task 1. envelope DTO 신규 + SearchController 응답 전환

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/dto/AqlSearchPageResponse.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/SearchController.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/web/SearchControllerTest.kt`]
- depends-on: []

**RED**:
- 파일: `SearchControllerTest.kt`
- C1 테스트를 envelope 구조 단언으로 변경:
  ```kotlin
  // 기존 $.content[0].key → envelope로
  .andExpect(jsonPath("$.data[0].key").value("ATLAS-1"))
  .andExpect(jsonPath("$.meta.page.number").value(0))
  .andExpect(jsonPath("$.meta.page.size").value(50))
  .andExpect(jsonPath("$.meta.page.totalElements").value(1))
  .andExpect(jsonPath("$.meta.page.totalPages").value(1))
  ```
- 실패: 현재 응답은 `$.content`(raw Page), `$.data` 없음 → 단언 실패

**GREEN**:
- 신규 `AqlSearchPageResponse.kt`:
  ```kotlin
  data class AqlSearchPageResponse<T>(val data: List<T>, val meta: PageMeta)
  data class PageMeta(val page: PageInfo)
  data class PageInfo(val number: Int, val size: Int, val totalElements: Long, val totalPages: Int)
  ```
- `SearchController.search()` 반환을 `ResponseEntity<AqlSearchPageResponse<AqlSearchHit>>`로 변경.
  기존 `Page<AqlSearchHit>` → envelope 매핑 (`page.content`, `page.number`, `page.size`, `page.totalElements`, `page.totalPages`).
- 0건도 `data:[]` 보장(EC1), data:null 금지.

**REFACTOR**:
- `Page<T>.toAqlEnvelope()` 확장함수로 매핑 추출 + KDoc(한글 1줄 헤더 규칙).

**검증**: `./gradlew :backend:search-export-import:test --tests "*SearchControllerTest"` (gradle task 경로는 impl에서 issue-tracking 선례로 확정)

### Task 2. springdoc 도입 + OpenApiConfig + 엔드포인트 어노테이션

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/build.gradle.kts`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/config/OpenApiConfig.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/web/SearchController.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/config/OpenApiAnnotationTest.kt`]
- depends-on: [1]   # SearchController.kt 파일 겹침(직렬) + OpenApiConfig 전제

**RED**:
- 신규 `OpenApiAnnotationTest.kt` — 검색 엔드포인트 OpenAPI 메타 단언:
  ```kotlin
  // /v3/api-docs (또는 OpenAPI bean) 에서
  // - paths."/api/v1/search/aql".post.tags 에 "Search" 포함
  // - operationId / summary 설정됨
  // - security 에 bearerAuth 요구
  // - 200 응답 스키마가 AqlSearchPageResponse(구체 타입) 참조
  ```
- 실패: springdoc 의존성·어노테이션 부재 → api-docs 미생성/태그 누락

**GREEN**:
- `build.gradle.kts`: `implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")` (issue-tracking 버전 일치)
- 신규 `config/OpenApiConfig.kt`: `@OpenAPIDefinition`(info: Atlas Search API) + `@SecurityScheme(name="bearerAuth", HTTP/bearer/JWT)` — issue-tracking 규약 동일, BEARER_AUTH_SCHEME="bearerAuth"
- `SearchController.search()`에 `@Tag(name="Search")` + `@Operation`(operationId, summary, offset 페이지네이션 설명) + `@ApiResponses`(200=AqlSearchPageResponse 구체타입, 400/401/403 ProblemDetail) + `@SecurityRequirement(name=BEARER_AUTH_SCHEME)`
- 제네릭 erasure 방지: 200 `@Schema(implementation = AqlSearchPageResponse::class)` (EC5)

**REFACTOR**:
- API 제목/설명/버전 상수 추출(issue-tracking OpenApiConfig 패턴).

**검증**: `./gradlew :backend:search-export-import:test --tests "*OpenApiAnnotationTest"`

### Task 3. 통합 + contract 테스트 (실 DB envelope 순회 + api-docs 정합)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/test/kotlin/com/bts/search/integration/SearchAqlSliceTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/integration/SearchOpenApiContractTest.kt`]
- depends-on: [2]

**RED**:
- `SearchAqlSliceTest` 응답 단언을 envelope로 갱신 + 신규 케이스:
  - S2: 120건 seed, size=50, page=1 → `data` 51~100, `meta.page.number=1`, `totalPages=3`
  - S3: 0건 → `data:[]`, `totalElements:0`, `totalPages:0`
  - S4: `ORDER BY priority DESC` + page=1 → priority 정렬 2페이지 envelope (offset 무충돌)
- 신규 `SearchOpenApiContractTest`: `/v3/api-docs` 응답에 `/api/v1/search/aql` 경로·AqlSearchPageResponse 스키마·bearerAuth 노출 단언 (실제 응답 형태 ↔ 스펙 일치)
- 실패: 기존 raw Page 단언/contract 미작성

**GREEN**:
- Task 1/2 구현으로 통과(테스트 추가가 주). 형태 불일치 시 매핑 보정.

**REFACTOR**:
- seed 헬퍼 / 페이지 순회 단언 헬퍼 정리.

**검증**: `./gradlew :backend:search-export-import:test --tests "*SearchAqlSliceTest" --tests "*SearchOpenApiContractTest"`

### Task 4. 프론트 envelope 파싱 동반 수정 (무회귀)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/search.ts`, `apps/web/src/routes/search.tsx`, `apps/web/src/mocks/search-handlers.ts`, `apps/web/src/mocks/search-fixtures.ts`, `apps/web/src/api/search.test.ts`]
- depends-on: []   # 백엔드 코드 import 없음(병렬). 단 envelope 형태는 상단 계약 JSON과 정확히 일치 — drift 금지

**RED**:
- `search.test.ts` PAGE_FIXTURE를 envelope `{data, meta:{page:{...}}}`로 변경 → 기존 `aqlSearchPageSchema`(content/totalElements...)가 파싱 실패

**GREEN**:
- `api/search.ts`: `aqlSearchPageSchema`를 envelope 구조로 재정의:
  ```ts
  const pageInfoSchema = z.object({ number: z.number().int().nonnegative(),
    size: z.number().int().positive(), totalElements: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative() })
  export const aqlSearchPageSchema = z.object({
    data: z.array(aqlSearchHitSchema), meta: z.object({ page: pageInfoSchema }) })
  ```
- `routes/search.tsx`: 소비처를 `content`→`data`, `totalElements`→`meta.page.totalElements`,
  `number`/`size`/`totalPages` 동일 경로로. `first`/`last`/`empty`는 derive(`page.number===0`,
  `page.number>=page.totalPages-1`, `page.totalElements===0`)
- `mocks/search-handlers.ts` + `search-fixtures.ts`: PAGE/EMPTY fixture를 envelope로(백엔드 형태 일치)

**REFACTOR**:
- derive 헬퍼(isFirst/isLast/isEmpty) 정리.

**검증**: `pnpm --filter web typecheck && pnpm --filter web test -- search`

## Plan 메타

- task 수: 4
- 예상 wave: 3 (Wave1: T1·T4 병렬 / Wave2: T2 / Wave3: T3)
- 예상 시간: 직렬 약 12분, wave 적용 약 8분
- TDD 강제: yes
- 병렬 dispatch: 같은 search 모듈 백엔드(T1→T2→T3) 직렬(파일 겹침+모듈 충돌 회피, 메모리 [fr-sr-03 모듈당1]),
  프론트(T4)만 T1과 병렬. 계약 형태는 상단 envelope JSON 단일 출처.
- 추가 검증: ktlintCheck, detekt(모듈 baseline), pnpm verify, E2E(search 화면 무회귀)

## 리뷰 결과

### plan-eng-review (집중 독립 리뷰, 2026-06-30)
- ✅ TDD 형식: 4 task 모두 RED/GREEN/REFACTOR + 메타(agent/files/depends-on) 완비.
- ✅ 트랜잭션: 검색 read-only, 경계 이슈 없음.
- ✅ 파일 겹침 직렬화: T1·T2가 `SearchController.kt` 겹침 → T2 depends-on [1] 명시 (모듈당1 정책 반영).
- ✅ 보안: `IssueSearchPort` 재사용(BROWSE/visibility 상속), 변경 0.
- ✅ 절대 규칙: 신규 파일 한글 헤더, 제네릭 erasure 방어(EC5 `@Schema(implementation)`).
- ⚠️ 주의(impl 인계): T3 통합/contract 테스트는 "구현(T1/T2) 후 통과" 성격이 강함. BTS TDD 게이트(test 커밋이
  feat보다 먼저)를 만족하려면 **기존 raw Page 단언에서 출발해 envelope 단언으로 바꿔 RED를 실제로 보이게** 할 것.
- BLOCKER: 없음.

### plan-devex-review (집중 독립 리뷰, 2026-06-30)
- ⚠️ **CONCERN-1 (API 일관성 — 게이트1 Maxi 확인 필요)**: FR-API-01에서 `GET /api/v1/issues`의
  **offset 모드는 raw Spring Page를 유지**했고 cursor 모드만 envelope였다. 본 FR은 AQL의 offset 응답을
  **envelope로 전환**한다. 결과적으로 두 엔드포인트의 offset 응답 형태가 일시적으로 갈린다
  (issues offset=raw Page, search offset=envelope). 표준 방향(envelope)에는 부합하나, issue 목록 offset이
  아직 raw라 전면 정합은 후속(FR-API-01 ADR "offset→cursor 이전은 후속"과 동일 맥락). → 수용 가능한 일시적
  불일치로 판단하되 게이트1에서 명시 확인.
- ⚠️ CONCERN-2 (OpenAPI 모듈 통합): search `OpenApiConfig`는 issue-tracking과 별개 모듈. 현 구조는 전체 앱
  배포 조립 부재(test-assembled가 표준). search 모듈 자체 컨텍스트의 `/v3/api-docs` 검증으로 충분하며,
  다중 모듈 OpenApiConfig 병합은 본 FR 범위 밖.
- ✅ Breaking change 범위: AQL 검색 소비자는 프론트 단일(FR-SR-02 도입). 외부 PAT 소비자 없음(FR-API-04 미구현).
  FR-5 프론트 동반으로 회귀 0. 사내 단일 소비자라 v1/v2 분리 불필요.
- BLOCKER: 없음.

### 종합
- BLOCKER 0. CONCERN 2건(둘 다 수용 가능, 후속/범위밖). 게이트1에서 CONCERN-1(offset 형태 불일치) Maxi 확인.
