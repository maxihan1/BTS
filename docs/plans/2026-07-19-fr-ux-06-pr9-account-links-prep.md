# FR-UX-06 Phase 3 PR9 — account-links route-id 결합 제거 prep

> slug: fr-ux-06-pr9-account-links-prep
> type: refactor (fast-track)
> agent: frontend-engineer
> primary_bc: personalization (물리 apps/web)
> 생성: 2026-07-19

## Brief

FR-UX-06 Jira 재개편 Phase 3(Shell)의 첫 스텝. `apps/web/src/routes/settings.account-links.tsx:411`의
`const search = useSearch({ from: '/settings/account-links' }) as AccountLinksSearch`를
`const search = useSearch({ strict: false }) as AccountLinksSearch`로 변경하는 1줄 refactor.

**목적.** 이 한 줄이 코드베이스를 TanStack Router route id에 완전히 무관하게 만든다. 현재 코드베이스에서
route id에 결합된 `from:`은 이 한 곳뿐이다(메모리 실측). 이 결합을 제거하면 다음 PR10(`_shell` pathless
layout 재부모화, route id가 `/settings/account-links` → `/_shell/settings/account-links`로 바뀜)이
이 파일에 아무 영향을 주지 않아 "렌더 결과 동일 = 무해" 증명이 성립한다.

**행위 무변화(no-op).** 오늘 시점엔 라우트가 아직 안 옮겨졌으므로 `from:` 지정본과 `strict:false`본이 동일한
search 객체를 반환한다. 관측 가능한 변화 0.

**범위 확정(Maxi 게이트 결정).** PR9 = 독립 PR. PR10 재부모화는 이 PR에 포함하지 않는다.

### classify 오분류 정정
classify-task가 "route" 키워드로 `type=api / agent=backend-engineer / primary_bc=automation` 오분류.
실측 정정: 대상이 `apps/web/**` .tsx 프론트엔드 refactor → `type=refactor(fast-track) / agent=frontend-engineer / BC=personalization`.

## 도메인 정리 (← /bts-domain)

**SKIP (fast-track: refactor)**. FR-UX-06 도메인은 허브 메모리 `fr-ux-06-jira-redesign-plan` +
필독 2종(`frontend-nav-aria-label-e2e-contract`·`tanstack-pathless-layout-router-test-blind`)에 확정됨.

## 스펙 (← /bts-spec)

**SKIP (fast-track: refactor)**. 신규 기능 없음. 순수 render-shell prep.

## Brainstorming Check (← /bts-spec Phase B)

**SKIP (fast-track: refactor)**.

## Plan (← /bts-plan 채움)

**writing-plans 미호출 근거.** 행위 무변화(no-op) 1줄 refactor라 새로 test-first로 실패시킬 동작이
없다(TDD red→green 부적용). 기존 회귀 테스트가 그대로 가드. 이런 경우 TDD 분해는 비용만 발생(bts-plan
fast-track 취지). 대신 아래 단일 task로 직접 기술.

### Task 1. account-links `useSearch` route-id 결합 제거

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/settings.account-links.tsx`]
- depends-on: []

**RED**: **N/A (no-op refactor)**. 새 실패 테스트 없음. 기존 회귀 가드(무수정 통과 = 성공 판별):
- `apps/web/src/__tests__/router.test.tsx` — `/settings/account-links` URL 진입 시 렌더 확인
- account-links E2E — OAuth 콜백 `search.link`/`search.reauth` 처리 흐름
- `already-authed.spec.ts` — login 가드(이 변경과 무관하나 라우터 회귀 전체 가드)

**GREEN**: `apps/web/src/routes/settings.account-links.tsx:411`
- `useSearch({ from: '/settings/account-links' })` → `useSearch({ strict: false })`
- `as AccountLinksSearch` 캐스팅 유지 (타입 좁힘 그대로)

**REFACTOR**: 없음 (1줄).

**검증**:
- `pnpm typecheck` (tsconfig.app — [[ci-typecheck-tsconfig-app-vs-local]])
- `pnpm lint` (eslint src, --fix 없음)
- `pnpm test` (vitest, `router.test.tsx` 무수정 green)
- `pnpm test:e2e` (account-links + already-authed 무수정 green) — **CI에 e2e 잡 없음이라 로컬 필수**

**실측 근거**: route-id 결합 `from:`은 코드베이스 전체에서 이 한 줄뿐(`getRouteApi` 0·타 훅 `from:` 0, 2026-07-19 재검증).

## Plan 메타

- task 수: 1
- 예상 시간: 약 2분 (1줄 변경) + 검증(typecheck/lint/unit/e2e) 약 5분
- TDD 강제: **N/A** (no-op refactor, RED 없음 — 기존 테스트 무수정 통과로 대체)
- 병렬 dispatch: 불필요 (단일 task)
- 커밋 접두사: `refactor:` (feat/test 없음 → TDD 순서 게이트 비대상)

## 리뷰 결과 (← /bts-review-plan)

**SKIP (fast-track: refactor)**. 게이트 2의 code-reviewer + /review가 검증.

## 검증 결과 (bts-impl)

- 변경: `apps/web/src/routes/settings.account-links.tsx:411` 1줄 (`from:` → `strict: false`). diff 1 insertion/1 deletion. 커밋 `a218ac821`.
- **typecheck** EXIT=0 (`tsc -p tsconfig.app.json --noEmit`).
- **lint** EXIT=0 (`eslint src`, 사전 존재 경고 8건 무관·에러 0).
- **unit** 462 files / **7349 passed** (baseline 불변, `router.test.tsx` 무수정 통과).
- **e2e** account-links + already-authed **9 passed** (16.6s). 핵심 표면 직접 검증:
  - `?link=success`/`?reauth=success`/`?link=conflict` 콜백 토스트·쿼리제거 (S5/S8/S9 — `search.link`·`search.reauth` 읽기)
  - already-authed `/login` 가드 리다이렉트 (라우터 회귀 센티넬)
- **실측**: route-id 결합 `from:`은 코드베이스 전체 이 한 줄뿐(`getRouteApi` 0, 타 훅 `from:` 0). 제거로 route-id 무관화 달성.
- e2e 러너 주의(다음 세션용): `pnpm exec playwright test <file>`는 positional 필터를 먹음 → 바이너리 직접 호출 `(cd apps/web && node_modules/.bin/playwright test <file>.spec.ts)` 필요.
