<!-- FR-API-01 이슈 CRUD REST API 표준화의 cursor 병행 도입 + 응답 envelope + OpenAPI 전역 통합 결정 ADR -->

# ADR — FR-API-01 이슈 CRUD REST API 표준화: cursor 병행 페이지네이션 + 응답 envelope + OpenAPI

- 날짜: 2026-06-30
- 상태: 채택 (Accepted)
- 관련 FR: FR-API-01
- 관련 PR: #207
- 선행: SDD 11장(API 설계) §11.1 원칙 · §11.3 응답 표준 · §11.8 OpenAPI 3 문서

## 맥락 (Context)

FR-API-01(`docs/plan/product/search-export-import.md §5.1`)은 "이슈 CRUD REST API (표준화)"다.
product D단계는 (D1) API 응답 표준(페이지네이션·에러 포맷), (D2) OpenAPI 3.1 스펙, (D4) cursor pagination +
bulk ops 표준 적용, (D5) OpenAPI 스펙 검증 + contract test를 명세한다. 논리적으로는 search-export-import FR이나
데이터·API 본체가 issue-tracking이므로 **구현 모듈은 issue-tracking**이다(선행 FR들이 issues 컨트롤러를 확장해 온 전례 일치).

코드베이스 실측 결과:

1. **버저닝은 이미 `/api/v1` 전면 적용.** 모든 컨트롤러가 `@RequestMapping("/api/v1/...")`. 신규 작업 아님.
2. **목록 페이지네이션은 offset 기반.** `IssueController.list`(GET /api/v1/issues)와 `.changelog`가
   Spring `Pageable`(`?page=N&size=M`)을 받아 raw `Page<T>`를 직렬화 반환한다(`{content, pageable, totalElements, ...}`).
   **`Pageable`/`Page<`를 import하는 컨트롤러는 issue-tracking 전체에서 `IssueController` 하나뿐**이다.
   나머지 목록 API(watchers/components/versions/worklogs/links/templates/custom-fields/attachments)는 unpaged `List` 반환.
3. **프론트(apps/web)가 offset Page를 광범위 소비.** `.content`/`totalElements`/`pageable`을 issue/changelog/inbox/
   audit/search 다수 컴포넌트·훅·MSW에서 읽는다. → 응답 형태를 바꾸면 프론트 대규모 회귀.
4. **단건 응답 래퍼는 `DataResponse<T>`(`{data: ...}`)** 로 SDD 11.3 성공 표준의 `data` 키와 정합. 단 `meta`는 없다.
5. **에러 포맷은 이미 RFC 7807 `ProblemDetail` + `errorCode`.** 단 (a) `RestControllerAdvice`가 BC별/컨트롤러별로
   분산(`assignableTypes`/`basePackages` 한정)되어 있고, (b) `type`이 상대 토큰("issue-not-found")이라
   SDD 11.3의 절대 URI(`https://atlas.docs/errors/...`)와 형식이 다르다.
6. **springdoc-openapi 미통합.** 어느 모듈에도 springdoc 의존성이 없다(jackson-databind-nullable만 존재).
7. **bulk ops는 이미 존재(FR-IS-05).** `BulkOperationController`가 `POST /api/v1/issues/bulk-update`,
   `GET /api/v1/bulk-operations/{id}`, `POST /api/v1/issues/bulk-transitions/available`를 비동기 202+폴링으로 제공.

핵심 긴장: SDD 11.1은 "Pagination: cursor (offset 대신)"를 원칙으로 명시하나, 현 시스템 전체가 offset으로 구현됐고
프론트가 이를 소비 중이다. 원칙을 글자대로 "전면 전환"하면 프론트 대공사 + BC 격리 위반(한 PR이 backend+frontend 경계를 넘음)이다.

## 결정 (Decision)

Maxi 확정(2026-06-30 AskUserQuestion):

### D1. cursor pagination은 **병행(parallel) 도입** — offset 미제거, 프론트 무회귀

`GET /api/v1/issues`에 cursor 모드를 **추가**하되 기존 offset 모드를 **제거하지 않는다**.

