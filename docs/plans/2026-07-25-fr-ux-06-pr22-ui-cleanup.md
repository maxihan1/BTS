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

## 스펙

전체 스펙. [docs/specs/2026-07-25-fr-ux-06-pr22-ui-cleanup.md](../specs/2026-07-25-fr-ux-06-pr22-ui-cleanup.md)

핵심 요약.
- **실측이 가정을 뒤집었다** — "자잘한 정리 3덩어리"가 아니라 각각 PR 하나 크기다.
  원시 `<button>` **프로덕션 106발생/52파일** · 인라인 `animate-pulse` **33발생/25파일**(프리미티브 소비는 1파일뿐) ·
  recharts **6종이 하드코딩 hex 6종** · 범주색 **10발생/3파일**(메모리의 "~14건"은 과다).
- **본 PR은 "사용자 눈에 안 보이는 것"이 목표**다. 시각 변화는 차트 색(S4)과 범주색(S5) 둘뿐이며 나머지는 무회귀.
- `FilteredEmptyState` 2중 정의는 **문구가 서로 다르다** → 통합해도 화면별 문구를 prop으로 보존한다.
- `--chart-1~5`는 `state-tokens.test.ts:221`이 **동결 계약으로 잠그고 있다**. PR22가 자물쇠를 여는 첫 PR
  (PR11의 `--sidebar-*` 선례). `--syntax-*`는 AQL 정렬 계약이라 **동결 유지**.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 9건 — 반증 3 · 스펙 누락 2 · Maxi 결정 2 · 확인 2)

- **반증(스펙보다 유리)**. `--muted` == `--bg-neutral` **동일값**(`#F7F8F9`/`#BCD6F00A`) → 스켈레톤 교체는
  **정확한 시각 no-op**, 스펙 §5의 "색이 미세하게 다르다"는 오류였음(EC5 폐기) ·
  `@theme`에 `--color-chart-1~5` **이미 배선**(EC6 리스크 하향) ·
  **E2E는 색/스켈레톤 클래스에 0건 의존**(EC10 해소, 유닛 1건만 문구 어서션).
- **스펙 누락 → FR 추가**. **PR22-F8** FR-UX-06 **D1·D3~D7 체크박스가 전부 미마킹**이고 "20 PR 체인" drift가
  남아 있음(마지막 PR이므로 전량 마킹 + BC 완료 게이트 해제가 본 PR 책임) ·
  **PR22-F9** `DESIGN.md:114`가 `--chart-*`를 "PR22 몫", `:106`이 범주색을 "PR22 소관"으로 **명시 대기 중**.
- **Maxi 결정 3건 확정**(스펙 §17). 스코프 **B(표준·범주색 IN)** · 차트 색 **기존 검증 팔레트에서 파생** ·
  재발방지 락 **ESLint 내장 `no-restricted-syntax` + 파일별 예외**(신규 의존성 0).

## Plan

> **For agentic workers:** 각 Task는 RED → GREEN → REFACTOR 3커밋 사이클. `git commit --no-verify`로
> lint-staged 공유 stash 레이스를 회피하고 **직렬 dispatch**한다 ([[worktree-lint-staged-shared-git-stash-collision]]).
> controller는 sub-agent 보고를 믿지 말고 `git diff -w`로 실물을 직접 검증한다.

**Goal.** FR-UX-06 개편의 마지막 PR — 공용 프리미티브(버튼·스켈레톤·빈 상태)의 실소비를 강제하고,
`--chart-*`/범주색 토큰을 실제 소비처와 함께 확정해 하드코딩 색을 없앤다.

**Architecture.** 토큰 정의(T1)를 먼저 고정하고 소비처(T2·T3)가 뒤따른다. 프리미티브 흡수(T4~T7)는
DOM 계약 verbatim 보존이 원칙이며, 마지막에 ESLint 락(T8)으로 재발을 차단한 뒤 전수 동기화(T9)·회귀 검증(T10)한다.

**Tech Stack.** React 19 · TypeScript 5 strict · Tailwind v4 `@theme` · vitest · Playwright · recharts.

### ★ 착수 전 확정 사실 (plan 단계 실측 — 스펙 수치 정정 3건)

1. **범주색은 10발생/3파일이 아니라 15발생/5파일이다.** 스펙 §8의 grep이 `emerald`·`yellow`·`gray`를 빠뜨렸다.
   전체 Tailwind 팔레트 패턴으로 재계수한 전수 목록.

| 파일:행 | 리터럴 | 의미 | 착륙 토큰 |
|---|---|---|---|
| `components/timeline/TimelineRow.tsx:25` | `bg-purple-500` | 이슈타입 epic 막대 | `--type-epic` |
| `:26` | `bg-blue-400` | 이슈타입 story 막대 | `--type-story` |
| `:27` | `bg-teal-400` | 이슈타입 task 막대 | `--type-task` |
| `:28` | `bg-red-400` | 이슈타입 bug 막대 | `--type-bug` |
| `:32` | `bg-gray-400` | 미지 타입 폴백 | `--type-default` |
| `:104` | `text-amber-500` | targetDate 마일스톤 ◆ | `--warning` (기존 토큰) |
| `features/calendar/WeekGrid.tsx:86` | `bg-slate-600` | 상태 TODO 칩 | `--neutral-bold` + `--neutral-bold-foreground` (**신설** — 리뷰 이슈 1) |
| `:87` | `bg-blue-800` | 상태 IN_PROGRESS 칩 | `--info` + `--info-foreground` (기존) |
| `:88` | `bg-emerald-800` | 상태 DONE 칩 | `--success` + `--success-foreground` (기존) |
| `:249` · `:266` | `bg-violet-800` ×2 | Worklog 칩 | `--discovery` + `--discovery-foreground` (**신설**) |
| `components/issue/EpicProgressBar.tsx:114` | `bg-emerald-500` | 진행바 done 세그먼트 | `--success` (기존) |
| `:121` | `bg-blue-400` | 진행바 inProgress 세그먼트 | `--info` (기존) |
| `components/favorite/FavoriteButton.tsx:101` | `text-yellow-400` | 즐겨찾기 별 채움 | `--favorite` (**신설**) |
| `components/workflow/WorkflowDiagram.tsx:79` | — | **주석 문자열**(코드 아님) | **OUT** |

→ 실제 코드 **14발생 / 4파일**. `WorkflowDiagram`은 mermaid가 `var()` 미지원이라 PR4가 이미 OUT 처리했고
본 건은 주석이라 대상이 아니다.

2. **`--prio-*` 5종과 `--type-subtask`는 만들지 않는다.** 실측 결과 **소비처가 0**이다 —
   우선순위는 `routes/search.tsx:181`·`IssueMetaPanel.tsx:271` 등에서 **텍스트로만** 표시되고 색 구분이 없으며,
   subtask 타입에 색을 쓰는 코드가 없다. 만들면 **ADR §D7("소비자 0인 토큰을 미리 채우면 그게 PoC")을
   PR22가 스스로 위반**한다. 디자인 스펙 §4.3 표는 **미래 소비 PR의 예약분**으로 남긴다.

3. **디자인 스펙 §4.2의 `type-subtask` 라이트값은 WCAG 1.4.11 미달이다** (`#579DFF` = 2.74:1 < 3:1).
   위 2번에 따라 이번에 만들지 않으므로 **본 PR에서는 무해**하나, 스펙 §4.2에 경고 주석을 남긴다(T9).

4. **현재 하드코딩 차트 색 6종 중 3종이 이미 대비 미달**이다 — `#f59e0b` 2.15:1 · `#94a3b8` 2.56:1 ·
   `#cbd5e1` 1.48:1 (vs 흰 배경). 따라서 T1·T2는 정리일 뿐 아니라 **접근성 결함 수정**이다.

### 확정 토큰 값 (전량 WCAG 1.4.11 3:1 이상 계산 검증 완료)

배경 기준 — 라이트 `--card #FFFFFF` · 다크 `--card #1D2125`. 출처는 디자인 스펙 §5.2 ADS v2 원시 팔레트(PR3 검증분).

