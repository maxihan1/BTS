# FR-UX-06 Phase 3 PR10 — _shell pathless layout 재부모화

> slug: fr-ux-06-pr10-shell-reparent
> type: refactor (fast-track 아님 — 위험 구조 리팩터)
> agent: frontend-engineer
> primary_bc: personalization (물리 apps/web)
> 생성: 2026-07-19

## Brief

FR-UX-06 Jira 재개편 Phase 3의 핵심. `apps/web/src/router.ts`에서 loginRoute를 제외한 라우트들을
새 pathless route `_shell`(id 시작 `_` = URL 기여 0) 밑으로 재부모화한다. **`_shell` 컴포넌트는 이번
PR에선 passthrough(`<Outlet/>`, DOM 추가 0)** — 전역 사이드바는 PR11.

**목적.** 전역 사이드바(Jira 신형 통합 사이드바)를 붙일 **구조적 앵커** 확보.

**무해 증거.** 렌더 결과 동일 → 기존 E2E + `router.test.tsx` 무수정 전원 통과가 무해함의 순수한 증거.
PR10(재부모화)과 PR11(사이드바)을 반드시 분리해야 원인 추적이 가능([[tanstack-pathless-layout-router-test-blind]]).

### 실측 (2026-07-19, main=2a6d02a41)
- 라우트 **53개** 전부 `getParentRoute: () => rootRoute` (flat). `createRoute` 53건.
- routeTree 조립부 `router.ts:681` `rootRoute.addChildren([...])`, `createRouter` `:783`.
- `rootRoute`(`:62`)는 `component: RootLayout`, **beforeLoad 없음**.
- beforeLoad 가드 52개(거의 전 라우트 자체 보유). loginRoute(`:73`) = `redirectIfAuth`+`requireAuth:false`.
- `RootLayout`(`__root.tsx`)이 **`isAuthenticated`로 크롬(Header·CommandPalette·단축키) 분기** — 경로 무관.
- PR9(#294)로 route-id 결합 제거 완료 → prep 스텝 불필요.

### 설계 결정 (게이트1 확인 대상)
- **passthrough `_shell`**: 컴포넌트 = 바 `<Outlet/>`(wrapper div 없음). 크롬은 RootLayout에 그대로 → 완벽 렌더 동일. 크롬→사이드바 이관은 PR11.
- **_shell 멤버십**: loginRoute만 rootRoute 직속 유지, 나머지 52개 `_shell` 밑. (passthrough라 렌더엔 무영향, PR11 사이드바 게이팅은 isAuthenticated로 처리.)

### 🔴 절대 실패 경로 2개 (금지)
1. **loginRoute를 `_shell` 안에 넣기 금지** — `already-authed.spec.ts`가 `redirectIfAuth` 검증. loginRoute는 rootRoute 직속.
2. **가드 hoist 금지** — 52개 beforeLoad를 `_shell` 하나로 올리지 말 것(평가 순서 바뀌어 `routeGuard.test.tsx` 깨짐). `_shell`엔 beforeLoad 없음, 각 라우트 가드 그대로.

## 도메인 정리 (← /bts-domain, 타깃 코드 검증으로 대체 — 새 도메인 개념 아님)

실측(worktree, base=main 2a6d02a41):
1. 라우트 **53개** 전부 `getParentRoute: () => rootRoute` flat. addChildren 조립부 `router.ts:681-780`(53항목), `createRouter` `:783`.
2. `rootRoute`(`:62`)=`component: RootLayout`, **beforeLoad 없음** → root엔 hoist 위험 없음.
3. `RootLayout`(`__root.tsx`)이 **`isAuthenticated`로 크롬 분기**(Header L23·CommandPalette L24·Shortcuts L25), 경로 무관 → **passthrough `_shell`이면 완벽 렌더 동일**.
4. `loginRoute`(`:73`)=rootRoute직속·`redirectIfAuth`·`requireAuth:false` → **shell 밖 유지 필수**(실패경로1).
5. beforeLoad 가드 52개 각 라우트 자체 보유 → **hoist 금지**(실패경로2). `_shell`엔 beforeLoad 없음.
6. `dashboardsSharedTokenRoute`(`:714`)=**공개 라우트**(무인증). passthrough라 PR10 렌더 무영향. PR11 사이드바 게이팅 시 재검토(isAuthenticated로 자동 억제).
7. **addChildren 순서 의미 있음**(주석: /admin/../new이 /:key보다 먼저·/dashboards/$id가 /dashboards보다 뒤) → **순서 보존 필수**.
8. `router.test.tsx`가 production `routeTree` import(`:6`·`:39`), URL→텍스트+가드 리다이렉트만, **트리구조 어서션 0** → 재부모화 무수정 통과.
9. `routeGuard.test.tsx` 가드함수 단위, 트리 의존 0 → 무영향.
10. Register 증강(`:785`) 표준, fullPath 불변 → 타입안전 navigate 무영향(typecheck 확인).
11. **route-id 결합 0**(PR9 완료) → 재부모화 안전.

## 스펙 (← /bts-spec)

**SKIP 예정 (신규 기능 아님, 재개편 스펙 `docs/design/fr-ux-06-jira-redesign.md` + 메모리가 정본).**

## Brainstorming Check (← /bts-spec Phase B)

**SKIP 예정.**

## Plan (← /bts-plan 채움)

**설계 = passthrough `_shell`** (게이트1 확인). `_shell` 컴포넌트 = 바 `<Outlet/>`(wrapper div·크롬 없음). 크롬은 RootLayout에 그대로 → 렌더 픽셀 동일. 크롬→사이드바 이관은 PR11.
**멤버십 = loginRoute만 rootRoute 직속, 나머지 52개 `_shell` 밑.** (passthrough라 PR10 렌더 무영향. 공개 share 라우트의 사이드바 노출 여부는 PR11에서 isAuthenticated 게이팅으로 결정.)

### Task 1. ShellLayout passthrough 컴포넌트
**메타**. agent: `frontend-engineer` · files: [`apps/web/src/components/layout/ShellLayout.tsx`] · depends-on: []
- **GREEN**: 새 파일. 한 줄 한글 헤더 주석 + `export function ShellLayout(): JSX.Element { return <Outlet /> }`. `Outlet`은 `@tanstack/react-router`. **DOM 추가 0**(fragment도 아닌 Outlet 직접 반환). PR11이 여기에 사이드바+콘텐츠 레이아웃을 채운다.
- **RED**: N/A(구조 컴포넌트, 신규 동작 없음). 소비는 Task 2가 검증.

### Task 2. router.ts — `_shell` 정의 + 52 재부모화 + addChildren 중첩
**메타**. agent: `frontend-engineer` · files: [`apps/web/src/router.ts`] · depends-on: [1]
편집 순서(순환·login포함 방지, delicate):
1. `import { ShellLayout } from './components/layout/ShellLayout'` 추가.
2. **replace_all** `getParentRoute: () => rootRoute,` → `getParentRoute: () => shellRoute,` (53개 전부 바뀜 — index·login 포함).
3. **loginRoute(구 :74) 되돌리기**: 그 한 줄만 `getParentRoute: () => rootRoute,`로 복원(실패경로1).
4. `shellRoute` 정의를 addChildren 직전(구 :680 부근)에 삽입: `const shellRoute = createRoute({ getParentRoute: () => rootRoute, id: '_shell', component: ShellLayout })`. **(2단계 이후 삽입 → 순환 안 걸림. `id` 시작 `_`=pathless=URL 기여 0, `path` 없음)**
5. **addChildren 중첩**: `rootRoute.addChildren([ loginRoute, shellRoute.addChildren([ …기존 52개 원래 순서 그대로(loginRoute만 제외) ]) ])`. **순서 절대 보존**(매칭 우선순위).
- **RED**: N/A(render-shell 구조 리팩터, 신규 동작 없음). 기존 테스트 무수정 통과가 GREEN 판별.
- **검증 grep**(구현 후 controller 직접): `grep -c "() => shellRoute" `=52 · `grep -c "() => rootRoute"`=2(loginRoute+shellRoute자신) · `id: '_shell'` 1건 · loginRoute 블록이 `() => rootRoute`.

### Task 3. 구조 회귀 가드 테스트 (게이트1 결정 대상 — 채택/생략)
**메타**. agent: `frontend-engineer` · files: [`apps/web/src/router.shell.test.tsx`] · depends-on: [2]
- 목적: 실패경로 2개를 회귀 가드로 못박음(PR11+ 보호). `router.routesById`에 `_shell` id 존재 + loginRoute가 shell 밖 + 표본 라우트(dashboard) shell 안.
- **주의**: 트리구조 어서션은 메모리가 경계한 결합([[tanstack-pathless-layout-router-test-blind]]는 URL→텍스트 견고성이 재부모화 저위험의 근거라 봄). routesById 키 포맷이 fiddly하면 생략하고 기존 테스트(render-identical)만으로 증거 삼음. **게이트1에서 채택 여부 확정.**

## Plan 메타
- task 수: 2(+1 조건부) · 예상: 구현 ~10분 + 검증(unit+e2e) ~10분
- TDD: **N/A**(render-shell 구조 리팩터, RED 없음 — 기존 full 테스트 무수정 통과로 대체). 커밋 `refactor:`.
- 병렬: 불필요(Task2 depends Task1). 실행=controller 직접(delicate 편집·sub-agent 자기보고 불신)+grep 검증.
- **검증(전원 무수정 통과 필수)**: typecheck(tsconfig.app)/lint/unit `router.test.tsx`+`routeGuard.test.tsx` 포함 462파일/**full E2E**(특히 `already-authed.spec.ts` 가드 센티넬·CI에 e2e 없음이라 로컬 필수). E2E 러너 [[e2e-playwright-filter-arg-drop]] 바이너리 직접호출.

## 리뷰 결과 (← /bts-review-plan)

**SKIP(신규 기능 아님)**. 게이트2의 code-reviewer + /review가 검증.

## 검증 결과 (bts-impl)

- 변경: `router.ts`(+_shell 정의·52 재부모화·addChildren 중첩·4-space 정규화, `git diff -w` 66/53) · 신규 `components/layout/ShellLayout.tsx`(passthrough) · 신규 `router.shell.test.tsx`(구조 가드 4). 커밋 `ab0cabc26`.
- **grep 구조 검증**: `() => shellRoute` 52 · `() => rootRoute` 2(login+shellRoute) · `id: '_shell'` 1 · shell 블록 52항목 · 조립부 균형.
- **typecheck** EXIT=0 · **lint** EXIT=0(lint-staged `--max-warnings 0` 3파일 통과=내 파일 경고 0).
- **unit** 463파일 **7353 green**(기존 462/7349 + 구조가드 4). `router.test.tsx`·`routeGuard.test.tsx` **무수정 통과** = 재부모화 렌더 동일 + 가드 보존.
- **e2e 크리티컬 19 green**: already-authed(로그인 가드 센티넬)·smoke·issue-crud-happy·dashboard(네비·/dashboard 렌더·링크 이동)·profile(Header 계정 트리거·아바타).
- **e2e 전수 533**: **528 passed · 2 failed · 3 skipped**(7.2분). **실패 2건은 PR10 회귀 아님(baseline 대조 확정)**:
  - `board-wip-swimlane:122` — **origin/main에서도 실패**(사전 존재, '김앨리스' Header 계정명 visible 충돌, PR7 hot-fix 계열).
  - `saved-filters:265` — **PR10·main 둘 다 격리 실행에선 통과**(부하 의존 flaky, /search 딥링크 네비 정상). 전수 4-worker 부하에서만 30s 타임아웃.
- 두 실패는 사전/flaky라 **PR10 범위 밖(surgical) — 미수정, 기록만**.
- e2e 러너 [[e2e-playwright-filter-arg-drop]] 바이너리 직접호출. 백그라운드 리다이렉트는 별도 로그파일로 감(task output엔 echo만).
