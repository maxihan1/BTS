# FR-UX-06 Phase 0 PR2 — ui 프리미티브 15종 추가

> slug: fr-ux-06-pr2-ui-primitives
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-18
> 정본 plan: [docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md](2026-07-17-fr-ux-06-jira-redesign/plan.md) §PR2
> 디자인 스펙: [docs/design/fr-ux-06-jira-redesign.md](../design/fr-ux-06-jira-redesign.md) §6
> ADR: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md)

## Brief

FR-UX-06 개편의 Phase 0 기반 작업. `apps/web/src/components/ui/`에 프리미티브 **15종**을 추가한다. **소비자 0인 순수 추가**라 기존 화면·E2E 계약을 건드리지 않는다.

정본 개편 plan(디렉토리형, #279 머지)은 재사용하며 새로 만들지 않는다. 이 파일은 PR2 범위만 좁게 담고 `bts-plan`이 TDD task를 채운다.

### 신설 15종 (디자인 스펙 §6)

`dialog` · `table` · `badge`(로젠지) · `tabs` · `tooltip` · `checkbox` · `popover` · `skeleton` · `separator` · `textarea` · `switch` · `radio-group` · `scroll-area` · `empty-state` · `command`

### 확정 결정 (Maxi, 2026-07-18)

| # | 결정 | 근거 |
|---|---|---|
| 1 | **§7 상태 토큰 11종을 이 PR에서 index.css에 신설** | 프리미티브가 처음부터 hover/focus/selected 동작하는 완제품이 되도록 |
| 2 | **§7 토큰 값은 기존 shadcn 토큰 alias** (예 `--bg-neutral-hover: var(--accent)`, `--border-focus: var(--ring)`) | 팔레트 정본은 PR3 몫(`plan.md:114`). 이름(배선)은 PR2, 값(팔레트)은 PR3. hex 직접 박으면 PR3과 충돌 |
| 3 | **개별 `@radix-ui/react-*` 설치 금지** | 통합 `radix-ui@1.4.3`에서 import (기존 `dropdown-menu.tsx` 관례) |
| 4 | **새 의존성 0개** | `radix-ui`·`cmdk` 모두 이미 package.json에 존재 |

## 🔒 반드시 지켜야 하는 것

- **`role="dialog"` 보존** — e2e 147건이 Radix `DialogPrimitive.Content`의 role에 의존. 래퍼가 같은 primitive를 감싸면 DOM 계약 불변
- **`--syntax-*` 5종 · `--font-mono` 건드리지 말 것** · **`--chart-1~5`는 PR22 몫** — 이 PR 범위 아님
- **소비자 0 유지** — 기존 파일(화면·라우트) 수정 금지. `components/ui/` 신규 파일 + `index.css` 토큰 추가만

## 도메인 정리 (← /bts-domain, 스킵 — #279 ADR 재사용)

## 스펙 (← /bts-spec, 스킵 — 디자인 스펙 §6 재사용)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan, 스킵 — 정본 plan 이미 리뷰됨)
