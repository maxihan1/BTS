# FR-API-01 — 이슈 CRUD REST API 표준화

> slug: fr-api-01-issue-crud
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-30

## Brief

사용자 원문: "fr-api-01 진행하자"

FR-API-01 — 이슈 CRUD REST API (표준화). 명세 `docs/plan/product/search-export-import.md §5.1`.
- 우선순위: 필수
- 선행: issue-tracking §2.1.1
- Plan slug(명세): `search/api-issue-crud`

D 단계 (명세):
- D1. 도메인 — API 응답 표준 (페이지네이션, 에러 포맷) (backend-engineer)
- D2. 명세 — OpenAPI 3.1 스펙 작성 (backend-engineer)
- D3. 데이터 모델 — (활용) (db-engineer)
- D4. 백엔드 — issue-tracking API에 cursor pagination + bulk ops 표준 적용 (backend-engineer)
- D5. 백엔드 테스트 — OpenAPI 스펙 검증 + contract test (backend-engineer)
- D6. 프론트 UI — (해당 없음 — API 문서는 §A) (-)
- D7. E2E — Postman/Insomnia 시나리오 (qa-engineer)

classify 결과: type=api, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **구현 BC**: issue-tracking (논리적 FR은 search-export-import §5.1, 데이터·API 본체가 issue-tracking)
- **영향 엔티티**: 없음 — 도메인 신설 0. 이 작업은 **transport(API 계약) 표준화**. cursor 토큰/envelope/OpenAPI는 도메인 개념이 아니라 기술 계약 → glossary 미추가, ADR로 기록.
- **현재 상태 실측**:
  - 버저닝 `/api/v1` 이미 전면 적용 (신규 아님)
  - `Pageable`/`Page<` 사용 컨트롤러는 **`IssueController` 하나뿐** (이슈 목록 + changelog). 나머지 목록 API는 unpaged List 반환
  - 단건 응답 `DataResponse<T>`(`{data}`) — SDD 11.3 정합, meta 없음
  - 에러 포맷 이미 RFC 7807 `ProblemDetail` + `errorCode` (BC별 핸들러 분산, type 상대 토큰)
  - springdoc 미통합, bulk ops는 FR-IS-05로 이미 존재

- **핵심 결정 (Maxi 확정, 2026-06-30)**:
  1. **cursor pagination 병행 도입** — offset 미제거, 프론트 무회귀. cursor 모드만 envelope 반환, offset은 기존 `Page` 유지
  2. **OpenAPI 전역 springdoc 통합** + Swagger UI 게시 (명세 §A)
  3. **적용 범위 = issue-tracking 전 목록 API** — 단 cursor는 실효 대상(이슈 목록/changelog) 우선, unpaged 목록 envelope은 spec서 회귀 영향 조사 후 확정
  4. 에러 포맷은 RFC 7807 유지·보강(전환 아님), type 절대 URI화는 이슈 API 한정
  5. bulk ops는 FR-IS-05 정비/문서화만, 신규 도메인 0

- **기존 결정 충돌**: SDD 11.1 "offset 대신 cursor" 원칙 ↔ 현 offset 구현 + 프론트 소비. **병행 도입으로 해소**(전면 전환 기각).
- **관련 ADR**: [docs/decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md](../decisions/2026-06-30-fr-api-01-cursor-pagination-envelope.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-30-fr-api-01-issue-crud.md](../specs/2026-06-30-fr-api-01-issue-crud.md)

핵심 시나리오 3줄 요약.
- 외부 클라이언트는 `?cursor=&limit=N` → `{data, meta.page.next}` envelope으로 끊김 없이 순회(keyset seek), 기존 프론트는 `?page&size` offset Page 그대로(무회귀)
- 위변조/형식오류 cursor·모드 충돌(cursor+page)은 400 RFC 7807 ProblemDetail
- springdoc 통합 → `/swagger-ui`+`/v3/api-docs`(OpenAPI 3.1), 이슈 CRUD + 전 목록 API annotation, contract test로 스펙↔응답 동기화

**범위 해석(게이트1 재확인)**: cursor+envelope 실적용=이슈목록/changelog, "전 목록 API"=OpenAPI 문서화 대상(unpaged 목록 응답형태 불변, 무회귀 우선).

## Brainstorming Check

✅ 통과 (1회 iteration). adversarial sanity check로 gap 3건 발견·보강(cursor 위변조 방어 근거·OpenAPI Bearer 스킴·springdoc 범위 issue-tracking 한정). Maxi 결정 필요 gap 없음.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