| 토큰 | 라이트 | 대비 | 다크 | 대비 | 램프 |
|---|---|---|---|---|---|
| `--chart-1` | `#0C66E4` | 5.20 | `#579DFF` | 5.92 | Blue 700/400 |
| `--chart-2` | `#6E5DC6` | 5.19 | `#9F8FEF` | 5.90 | Purple 700/400 |
| `--chart-3` | `#758195` | 3.94 | `#8C9BAB` | 5.70 | Neutral 600 / DarkNeutral 800 |
| `--chart-4` | `#B65C02` | 4.65 | `#FAA53D` | 8.11 | Orange 700/400 |
| `--chart-5` | `#1F845A` | 4.66 | `#4BCE97` | 8.16 | Green 700/400 |
| `--type-epic` | `#6E5DC6` | 5.19 | `#9F8FEF` | 5.90 | Purple 700/400 (스펙 §4.2 일치) |
| `--type-story` | `#22A06B` | 3.33 | `#4BCE97` | 8.16 | Green 600/400 (스펙 §4.2 일치) |
| `--type-task` | `#0C66E4` | 5.20 | `#579DFF` | 5.92 | Blue 700/400 (스펙 §4.2 일치) |
| `--type-bug` | `#C9372C` | 5.16 | `#F87168` | 5.83 | Red 700/400 (스펙 §4.2 일치) |
| `--type-default` | `#758195` | 3.94 | `#8C9BAB` | 5.70 | Neutral 600 / DarkNeutral 800 |
| `--discovery` | `#6E5DC6` | 5.19 | `#9F8FEF` | 5.90 | Purple 700/400 (스펙 §5.3) |
| `--discovery-foreground` | `#FFFFFF` | 5.19† | `#161A1D` | 6.38† | 기존 4쌍과 동일 규칙 |
| `--neutral-bold` | `#44546F` | 7.65† | `#9FADBC` | 7.65† | Neutral 800 / DarkNeutral 850 |
| `--neutral-bold-foreground` | `#FFFFFF` | — | `#161A1D` | — | 기존 4쌍과 동일 규칙 |
| `--favorite` | `#B38600` | 3.32 | `#F5CD47` | 10.57 | Yellow 600/300 |

† `--discovery-foreground`는 bold 배경 **위 글자** 대비(AA 4.5 기준)로 계산했다.
★ `--favorite`가 ADS Yellow400 `#E2B203`(1.98:1)이 아니라 **Yellow600 `#B38600`**인 이유 — 별 아이콘은
채움 상태가 의미를 가지므로 1.4.11 대상이고, Yellow400은 흰 배경에서 미달이다. 현재 `text-yellow-400`(1.53:1)보다 개선된다.

### 차트 역할 → 토큰 매핑 (하드코딩 6종을 5토큰으로)

| 역할 | 현재 | 새 토큰 | 적용 컴포넌트 |
|---|---|---|---|
| 주 시리즈(실측·완료·막대) | `#6366f1` | `--chart-1` | Burndown·CFD·BoxPlot·Histogram·Velocity·Worklog |
| 보조 시리즈(이상선·진행중) | `#f59e0b` | `--chart-4` | Burndown(ideal) · CFD(inProgress) |
| 중립(스코프선·TODO·약속) | `#94a3b8` · `#cbd5e1` | `--chart-3` | Burndown(scope) · CFD(todo) · Velocity(commitment) |
| 평균선 1 | `#475569` | `--chart-3` | Velocity(avgCommitment) — 중립 계열 통합 |
| 평균선 2 | `#4338ca` | `--chart-2` | Velocity(avgCompleted) |

★ Velocity의 `commitment`와 `avgCommitment`가 같은 `--chart-3`이 되면 **막대와 평균선이 같은 색**이 된다.
평균선은 `strokeDasharray`로 이미 형태가 다르므로 WCAG 1.4.1(색만으로 구분 금지)은 충족하나,
**시각 구분을 위해 평균선에는 `strokeWidth`를 유지**한다. 이 결정은 T2 REFACTOR에서 주석으로 남긴다.

---

### Task 1. 토큰 값 정본 문서 + `index.css` 정의 + 동결 해제

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plans/2026-07-25-fr-ux-06-pr22-token-values.md`, `apps/web/src/index.css`, `apps/web/src/components/ui/__tests__/state-tokens.test.ts`]
- depends-on: []

**RED**. `state-tokens.test.ts`
- 기존 `describe('🔒 동결 계약 — PR3가 건드리면 안 되는 토큰')`의 `frozen` 배열에서 **`--chart-1~5`만 제거**하고
  `--syntax-*` 5종은 그대로 둔다(`DESIGN.md:130` AQL 정렬 계약).
- 새 행렬 상수 추가 후 `it.each`로 라이트/다크 정확-hex 대조.

```ts
/**
 * FR-UX-06 PR22 확정 팔레트 — 정본: docs/plans/2026-07-25-fr-ux-06-pr22-token-values.md
 * ADS v2 원시 팔레트(디자인 스펙 §5.2, PR3 검증분)에서 파생. 전량 WCAG 1.4.11 3:1 이상.
 * 값을 바꾸려면 정본 문서와 이 표를 같은 커밋에서 바꿔야 한다 — 값 지어내기 방지 가드.
 */
const CHART_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--chart-1', '#0C66E4', '#579DFF'],
  ['--chart-2', '#6E5DC6', '#9F8FEF'],
  ['--chart-3', '#758195', '#8C9BAB'],
  ['--chart-4', '#B65C02', '#FAA53D'],
  ['--chart-5', '#1F845A', '#4BCE97'],
] as const

const CATEGORY_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--type-epic', '#6E5DC6', '#9F8FEF'],
  ['--type-story', '#22A06B', '#4BCE97'],
  ['--type-task', '#0C66E4', '#579DFF'],
  ['--type-bug', '#C9372C', '#F87168'],
  ['--type-default', '#758195', '#8C9BAB'],
  ['--discovery', '#6E5DC6', '#9F8FEF'],
  ['--discovery-foreground', '#FFFFFF', '#161A1D'],
  ['--neutral-bold', '#44546F', '#9FADBC'],
  ['--neutral-bold-foreground', '#FFFFFF', '#161A1D'],
  ['--favorite', '#B38600', '#F5CD47'],
] as const

describe('차트 토큰 5종 (PR22, ADR D7 이행) — 동결 해제 + 정확 hex', () => {
  it.each(CHART_TOKENS)('%s — :root=%s', (token, light) => {
    expect(declarationOf(rootBlock, token)).toBe(light)
  })
  it.each(CHART_TOKENS)('%s — .dark=%s', (token, _light, dark) => {
    expect(declarationOf(darkBlock, token)).toBe(dark)
  })
})

describe('범주 토큰 8종 (PR22) — 실소비처가 있는 토큰만 정의한다 (ADR D7)', () => {
  it.each(CATEGORY_TOKENS)('%s — :root=%s', (token, light) => {
    expect(declarationOf(rootBlock, token)).toBe(light)
  })
  it.each(CATEGORY_TOKENS)('%s — .dark=%s', (token, _light, dark) => {
    expect(declarationOf(darkBlock, token)).toBe(dark)
  })
})

describe('소비처 0인 토큰은 만들지 않는다 (ADR D7 자기준수)', () => {
  it.each(['--prio-highest', '--prio-high', '--prio-medium', '--prio-low', '--prio-lowest', '--type-subtask'])(
    '%s — 미정의 (우선순위/서브태스크는 색 소비처가 없다)',
    (token) => {
      expect(declarationOf(rootBlock, token)).toBeUndefined()
    },
  )
})
```

- 실패 메시지(예상). `expected 'oklch(0.87 0 0)' to be '#0C66E4'` (chart) / `expected undefined to be '#6E5DC6'` (category).

**GREEN**.
- `docs/plans/2026-07-25-fr-ux-06-pr22-token-values.md` 신규 — 위 "확정 토큰 값" 표 + 대비 계산 근거 + 출처(§5.2 램프) 기록.
- `apps/web/src/index.css` — `:root` 블록의 `--chart-1~5` oklch 5줄을 확정 hex로 교체, `.dark` 블록 동일.
  범주 토큰 8종을 시맨틱 4쌍 바로 뒤에 신설(라이트/다크 각각).
- `@theme inline` 배선 추가 — 기존 `--color-chart-N: var(--chart-N)` 5종은 이미 존재하므로 **추가 불필요**.
  신규 8종만 배선.

```css
  --color-type-epic: var(--type-epic);
  --color-type-story: var(--type-story);
  --color-type-task: var(--type-task);
  --color-type-bug: var(--type-bug);
  --color-type-default: var(--type-default);
  --color-discovery: var(--discovery);
  --color-discovery-foreground: var(--discovery-foreground);
  --color-neutral-bold: var(--neutral-bold);
  --color-neutral-bold-foreground: var(--neutral-bold-foreground);
  --color-favorite: var(--favorite);
