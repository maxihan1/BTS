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

---

## 14. Brainstorming Sanity Check — gap 9건 (2026-07-25)

Phase B는 스펙을 재작성하지 않고 **가정을 반증**하는 데 집중했다. 결과 2건은 스펙이 틀렸고(무회귀에 유리),
2건은 미해결 결정이며, 2건은 스펙이 통째로 빠뜨린 필수 작업이다.

### ✅ G1 (반증 — 스펙보다 유리). Tailwind 유틸리티가 이미 배선돼 있다

`index.css` `@theme` 45~59행에 **`--color-chart-1~5: var(--chart-N)`가 이미 존재**한다.
즉 `bg-chart-1` · `text-chart-1` · `fill-chart-1` 유틸리티가 **이미 생성되고 있다**(값이 무채색일 뿐).
→ EC6의 우회안(`fill="currentColor"` + `text-chart-1`)이 **추가 배선 없이 즉시 사용 가능**하다.
`var(--chart-1)` 직접 전달이 실패해도 대안이 확보돼 있으므로 EC6은 리스크에서 내려온다.

### ✅ G2 (반증 — 스펙 §5 오류 정정). `bg-muted` → `bg-(--bg-neutral)`는 정확한 no-op

스펙 §5는 "색이 미세하게 다르다"고 적었으나 **실측 결과 두 토큰 값이 완전히 동일**하다.

| | 라이트 | 다크 |
|---|---|---|
| `--muted` (`index.css:154`/`291`) | `#F7F8F9` | `#BCD6F00A` |
| `--bg-neutral` (`index.css:216`/`346`) | `#F7F8F9` | `#BCD6F00A` |

→ **EC5 폐기.** 인라인 스켈레톤 33발생의 `bg-muted` → `Skeleton` 프리미티브 교체는 **시각 변화 0**이며
무회귀 주장이 성립한다. 시각 변화가 남는 것은 차트(S4)와 범주색(S5)뿐이다.

### ✅ G9 (반증 — EC10 해소). E2E는 색·스켈레톤 클래스에 의존하지 않는다

`grep -rn 'animate-pulse|bg-purple|bg-teal|bg-violet|bg-slate|#6366f1' apps/web/e2e` = **0건**.
`FilteredEmptyState` 문구에 의존하는 것은 **유닛 1건**뿐 — `routes/__tests__/projects.board.test.tsx:639`
(`/조건에 맞는 카드가 없습니다/i`). e2e의 "필터 초기화" 3건은 전부 **주석·테스트 제목**이고 셀렉터가 아니다.
→ 통합 시 보드 문구만 verbatim 보존하면 된다.

### ❓ G3 (미해결 — Maxi 결정 필요). ADS 데이터 시각화 팔레트의 **정본이 존재하지 않는다**

ADR §D7은 "ADS 데이터 시각화 팔레트로 채운다"고 방향만 정했고, **구체 hex가 어느 문서에도 없다.**
디자인 스펙 §5.2는 core 팔레트(Blue/Green/Red/Orange/Purple 램프)만 담고 있다.
게다가 §5.1이 기록하듯 `atlassian.design`은 JS 렌더링이라 **PR3에서 전수 검증에 실패한 전력**이 있다.

선택지 3안.

| | 출처 | 장점 | 단점 |
|---|---|---|---|
| **가. core 팔레트에서 파생** | 디자인 스펙 §5.2의 이미 검증된 램프에서 5 hue 선정(Blue700·Green600·Purple700·Orange600·Red700 등) | 추가 외부 조회 0 · 이미 PR3가 대비 검증한 값 · 나머지 UI와 색 일관 | ADS 공식 dataviz 팔레트와 다를 수 있음 |
| **나. 현 하드코딩 hex를 ADS 근사로 매핑** | `#6366f1`→Purple, `#f59e0b`→Orange … | 시각 변화 최소 | 원본이 임의값이라 "왜 이 색?"의 근거가 약함 |
| **다. atlassian.design 재조회** | 외부 | 공식성 | PR3 실패 전력 · 검증 불가 시 작업 중단 |

**권장 = 가.** PR3가 "팔레트는 정본이 아니다"를 겪고 **이미 검증한 값만 쓰는** 원칙을 세웠으므로 일관된다.

### ❓ G4 (미해결 — 설계 필요). 재발 방지 락(F7)이 제약과 충돌한다

- `eslint-plugin-react` **미설치**(`eslint-plugin-react-hooks`·`react-refresh`만) → `react/forbid-elements` 사용 불가.
  플러그인 추가는 **NFR-N5(신규 의존성 0)와 충돌**.
- 게다가 §4.1의 OUT 판정분(P4 `role="tab"` 4 · P5 옵션행 · P6 카드영역)이 **정당하게 살아남으므로**
  원시 `<button>` 전면 금지 락은 애초에 불가능하다 — 예외 목록이 필요하다.

선택지.

| | 방식 | 비고 |
|---|---|---|
| **가** | `no-restricted-syntax` (플러그인 불필요·내장 룰)로 `JSXOpeningElement[name.name='button']` 차단 + OUT 파일 `overrides`에서 off | PR8 ESLint 락과 동일 구조 · 신규 의존성 0 |
| **나** | 테스트 기반 가드 (`state-tokens.test.ts` 선례) — 소스를 읽어 발생 수 상한을 어서션 | 개수 가드는 [[spec-stated-count-becomes-blindfold]] 위험 |
| **다** | 락 생략, 문서 규칙만 | PR8이 "락 없으면 재발"을 이미 겪음 → 기각 |

