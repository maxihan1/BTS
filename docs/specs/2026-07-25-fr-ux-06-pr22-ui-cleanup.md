# FR-UX-06 Phase 5 PR22 — 원시 button 정리 + EmptyState/Skeleton 중복 제거 + --chart-* 실소비 — 스펙

> slug: fr-ux-06-pr22-ui-cleanup · type: ui · BC: personalization · PR #308
> 기준 커밋: main `f2beb806c` · 작성 2026-07-25

## 0. 요약 — 실측이 스코프 가정을 뒤집었다

착수 시 가정은 "PR22 = 자잘한 정리 3덩어리"였다. **전수 실측 결과 3덩어리 각각이 PR 하나 크기다.**

| 덩어리 | 착수 시 가정 | 실측 | 배수 |
|---|---|---|---|
| 원시 `<button>` | "정리" | **프로덕션 106발생 / 52파일** (+테스트 18) | — |
| Skeleton 중복 | "인라인 중복 2곳" (디자인 스펙 §6) | **인라인 `animate-pulse` 33발생 / 25파일**, 로컬 `*Skeleton` 함수 **19개 정의**, 프리미티브 소비 **1파일뿐** | 16배 |
| EmptyState 중복 | "`FilteredEmptyState` 중복 2곳" | 중복 2곳은 사실. 단 프리미티브 소비 **1파일뿐**, "…없습니다" 문구 **50파일 / i18n 41키** | — |
| `--chart-*` 실소비 | "차트 5색 정의" | recharts 컴포넌트 **6종이 하드코딩 hex 6종** 사용. `--chart-1~5`는 소비 0 + **동결 테스트로 잠김** | — |
| 범주색 (PR4 이연분) | "~14건" | **10발생 / 3파일** (TimelineRow 5·WeekGrid 4·EpicProgressBar 1) | — |

**따라서 본 스펙은 스코프 확정을 게이트 1의 필수 결정으로 올린다** (§9).

---

## 1. 사용자 시나리오 (Given-When-Then)

본 PR은 **사용자 눈에 보이는 동작을 바꾸지 않는 것이 목표**인 정리 PR이다.
따라서 시나리오는 "무엇이 달라지는가"가 아니라 **"무엇이 달라지면 안 되는가"**로 쓴다.
예외는 S4(차트 색)뿐이다.

- **S1 (무회귀 — 버튼).** Given 사용자가 아무 화면에서 버튼을 누른다.
  When 그 버튼이 원시 `<button>`에서 `Button` 프리미티브로 교체됐다.
  Then 클릭 동작·`aria-label`·`disabled`·키보드 포커스 순서가 **모두 이전과 같다**.
  단 focus 링은 프리미티브 표준(`focus-visible:ring-3 ring-ring/50`)으로 통일된다.

- **S2 (무회귀 — 로딩).** Given 목록/차트/상세가 로딩 중이다.
  When 인라인 스켈레톤이 `Skeleton` 프리미티브로 교체됐다.
  Then 스켈레톤 **개수·배치·크기**가 이전과 같고, `role="status"`/`aria-hidden`/`aria-label`이 보존된다.

- **S3 (무회귀 — 빈 상태).** Given 필터를 걸어 결과가 0건이다.
  When `FilteredEmptyState` 중복 정의가 공용 컴포넌트로 통합됐다.
  Then 안내 문구·"필터 초기화" CTA 동작·E2E 셀렉터가 **문구 단위로 동일**하다.
  ★ 두 정의는 **문구가 서로 다르다**(이슈 "필터 조건에 맞는 이슈가 없습니다." 2줄 + 보드 "조건에 맞는 카드가 없습니다" 1줄) → 통합해도 **문구는 각 화면 것을 유지**한다.

- **S4 (변화 — 차트 색).** Given 사용자가 번다운·CFD·속도·사이클타임·작업시간 차트를 본다.
  When 하드코딩 hex가 `--chart-1~5` 토큰으로 교체됐다.
  Then 차트 색이 ADS 데이터 시각화 팔레트로 **바뀌고**, 다크 모드에서 별도 값이 적용되며,
  라이트/다크 모두 배경 대비 3:1(비텍스트 대비, WCAG 1.4.11) 이상을 만족한다.