```

**REFACTOR**. `index.css` 신규 절에 주석 — 각 토큰의 ADS 램프 출처와 소비처를 1줄로 명시.

**검증**. `cd apps/web && pnpm vitest run src/components/ui/__tests__/state-tokens.test.ts`

---

### Task 2. recharts 6종이 차트 토큰을 소비

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/burndown/BurndownChart.tsx`, `apps/web/src/components/cfd/CfdChart.tsx`, `apps/web/src/components/cycle-time/CycleTimeBoxPlot.tsx`, `apps/web/src/components/cycle-time/CycleTimeHistogram.tsx`, `apps/web/src/components/velocity/VelocityChart.tsx`, `apps/web/src/components/worklog/WorklogAggregateChart.tsx`, `apps/web/src/components/burndown/BurndownChart.test.tsx`, `apps/web/src/components/cfd/CfdChart.test.tsx`, `apps/web/src/components/velocity/VelocityChart.test.tsx`]
- depends-on: [1]

**★ 선행 실물 검증 (RED 전 첫 스텝).** recharts가 `fill="var(--chart-1)"`를 SVG에 그대로 통과시키는지 확인한다.
통과하면 `var()` 직접 전달, 실패하면 `className="fill-chart-1"` + `fill="currentColor"` 우회를 쓴다
(`@theme`에 `--color-chart-N`이 이미 있어 `fill-chart-N` 유틸리티가 생성된다 — 스펙 §14 G1).
**검증 방법을 추측하지 말 것** — 실제 렌더 후 `getAttribute('fill')`로 확인한다.

**RED**. 각 차트 테스트에 하드코딩 hex 부재 어서션.

```ts
it('하드코딩 hex 색상을 쓰지 않는다 (PR22 — --chart-* 토큰 소비)', () => {
  const source = readFileSync(resolve(__dirname, './BurndownChart.tsx'), 'utf-8')
  expect(source).not.toMatch(/#[0-9a-fA-F]{6}/)
})
```

- 실패 메시지(예상). `expected '…#6366f1…' not to match /#[0-9a-fA-F]{6}/`

**GREEN**. 6개 컴포넌트의 색 상수를 "차트 역할 → 토큰 매핑" 표대로 교체.

```tsx
/** 주 시리즈 — --chart-1 (PR22, ADR D7 이행) */
const COLOR_ACTUAL = 'var(--chart-1)'
/** 보조 시리즈(이상선) — --chart-4 */
const COLOR_IDEAL = 'var(--chart-4)'
/** 중립(스코프선) — --chart-3 */
const COLOR_SCOPE = 'var(--chart-3)'
```

**REFACTOR**. Velocity의 `commitment`(막대)와 `avgCommitment`(평균선)가 같은 `--chart-3`을 쓰는 이유와
`strokeDasharray`+`strokeWidth`로 구분한다는 근거를 KDoc으로 남긴다.

**검증**. `cd apps/web && pnpm vitest run src/components/burndown src/components/cfd src/components/cycle-time src/components/velocity src/components/worklog`

---

### Task 3. 범주색 14발생을 토큰으로 (4파일)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/timeline/TimelineRow.tsx`, `apps/web/src/features/calendar/WeekGrid.tsx`, `apps/web/src/components/issue/EpicProgressBar.tsx`, `apps/web/src/components/favorite/FavoriteButton.tsx`, `apps/web/src/components/timeline/TimelineRow.test.tsx`, `apps/web/src/features/calendar/WeekGrid.test.tsx`, `apps/web/src/components/issue/EpicProgressBar.test.tsx`, `apps/web/src/components/favorite/FavoriteButton.test.tsx`]
- depends-on: [1]

**RED**. 4개 파일 각각에 리터럴 색 부재 어서션 + 토큰 클래스 존재 어서션.

```ts
it('Tailwind 리터럴 색을 쓰지 않는다 (PR22 — 범주 토큰 소비)', () => {
  const source = readFileSync(resolve(__dirname, './TimelineRow.tsx'), 'utf-8')
  expect(source).not.toMatch(/\b(bg|text)-(purple|blue|teal|red|gray|amber)-\d{2,3}\b/)
})

it('이슈 타입별 막대가 --type-* 토큰을 쓴다', () => {
  expect(ISSUE_TYPE_COLORS.epic).toBe('bg-(--type-epic)')
})
```

**GREEN**. 위 "범주색 전수 목록" 표의 착륙 토큰대로 교체.

```tsx
const ISSUE_TYPE_COLORS: Record<string, string> = {
  epic: 'bg-(--type-epic)',
  story: 'bg-(--type-story)',
  task: 'bg-(--type-task)',
  bug: 'bg-(--type-bug)',
}
const COLOR_FALLBACK = 'bg-(--type-default)'
```

```tsx
export const STATE_CATEGORY_STYLE: Record<StateCategory, CategoryStyle> = {
  TODO: { bgClass: 'bg-neutral-bold text-neutral-bold-foreground', Icon: Circle, label: calendarLabels.category.TODO },
  IN_PROGRESS: { bgClass: 'bg-info text-info-foreground', Icon: CircleDot, label: calendarLabels.category.IN_PROGRESS },
  DONE: { bgClass: 'bg-success text-success-foreground', Icon: CheckCircle2, label: calendarLabels.category.DONE },
}
```

★ **리뷰 이슈 1 — 기존 결정을 뒤집을 뻔했다.** `WeekGrid.tsx:76` 주석이
*"옅은 배지 대비 미달로 배제, 중간톤 solid fill 채택"*(FR-CA-01 T5 §4.1)이라고 기록하고 있다.
초안은 TODO를 `--bg-neutral-solid`(라이트 `#F7F8F9` = 거의 흰색)로 매핑했는데, 이는 흰 배경 위에서
칩을 사실상 소멸시켜 **그 결정을 정면으로 되돌리는 것**이었다. 중간톤 solid를 유지하는
`--neutral-bold` 신설로 정정했다(라이트 7.65:1 · 다크 7.65:1 — 현재 `bg-slate-600` 7.58:1과 동등).

★ WeekGrid 칩은 현재 `text-white`가 클래스에 **따로** 붙어 있으므로(`:194` · `:223`),
`*-foreground` 페어로 바꾸면서 중복 `text-white`를 제거해야 한다(PR4 정본 페어링 규칙).
이 제거를 빠뜨리면 다크 모드에서 밝은 칩 위 흰 글자가 되어 대비가 무너진다.

**REFACTOR**. `--favorite` 사용처에 "Yellow400은 흰 배경 1.98:1 미달이라 Yellow600을 쓴다"는 주석을 남긴다.

**검증**. `cd apps/web && pnpm vitest run src/components/timeline src/features/calendar src/components/issue/EpicProgressBar.test.tsx src/components/favorite`

---

### Task 4. 인라인 스켈레톤 33발생 → `Skeleton` 프리미티브 (25파일)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/dashboards.tsx`, `apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/projects.$projectKey.timeline.tsx`, `apps/web/src/routes/dashboards.$dashboardId.tsx`, `apps/web/src/routes/dashboards.shared.$token.tsx`, `apps/web/src/routes/inbox.tsx`, `apps/web/src/routes/admin.workflow-schemes.$schemeKey.tsx`, `apps/web/src/routes/projects.$projectKey.sprints.$sprintId.burndown.tsx`, `apps/web/src/routes/settings.account-links.tsx`, `apps/web/src/features/calendar/CalendarView.tsx`, `apps/web/src/components/auth/TrustedDevicesSection.tsx`, `apps/web/src/components/auth/SessionList.tsx`, `apps/web/src/components/auth/WebauthnSection.tsx`, `apps/web/src/components/auth/MfaSettings.tsx`, `apps/web/src/components/auth/BackupCodesSection.tsx`, `apps/web/src/components/admin/WebhookDeliveryTable.tsx`, `apps/web/src/components/admin/WorkflowSchemeSidebar.tsx`, `apps/web/src/components/admin/MemberList.tsx`, `apps/web/src/components/field-permissions/FieldPermissionList.tsx`, `apps/web/src/components/component/ComponentList.tsx`, `apps/web/src/components/version/VersionList.tsx`, `apps/web/src/components/version/ReleaseNotesDialog.tsx`, `apps/web/src/components/issue/EpicProgressBar.tsx`, `apps/web/src/components/issue/IssueChangelog.tsx`, `apps/web/src/components/issue-templates/IssueTemplateList.tsx`, `apps/web/src/components/custom-fields/CustomFieldList.tsx`]
- depends-on: [3]  # EpicProgressBar가 T3과 겹침 → 직렬

