# FR-UX-06 Phase 5 PR22 — 원시 button 정리 + EmptyState/Skeleton 중복 제거 + --chart-* 실소비

> slug: fr-ux-06-pr22-ui-cleanup
> type: ui (classify backend→ui 실측 정정)
> agent: frontend-engineer
> BC: personalization (물리 identity-access, 화면은 apps/web)
> 생성: 2026-07-25

## Brief

FR-UX-06(BTS UI/UX를 Jira Cloud 2025 방식으로 개편) **22 PR 체인의 마지막 PR**.
Phase 5 화면 PR(PR17~21b)이 끝난 뒤 남은 **정리 3덩어리**를 처리해 개편을 종료한다.

1. **원시 `<button>` 정리** — 공용 `components/ui/button`을 우회한 raw `<button>`을 프리미티브로 흡수.
2. **`FilteredEmptyState` / `Skeleton` 중복 제거** — 화면별로 복붙된 빈 상태·로딩 스켈레톤을 공용 컴포넌트로 통합.
3. **`--chart-1~5` 실소비 정의** — PR3에서 "소비자 0인 토큰을 미리 채우면 그게 PoC"라며 동결한 차트 색 5종을,
   PR4가 이연한 **범주색**(이슈타입 점·진행바·캘린더 스와치 등)에 실제로 소비하면서 값을 확정.

**classify 정정**. classify-task가 `type=backend / agent=backend-engineer`로 오분류.
실측 = 순수 프론트(`apps/web/**`), 백엔드/마이그레이션 변경 0 예상. FR-UX-06 주 BC=personalization.
PR9·PR10·PR12·PR21b 동일 오분류 선례.

**착수 시점 실측(bts-start, main `f2beb806c` 기준)**.

| 항목 | 실측 |
|---|---|
| 원시 `<button>` | **60파일 / 124발생** (`grep -rno '<button' apps/web/src --include='*.tsx'`) |
| `FilteredEmptyState` | **동일 이름 2중 정의** — `routes/issues.index.tsx:155·166`, `routes/projects.$projectKey.board.tsx:241·246` |
| `EmptyState` 계열 | 공용 `components/ui/empty-state.tsx` 존재 + 19파일이 참조 |
| `Skeleton` | 공용 `components/ui/skeleton.tsx` 존재 + 21파일이 참조 |
| `--chart-1~5` | `index.css` 라이트 170~178 / 다크 307~315에 **회색조 placeholder**, 소비처 0, `state-tokens.test.ts:222`가 "동결" 가드 중 |

★ 위 숫자는 **착수 시점 원시 grep**이며 IN-SCOPE 확정치가 아니다. 정당하게 남아야 할 raw `<button>`
(프리미티브 내부 구현, Radix `asChild` 트리거 등)이 섞여 있으므로 **spec 단계에서 전수 분류**해 IN/OUT을 가른다.
[[spec-stated-count-becomes-blindfold]] · [[orchestrator-instruction-counts-are-blindfolds]] — 이 표의 숫자를
믿지 말고 각 단계에서 직접 재계수할 것.

**참조 메모리**. [[fr-ux-06-jira-redesign-plan]] (허브·22 PR 체인·§함정) · [[fr-ux-06-pr21b-swimlane-field-change-done]] (직전 PR21b)
**착수 전 필독**. [[frontend-nav-aria-label-e2e-contract]] · [[playwright-getbyrole-exact-strict-mode]] · [[e2e-playwright-filter-arg-drop]]
**CI에 e2e 잡 없음** ([[frontend-ci-10min-timeout-nonrequired]]) → 로컬 e2e 필수.

## 도메인 정리

- **BC**. personalization (논리) / `apps/web` 프론트 (물리 identity-access). 화면이 걸치는 데이터는
  issue-tracking(이슈 타입·우선순위)·agile-planning(보드·번다운·속도)·notification-dashboard(대시보드 가젯)이지만
  **읽기만 하며 백엔드 변경 0**. BC 격리 위반 없음.
- **영향 엔티티**. 신규 0 · 변경 0. 순수 프론트 프리젠테이션 계층.
- **새 용어**. **0건.** "프리미티브 / 시맨틱 토큰 / 로젠지 / 스켈레톤 / 빈 상태"는 UI 어휘이며
  `glossary.md`는 도메인 엔티티 사전이라 대상이 아니다 (PR2~PR21b 전 PR 동일 판단).
- **기존 결정 충돌**. **없음.** PR22는 아래 결정들의 **이행(fulfilment)**이지 번복이 아니다.

