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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
