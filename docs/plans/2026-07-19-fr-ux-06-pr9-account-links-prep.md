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

## 리뷰 결과 (← /bts-review-plan)

**SKIP (fast-track: refactor)**. 게이트 2의 code-reviewer + /review가 검증.