### 관련 ADR / 스펙 (전부 PR22를 명시적으로 지목하고 있음)

| 출처 | 내용 |
|---|---|
| `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` **§D7** | `--chart-1~5`는 **실소비 PR에서** 정의한다 — "소비자가 없는 토큰을 미리 채우면 그게 PoC" · "Recharts 가젯 6종이 실제로 토큰을 쓰게 되는 **PR22에서 함께 정의**" |
| 같은 ADR **§후속(153행)** | `--chart-*` 실소비 정의 = 명시적 후속 항목 |
| `docs/design/fr-ux-06-jira-redesign.md` **§6(252·258행)** | `skeleton` 흡수 대상 = "board.tsx·dashboards.tsx **인라인 중복 2곳**" / `empty-state` 흡수 대상 = "`FilteredEmptyState` **중복 정의 2곳** + '…없습니다' 41파일" |
| 같은 스펙 **§7(274·276행)** | loading = skeleton 프리미티브(**인라인 재정의 금지**) · empty = empty-state 프리미티브 — PR22가 이 계약을 실제로 강제하는 PR |
| 같은 스펙 **§4.2 / §4.3** | 이슈 타입 5종 `--type-*` · 우선순위 5종 `--prio-*` **색값이 이미 확정 표로 존재**하나 `index.css`에는 **미신설**(실측 grep 0건) |
| 같은 스펙 **§14(381행)** | `--chart-1~5` 정의 = "실소비 PR22에서" |
| `docs/decisions/2026-07-25-fr-ux-06-pr21-board-in-column-rank.md` | 직전 PR21 드래그 규칙 — PR22와 무관(회귀 대상으로만 관리) |

### 도메인 단계 실측 (spec으로 이월할 확정 사실)

1. **`--chart-1~5` 소비처 0**. `index.css` 라이트 170~178 / 다크 307~315에 `oklch(... 0 0)` 무채색.
   `state-tokens.test.ts:221~227`이 **"🔒 동결 계약 — PR3가 건드리면 안 되는 토큰"**으로 잠그고 있어,
   PR22는 **이 동결 테스트를 해제·재작성하는 첫 PR**이다 (PR11이 `--sidebar-*` 8종에서 한 것과 같은 패턴).
2. **Recharts 소비 컴포넌트 6종 실재** — `BurndownChart` · `CfdChart` · `CycleTimeBoxPlot` ·
   `CycleTimeHistogram` · `VelocityChart` · `WorklogAggregateChart`. ADR D7의 "가젯 6종"과 일치.
3. **`--type-*` / `--prio-*` 미신설** — 디자인 스펙에 값은 확정돼 있으나 `index.css`에 **선언 0건**.
   PR4가 "범주색 ~14건(이슈타입 점·진행바·캘린더 스와치)은 `--chart-*` 동결 때문에 PR22로 이연"이라 기록한 대상이 이것.
4. **`FilteredEmptyState` 2중 정의 확정** — `routes/issues.index.tsx:155·166`,
   `routes/projects.$projectKey.board.tsx:241·246` (인터페이스+함수 각각 중복).

### ★ spec 단계로 넘기는 스코프 갈림길 (게이트 1에서 Maxi 확정 필요)

**PR22의 "`--chart-*` 실소비"가 차트 5색만인가, 범주색 토큰(`--type-*` 5 + `--prio-*` 5)까지인가.**
ADR D7은 `--chart-*`만 명시하지만, PR4는 범주색 이연 사유를 "`--chart-*` 동결"이라고 적었고
디자인 스펙 §4.2/§4.3에 범주색 값이 이미 확정돼 있다. **PR22가 개편의 마지막 PR**이므로 범주색을
빼면 하드코딩 색이 남은 채 FR-UX-06이 종료된다. spec에서 두 안의 범위·리스크를 실측해 게이트 1에 올린다.

### grill-with-docs 생략

순수 프론트 정리 PR로 **새 도메인 모델·엔티티·관계 0**, 새 용어 0, 기존 ADR 번복 0.
PR17~PR21b 화면 PR과 동일 판단. 시각 결정(`--chart-*` 값 확정·동결 해제)은
`/bts-review-plan`의 **plan-design-review**가 정본으로 다루고, 확정 후 **ADR 신설**한다
(`docs/decisions/2026-07-25-fr-ux-06-pr22-chart-categorical-tokens.md` 예정 — ADR D7의 이행 기록).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