**RED**. 전역 가드 테스트 신규 — `apps/web/src/components/ui/__tests__/skeleton-usage.test.ts`

```ts
// 인라인 animate-pulse 재정의 금지를 소스 전수 스캔으로 강제하는 회귀 가드 (FR-UX-06 PR22)
import { readFileSync, globSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

// Node 22+ 의 fs.globSync (로컬/CI 모두 Node 24). src 루트 = 이 파일 기준 ../../..
const SRC_ROOT = resolve(__dirname, '../../..')
const FILES = globSync('**/*.tsx', { cwd: SRC_ROOT })
  .filter((f) => !f.endsWith('.test.tsx') && !f.includes('components/ui/skeleton.tsx'))

describe('로딩 스켈레톤은 프리미티브만 쓴다 (디자인 스펙 §7 — 인라인 재정의 금지)', () => {
  it('animate-pulse 인라인 재정의가 0건이다', () => {
    const offenders = FILES.filter((f) =>
      readFileSync(resolve(__dirname, '../../..', f), 'utf-8').includes('animate-pulse'),
    )
    expect(offenders).toEqual([])
  })
})
```

- 실패 메시지(예상). `expected [ 'src/routes/inbox.tsx', … 24 more ] to deeply equal []`
- ★ **개수 상한이 아니라 목록 전수 비교**다 — 개수 가드는 [[spec-stated-count-becomes-blindfold]] 함정.

**GREEN**. 3단계로 진행한다.
1. 이름충돌 로컬 `function Skeleton`(dashboards `:21` · board `:51` · timeline `:94`) **삭제** 후
   `import { Skeleton } from '@/components/ui/skeleton'` 추가. 호출부는 이름이 같아 **무변경**.
2. 나머지 인라인 `<div className="… animate-pulse … bg-muted …">`를 `<Skeleton className="…">`로 교체.
   `bg-muted` → 프리미티브의 `bg-(--bg-neutral)`는 **동일 hex라 시각 no-op**(스펙 §14 G2).
3. `role="status"` · `aria-label` · `aria-hidden`은 **verbatim 보존**(NFR-N1). `MfaSettings.tsx:283`의
   `role="status" aria-label="2단계 인증 상태 로딩 중"`은 래퍼 `<div>`에 남기고 내부만 프리미티브로 바꾼다.

**REFACTOR**. 로컬 `*Skeleton` 구성 함수 16개는 **유지**한다(리스트 형태 조립 책임). 내부만 프리미티브 소비.

**검증**. `cd apps/web && pnpm vitest run src/components/ui/__tests__/skeleton-usage.test.ts && pnpm typecheck`

---

### Task 5. `FilteredEmptyState` 2중 정의 → 공용 컴포넌트 1개

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/filters/FilteredEmptyState.tsx`, `apps/web/src/components/filters/FilteredEmptyState.test.tsx`, `apps/web/src/routes/issues.index.tsx`, `apps/web/src/routes/projects.$projectKey.board.tsx`]
- depends-on: [4]  # board.tsx·issues.index.tsx가 T4와 겹침 → 직렬

**RED**. `FilteredEmptyState.test.tsx` 신규 — 화면별 문구가 prop으로 주입되는지 검증.

```tsx
it('제목과 CTA 라벨을 prop으로 받아 렌더한다', () => {
  render(<FilteredEmptyState title="조건에 맞는 카드가 없습니다" resetLabel="필터 초기화" onReset={vi.fn()} />)
  expect(screen.getByText('조건에 맞는 카드가 없습니다')).toBeInTheDocument()
})

it('description이 없으면 2행을 렌더하지 않는다 (보드 화면 계약)', () => {
  render(<FilteredEmptyState title="t" resetLabel="r" onReset={vi.fn()} />)
  expect(screen.queryByTestId('filtered-empty-description')).not.toBeInTheDocument()
})
```

**GREEN**. `components/filters/FilteredEmptyState.tsx` 신규 — `ui/empty-state`의 `EmptyState`를 조립한다.

```tsx
// 필터 결과 0건일 때 안내 문구와 초기화 CTA를 보여주는 공용 빈 상태 컴포넌트
interface FilteredEmptyStateProps {
  title: string
  description?: string
  resetLabel: string
  onReset: () => void
  /** 화면별 여백/최소높이 보존용 (리뷰 이슈 2) — EmptyState 기본 px-4 py-12에 덧씌운다 */
  className?: string
}

export function FilteredEmptyState({
  title,
  description,
  resetLabel,
  onReset,
  className,
}: FilteredEmptyStateProps): JSX.Element {
  return (
    <EmptyState
      title={title}
      description={description}
      className={className}
      action={
        <Button type="button" variant="outline" size="sm" onClick={onReset}>
          {resetLabel}
        </Button>
      }
    />
  )
}
```

- `routes/issues.index.tsx` — 로컬 정의 삭제, `title="필터 조건에 맞는 이슈가 없습니다."`
  `description="다른 조건을 시도하거나 필터를 초기화하세요."` `resetLabel="필터 초기화"`
  **`className="py-16"`**(원본 여백 보존).
- `routes/projects.$projectKey.board.tsx` — 로컬 정의 삭제, `title="조건에 맞는 카드가 없습니다"`
  `resetLabel={boardFilterLabels.filter.reset}`, **`className="min-h-48"`**(원본 최소높이 보존).
  **description 없음**(원본 1행 계약).

★ **리뷰 이슈 2.** `EmptyState`는 `px-4 py-12` **고정**이고 `min-height`가 없다. 그대로 흡수하면
이슈 목록은 여백이 줄고(`py-16`→`py-12`), 보드는 최소높이(`min-h-48`)를 잃어 필터 0건일 때
칸반 영역이 오그라들며 레이아웃이 튄다. `EmptyState`가 이미 `className`을 `cn()`으로 병합하므로
호출부에서 원본 값을 그대로 넘겨 **시각 no-op**을 유지한다(NFR-N1·S3 준수).

★ `routes/__tests__/projects.board.test.tsx:639`가 `/조건에 맞는 카드가 없습니다/i`를 어서션 중이므로
문구 verbatim 보존이 필수다(스펙 §14 G9).

**REFACTOR**. 이슈 화면의 하드코딩 문구 3개를 `i18n/ko.ts`로 올릴지는 **본 PR 범위 밖**으로 명시(주석).

**검증**. `cd apps/web && pnpm vitest run src/components/filters src/routes/__tests__/projects.board.test.tsx src/routes/issues.index.test.tsx`

---

### Task 6. 원시 `<button>` → `Button` (배치 1 — 공용 Button 이미 소비 중인 21파일)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/admin/AddMemberDialog.tsx`, `apps/web/src/components/admin/AuditLogFilters.tsx`, `apps/web/src/components/admin/WorkflowSchemeSidebar.tsx`, `apps/web/src/components/automation/ActionConfigEditor.tsx`, `apps/web/src/components/automation/ActionListEditor.tsx`, `apps/web/src/components/automation/AutomationRuleFormDialog.tsx`, `apps/web/src/components/automation/ConditionBuilder.tsx`, `apps/web/src/components/automation/RuleExecutionTraceRow.tsx`, `apps/web/src/components/board/QuickFilterChips.tsx`, `apps/web/src/components/dashboard/DashboardForm.tsx`, `apps/web/src/components/filters/FilterBar.tsx`, `apps/web/src/components/global-permissions/GlobalPermissionFormDialog.tsx`, `apps/web/src/components/issue/EpicChildrenSection.tsx`, `apps/web/src/components/issue/IssueChangelog.tsx`, `apps/web/src/components/issue/IssueDescription.tsx`, `apps/web/src/components/issue/IssueLinksPanel.tsx`, `apps/web/src/components/issue/meta/IssueLabelsEdit.tsx`, `apps/web/src/components/ooo/OooModal.tsx`, `apps/web/src/components/search/SavedFilterMenu.tsx`, `apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.index.tsx`]
- depends-on: [5]  # FilterBar가 T5와 인접, QuickFilterChips가 board 계열 → 직렬 유지

★ **위 `files`는 세션 2 재열거(§구현 진행 상황 「재열거 정본」)로 교체된 실측 목록이다.** plan 단계 초안은
"공용 `Button` 이미 소비 중" 판정이 뒤집혀 T7 목록과 **11:11로 교차 오배정**돼 있었다. 판별식은
`grep -l "from '@/components/ui/button'"` 단 하나 — 이 import가 있으면 배치 1, 없으면 배치 2(T7)다.