**권장 = 가.** 스켈레톤 락도 같은 룰로 `JSXAttribute[name.name='className']` 값에 `animate-pulse` 포함 시 차단 가능.
★ 락은 반드시 **비어있지 않음을 먼저 증명**한다(락 → 위반 실재 error 확인 → 수정 → 0).

### ⚠️ G5 (스펙 누락 — 필수). FR-UX-06 **D단계 체크박스가 하나도 안 채워져 있다**

`docs/plan/product/personalization.md:170~176` 실측.

| 단계 | 현재 | PR22에서 |
|---|---|---|
| D1 도메인 (디자인 토큰 + 프리미티브 15종) | `[ ]` | **PR2·PR3로 완료됨 → 체크** |
| D2 명세 | `[x]` (#279) | 유지 |
| D3 데이터 모델 (없음) | `[ ]` | 해당 없음 → 체크 |
| D4 백엔드 (없음) | `[ ]` | 해당 없음 → 체크 |
| D5 백엔드 테스트 (해당 없음) | `[ ]` | 해당 없음 → 체크 |
| D6 프론트 UI | `[ ]` | **PR22 완료 시 체크** |
| D7 E2E | `[ ]` | **PR22 완료 시 체크** |

★ 같은 줄의 **"20 PR 체인"은 실제 22 PR과 drift** → 문구도 정정한다.
PR22는 FR-UX-06의 **마지막 PR**이므로 D단계 전량 마킹 + **BC 완료 게이트 해제**가 본 PR의 책임이다
(허브 메모리가 "personalization 12/13 … UX-06 개편 진행 중 → 완료게이트 해제"로 기록한 상태를 되돌리는 작업).
CLAUDE.md §명세/범위 변경 시 전수 동기화 체크리스트 3·6·7·8번 대상.

### ⚠️ G6 (스펙 누락 — 필수). `DESIGN.md`가 PR22를 명시적으로 기다리고 있다

- `DESIGN.md:114` — 표 행 `| --chart-1 ~ --chart-5 | 5 | OKLCH 회색조(그대로 유지, 색 구분 없음) | **차트/시각화 도입 PR(PR22) 몫** |`
- `DESIGN.md:106` — "여전히 §C 미소비로 남은 것은 … **범주색(이슈타입·차트, PR22 `--chart-*` 소관)**"

→ **DESIGN.md 갱신이 본 PR의 필수 산출물**이다(스펙 §12 완료 기준에 누락돼 있었음).
그리고 106행은 **범주색을 이미 PR22 소관으로 문서화**하고 있다 → §9 스코프 질문에서 **B가 문서 정합적**이라는 방증.

### ℹ️ G7 (확인). 동결 배열 분리는 근거가 있다

`state-tokens.test.ts:221` 동결 describe가 `--chart-*` 5 + `--syntax-*` 5를 **한 배열**로 묶고 있다.
`DESIGN.md:130`이 `--syntax-*`를 **"동결 계약 — AQL textarea와 overlay `<pre>`가 같은 값을 참조해야
정렬이 깨지지 않으므로 임의 변경 금지"**로 명시하므로, EC8대로 **`--chart-*`만 해제**하는 것이 정당하다.

### ℹ️ G8 (스코프 방증). 문서 3곳이 범주색을 PR22 소관으로 지목

ADR §D7(차트) · `DESIGN.md:106`(범주색+차트) · PR4 이연 기록. → §9 **스코프 B**가 문서 정합적.

### 판정

- **수정 가능 gap (G1·G2·G9·G7)** — 본 절에 정정 기록 완료. 스펙 §5 EC5는 폐기, EC6·EC10은 리스크 하향.
- **스펙 누락 (G5·G6)** — §2 FR에 **PR22-F8(D단계 마킹 + 전수 동기화)**, **PR22-F9(DESIGN.md 갱신)** 추가로 흡수. §12 완료 기준 11·12번 추가.
- **Maxi 결정 필요 (G3·G4)** — 게이트 1 상정. §9 스코프와 함께 묻는다.

## 15. 추가 FR (Brainstorming 흡수분)

| ID | 요구사항 |
|---|---|
| PR22-F8 | `docs/plan/product/personalization.md` FR-UX-06 **D1·D3~D7 체크박스 마킹** + "20 PR 체인"→"22 PR 체인" 정정 + **BC 완료 게이트 해제 문구 원복**. CLAUDE.md 전수 동기화 체크리스트 3·4·6·7·8 동반 확인 |
| PR22-F9 | `DESIGN.md` §C 표(`--chart-*` 행)·106행 서술 갱신 + (스코프 B) `--type-*`/`--prio-*` 절 신설 |

## 16. 추가 완료 기준

11. `docs/plan/product/personalization.md`의 FR-UX-06 D1~D7이 전부 `[x]`이고 "22 PR 체인"으로 정정됐다.
12. `DESIGN.md`에 "PR22 몫"·"PR22 소관" 미결 표기가 **0건**이다.
13. `bash scripts/verify-master-plan.sh` EXIT 0 (129/129) — D단계 마킹 후 재확인.