- **요청 분기.** `?cursor=<token>&limit=N` 이 오면 cursor 모드, 기존 `?page=N&size=M`(또는 무파라미터)이면 offset 모드.
- **응답 분기(중요).** "envelope 통일"과 "프론트 무회귀"는 응답 형태를 동시에 만족할 수 없다
  (현 목록은 raw `Page`라 `{data, meta}` envelope으로 바꾸면 그 자체가 회귀). 따라서 **cursor 모드만**
  SDD 11.3 envelope(`{data:[...], meta:{page:{next}}}`)을 반환하고, **offset 모드는 기존 `Page<T>` 응답을 그대로 유지**한다.
  → 외부 통합은 cursor+표준 envelope를 사용, 기존 프론트는 offset+`Page`를 계속 사용(무회귀).
- **cursor 토큰.** 불투명(opaque) 문자열. 정렬 키(예: `(updatedAt desc, id desc)` 또는 안정 정렬용 `id`)를
  Base64URL로 인코딩. 클라이언트는 내부를 파싱하지 않는다. 위변조/역호환을 위해 인코딩 포맷 버전 prefix 포함.
- **정렬 안정성.** cursor는 keyset pagination이므로 **고유·단조 정렬 키**(tie-breaker로 `id`)가 필수. seek 조건은 `WHERE (sortKey, id) < (:cursorSortKey, :cursorId)`.

### D2. 응답 envelope 표준 — cursor 경로 한정

cursor 응답은 `{ "data": [...], "meta": { "page": { "next": "<cursor|null>", "limit": N } } }`.
`next`가 null이면 마지막 페이지. 단건 응답은 기존 `DataResponse`(`{data}`) 유지(이미 표준 정합).

### D3. 에러 포맷 — RFC 7807 유지·보강(전환 아님)

이미 `ProblemDetail` 기반이므로 형식을 **갈아엎지 않는다**. 이슈 CRUD 경로 핸들러가 SDD 11.3 필드
(`type`/`title`/`status`/`detail`/`instance`/`errorCode`)를 일관 충족하는지 검증·보강한다.
`type` 절대 URI화는 전역 영향이 크므로 본 PR에서는 이슈 API 한정으로 정렬하고, 전역 일괄 정렬은 후속으로 둔다(범위 통제).

### D4. OpenAPI — springdoc **전역 통합** + Swagger UI 게시

`springdoc-openapi-starter-webmvc-ui` 전역 통합 + `/swagger-ui` + `/v3/api-docs`(SDD 11.8: `/api/v1/docs`·`/api/v1/openapi.json` 경로 정렬) 게시(명세 §A).
이슈 CRUD/목록 엔드포인트에 OpenAPI annotation을 우선 부여하고 OpenAPI 3.1로 생성한다.

### D5. 적용 범위 — issue-tracking 전 목록 API, 단 cursor는 실효 대상만

표준(envelope·OpenAPI annotation)은 issue-tracking 목록 API 전반을 대상으로 하되,
**cursor pagination은 실제 페이지네이션이 의미 있는 큰 목록(이슈 목록, changelog)에 우선 적용**한다.
unpaged List 목록의 envelope 적용 여부·범위는 spec 단계에서 회귀 영향 전수 조사 후 확정한다(무회귀 원칙 우선).

### D6. bulk ops — 기존 FR-IS-05 정비, 신규 도메인 0

bulk 비동기 모델(FR-IS-05)은 그대로 두고, OpenAPI 문서화 + 응답/에러 표준 정합 검증만 수행한다.
REST 표준 bulk create/delete 신규 추가는 본 FR 범위 밖(필요 시 후속 FR).

## 대안 (Alternatives)

- **전면 cursor 전환 + 프론트 동반 수정.** SDD 11.1에 가장 충실하나 대규모 회귀 + BC 격리 위반. 기각(D1).
- **표준만 문서화하고 cursor 구현 보류.** 범위 최소이나 D4(cursor 적용) 명세 미충족. 기각.
- **에러 type 전역 절대 URI 일괄 전환.** 전역 핸들러 다수 동시 변경 → 폭발 반경 큼. 본 PR 범위 밖으로 분리(D3).

## 결과 (Consequences)

- **장점.** 외부 통합용 표준(cursor envelope + OpenAPI 문서)을 확립하면서 기존 프론트 무회귀. BC 격리 유지(백엔드 단독 PR).
- **비용.** 같은 엔드포인트가 두 페이지네이션 모드를 가져 일관성이 일시적으로 약화. → 후속에서 프론트를 cursor로 이전하고 offset deprecate 경로 권장(문서에 명시).
- **회귀 가드.** cursor 토큰 인코딩/디코딩 round-trip 테스트, keyset 정렬 안정성 테스트(동률 키 tie-break), OpenAPI 스펙 ↔ 실제 응답 contract test.