**RED**. 파일별 렌더 테스트에 `data-slot="button"` 존재 어서션 추가(프리미티브 마커).

```tsx
it('사이드바 토글이 Button 프리미티브를 쓴다 (PR22)', () => {
  render(<TopBar />)
  expect(screen.getByLabelText(navLabels.collapseSidebar)).toHaveAttribute('data-slot', 'button')
})
```

- 실패 메시지(예상). `expected element to have attribute data-slot="button"`

**GREEN**. P1(아이콘 액션)·P2(텍스트 액션)·P3(`Trigger asChild`) 패턴만 교체.

```tsx
<Button
  type="button"
  variant="ghost"
  size="icon-sm"
  className="rounded-md"
  aria-label={collapsed ? navLabels.expandSidebar : navLabels.collapseSidebar}
  onClick={toggle}
>
  {collapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
</Button>
```

**교체 규칙 (전 파일 공통)**.
- `aria-label`·`data-testid`·`disabled`·`onClick`·`type`은 **verbatim 이관**(NFR-N1).
- `min-h-[44px]`/`min-w-[44px]`는 WCAG 2.5.5 근거가 있으므로 `className`에 **유지**(EC2).
- `w-full text-left` 형태(P5)와 카드 전체 클릭 영역(P6)은 **건드리지 않는다** — OUT.
- `role="tab"` 지정 버튼(P4, 4발생)은 **건드리지 않는다** — OUT.
- `Trigger asChild` 하위 3발생은 교체 후 `aria-expanded`/`data-state` 주입이 살아 있는지 렌더 테스트로 확인(EC3).

**REFACTOR**. 파일별로 반복되는 `variant`/`size` 선택 근거를 1줄 주석으로 남긴다.

**검증**. `cd apps/web && pnpm vitest run src/components/layout src/components/dashboard src/components/issue src/components/board src/components/backlog src/components/filters src/components/admin src/components/workflow src/components/ooo src/components/search`

---

### Task 7. 원시 `<button>` → `Button` (배치 2 — 나머지 P1/P2 파일)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/CreateSprintForm.tsx`, `apps/web/src/components/backlog/SprintColumn.tsx`, `apps/web/src/components/component/ComponentLeadSelect.tsx`, `apps/web/src/components/dashboard/DashboardGrid.tsx`, `apps/web/src/components/dashboard/DashboardTile.tsx`, `apps/web/src/components/dashboard/GadgetCatalogModal.tsx`, `apps/web/src/components/dashboard/GadgetConfigForm.tsx`, `apps/web/src/components/dashboard/ShareDashboardModal.tsx`, `apps/web/src/components/favorite/FavoritesMenu.tsx`, `apps/web/src/components/inbox/InboxListItem.tsx`, `apps/web/src/components/inbox/SenderAutocomplete.tsx`, `apps/web/src/components/issue/AttachmentSection.tsx`, `apps/web/src/components/issue/LinkGraph.tsx`, `apps/web/src/components/issue/WorklogSection.tsx`, `apps/web/src/components/issue/meta/AssigneeUserList.tsx`, `apps/web/src/components/issue/meta/IssueAssigneeSelect.tsx`, `apps/web/src/components/issues/IssueTable.tsx`, `apps/web/src/components/layout/AccountMenu.tsx`, `apps/web/src/components/layout/ProjectTree.tsx`, `apps/web/src/components/layout/Sidebar.tsx`, `apps/web/src/components/layout/TopBar.tsx`, `apps/web/src/components/project/ProjectLeadSelect.tsx`, `apps/web/src/components/timeline/GanttChart.tsx`, `apps/web/src/components/workflow/PostActionConfigSection.tsx`, `apps/web/src/components/workflow/PostActionFormDialog.tsx`, `apps/web/src/features/calendar/MonthGrid.tsx`, `apps/web/src/routes/admin.workflow-schemes.tsx`, `apps/web/src/routes/dashboards.$dashboardId.tsx`, `apps/web/src/routes/inbox.tsx`, `apps/web/src/routes/projects.$projectKey.sprints.$sprintId.burndown.tsx`]
- depends-on: [6]  # 교체 규칙 정본이 T6에서 확정된 뒤 적용. inbox/ProjectTree가 T4·T5와 겹침

★ **위 `files`도 세션 2 재열거로 교체됐다 (27 → 30파일 · 코드 70발생).** 초안이 T6와 11:11 교차
오배정돼 있었고, 그 위에 **`dashboard/DashboardGrid.tsx`(1발생) 1파일이 T6·T7 어디에도 없었다**
(§구현 진행 상황 「재열거 정본」 정정 3). `SenderAutocomplete`·`AssigneeUserList`는 아래 GREEN 본문에
OUT으로 명시돼 있었으나 `files`에 누락돼 있어 함께 등재했다 — OUT 사유 주석 대상이므로 파일은 열어야 한다.

**RED**. Task 6과 동일 패턴으로 파일별 `data-slot="button"` 어서션.

**GREEN**. Task 6의 "교체 규칙"을 그대로 적용. ★ 다음은 **OUT이므로 남긴다**.
- `components/issue/meta/AssigneeUserList.tsx` · `components/inbox/SenderAutocomplete.tsx` ·
  `components/project/ProjectLeadSelect.tsx` · `components/component/ComponentLeadSelect.tsx`의
  **옵션 후보 행**(`w-full text-left`) — P5.
- `components/inbox/InboxListItem.tsx` · `components/dashboard/DashboardTile.tsx`의 **전체 클릭 영역** — P6.
- `role="tab"` 4발생 — P4.

(P5/P6 파일이 files에 있는 이유는 **같은 파일 안의 다른 P1/P2 버튼**은 교체 대상이기 때문이다.)

**REFACTOR**. OUT으로 남긴 각 발생 위에 `// PR22 OUT — P5 옵션 행: Button의 inline-flex/justify-center와 충돌` 형식의
사유 주석을 단다. T8의 ESLint 예외 목록과 1:1 대응시킨다.

**검증**. `cd apps/web && pnpm vitest run && pnpm typecheck`

---

### Task 8. ESLint 락 2종 + 비어있지 않음 증명

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/eslint.config.js`]
- depends-on: [4, 7]

**RED**(락은 테스트가 아니라 **실증**으로 red를 만든다).
1. 락을 먼저 추가한다.
2. `pnpm lint`를 돌려 **위반이 실제로 error로 잡히는지 확인**한다 — 0건이면 락이 vacuous이므로
   일부러 `<button>` 1개를 임시 추가해 error가 나는 것을 **로그로 남긴 뒤 되돌린다**
   ([[archunit-vacuous-rule-silent-pass]]).

**GREEN**. `apps/web/eslint.config.js`에 내장 룰만 사용(신규 의존성 0).

```js
{
  rules: {
    'no-restricted-syntax': [
      'error',
      {
        selector: "JSXOpeningElement[name.name='button']",
        message:
          '원시 <button> 대신 @/components/ui/button 의 <Button>을 쓰세요 (FR-UX-06 PR22). ' +
          'role="tab"·옵션 후보 행·카드 전체 클릭 영역은 eslint.config.js overrides 예외로 등재하세요.',
      },
      {
        selector: "JSXAttribute[name.name='className'] Literal[value=/animate-pulse/]",
        message:
          '인라인 스켈레톤 대신 @/components/ui/skeleton 의 <Skeleton>을 쓰세요 (디자인 스펙 §7 — 인라인 재정의 금지).',
      },
    ],
  },
}
```

**예외 override** — T7 REFACTOR의 사유 주석과 1:1 대응.

```js
{
  files: [
    'src/components/issue/meta/AssigneeUserList.tsx',   // P5 옵션 후보 행
    'src/components/inbox/SenderAutocomplete.tsx',      // P5
    'src/components/project/ProjectLeadSelect.tsx',     // P5
    'src/components/component/ComponentLeadSelect.tsx', // P5
    'src/components/inbox/InboxListItem.tsx',           // P6 전체 클릭 영역
    'src/components/dashboard/DashboardTile.tsx',       // P6
    'src/components/ui/**',                             // 프리미티브 레이어 자체
    '**/*.test.tsx',                                    // 테스트 픽스처
  ],
  rules: { 'no-restricted-syntax': 'off' },
}
```

★ `role="tab"` 4발생이 어느 파일인지 T7에서 확정한 뒤 이 목록에 추가한다 — **추측으로 채우지 말 것**.

**REFACTOR**. 락 신설 사유와 예외 판정 기준을 `eslint.config.js` 상단 주석으로 남긴다.

**검증**. `cd apps/web && pnpm lint` → error 0. 위반 임시 삽입 시 error 1 (로그 첨부).

---

### Task 9. 전수 동기화 — D단계 마킹 · DESIGN.md · ADR 신설

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `DESIGN.md`, `docs/decisions/2026-07-25-fr-ux-06-pr22-chart-categorical-tokens.md`, `docs/design/fr-ux-06-jira-redesign.md`]
- depends-on: [8]

**RED**. `bash scripts/verify-master-plan.sh`를 먼저 돌려 **현재 EXIT 0**임을 기준선으로 확인한다
(변경 후 깨지면 내 변경 탓임을 확정하기 위함 — [[verify-logic-vs-verify-guard]]).

**GREEN**.
1. `docs/plan/product/personalization.md:170~176` — FR-UX-06 D1·D3·D4·D5·D6·D7을 `[x]`로.
   D6 설명의 **"20 PR 체인" → "22 PR 체인"** 정정. BC 완료 게이트 문구 원복(personalization 13/13).
2. `DESIGN.md` — §C 표 `--chart-1~5` 행을 확정 hex로 교체하고 "PR22 몫" 표기 제거.
   106행 서술의 "범주색(이슈타입·차트, PR22 `--chart-*` 소관)" → 소비 완료로 갱신.
   범주 토큰 8종(`--type-*` 4 + `--type-default` + `--discovery` 2 + `--favorite`) 절 신설.
3. `docs/decisions/2026-07-25-fr-ux-06-pr22-chart-categorical-tokens.md` 신규 ADR —
   ADR §D7의 **이행 기록**. 값 선정 근거(§5.2 램프 파생), 대비 계산표,
   **`--prio-*`·`--type-subtask`를 만들지 않은 이유**(소비처 0 = D7 자기준수)를 명시.
4. `docs/design/fr-ux-06-jira-redesign.md` §4.2 — `type-subtask` 라이트 `#579DFF`가 2.74:1로
   1.4.11 미달임을 경고 주석으로 추가(미래 소비 PR 대비).

