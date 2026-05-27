# FR-IS-01 D6 — IssueDetail.tsx 프론트 UI (TanStack Query 캐싱 + 낙관적 업데이트)

> slug: fr-is-01-d6-issuedetail-tsx-ui-tanstack-query
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking
> 생성: 2026-05-27

## Brief

FR-IS-01 (이슈 CRUD + 상태 전이 검증 + 알림)의 D6 단계 — 프론트 UI.

- 대상. `IssueDetail.tsx` — 이슈 상세 화면. TanStack Query 캐싱 + 낙관적 업데이트.
- 백엔드 D1~D5 완료. `GET/POST/PATCH/DELETE /api/v1/issues` (PR #17 비즈니스 로직 + PR #23 codereview cleanup — sealed Result port + PATCH partial RFC 7396 + PR #24 Flyway namespace 격리).
- 담당 흐름. designer (디자인 스펙) → frontend-engineer (TSX 구현).
- 진척 문서 근거. `docs/plan/product/issue-tracking.md` §2.1.1 D6.

분류 결과. type=ui, agent=frontend-engineer, BC=issue-tracking.

선행 컨텍스트.
- D7 (E2E)은 풀스택 기동 필요 → 백엔드 clean build 버그(메모리 backend-clean-build-broken, project-workflow jOOQ task 의존 미선언) 선결. D6(프론트)는 무관.
- 관련 learnings. TanStack Router code-based adapter 패턴 (#11/#13), vi.mock 좁은 범위 (#20), lint-staged 병렬 race (#20), 이슈키 재사용 금지/IssueKeyRedirect (D7 직결).

## 도메인 정리

- BC. issue-tracking
- 영향 엔티티. Issue (조회/렌더만, 신규 엔티티 없음). 프론트는 도메인 모델을 변경하지 않음.
- 새 용어. **없음** (기존 용어 재사용 — Issue, IssueKey, currentStateKey, version, 소프트 삭제, IssueKeyRedirect)
- 기존 결정 충돌. 없음
- 관련 ADR. **없음** (issue-tracking BC 첫 ADR 후보. docs/decisions/ 에 issue-tracking ADR 부재)

### 백엔드 계약 (프론트 타입이 정합해야 할 정본 — `com.bts.issue.adapter.inbound.rest`)

엔드포인트 (`/api/v1/issues`).
- `POST /` → 201 `DataResponse<IssueResponse>` (`{ data }`) + `Location: /api/v1/issues/{key}` 헤더. body: `{ projectKey, summary }`
- `GET /{key}` → 200 `DataResponse<IssueResponse>`
- `GET /?projectKey=&page=&size=` → 200 **`Page<IssueResponse>`** (Spring Page, **`{ data }` 래퍼 없음** — 단건과 비대칭)
- `PATCH /{key}` → 200 `DataResponse<IssueResponse>`. body: `{ summary?, expectedVersion }`. RFC 7396 — `summary=null`이면 미변경
- `POST /{key}/transition` → 200 `DataResponse<IssueResponse>`. body: `{ toStatusKey, expectedVersion }`
- `DELETE /{key}` → 204 No Content (소프트 삭제). 이후 `GET /{key}` → 404

`IssueResponse` (정본 필드명 — 프론트 Zod/타입은 이대로).
```
{ key: string, id: string(UUID), projectKey: string, summary: string,
  currentStateKey: string, reporterId: string(UUID), version: number(Long),
  createdAt: string|null (Instant), updatedAt: string|null (Instant) }
```

에러 (RFC 7807 ProblemDetail + 커스텀 `errorCode` + `timestamp`). 9종.
`VALIDATION_FAILED`(400) · `UNAUTHENTICATED`(401) · `ACCESS_DENIED`(403) · `ISSUE_NOT_FOUND`(404) · `PROJECT_NOT_FOUND`(404) · `KEY_PREFIX_RESERVED`(409) · **`VERSION_CONFLICT`(409)** · `TRANSITION_NOT_ALLOWED`(409) · `INTERNAL_ERROR`(500)

### 도메인/계약 기반 위험 (→ /bts-spec 결정 대상)

1. **낙관적 업데이트 ↔ 백엔드 낙관락 충돌.** PATCH/transition은 `expectedVersion` 필수. 충돌 시 409 `VERSION_CONFLICT`. TanStack Query 낙관적 업데이트는 (a) 응답의 `version`을 항상 보관 (b) 변경 요청에 그 `version`을 `expectedVersion`으로 전송 (c) 409 시 낙관적 변경 롤백 + 최신 재조회 필요.
2. **응답 래퍼 비대칭.** 단건/생성/수정/전이 = `{ data: IssueResponse }`, 목록 = `Page<IssueResponse>` (래퍼 없음). API 클라이언트/Zod 스키마가 두 형태 모두 처리해야 함.
3. **인증 미연동.** 컨트롤러 ActorId가 고정 `SYSTEM_ACTOR_UUID`. D6은 별도 인증 헤더 불요하나, 기존 `apps/web/src/api/client.ts`의 세션 처리와 정합 확인 필요.
4. **필드명 주의.** 전이 요청 = `toStatusKey`(status), 응답 = `currentStateKey`(state). REST 계약 명칭 그대로 사용 (learnings #13 plan/spec drift 회피).

## 스펙

전체 스펙. [docs/specs/2026-05-27-fr-is-01-d6-issuedetail-tsx-ui-tanstack-query.md](../specs/2026-05-27-fr-is-01-d6-issuedetail-tsx-ui-tanstack-query.md)

범위 (Maxi 결정).
- **포함.** 목록(issues.tsx) + 생성 폼 + 상세(issues.$key.tsx) + 요약 인라인 수정(완전 낙관적) + 소프트 삭제.
- **상태.** `currentStateKey` 읽기 전용 배지.
- **제외.** 상태 전이(transition) — 백엔드 미연동(메모리 [[issue-transition-backend-gap]]). 후속 slice.

핵심.
- 디자인 시안 2 (사이드 메타패널, Jira풍). 목업 `apps/web/public/mockups/fr-is-01-d6-issuedetail-2.html`.
- 라우팅 code-based adapter 패턴(workflows.$key 선례). API 모듈 `@/api/issues.ts` + 기존 `apiFetch`/Zod.
- 낙관적 업데이트 onMutate/onError/onSettled, 409 VERSION_CONFLICT 롤백+재조회+토스트.

## Brainstorming Check

✅ 통과 (1 iteration). 전이 백엔드 미연동 gap 발견 → Maxi 결정으로 전이 D6 제외 + 메모리 기록. projectKey 자유입력 / 목록 진입 동선은 기본값 적용.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