- **S5 (변화 — 범주색, 스코프 B 채택 시).** Given 사용자가 타임라인 막대/캘린더 칩/에픽 진행바를 본다.
  When Tailwind 리터럴 색이 `--type-*`/`--prio-*` 토큰으로 교체됐다.
  Then 이슈 타입별 색이 디자인 스펙 §4.2 표(Epic 보라 · Story 초록 · Task 파랑 · Bug 빨강 · Subtask 연파랑)와 일치한다.
  ★ 현재 TimelineRow의 Story=파랑·Task=청록은 **스펙과 어긋나 있다** → 채택 시 **눈에 보이는 색 변화**가 발생한다.

## 2. 기능 요구사항 (FR)

FR 대장 신설 없음 — **FR-UX-06(129번째 FR)의 D단계 소비 화면 작업**이다. **FR 총수 129 불변.**

| ID | 요구사항 | 근거 |
|---|---|---|
| PR22-F1 | 원시 `<button>` 중 **IN-SCOPE 판정분**을 `Button` 프리미티브로 교체한다 | 디자인 스펙 §6 컴포넌트 계층 |
| PR22-F2 | 인라인 스켈레톤을 `Skeleton` 프리미티브로 교체한다 | 디자인 스펙 §7 "loading = skeleton 프리미티브(인라인 재정의 금지)" |
| PR22-F3 | `FilteredEmptyState` 2중 정의를 공용 컴포넌트 1개로 통합한다 | 디자인 스펙 §6(258행) |
| PR22-F4 | `--chart-1~5`에 ADS 데이터 시각화 팔레트 실값(라이트/다크)을 정의하고, recharts 6종이 실제로 소비한다 | ADR **§D7** |
| PR22-F5 | `state-tokens.test.ts`의 `--chart-*` **동결 계약을 해제**하고 정확-hex 행렬 가드로 대체한다 | PR11이 `--sidebar-*`에서 한 것과 동일 패턴 |
| PR22-F6 | (스코프 B) `--type-*` 5종 · `--prio-*` 5종을 신설하고 범주색 10발생이 소비한다 | 디자인 스펙 §4.2·§4.3, PR4 이연분 |
| PR22-F7 | 재발 방지 락 — 인라인 재정의를 CI가 차단한다 | PR8의 ESLint 락 선례 |

## 3. 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| PR22-N1 | **DOM 계약 보존.** `data-testid` · `aria-label` · `role` · `id`는 verbatim 유지. E2E 셀렉터가 깨지면 안 된다 |
| PR22-N2 | **로직 diff 0.** 교체 대상 파일은 `git diff -w`로 봤을 때 동작 로직 변경이 없어야 한다 (PR5~PR7 흡수 선례) |
| PR22-N3 | **대비.** 차트/범주 색은 라이트·다크 모두 비텍스트 대비 3:1 이상 (WCAG 1.4.11) |
| PR22-N4 | **색만으로 구분 금지** (WCAG 1.4.1) — 차트는 범례 텍스트, 타입은 글리프 형태가 이미 다름 |
| PR22-N5 | 백엔드 변경 0 · 마이그레이션 0 · 신규 npm 의존성 0 |
| PR22-N6 | 기존 유닛 7845 + board E2E 13 무회귀 |

## 4. 실측 상세 — 원시 `<button>` 106발생 / 52파일

### 4.1 패턴 분류 (표본 검증 완료)