**REFACTOR**. `docs/plans/2026-07-17-fr-ux-06-jira-redesign/checklist.md`의 PR22 항목 체크.

**검증**. `bash scripts/verify-master-plan.sh` → EXIT 0 (129/129).

---

### Task 10. 회귀 검증 — 유닛 전수 + 관련 E2E 로컬 실행

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/`(신규 없음 — 기존 spec 실행만)]
- depends-on: [9]

**RED**. 없음(검증 전용 태스크). 대신 **완료 기준을 측정으로 확인**한다.

**GREEN**(측정 스크립트).

```bash
cd apps/web
set -o pipefail
echo "원시 button(프로덕션): $(grep -rno '<button' src --include='*.tsx' | grep -v '\.test\.tsx' | wc -l)"
echo "인라인 animate-pulse : $(grep -rn 'animate-pulse' src --include='*.tsx' | grep -v 'ui/skeleton' | grep -v '\.test\.' | wc -l)"
echo "FilteredEmptyState 정의: $(grep -rn 'function FilteredEmptyState' src | wc -l)"
echo "차트 하드코딩 hex   : $(grep -rnoE '#[0-9a-fA-F]{6}' src/components/{burndown,cfd,cycle-time,velocity,worklog} | grep -v '\.test\.' | wc -l)"
pnpm typecheck; echo "EXIT=$?"
pnpm lint; echo "EXIT=$?"
pnpm vitest run; echo "EXIT=$?"
pnpm build; echo "EXIT=$?"
```

기대. 원시 button = OUT 판정분만 잔존(전수 열거로 설명 가능) · animate-pulse 0 · FilteredEmptyState 정의 1 ·
차트 hex 0 · 전부 EXIT=0.

★ `zsh`에서 `${PIPESTATUS[0]}`는 항상 빈 문자열이다 — `set -o pipefail` + `$?`를 쓴다
([[zsh-pipestatus-1-based-false-green]]).

**E2E**(로컬 필수 — CI에 e2e 잡 없음).

대상 spec은 `ls apps/web/e2e/`로 **실측 확인한 실제 파일명**이다(추정 아님).

```bash
cd apps/web
./node_modules/.bin/playwright test \
  e2e/issue-table.spec.ts e2e/issue-split-view.spec.ts e2e/issue-filter.spec.ts \
  e2e/issue-changelog.spec.ts e2e/issue-attachments.spec.ts e2e/issue-links.spec.ts \
  e2e/board-kanban.spec.ts e2e/board-filter.spec.ts e2e/board-reorder.spec.ts \
  e2e/board-swimlane-field-change.spec.ts e2e/quick-filter.spec.ts e2e/saved-filters.spec.ts \
  e2e/dashboard.spec.ts e2e/dashboard-gadgets.spec.ts e2e/dashboard-share.spec.ts \
  e2e/timeline.spec.ts e2e/timeline-zoom.spec.ts e2e/calendar.spec.ts \
  e2e/inbox.spec.ts e2e/keyboard-shortcuts.spec.ts \
  --reporter=line
```

★ `pnpm exec playwright test <file>`은 positional 필터를 삼켜 전수 533건이 돌아간다 —
**바이너리를 직접 호출하고 실행 개수로 판정**한다 ([[e2e-playwright-filter-arg-drop]]).
line reporter의 `\r`가 `tail`/`wc`에 빈 출력처럼 보이므로 **개수로 판정**한다.
`board-wip-swimlane.spec.ts`·`board-epic-swimlane.spec.ts`는 본 PR이 건드리지 않으므로 제외하되,
실패 시 `origin/main` 기준선과 대조해 PRE_EXISTING 여부를 먼저 가른다.

**REFACTOR**. 없음.

**검증**. 위 측정값 전부 기대치 일치 + E2E green.

## Plan 메타

- task 수: **10**
- 예상 wave: 파일 겹침이 광범위(T3↔T4 `EpicProgressBar`, T4↔T5 `board.tsx`, T4↔T7 `inbox.tsx`·`issues.index.tsx`·`ProjectTree.tsx`)해
  **전 태스크 직렬 dispatch**가 기본이다. 병렬 이득이 거의 없고 lint-staged 공유 stash 레이스 위험만 커진다
  ([[worktree-lint-staged-shared-git-stash-collision]] — PR19에서 wave1 병렬이 커밋 귀속을 잃은 선례).
- TDD 강제: yes (T10은 검증 전용이라 RED 없음 — plan에 명시)
- 추가 검증: typecheck · eslint · vitest 전수 · build · 로컬 E2E · `verify-master-plan.sh`
- ★ controller 책임. sub-agent 보고를 믿지 말고 각 태스크 종료 시 `git diff -w`로 실물을 직접 검증한다
  ([[subagent-ktlint-false-green-controller-verify]]).

## 리뷰 결과

### plan-design-review (2026-07-25)

리뷰 대상 = 이 plan 문서. 시안 생성·외부 모델 교차검증은 **Maxi 결정으로 생략**
(새 화면 0개 · PR1의 8화면 프로토타입 시안 존재 · 디자인 스펙 14섹션이 이미 정본).

| 패스 | 초기 | 수정 후 | 근거 |
|---|---|---|---|
| 1. 정보 구조 | 7/10 | **9/10** | PR22는 IA를 바꾸지 않는다(네비·레이아웃·라우트 변경 0). DOM/testid/aria verbatim 보존이 NFR-N1로 명시됨. 감점 사유였던 캘린더 칩 시각 위계 붕괴는 이슈 1로 해소 |
| 2. 상호작용 상태 커버리지 | 6/10 | **9/10** | loading(Skeleton)·empty(FilteredEmptyState) 모두 계획에 명시. 감점 사유 = 빈 상태 컨테이너 크기 손실 → 이슈 2로 해소. error/success는 PR22가 건드리지 않음(스코프 밖) |
| 3. 사용자 여정 | 8/10 | **8/10** | 정리 PR이라 여정 변화 없음. 시각 변화 2종(차트 색·타임라인 타입색)은 S4·S5로 명시돼 있고 사내 도구라 별도 공지 불필요. **findings 없음** |
| 4. AI slop 위험 | 9/10 | **9/10** | ADS 정본 팔레트 파생이고 생성형 패턴(3열 카드 그리드·중앙정렬·장식 blob) 도입 0. 하드 리젝션 7종 해당 없음. 이슈 3(브랜드 파랑 재사용)은 Jira 관례로 확인 후 유지 결정. **BLOCKER 없음** |
| 5. 디자인 시스템 정합 | 5/10 | **9/10** | ★ 초기 5점 = 계획이 `WeekGrid.tsx:76`의 기존 결정("옅은 배지 대비 미달로 배제")을 **모르고 정반대로 매핑**. 이슈 1로 정정. DESIGN.md 갱신(T9·PR22-F9)과 값 정본 문서(T1)가 계획에 있어 시스템 정합은 강함 |
| 6. 반응형 · 접근성 | 7/10 | **8/10** | 대비 13토큰 전량 계산 검증 · 44px 터치 타깃 유지(EC2) · 키보드 내비 무변경. 감점 = `Button` 기본 높이 `h-8`이 원본 아이콘 버튼(`p-1.5`)보다 커 레이아웃이 밀릴 수 있음 → T6 교체 규칙에 size 매핑 지침 존재하나 **파일별 실측은 구현 단계 몫** |
| 7. 미해결 결정 | — | **3건 전부 해소** | 이슈 1·2·3 모두 Maxi 결정 완료 |

**해소한 findings 3건.**

1. **[BLOCKER급 — 기존 결정 역행]** 캘린더 TODO 칩을 `--bg-neutral-solid`(거의 흰색)로 매핑 →
   흰 배경에서 칩 소멸. `WeekGrid.tsx:76` 주석이 이미 "옅은 배지 = 대비 미달"을 기록하고 있었다.
   **결정 A** — `--neutral-bold` / `--neutral-bold-foreground` 신설(라이트 `#44546F` 7.65:1 · 다크 `#9FADBC` 7.65:1),
   중간톤 solid fill이라는 기존 결정 유지. 소비처 실재(캘린더 칩)라 ADR D7 준수.
