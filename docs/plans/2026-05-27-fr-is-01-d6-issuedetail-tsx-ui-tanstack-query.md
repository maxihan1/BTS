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

## Plan

모든 task agent=`frontend-engineer`. TDD red→green→refactor 강제. 테스트=Vitest+RTL, API=MSW. `vi.mock`은 컴포넌트/함수 단위 좁은 범위(learnings 2026-05-26). commit 시 `git add <file>` 파일 단위(lint-staged race 회피, learnings 2026-05-26).

### Task 1. 이슈 API 모듈 + Zod 스키마 (`@/api/issues.ts`)

**메타.**
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`]
- depends-on: []

**RED.** `issues.test.ts` — (a) `issueResponseSchema`가 IssueResponse 9필드 파싱 + createdAt/updatedAt nullable, (b) 단건 `DataResponse` 언래핑, (c) 목록 `Page<IssueResponse>`(래퍼 없음) 파싱, (d) fetchIssue/fetchIssues/createIssue/updateIssue/deleteIssue가 올바른 method·path·body로 `apiFetch` 호출(MSW). 실패: 모듈 없음.

**GREEN.** `issues.ts` — Zod 스키마(issueResponseSchema, dataResponse 래퍼, issuePageSchema) + 5 함수. 기존 `@/api/client.ts`의 `apiFetch`/`apiGet`/`apiPost` 재사용(`@/api/workflows.ts` 패턴).

**REFACTOR.** 타입 export(`IssueResponse`, `CreateIssueInput`, `UpdateIssueInput`) + KDoc + 헤더 한국어 주석.

**검증.** `pnpm -C apps/web test issues.test`

### Task 2. toast — sonner 도입 (`@/components/ui/sonner` + Toaster)

> Maxi 승인(게이트1): sonner 새 의존성 도입 확정.

**메타.**
- files: [`apps/web/package.json`, `apps/web/src/components/ui/sonner.tsx`, `apps/web/src/components/ui/sonner.test.tsx`, `apps/web/src/main.tsx`]
- depends-on: []

**RED.** `sonner.test.tsx` — `<Toaster>` 렌더 + `toast.error(msg)` 호출 시 메시지가 접근성 영역(role)으로 노출. 실패: 모듈 없음.

**GREEN.** `pnpm -C apps/web add sonner`. shadcn sonner 래퍼(`components/ui/sonner.tsx`, DESIGN.md 토큰 정렬 — `theme`/`toastOptions`). `<Toaster richColors />` 를 main.tsx에 마운트(QueryClientProvider 인접).

**REFACTOR.** KDoc + 헤더 한국어 주석. 409/일반 오류 메시지 카피 상수화.

**검증.** `pnpm -C apps/web test sonner.test` + `pnpm -C apps/web typecheck`

### Task 3. 요약 수정 훅 (`useUpdateIssueSummary`) — 완전 낙관적 + 409 토스트

**메타.**
- files: [`apps/web/src/api/useUpdateIssueSummary.ts`, `apps/web/src/api/useUpdateIssueSummary.test.ts`]
- depends-on: [1, 2]

**RED.** test — (a) onMutate가 cancelQueries + snapshot + 캐시 선반영(낙관적), (b) 성공 시 응답 `version` 반영, (c) 409 VERSION_CONFLICT 시 snapshot 롤백 + invalidate 재조회 + toast 호출, (d) onSettled invalidate. `ApiError(409)` 모킹. 실패: 훅 없음.

**GREEN.** `useMutation` 래핑 훅. `updateIssue(key, {summary, expectedVersion})`. onMutate/onError/onSettled. 409는 `ApiError.status===409 && body.errorCode==='VERSION_CONFLICT'` 분기 → toast.

**REFACTOR.** queryKey 상수화(`['issue', key]`) + KDoc.

**검증.** `pnpm -C apps/web test useUpdateIssueSummary.test`

### Task 4. 삭제 훅 (`useDeleteIssue`)

**메타.**
- files: [`apps/web/src/api/useDeleteIssue.ts`, `apps/web/src/api/useDeleteIssue.test.ts`]
- depends-on: [1]

**RED.** test — deleteIssue(key) 성공 시 목록 캐시 invalidate + onSuccess 콜백(navigate용) 호출. 404는 toast. 실패: 훅 없음.

**GREEN.** `useMutation` 래핑. `deleteIssue(key)`.

**REFACTOR.** KDoc + queryKey 상수 공유.

**검증.** `pnpm -C apps/web test useDeleteIssue.test`

### Task 5. 이슈 목록 페이지 (`routes/issues.index.tsx`)

**메타.**
- files: [`apps/web/src/routes/issues.index.tsx`, `apps/web/src/routes/issues.index.test.tsx`]
- depends-on: [1]

**RED.** test — `IssueListPage`(props 기반) + 라우트 어댑터. useQuery fetchIssues. 3-상태(로딩/에러/성공) + 빈 목록 빈 상태 + 항목(키·요약·상태 배지) + "새 이슈" 링크 + 항목 클릭 시 상세 링크 + 페이지네이션. MSW Page 응답. 실패: 모듈 없음.

**GREEN.** IssueListPage + IssueListRouteAdapter(code-based, workflows.$key 패턴). shadcn card/button.

**REFACTOR.** 항목 컴포넌트 추출 + KDoc.

**검증.** `pnpm -C apps/web test issues.index.test`

### Task 6. 이슈 생성 폼 (`routes/issues.new.tsx`)

**메타.**
- files: [`apps/web/src/routes/issues.new.tsx`, `apps/web/src/routes/issues.new.test.tsx`]
- depends-on: [1]

**RED.** test — projectKey + summary 입력. 빈 summary 검증 차단(서버 도달 전). 제출 시 createIssue 호출 → 201 응답 key로 `/issues/$key` navigate. PROJECT_NOT_FOUND(404)는 폼 에러 표시. MSW. 실패: 모듈 없음.

**GREEN.** IssueCreateForm + 어댑터. shadcn form/input/label/button.

**REFACTOR.** Zod 폼 검증 + KDoc.

**검증.** `pnpm -C apps/web test issues.new.test`

### Task 7. 이슈 상세 페이지 (`routes/issues.$key.tsx`) — 시안 2 + 수정/삭제 연결

**메타.**
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`]
- depends-on: [1, 3, 4]