| 패턴 | 실측 | 판정 | 사유 |
|---|---|---|---|
| **P1. 아이콘 전용 액션** (`aria-label` + lucide 아이콘 + `p-1.5 hover:bg-accent`) | TopBar 4 · DashboardTile 1 · QuickFilterChips 2 등 다수 | **IN** | `<Button variant="ghost" size="icon">` 정확 대응 |
| **P2. 텍스트 액션** (`bg-primary text-primary-foreground` 등 프리미티브 variant 재현) | TopBar "만들기" 등 | **IN** | `<Button>` / `variant="outline"` 대응 |
| **P3. Radix `Trigger asChild` 직속** | **3발생** | **IN(주의)** | `asChild`가 props를 주입하므로 `<Button>`으로 교체 가능. 단 `aria-expanded`/`data-state` 전달 확인 필수 |
| **P4. `role="tab"` 지정** | **4발생** | **OUT** | 탭 시맨틱. `Button`으로 감싸면 `role` 충돌 위험. 디자인 스펙 §6은 "뷰 전환 Tabs 금지" 별도 규칙도 있음 |
| **P5. 옵션/목록 행 전체 클릭** (`w-full text-left` 콤보박스 후보 행) | AssigneeUserList · SenderAutocomplete · ProjectLeadSelect · ComponentLeadSelect 등 | **판정 보류** | `Button`의 `inline-flex justify-center`가 `w-full text-left`와 충돌. `className` override로 가능하나 **프리미티브 의미를 훼손** |
| **P6. 카드/타일 전체 클릭 영역** (DashboardTile 제목·InboxListItem) | 4발생 | **OUT** | 버튼이 아니라 "클릭 가능한 영역". variant 어디에도 안 맞음 |
| **P7. 테스트 파일 내 `<button>`** | **18발생 / 9파일** | **OUT** | 테스트 픽스처(`action={<button>}`)는 프리미티브 대상 아님 |

### 4.2 파일 상위 분포 (프로덕션 106)

`dashboards.$dashboardId.tsx` 8 · `WorklogSection` 7 · `ShareDashboardModal` 6 · `AttachmentSection` 5 ·
`inbox.tsx`/`TopBar`/`GadgetConfigForm` 각 4 · 3발생 5파일 · 2발생 13파일 · 1발생 25파일.

**52파일 중 21파일은 이미 `Button`을 import 하면서 원시 `<button>`이 섞여 있다** — 즉 같은 파일 안에
두 스타일이 공존한다. 이 21파일이 **가장 이득이 큰 우선순위 대상**이다.

## 5. 실측 상세 — Skeleton

- **프리미티브 소비: `components/layout/ProjectTree.tsx` 단 1파일** (PR12 산출물).
- **인라인 `animate-pulse` 33발생 / 25파일.**
- **로컬 `*Skeleton` 함수 정의 19개.** 그중 **3개는 이름이 그냥 `Skeleton`** —
  `routes/dashboards.tsx:21` · `routes/projects.$projectKey.board.tsx:51` · `routes/projects.$projectKey.timeline.tsx:94`.
  **프리미티브와 이름이 정면 충돌**하는 로컬 재정의이며, 셋 다 본문이 동일하다
  (`animate-pulse rounded-md bg-muted` + `className` 병합) → 프리미티브와 **기능적으로 동일**.
- 나머지 16개(`MemberListSkeleton` 등)는 **구성(composition)** — 프리미티브를 조립해 리스트 형태를 만드는 래퍼.
  이들은 삭제 대상이 아니라 **내부의 `<div className="animate-pulse …">`를 `<Skeleton>`으로 바꾸는 대상**이다.
- ★ `bg-muted`(shadcn alias) vs 프리미티브의 `bg-(--bg-neutral)`(PR3 ADS 토큰) — **색이 미세하게 다르다.**
  교체 시 시각 변화가 발생하므로 무회귀 주장을 하려면 두 값의 실제 계산색을 대조해야 한다.

## 6. 실측 상세 — EmptyState

