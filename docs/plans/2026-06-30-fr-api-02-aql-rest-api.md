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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