**RED.** test — `IssueDetailPage`(props 기반) + RouteAdapter(useParams). useQuery fetchIssue 3-상태(로딩/404 role=alert/성공). 시안 2 레이아웃(좌 본문: breadcrumb+제목, 우 메타패널: 상태 **읽기전용 배지**+보고자+프로젝트+버전+생성·수정). 제목 인라인 편집 → useUpdateIssueSummary 연결. 삭제 버튼+확인 → useDeleteIssue 연결 후 목록 navigate. 실패: 모듈 없음.

**GREEN.** IssueDetailPage + adapter. useUpdateIssueSummary(T3)/useDeleteIssue(T4) 와이어링. createdAt/updatedAt null → "—".

**REFACTOR.** 메타패널 컴포넌트 추출 + KDoc. 시안 2 목업 대조.

**검증.** `pnpm -C apps/web test issues.\$key.test`

### Task 8. 라우터 등록 + 네비 링크

**메타.**
- files: [`apps/web/src/router.ts`, `apps/web/src/router.test.tsx`, `apps/web/src/routes/dashboard.tsx`]
- depends-on: [5, 6, 7]

**RED.** `router.test.tsx` — `/issues`, `/issues/new`, `/issues/$key` 라우트 resolve + `requireAuth` 가드(dashboard 일관). dashboard에 "이슈" 링크 1개. 실패: 라우트 미등록.

**GREEN.** router.ts에 3 라우트 추가(어댑터 import, `staticData:{requireAuth:true}`, `beforeLoad: requireAuth`) + dashboard 네비 링크.

**REFACTOR.** 주석 갱신(헤더의 "4개 라우트" → 7개).

**검증.** `pnpm -C apps/web test router.test` + `pnpm -C apps/web verify`

## Plan 메타

- task 수: 8
- 예상 wave: 4 (w1: T1·T2 / w2: T3·T4·T5·T6 / w3: T7 / w4: T8). 파일 겹침 0 + depends-on 기준.
- TDD 강제: yes (red→green→refactor)
- 병렬 dispatch: bts-impl이 메타(depends-on + files)로 wave 계산
- 추가 검증: typecheck, eslint(no-console), vitest, 최종 `pnpm verify`. E2E(D7)는 별도 + 백엔드 wiring 선결
- 결정(게이트1 완료): toast = **sonner 도입** (Maxi 승인). 게이트1 승인 → bts-impl 진입.

## 리뷰 결과

### plan-design-review (2026-05-27, ui 타입)

- ✅ 상태 커버리지. 목록 로딩/에러/빈상태(T5), 상세 3-상태 로딩/404 role=alert/성공(T7). workflows.$key 선례와 일관.
- ✅ 접근성(WCAG AA). role=alert 에러(T7), toast role=status(T2), 키보드 탐색·44px 터치(spec NFR). 디자인 시스템 DESIGN.md §8 준수.
- ✅ 디자인 시스템. shadcn 기존 컴포넌트 + DESIGN.md 토큰. 임의 색/폰트 0. 시안 2 레이아웃.
- ✅ 낙관적 피드백. T3 onMutate 선반영 + 409 VERSION_CONFLICT 토스트 + 재조회.
- ⚠️ 주의 1. 시안 2 목업의 상태 select 드롭다운은 전이 제외로 **읽기전용 배지**로 구현. plan T7 + spec에 명시 — 일관 확인.
- ⚠️ 주의 2. 긴 summary 말줄임 + 빈 목록 빈상태는 spec 엣지케이스에 있으나 task 본문엔 암묵 — T5/T7 구현 시 명시 반영 권장.
- 🛑 BLOCKER. 없음.

미해결 결정(게이트1). toast = in-house 최소 구현(기본 권장) vs sonner 새 의존성 도입(승인 필요).