- **프리미티브 소비: `components/project/ProjectListTable.tsx` 단 1파일** (#277 PR-5 산출물).
- `FilteredEmptyState` 2중 정의 — `routes/issues.index.tsx:155·166`, `routes/projects.$projectKey.board.tsx:241·246`.
  **props 시그니처는 동일**(`{ onReset }`)하나 **본문이 다르다**.

| | 이슈 | 보드 |
|---|---|---|
| 컨테이너 | `py-16 text-muted-foreground` | `min-h-48 gap-3 text-center` |
| 1행 | "필터 조건에 맞는 이슈가 없습니다." (`text-base`) | "조건에 맞는 카드가 없습니다" (`text-sm`) |
| 2행 | "다른 조건을 시도하거나 필터를 초기화하세요." | 없음 |
| CTA 라벨 | 하드코딩 "필터 초기화" | i18n `boardFilterLabels.filter.reset` |

→ **통합 시 문구는 각 화면 것을 prop으로 주입**해야 한다(N1 위반 방지). CTA 라벨도 이슈 쪽을 i18n으로 올릴지 별도 판단.
- "…없습니다" 문구는 **프로덕션 50파일 / i18n `ko.ts` 41키**. 전면 통합은 PR22 범위를 다시 몇 배로 키우므로
  **본 PR은 `FilteredEmptyState` 2건만 통합**하고 나머지는 후속으로 명시한다.

## 7. 실측 상세 — `--chart-*` 실소비

### 7.1 현재 상태

- `index.css` 라이트 170~178 · 다크 307~315 — `--chart-1~5` 전부 `oklch(… 0 0)` **무채색 placeholder**.
- `state-tokens.test.ts:221~227` — **"🔒 동결 계약 — PR3가 건드리면 안 되는 토큰"**으로 `--chart-1~5` +
  `--syntax-*` 5종을 잠그고 `oklch(` 원값 유지를 강제. **PR22가 이 자물쇠를 여는 첫 PR.**

### 7.2 실제 소비자 (ADR D7의 "Recharts 가젯 6종"과 정확히 일치)

| 컴포넌트 | 현재 하드코딩 |
|---|---|
| `BurndownChart` | `#6366f1`(실측) · `#f59e0b`(이상선) · `#94a3b8`(스코프) |
| `CfdChart` | `#6366f1`(Done) · `#f59e0b`(진행) · `#cbd5e1`(TODO) |
| `CycleTimeBoxPlot` | `#6366f1` 단일 accent |
| `CycleTimeHistogram` | `#6366f1` 막대 |
| `VelocityChart` | `#94a3b8`(약속) · `#6366f1`(완료) · `#475569`(평균약속) · `#4338ca`(평균완료) |
| `WorklogAggregateChart` | `#6366f1` 막대 ×2 |

**서로 다른 hex 6종** = `#6366f1` · `#f59e0b` · `#94a3b8` · `#cbd5e1` · `#475569` · `#4338ca`.
→ **5개 토큰(`--chart-1~5`)으로는 1개 부족**하다. 시리즈 역할이 "주 계열 / 보조 / 중립 / 강조"로 나뉘므로
**역할 기반 매핑**(주=chart-1, 이상·진행=chart-2, 중립=chart-3, 평균선=chart-4/5)으로 재배치해야 하며,
이는 **차트 색이 눈에 띄게 바뀐다**는 뜻이다(S4).

★ recharts는 CSS 변수를 SVG `fill`/`stroke`로 직접 못 받는 경우가 있어 `var(--chart-1)` 문자열 전달이
동작하는지 **실물 검증 필요**(구현 단계 첫 태스크에서 확인). 안 되면 `getComputedStyle` 대신
Tailwind `text-chart-1` + `fill="currentColor"` 우회를 쓴다.

## 8. 실측 상세 — 범주색 (PR4 이연분)

**10발생 / 3파일** (착수 시 메모리의 "~14건"보다 적음 — 메모리 카운트 불신 원칙대로 재계수함).

| 파일 | 발생 | 현재 값 | 디자인 스펙 §4.2 정본 | 일치? |
|---|---|---|---|---|
| `TimelineRow.tsx:25-28` | 4 | epic `bg-purple-500` · story `bg-blue-400` · task `bg-teal-400` · bug `bg-red-400` (+ 폴백 `bg-gray-400`) | Epic Purple700 · Story **Green600** · Task **Blue700** · Bug Red700 | **불일치 2종**(story·task) |
| `WeekGrid.tsx:86-87,249,266` | 4 | TODO `bg-slate-600` · IN_PROGRESS `bg-blue-800` · 칩 `bg-violet-800` ×2 | 상태는 §4.1 로젠지 토큰(`--info-bg` 등) | 별개 축(상태색) |
| `EpicProgressBar.tsx:121` | 1 | `bg-blue-400` | Epic 계열이면 `--type-epic` | 불일치 |

★ **`IssueTypeIcon.tsx`는 색을 전혀 쓰지 않는다** — lucide 아이콘 형태만으로 타입을 구분한다.
디자인 스펙 §4.2("16×16 채운 라운드 사각 + 흰 글리프")와 다르며, 이를 맞추면 **정리가 아니라 신규 디자인 적용**이 된다 → **OUT**(후속 명시).

## 9. ★ 스코프 옵션 — 게이트 1 결정 사항

전체를 다 하면 **한 PR에 60+파일**이 된다. FR-UX-06 마지막 PR이라는 상징성과 실행 가능성의 균형이 필요하다.

| | A. 계약 확립 (최소) | B. 표준 (권장) | C. 전량 |
|---|---|---|---|
| 원시 `<button>` | 이미 `Button` 쓰는 **21파일만** | 21파일 + P1/P2 패턴 전량 (~40파일) | 106발생 전량 (P5·P6 포함) |
| Skeleton | 이름충돌 로컬 `Skeleton` **3개 제거** | 3개 + 인라인 `animate-pulse` **33발생 전량** | 좌동 |
| EmptyState | `FilteredEmptyState` 2→1 | 좌동 | + "없습니다" 50파일 통합 |
| `--chart-*` | 값 정의 + 6종 소비 + 동결해제 | 좌동 | 좌동 |
| 범주색 | OUT (후속) | **IN** (10발생·`--type-*`/`--prio-*` 신설) | IN |
| 재발 방지 락 | ESLint 락 (인라인 스켈레톤/원시 button) | 좌동 | 좌동 |
| 예상 파일 수 | ~30 | ~50 | ~75 |
| 리스크 | 낮음 (계약만 세우고 나머지는 락이 막음) | 중 (시각 변화 3종: 차트·범주·skeleton 색) | 높음 (P5/P6 프리미티브 의미 훼손) |

**권장 = B.** 이유. (1) PR22는 개편의 **마지막** PR이라 A로 끝내면 하드코딩이 남은 채 FR-UX-06이 종료된다.
(2) C의 P5(옵션 행)·P6(카드 클릭 영역)은 `Button` 프리미티브가 애초에 의도한 형태가 아니어서
`className` override로 우겨넣으면 **프리미티브 계약이 오히려 약해진다**.
(3) 시각 변화 3종은 어차피 A에서도 차트 때문에 발생하므로 B가 추가로 지는 위험은 범주색뿐이다.

## 10. 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| EC1 | `Button`의 기본 `rounded-lg`가 원본 `rounded-md`/`rounded-full`과 다름 | `className`으로 override. 라운드는 디자인 스펙 §5.5가 정본(버튼 3px) — **원본이 아니라 스펙을 따른다** |
| EC2 | `Button` 기본 `h-8`이 접근성 최소 타깃 `min-h-[44px]`와 충돌 (QuickFilterChips·AssigneeUserList 등) | `min-h-[44px]` className 유지. 44px는 WCAG 2.5.5 근거가 있는 의도적 값 |
| EC3 | `Trigger asChild` 하위를 `Button`으로 바꿀 때 `aria-expanded`/`data-state` 주입 소실 | `Slot.Root`가 props를 병합하므로 보존됨. **3발생 각각 렌더 테스트로 확인** |
| EC4 | 로컬 `function Skeleton` 제거 시 같은 파일의 사용처가 프리미티브를 import해야 함 | import 추가 + 이름 동일이라 호출부 무변경 |
| EC5 | `bg-muted` → `bg-(--bg-neutral)` 색 변화 | 두 값 실계산 대조 후, 차이가 있으면 **의도된 변화로 기록**(스펙 §7 loading 정본이 프리미티브) |
| EC6 | recharts가 `var(--chart-1)` 문자열을 SVG에 못 넘김 | §7.2 우회안. 구현 첫 태스크에서 실물 검증 |
| EC7 | 다크 모드 차트 색 미정의 시 라이트 값이 그대로 노출 | `.dark` 블록에 5종 전부 정의 + state-tokens.test 행렬 가드 |
| EC8 | 동결 테스트 해제가 `--syntax-*` 5종까지 같이 풀림 | **`--chart-*`만 해제**하고 `--syntax-*`는 동결 유지 (AQL 하이라이터 전용·소비처 별개) |
| EC9 | 범주색 변경으로 타임라인 story/task 색이 눈에 띄게 바뀜 | S5로 명시. 게이트 1에서 Maxi 승인 후 진행 |
| EC10 | E2E가 색/클래스에 의존 | `grep -rn "bg-purple\|animate-pulse" apps/web/e2e`로 사전 확인 |

## 11. 제약 조건

- 백엔드·마이그레이션·신규 의존성 **0**. FR 총수 **129 불변** (`verify-master-plan.sh` EXIT 0 유지).
- 🔒 `aria-label` 4종 네비 계약 · `<h1>` 단독 · `role="dialog"` 보존 ([[frontend-nav-aria-label-e2e-contract]]).
- **CI에 e2e 잡이 없음** → 로컬 E2E 필수. `pnpm exec playwright test <file>`은 positional 필터를 무시하므로
  **바이너리 직접 호출 + 개수로 판정** ([[e2e-playwright-filter-arg-drop]]).
- ESLint 락을 새로 걸면 **비어있지 않음을 먼저 증명**한다 (락 → 위반 실재 확인 → 수정 → 0). PR8 선례·[[archunit-vacuous-rule-silent-pass]].
- 병렬 dispatch 시 `git commit --no-verify` + 직렬 커밋 ([[worktree-lint-staged-shared-git-stash-collision]]).

## 12. 측정 가능한 완료 기준

1. `grep -rno '<button' apps/web/src --include='*.tsx' | grep -v test` 발생 수가 **스코프 판정 IN 항목만큼 정확히 감소**하고, 남은 발생이 전부 §4.1의 OUT 패턴으로 **전수 열거·설명**된다.
2. `grep -rn "animate-pulse" apps/web/src --include='*.tsx' | grep -v 'ui/skeleton'` = **0** (스코프 B).
3. `FilteredEmptyState` 정의 수 = **1**.
4. `--chart-1~5`가 라이트/다크 각각 확정 hex이고 `state-tokens.test.ts`가 **정확-hex 행렬**로 가드 (동결 describe 삭제, `--syntax-*` 동결은 잔존).
5. recharts 6종에서 하드코딩 hex 문자열 **0**.
6. (B) `--type-*`/`--prio-*` 10종 정의 + 범주색 하드코딩 **0**.
7. ESLint 락이 위반을 실제로 error 처리함을 **일부러 위반을 넣어 확인**한 로그가 남는다.
8. `pnpm typecheck` 0 · `pnpm lint` error 0 · 유닛 **7845 이상** green · `pnpm build` 0.
9. 관련 E2E(이슈목록·보드·대시보드·타임라인·캘린더·인박스) 로컬 green.
10. `bash scripts/verify-master-plan.sh` EXIT 0 (129/129).

## 13. Out of scope (후속 명시)

- "…없습니다" 문구 50파일 EmptyState 전면 통합.
- `IssueTypeIcon`을 디자인 스펙 §4.2 규격(채운 사각 + 흰 글리프)으로 재디자인 — 정리가 아닌 신규 디자인.
- P5(옵션 행)·P6(카드 클릭 영역) 원시 button — 프리미티브 의미와 불일치.
- `--syntax-*` 5종 동결 해제.
- `AlertDialog` 2파일(`auth/AccountLinkCard`·`admin/SchemeInUseModal`) → `ui/alert-dialog` 래퍼 신설 (별도 스코프, 기존 후속).
- Pretendard 한글 웹폰트 (ADR §후속 — 토큰 PR과 엮지 말 것).