2. **[회귀 위험]** `EmptyState` 프리미티브가 `px-4 py-12` 고정·`min-height` 없음 →
   흡수 시 이슈 목록 여백 축소(`py-16`→`py-12`), 보드 최소높이(`min-h-48`) 소실로 칸반 영역이 튐.
   **결정 A** — `FilteredEmptyState`가 `className`을 받아 호출부에서 원본 값을 넘긴다(시각 no-op).
3. **[확인 후 유지]** `--chart-1` = `#0C66E4`가 `--primary`/`--brand`와 동일 →
   차트 막대가 버튼처럼 보일 여지. **결정 A** — 유지. Atlassian 제품군 관례이고 차트는
   카드 경계+범례 텍스트로 맥락이 분리된다.

**BLOCKER 0건.** 이슈 1은 구현 전에 잡혀 계획 수정으로 해소됐다.

**리뷰 한계 (정직한 기록).** 이 리뷰는 plan 작성자(메인 에이전트)가 자기 계획을 검토한 것이라
독립 시각이 아니다([[bts-review-plan-autoplan-overkill]]가 지적한 편향). 독립 검증은
게이트 2의 `superpowers:code-reviewer`가 담당한다.

### NOT in scope (검토 후 명시적 이연)

| 항목 | 이연 사유 |
|---|---|
| `IssueTypeIcon` 규격화 (16×16 채운 사각 + 흰 글리프) | 디자인 스펙 §4.2 규격이나 **정리가 아닌 신규 디자인 적용**. 현재는 lucide 아이콘 형태로 타입 구분(WCAG 1.4.1 충족) |
| `--prio-*` 5종 · `--type-subtask` | **소비처 0** — 만들면 ADR §D7을 PR22가 스스로 위반 |
| "…없습니다" 50파일 EmptyState 전면 통합 | 범위가 다시 몇 배로 커짐. `FilteredEmptyState` 2건만 통합 |
| P4 `role="tab"` 4발생 · P5 옵션 행 · P6 카드 클릭 영역 | `Button` 프리미티브가 의도한 형태가 아님. ESLint 예외로 사유 등재 |
| `--syntax-*` 5종 동결 해제 | AQL textarea/overlay 정렬 계약 (`DESIGN.md:130`) |
| 차트 인터랙션(툴팁 스타일·범례 토글) | 색 토큰화만 본 PR 범위. 상호작용은 변경 0 |
| 모션/애니메이션 가이드 | 디자인 스펙 §14가 이미 후속으로 분류 |
| Pretendard 한글 웹폰트 | ADR 후속 — 토큰 PR과 엮지 말 것 |

### What already exists (재사용 대상)

| 자산 | 위치 | PR22에서의 역할 |
|---|---|---|
| `DESIGN.md` | 리포 루트 | 디자인 시스템 정본. §C 표에 `--chart-*` "PR22 몫" 표기가 **대기 중** → T9가 갱신 |
| 디자인 스펙 14섹션 | `docs/design/fr-ux-06-jira-redesign.md` | §5.2 ADS 원시 팔레트(차트 색 파생 출처) · §4.2 타입 색 · §6 컴포넌트 계층 · §7 상태 매트릭스 |
| PR3 팔레트 정본 | `docs/plans/2026-07-19-fr-ux-06-pr3-ads-palette-values.md` | "값 지어내기 방지" 선례. T1이 같은 형식으로 PR22 정본 문서 작성 |
| `state-tokens.test.ts` | `apps/web/src/components/ui/__tests__/` | 정확-hex 행렬 가드 패턴. `declarationOf`/`rootBlock`/`darkBlock` 헬퍼 재사용 |
| 프리미티브 3종 | `ui/button.tsx`(CVA variant 6/size 8) · `ui/skeleton.tsx` · `ui/empty-state.tsx` | 흡수 대상의 착륙지점. 전부 `cn()`으로 className 병합 지원 |
| 시맨틱 토큰 4쌍 | `index.css` 242~266 / 372~396 | `--{name}` / `-foreground` / `-text` 3단 패턴. 신규 토큰이 따를 규칙 |
| 8화면 프로토타입 시안 | PR1 Artifact | 이번 리뷰에서 시안 재생성이 불필요한 이유 |

### 구현 태스크 (리뷰 findings → 계획 반영 완료)

- [x] **R1 (P1)** — `index.css`/`state-tokens.test` — `--neutral-bold` 2종 추가 (이슈 1) → T1·T3에 반영됨
- [x] **R2 (P1)** — `components/filters/FilteredEmptyState.tsx` — `className` prop 추가 (이슈 2) → T5에 반영됨
- [x] **R3 (P3)** — 결정 기록만 — `--chart-1` 브랜드 파랑 유지 근거를 T9 ADR에 남긴다 (이슈 3)

_리뷰에서 새로 발견된 별도 TODO는 없음 — findings 3건 모두 본 PR 계획에 흡수._

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 0 | — | — |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | CLEAR (FULL) | score: 7/10 → 9/10, 3 decisions |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**VERDICT:** DESIGN CLEARED — 3 findings resolved into the plan, BLOCKER 0.
`/bts-review-plan`의 type=ui 체인은 `plan-design-review` 단일 리뷰이며, 독립 엔지니어링 검증은
게이트 2의 `superpowers:code-reviewer`가 담당한다(BTS 워크플로우 규약).

NO UNRESOLVED DECISIONS

---

## 구현 진행 상황 (2026-07-25 세션 1)

### 완료 — Task 1~5 (RED→GREEN→REFACTOR 각 3커밋)

| Task | 상태 | 커밋 | 결과 |
|---|---|---|---|
| T1 토큰 정의 + 동결 해제 | ✅ | `c51b8bc38` / `063d7e1a5` / `ed3ec0cc7` | `--chart-1~5` 확정 hex · 범주 10종 신설 · `@theme` 배선 · 값 정본 문서 · D7 자기준수 음성 테스트 |
| T2 recharts 토큰 소비 | ✅ | `4df6ded0e` / `f47280fcc` / `1faaaba58` | 6종 하드코딩 hex 0 · 역할 매핑 정정 |
| T3 범주색 토큰화 | ✅ | (T3 red) / `1a150a9c7` / `ec2267d8b` | 14발생/4파일 · 뮤테이션 검증 완료 |
| T4 스켈레톤 프리미티브 | ✅ | (T4 red) / `ae48dbfeb` / `73f34355a` | 인라인 33발생 → 프리미티브 · 소비 파일 1→25 |
| T5 빈 상태 통합 | ✅ | (T5 red) / `601ecb1aa` / `b78eb535b` | `FilteredEmptyState` 정의 2→1 · 화면별 여백 prop 보존 |

**T5 시점 검증** — typecheck 0 · eslint error 0(경고 8 사전존재) · 유닛 **512파일 / 7929** green ·
build 0 · git clean.

### 남은 작업 — Task 6~10

#### 재열거 정본 (2026-07-25 세션 2 — T6 착수 직전 전수 재계수)

세션 1이 남긴 개수 표기를 **믿지 않고 직접 다시 셌다**([[spec-stated-count-becomes-blindfold]] ·
[[orchestrator-instruction-counts-are-blindfolds]]). 판별식은 **`grep -l "from '@/components/ui/button'"`
하나** — 이 import가 있으면 배치 1(T6), 없으면 배치 2(T7). 개수 대신 **파일 집합을 `comm`으로 대조**했다.

| 축 | grep 발생 | 주석 | **코드 발생** | 파일 |
|---|---|---|---|---|
| 전체 `<button` (테스트·스토리 제외) | 106 | 2 | **104** | 51 |
| 배치 1 = `Button` import 있음 (T6) | 35 | 1 | **34** | **21** |
| 배치 2 = `Button` import 없음 (T7) | 71 | 1 | **70** | **30** |

주석 2발생(교체 대상 아님). `routes/issues.index.tsx:386`(JSDoc) · `components/issues/IssueTable.tsx:68`(JSDoc).

**정정 3건.**

1. **「OUT 12건」 → OUT 11건.** 아래 OUT 표의 실제 행 합은 11이고 표에 빠진 항목은 **없다**.
   세션 1이 `issues.index.tsx:386`의 **JSDoc 주석 리터럴을 코드로 세어** 12로 적었을 뿐이다.
   따라서 배치 1은 `IN 23 + OUT 11 = 코드 34발생`(grep 35). T3에서도 같은 착오가 있었고
   가드에 `stripComments`를 도입한 이유가 이것이다.
2. **T6·T7 메타 `files`가 11:11로 교차 오배정돼 있었다.** plan 단계 초안이 "공용 `Button` 이미 소비 중"
   판정을 뒤집었다. **아래 IN/OUT 표의 파일 집합은 실측 배치 1 21파일과 완전히 일치**하므로
   **표가 정본이고 메타 `files`가 stale**이었다. 양쪽 메타를 실측 목록으로 교체했다(T6 21 · T7 30).
   - T6 → T7로 이동(`Button` import 0). `TopBar` · `AccountMenu` · `Sidebar` · `DashboardTile` ·
     `GadgetConfigForm` · `GadgetCatalogModal` · `ShareDashboardModal` · `AttachmentSection` ·
     `SprintColumn` · `CreateSprintForm` · `PostActionFormDialog`
   - T7 → T6로 이동(`Button` import 1). `issues.index` · `issues.$key` · `IssueChangelog` ·
     `IssueLabelsEdit` · `ConditionBuilder` · `ActionListEditor` · `ActionConfigEditor` ·
     `AutomationRuleFormDialog` · `RuleExecutionTraceRow` · `WorkflowSchemeSidebar` ·
     `GlobalPermissionFormDialog`
3. **★진짜 누락 1파일 — `components/dashboard/DashboardGrid.tsx:136`.** T6·T7 메타·IN/OUT 표
   **어디에도 없었다.** 빈 대시보드의 "위젯 추가" P2 텍스트 액션(`bg-primary` + `min-h-[44px]` +
   `aria-label` + `Plus` 아이콘)으로 **T7 IN 대상**이다. 배치 2라 T7에서 처리한다.
   부수로 `inbox/SenderAutocomplete` · `issue/meta/AssigneeUserList`는 T7 GREEN 본문에 OUT으로
   명시돼 있었으나 메타 `files`에 없었다 → 등재(OUT 사유 주석을 달아야 하므로 파일을 열어야 한다).

★ **T8 ESLint 락 영향.** `no-restricted-syntax`는 AST(추상 구문 트리 — 코드를 구조로 파싱한 형태)
셀렉터라 **주석 속 리터럴은 애초에 위반으로 잡지 않는다.** 따라서 위 주석 2발생은 `overrides` 예외
목록에 넣지 않는다. 예외 목록은 **코드 OUT 11건 + T7 OUT 발생**만으로 구성한다.

**T6 작업 목록 (배치 1 = 공용 `Button` 이미 소비 중인 21파일 · 코드 34발생)**
아래 표가 그대로 T6의 작업 목록이다. 실측으로 확증됐다.

**IN 23건** — `<Button variant="ghost">` 흡수 대상.

| 파일 | 행 | 형태 |
|---|---|---|
| `automation/ActionConfigEditor.tsx` | 318 · 495 | 라벨 칩 × · 헤더 삭제 × |
| `automation/ActionListEditor.tsx` | 122 · 133 · 145 | ↑ · ↓ · × |
| `automation/AutomationRuleFormDialog.tsx` | 463 | 필드 칩 × |
| `automation/ConditionBuilder.tsx` | 141 · 187 · 212 | 비교 삭제 × · AND/OR 토글 · 그룹 삭제 |
| `board/QuickFilterChips.tsx` | 178 · 188 · 196 | 칩 적용 · ✎ · ✕ |
| `dashboard/DashboardForm.tsx` | 194 | 칩 제거 × |
| `filters/FilterBar.tsx` | 330 | 칩 제거 × |
| `issue/EpicChildrenSection.tsx` | 97 | 연결 해제 |
| `issue/IssueLinksPanel.tsx` | 128 · 314 · 436 | 링크 삭제 · 부모 해제 · 에픽 해제 |
| `issue/meta/IssueLabelsEdit.tsx` | 135 | 라벨 제거 × |
| `ooo/OooModal.tsx` | 228 | 대리자 선택 해제 |
| `search/SavedFilterMenu.tsx` | 214 | 메뉴 트리거 |
| `routes/issues.$key.tsx` | 714 | 제목 편집 |
| `routes/issues.index.tsx` | 403 | 새 이슈(비활성 fail-closed) |

**OUT 11발생** — 남기고 T8 ESLint 예외로 등재할 대상(사유 포함).
★ 세션 2 검산. **표 행 10개 ≠ 발생 11건** — `IssueDescription`이 한 행에 2발생(247·257)을 담고 있다.
"행 수"와 "발생 수"를 같은 것으로 세지 말 것. T8 예외 목록은 **발생 단위 11건**으로 만든다.

| 파일 | 행 | 패턴 | 사유 |
|---|---|---|---|
| `admin/AddMemberDialog.tsx` | 83 | P5 옵션 행 | `w-full text-left` 콤보박스 후보 |
| `admin/AuditLogFilters.tsx` | 89 | P5 | 좌동 |
| `admin/WorkflowSchemeSidebar.tsx` | 93 | P5 | 사이드바 항목(`aria-current`) |
| `dashboard/DashboardForm.tsx` | 173 | P5 | 좌동 |
| `filters/FilterBar.tsx` | 255 | P5 | 좌동 |
| `global-permissions/GlobalPermissionFormDialog.tsx` | 95 | P5 | 좌동 |
| `ooo/OooModal.tsx` | 64 | P5 | 좌동 |
| `issue/IssueDescription.tsx` | 247 · 257 | **P4 `role="tab"`** | 탭 시맨틱 — `Button`이 role 충돌 위험 |
| `issue/IssueChangelog.tsx` | 309 | P6 전체행 디스클로저 | `w-full` 행 토글 |
| `automation/RuleExecutionTraceRow.tsx` | 88 | P6 | 좌동 |

★ **T6 구현 시 주의(세션 1에서 실제로 밟은 함정).** 배치 1 파일은 **이미 `<Button>`을 쓰고 있다.**
`type="button"` 같은 공통 속성으로 일괄 치환하면 **기존 `<Button>` 사용처에도 variant가 주입돼
멀쩡한 버튼의 외형이 바뀐다**(1차 시도에서 `AutomationRuleFormDialog` 1→2, `ConditionBuilder` 3→5로
검출·전량 원복). 반드시 **`^\s*<button$` 형태의 여는 줄만** 대상으로 삼고, 치환 후
`grep -c 'variant='` 개수를 예상치와 대조할 것.

★ `cn()`이 `tailwind-merge`를 쓰므로 호출부 `className`이 variant 클래스를 덮어쓴다 — 원본 className을
그대로 넘기면 시각 변화를 최소화할 수 있다(`min-h-[44px]`는 WCAG 2.5.5 근거가 있어 유지).

**T7~T10.** 계획 본문 그대로. T7은 나머지 P1/P2 파일(~27파일), T8 ESLint 락(위 OUT 12건 + P4/P5/P6
잔여를 `overrides` 예외로 등재), T9 전수 동기화(D단계 마킹·DESIGN.md·ADR), T10 회귀 검증.
