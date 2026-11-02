<!-- BTS 디자인 시스템 — ADS v2 팔레트 + 프리미티브 정본 (FR-UX-06) -->

# BTS 디자인 시스템 v2.0 (ADS v2)

> **상위 정책 (2026-10-30 Maxi 결정).** 색 · 글꼴 · 모양 · 움직임 · 창 · 휴대폰 · 문구는
> [QA Hub 제품군 공통 UI/UX 정책](docs/design/2026-10-30-qa-hub-shared-ui-policy.md)이 이 문서보다 우선한다.
> 정보 구조 · 화면 배치는 Jira 패리티([`jira-parity-contract.md`](docs/design/jira-parity-contract.md))를 따른다.
> 아래 값은 **지금 코드의 값**이다. §2 색 · §7 라운드 · §9 다크 모드는 Q1 에서 이행했고(2026-11-02), 남은 절(§5 타이포)에는 「이행 예정」 표지를 달았다 —
> 이행 일은 [`jira-parity-roadmap.md`](docs/design/jira-parity-roadmap.md) 「3차 캠페인」 Q1~Q6.

BTS(Project Atlas)는 사내 1,000명 규모 협업 워크스페이스다. 이 문서는 **shadcn/ui radix-nova 프리셋** + **Tailwind v4 CSS-first `@theme`** 위에 구축된 디자인 토큰·프리미티브의 단일 출처(Single Source of Truth)다. 컬러 팔레트는 QA Hub 제품군 공통 정책 값이다(Q1, 2026-11-02 — 그 전에는 Atlassian Design System v2, 이하 **ADS v2** 이식값이었다).

정본 CSS 구현. `apps/web/src/index.css`. 이 문서의 모든 표는 그 파일의 `:root` / `.dark` 블록과 1:1 대응해야 한다(`state-tokens.test.ts` 가 전수 대조).

---

## 1. 디자인 원칙

| # | 원칙 | 설명 |
|---|---|---|
| 1 | **명료성 (Clarity)** | 회사 업무 도구다. 사용자가 정보를 읽고 행동하는 데 걸리는 시간을 최소화한다. 장식보다 인지 속도가 우선이다. |
| 2 | **일관성 (Consistency)** | shadcn/ui 표준 패턴 + ADS v2 팔레트를 그대로 활용한다. 컴포넌트를 직접 발명하거나 임의 색상을 추가하기 전에 이 문서에 등록된 토큰으로 해결 가능한지 먼저 확인한다. |
| 3 | **접근성 (Accessibility)** | WCAG AA를 준수한다. 키보드 탐색, `aria-*` 속성, 색 대비 4.5:1(본문) / 3:1(UI 컴포넌트·큰 텍스트)을 모든 컴포넌트에서 만족해야 한다. |
| 4 | **시각적 위계 (Visual Hierarchy)** | 타이포그래피 크기·굵기와 색의 강약으로 위계를 표현한다. 별도 색상을 추가하기 전에 `foreground` / `muted-foreground` / `primary` 조합으로 해결 가능한지 먼저 검토한다. |
| 5 | **절제 (Restraint)** | 장식을 최소화한다. 사용자의 콘텐츠가 주인공이다. 그림자, 애니메이션, 색은 의미가 있을 때만 쓴다. |

**ADS v2 참조.** 팔레트 스텝 이름(Neutral100, Blue700 등)과 시맨틱 토큰 이름(`color.text.subtle`, `color.background.brand.bold` 등)은 Atlassian Design System v2 명명을 그대로 따른다. 새 토큰이 필요하면 먼저 ADS v2에 대응 토큰이 있는지 확인하고, 있으면 그 값을 가져와 이 문서에 등록한다.

---

## 2. 컬러 토큰

> **✅ 이행 완료 — 공통 정책 §1 · §11.2~11.3 (로드맵 Q1, 2026-11-02).** 아래 표가 지금 코드의 값이다.
> 대표색은 오렌지 `#FF7A1A`, 바탕 · 글자 단계는 공통 두 벌 토큰, `--success` · `--danger` 는 판정 색, 경고는 노랑 쪽이다.

### 출처

`apps/web/src/index.css`의 `:root`(라이트) / `.dark`(다크) 블록. 값은 모두 **hex**(sRGB)다.
값 정본은 [QA Hub 공통 정책](docs/design/2026-10-30-qa-hub-shared-ui-policy.md) §1 이다. ADS v2(`@atlaskit/tokens@1.4.2`) 이식값은 Q1 에서 걷었다 — 옛 도출 기록은 `docs/plans/2026-07-19-fr-ux-06-pr3-ads-palette-values.md`.

**Q1 에서 정한 것 (정책 §11.2 · 11.3 · 11.5 「Q1 착수 때 정한다」).**
- **토큰 이름은 shadcn 그대로 · 값만 옮긴다.** 소비처(약 140 파일)가 전부 토큰을 거치므로 이름을 새로 들이면 diff 만 커진다. 공통 이름(`bg` · `panel` · `well` …)과의 대응은 아래 표 「공통 토큰」 칸.
- **흐린 글 `faint`(`#848A96`)는 흰 판 위 3.47:1 로 AA 미달**이다. 그래서 `--text-subtlest` 는 `muted` 값을 쓰고, `faint` 는 AA 예외인 `--text-disabled` 에만 쓴다.
- **경고는 라이트 Yellow600 `#B38600`**(흰 판 3.32:1 · 잉크 글자 5.40:1). 원문에 값이 없고, Yellow400 은 흰 판 1.98:1 로 테두리 표식이 안 보인다. 다크는 원래 Yellow400 이라 그대로.
- **포커스 링 `--ring` = 대표색 글자 값**(`#B84D05` 5.12:1 / `#FF9A4D` 8.37:1). 채움 `#FF7A1A` 은 흰 판 2.61:1 로 비글자 3:1 에 못 미친다.
- **`--info` = 대표색.** 소비처가 진행 막대 · 활성 스프린트 · 진행 중 채움이라 정책 §1 「진행 중인 것」 자리다.
- **처음 방문자 기본 테마는 이미 `system`** — `index.html` 프리하이드레이션이 저장값이 없으면 `prefers-color-scheme` 를 따른다.

### §A. shadcn 베이스 토큰 18종 (`--color-*` Tailwind 유틸로 완전 배선됨)

`@theme inline` 블록이 이 18종 전부를 `--color-*`로 연결한다. `bg-primary`, `text-muted-foreground`, `border-input` 같은 표준 Tailwind 유틸리티로 바로 쓸 수 있다.

| CSS 변수 | 라이트 | 다크 | 공통 토큰 (정책 §1) | 한국어 역할 |
|---|---|---|---|---|
| `--background` | `#F5F6F8` | `#101114` | bg | 페이지 배경 |
| `--foreground` | `#15171C` | `#ECEDEF` | ink | 기본 텍스트 |
| `--card` | `#FFFFFF` | `#17191D` | panel | 카드(판) 배경 |
| `--card-foreground` | `#15171C` | `#ECEDEF` | ink | 카드 내부 텍스트 |
| `--popover` | `#FFFFFF` | `#17191D` | panel | 팝오버 · 드롭다운 · 창 배경 |
| `--popover-foreground` | `#15171C` | `#ECEDEF` | ink | 팝오버 텍스트 |
| `--primary` | `#FF7A1A` | `#FF7A1A` | 대표색 채움 | 주 버튼 · 로고 · 진행 막대. 한 화면에 하나 |
| `--primary-foreground` | `#1A0D02` | `#1A0D02` | 채움 위 글자 | 주 버튼 글자(7.30:1) |
| `--secondary` | `#F2F3F6` | `#1D2025` | well | 보조 액션 배경 |
| `--secondary-foreground` | `#15171C` | `#ECEDEF` | ink | 보조 액션 텍스트 |
| `--muted` | `#F2F3F6` | `#1D2025` | well | 오목한 칸 · 비활성 영역 배경 |
| `--muted-foreground` | `#575E6B` | `#A6ABB5` | muted | 보조 텍스트 |
| `--accent` | `#F2F3F6` | `#1D2025` | well | 마우스 올림 · 포커스 배경 |
| `--accent-foreground` | `#15171C` | `#ECEDEF` | ink | 강조 영역 텍스트 |
| `--destructive` | `#D4294F` | `#EE3D63` | 실패 칠 | 입력 오류 표시 · 위험 액션 |
| `--border` | `#E3E5EA` | `#272A31` | line | 괘선 |
| `--input` | `#CFD3DA` | `#363A43` | line2 | 입력 칸 테두리 |
| `--ring` | `#B84D05` | `#FF9A4D` | 대표색 글자 | 포커스 링 |

> **`--accent` = `--secondary` = `--muted` = well.** 셋이 같은 값이다. DropdownMenu/Select 는 `focus:bg-accent`, Tabs/Dialog 는 `hover:bg-(--bg-neutral-hover)`(line, 한 단계 진함)를 쓴다.
> 신규 컴포넌트는 "shadcn 표준 hover/focus 패턴이면 `--accent`, §7 상태 토큰 문맥이면 `--bg-*`"로 고른다.
> 공통 정책의 `rail`(가장 바깥 줄 `#EBEDF1` / `#0A0B0D`)은 소비할 토큰이 없어 만들지 않았다 — 위쪽 바 · 왼쪽 줄을 그 색으로 칠할 때 정의한다(ADR D7).

### §7. 상태 토큰 12종 (Tailwind 유틸 미배선 — 임의값 문법으로 소비)

이 12종은 `@theme inline`에 `--color-*`로 별칭되지 **않는다**. `bg-bg-neutral` 같은 유틸리티 클래스는 존재하지 않는다. 대신 Tailwind v4의 CSS 변수 임의값 문법 `(--var-name)`으로 직접 참조한다 — 예. `bg-(--bg-neutral)`, `text-(--text-subtle)`, `outline-(--border-focus)`. `apps/web/src/components/ui/` 전 프리미티브가 이 패턴을 쓴다(§4 표 참조).

| CSS 변수 | 라이트 | 다크 | 한국어 역할 | Tailwind 사용 예 |
|---|---|---|---|---|
| `--bg-neutral` | `#F2F3F6` | `#1D2025` | 뉴트럴 배경(테이블 hover/footer, 스켈레톤) = well | `bg-(--bg-neutral)` |
| `--bg-neutral-hover` | `#E3E5EA` | `#272A31` | 뉴트럴 hover 배경 = line | `hover:bg-(--bg-neutral-hover)` |
| `--bg-neutral-press` | `#CFD3DA` | `#363A43` | 뉴트럴 press(active) 배경 = line2 | `active:bg-(--bg-neutral-press)` |
| `--bg-neutral-solid` | `#F2F3F6` | `#1D2025` | **가림(occlusion)용 불투명 뉴트럴** — sticky 헤더 등. well 이 이미 불투명이라 `--bg-neutral` 과 같은 값 | `sticky` 요소의 `bg-(--bg-neutral-solid)` |
| `--bg-selected` | `#FFF1E6` | `#FF7A1A21` | 선택 상태 배경 = 대표색 옅은 바탕(다크 `rgba(255,122,26,.13)`) | `data-[state=selected]:bg-(--bg-selected)` |
| `--text-selected` | `#B84D05` | `#FF9A4D` | 선택 상태 텍스트 = 대표색 글자 | `data-[state=active]:text-(--text-selected)` |
| `--text-subtle` | `#575E6B` | `#A6ABB5` | 보조 텍스트 = muted | `text-(--text-subtle)` |
| `--text-subtlest` | `#575E6B` | `#A6ABB5` | 최약 텍스트 = muted (faint 는 AA 미달 — §2 출처) | `text-(--text-subtlest)` |
| `--text-disabled` | `#848A96` | `#737985` | 비활성 텍스트 = faint | `disabled:text-(--text-disabled)` |
| `--border-focus` | `#B84D05` | `#FF9A4D` | 포커스 아웃라인(`--ring`과 동일 값) | `focus-visible:outline-(--border-focus)` |
| `--brand-hover` | `#FF7A1A` | `#FF7A1A` | 체크박스 · 스위치 켜짐 채움 = 대표색 채움(글리프는 `--primary-foreground`) | `data-[state=checked]:bg-(--brand-hover)` |
| `--brand-text` | `#B84D05` | `#FF9A4D` | 대표색 글자(멘션 · 링크) | `.mention { color: var(--brand-text) }` |

> **`--border-focus` = `--ring`.** 포커스 신호를 하나로 통일한다.

### §C. 시맨틱 상태색 4종 + foreground 4종 + text 4종 (Tailwind 유틸로 완전 배선됨)

`--color-*`로 배선되어 `bg-warning`, `text-danger` 등 표준 유틸로 쓸 수 있다. 판정 색은 판정에만 쓴다(정책 §1) — 예외는 실패 색의 입력 오류 표시 하나.

| CSS 변수 | 라이트 | 다크 | 정책 §1 대응 |
|---|---|---|---|
| `--warning` | `#B38600` | `#E2B203` | 경고 — 노랑 쪽(원문에 값 없음, §2 출처) |
| `--success` | `#16A34A` | `#3FB871` | 통과 칠 |
| `--danger` | `#D4294F` | `#EE3D63` | 실패 칠(=`--destructive`와 동일 값) |
| `--info` | `#FF7A1A` | `#FF7A1A` | 진행 중 = 대표색 채움 |
| `--warning-foreground` | `#15171C` | `#161A1D` | 경고 칠 위 글자(잉크 5.40:1) |
| `--success-foreground` | `#15171C` | `#161A1D` | 통과 칠 위 글자(흰 글자 3.30:1 미달 → 잉크 5.44:1) |
| `--danger-foreground` | `#FFFFFF` | `#161A1D` | 실패 칠 위 글자(4.97:1 / 4.57:1) |
| `--info-foreground` | `#1A0D02` | `#1A0D02` | 대표색 채움 위 글자 |
| `--warning-text` | `#7F5F01` | `#F5CD47` | 옅은 바탕 위 경고 글자(원문에 값 없음 — 기존 값 유지) |
| `--success-text` | `#166534` | `#6FD49A` | 통과 글자. ★라이트 편차 — 원문 `#15803D` 는 well 위 `/10` 에서 4.07:1 미달이라 한 단계 진한 green-800 |
| `--danger-text` | `#B81E42` | `#FF8AA3` | 실패 글자 |
| `--info-text` | `#9A3412` | `#FF9A4D` | 대표색 글자. ★라이트 편차 — 원문 `#B84D05` 는 well 위 `/10` 에서 4.22:1 미달이라 orange-800. 맨 글자(`--brand-text`)는 원문 값 그대로 |

**텍스트 페어링 (★ 칠 vs 옅은 바탕 구분).**
- **칠 위** (`bg-warning` 등): `text-{status}-foreground`.
- **옅은 바탕 위 / 색 텍스트** (`bg-warning/10` 배너·상태 배지·색 텍스트): `text-{status}-text` (칠 토큰 아님 — 칠 값은 글자로 쓰면 AA 미달). **배지가 같은 색 옅은 배너 위에 중첩되면** 둘 다 `/10`으로 붕괴하므로 내부 배지는 칠(`bg-warning text-warning-foreground`)로 대비를 살린다.

**현재 소비처.** PR4(FR-UX-06)가 하드코딩 Tailwind 색 리터럴을 §C 토큰으로 전면 이관하면서, §C는 이제 프리미티브와 화면 전반에서 소비된다. 프리미티브 `components/ui/badge.tsx`의 색 variant(blue→info·green→success·red→danger·yellow→warning)가 tint 패턴 `bg-{status}/10 text-{status}-text`로 소비하고, 상태 배너·배지 약 29개 파일(`components/automation/RuleConflictWarningModal.tsx`·`AutomationYamlImportDialog.tsx`, `components/admin/WebhookDeliveryTable.tsx`, `components/backlog/SprintColumn.tsx`, `components/auth/BackupCodesSection.tsx` 등)이 동일 패턴 또는 bold(`bg-{status} text-{status}-foreground`)로 소비한다. `components/backlog/BacklogBoard.tsx`(백로그 절단 경고 배너, `border-warning bg-warning/10`)는 PR3가 먼저 이관한 선례다. **여전히 §C 미소비**로 남은 것은 DEFER/OUT 대상 — `components/workflow/WorkflowDiagram.tsx`(mermaid flowchart가 `var()` 미지원이라 하드코딩 유지) **1건뿐이다**. 범주색(이슈타입·차트)은 **PR22가 §C-2로 정의·소비 완료**했다.

### §C-2 범주색 — 차트 5종 + 이슈타입/상태 8종 (PR22 신설)

**정의 시점 원칙.** ADR §D7 — 소비자 0인 토큰은 만들지 않는다. 그래서 PR3가 `--chart-*`를 회색조로 남겨 두고 실소비 PR(PR22)에 넘겼다. PR22는 **실제 소비처가 있는 것만** 정의했다.

**차트 5종** (recharts 6종 컴포넌트가 소비 — 번다운·CFD·Velocity·처리량·리드타임·산포도).

| 토큰 | 라이트 | 다크 | 역할 |
|---|---|---|---|
| `--chart-1` | `#0C66E4` | `#579DFF` | 주 시리즈(Blue) |
| `--chart-2` | `#6E5DC6` | `#9F8FEF` | 보조 시리즈(Purple) |
| `--chart-3` | `#758195` | `#8C9BAB` | 중립·기준선(Neutral) |
| `--chart-4` | `#B65C02` | `#FAA53D` | 경고·지연(Orange) |
| `--chart-5` | `#1F845A` | `#4BCE97` | **완료(Green)** |

★ 차트 · 범주색은 공통 정책 원문에 값이 없어 Q1 에서 그대로 뒀다. 정책 §1 「그래프 수치에 대표색을 쓰지 않는다」는 지켜진다 — 차트 토큰 어디에도 `--primary`(`#FF7A1A`) 값이 없다. `--chart-1` 파랑은 Q1 뒤로 대표색과 무관한 범주색이다.
★ `--chart-5` = 완료(초록)는 **가드가 적발해 정정한 매핑**이다. 초안의 "1=주 시리즈" 역할 배정이 완료 계열까지 흡수해 `--chart-5` 소비처가 0이 됐고, "6종이 `--chart-1~5`를 모두 소비한다" 어서션이 이를 잡았다. 부수 효과로 CFD·Velocity의 "완료"가 같은 색으로 통일됐다.

**이슈타입·상태 8종** (타임라인 막대·캘린더 칩·즐겨찾기 별이 소비).

| 토큰 | 라이트 | 다크 | 소비처 |
|---|---|---|---|
| `--type-epic` | `#6E5DC6` | (동일) | `TimelineRow` 에픽 막대 |
| `--type-story` | `#22A06B` | (동일) | 스토리 막대 |
| `--type-task` | `#0C66E4` | (동일) | 태스크 막대 |
| `--type-bug` | `#C9372C` | (동일) | 버그 막대 |
| `--type-default` | `#758195` | (동일) | 매핑 없는 타입 폴백(Neutral600) |
| `--discovery` / `-foreground` | `#6E5DC6` / `#FFFFFF` | (동일) | 캘린더 Worklog 칩 |
| `--neutral-bold` / `-foreground` | `#44546F` / `#FFFFFF` | `#9FADBC` / `#FFFFFF` | 캘린더 TODO 칩 |
| `--favorite` | `#B38600` | (동일) | 즐겨찾기 별 |

★ `--neutral-bold`는 plan-design-review가 잡은 **BLOCKER급 역행**을 막은 결과다. 초안이 TODO 칩을 `--bg-neutral-solid`(거의 흰색)로 매핑해 `WeekGrid.tsx`의 기존 결정("옅은 배지는 대비 미달로 배제, 중간톤 solid fill 채택")을 정반대로 되돌릴 뻔했다. 라이트/다크 모두 7.65:1.
★ `--favorite`는 ADS Yellow400이 흰 배경 1.98:1로 미달이라 Yellow600(`#B38600`)을 채택했다.
★ **만들지 않은 토큰** — `--prio-*` 5종과 `--type-subtask`. 실측 소비처가 0이었다(우선순위는 `routes/search.tsx`·`IssueMetaPanel.tsx`에서 `text-muted-foreground` 글자로만 표시). 만들면 PR22가 ADR §D7을 스스로 위반한다. `state-tokens.test.ts`의 `UNCONSUMED_TOKENS` 음성 테스트로 **미정의를 고정**했다.

**접근성 부수 성과.** 교체 전 하드코딩 차트 색 6종 중 3종이 이미 WCAG 1.4.11(비텍스트 대비 3:1) 미달이었다 — `#f59e0b` 2.15:1 · `#94a3b8` 2.56:1 · `#cbd5e1` 1.48:1. 이번 토큰화가 결함 수정을 겸한다.

**값 정본.** `docs/plans/2026-07-25-fr-ux-06-pr22-token-values.md` (PR3 선례 — "값 지어내기 방지" 가드). ADR. `docs/decisions/2026-07-25-fr-ux-06-pr22-chart-categorical-tokens.md`.

### 🔒 동결 토큰 — 이 문서에서 다루지 않음

아래 토큰은 `index.css`에 이미 정의돼 있지만 **다른 PR의 소관**이다. 값을 임의로 바꾸지 말 것.

| 토큰 | 개수 | 현재 상태 | 소관 |
|---|---|---|---|
| ~~`--chart-1` ~ `--chart-5`~~ | 5 | **동결 해제 완료 (PR22)** — ADS v2 hex 5색으로 교체·실소비 중. 값은 아래 §C-2 참조 | — |
| `--syntax-keyword/field/operator/string/number` (+`.dark`) | 5 | OKLCH, WCAG AA 검증 완료(§3 참조) | AQL overlay 정렬 정본 — 값 변경 금지, §3에서 별도 관리 |
| `--font-mono` | 1 | D2Coding 기반 한글 mono 스택 | AQL overlay 정렬 정본 — §5에서 별도 관리, 값 변경 금지 |
| ~~`--sidebar*`~~ 8종 | 8 | **동결 해제 완료 (Q1)** — 배경 side(`#F5F6F8` / `#0D0E11`) · 글자 ink · 활성 = 대표색 옅은 바탕 + 대표색 글자 · hover well · 테두리 line · 링 = `--ring` | — |

### 사용 가이드

- **primary** — 페이지당 한 개의 핵심 CTA에만 쓴다. 대표색 자리는 다섯 곳뿐 — 누를 것 · 지금 고른 것 · 진행 중인 것 · 새 알림 · 로고(정책 §1). 줄마다 되풀이되는 버튼은 테두리 버튼.
- **destructive / danger** — 에러 메시지 · 입력 오류. `--destructive`(§A)와 `--danger`(§C)는 라이트/다크 값이 동일하다 — 폼 검증 에러는 `--destructive`(shadcn 관례 유지), 상태 배지·알림 배너는 `--danger`(§C)를 쓴다. 정책 §1 「되돌릴 수 없는 일 단추는 잉크 채움」은 버튼 variant 를 바꾸는 일이라 Q4(창 · 확인 단추)에서 한다.
- **muted / muted-foreground / text-subtle / text-subtlest** — 부가 정보, placeholder, 힌트. 본문 가독성이 요구되는 곳에는 쓰지 않는다.
- **임의 색상 추가 금지** — 위 37개 토큰(§A 18 + §7 11 + §C 8) 외 색이 필요하면 이 문서에 신규 토큰을 먼저 등록한 뒤 사용한다.

---

## 3. Syntax Highlight 토큰 (FR-SR-02 AQL 입력창)

AQL(BTS의 이슈 검색 쿼리 언어) 쿼리 입력창의 syntax highlight용 색상 5종. `--chart-1~5`는 chroma=0 회색조라 색 구분 불가하므로 별도 토큰으로 등록했다. **기능적 색 구분**이 목적이므로 BTS 팔레트 예외적으로 유채색 도입이 정당화된다. 이 절의 값은 **동결 계약**이다 — AQL `textarea`와 overlay `<pre>`가 같은 값을 참조해야 정렬이 깨지지 않으므로 임의 변경 금지.

`index.css` 등록 위치. `:root` 블록 및 `.dark` 블록 — `@theme inline`에 `--color-syntax-*`로 완전 배선됨(Tailwind 유틸 `text-syntax-*` 사용 가능).

### 라이트 모드

| CSS 변수 | OKLCH 값 | 색상 | 역할 | Tailwind 유틸 |
|---|---|---|---|---|
| `--syntax-keyword` | `oklch(0.38 0.15 250)` | 파란색 | AND / OR / NOT / IN / ORDER BY / ASC / DESC | `text-syntax-keyword` |
| `--syntax-field` | `oklch(0.36 0.14 290)` | 보라색 | status / label / summary / priority | `text-syntax-field` |
| `--syntax-operator` | `oklch(0.44 0.13 55)` | 황갈색 | = / != / ~ | `text-syntax-operator` |
| `--syntax-string` | `oklch(0.40 0.14 145)` | 녹색 | `"따옴표 문자열"` | `text-syntax-string` |
| `--syntax-number` | `oklch(0.44 0.17 25)` | 적갈색 | 정수 리터럴 | `text-syntax-number` |

### 다크 모드

| CSS 변수 (`.dark`) | OKLCH 값 | 색상 |
|---|---|---|
| `--syntax-keyword` | `oklch(0.72 0.15 250)` | 하늘색 |
| `--syntax-field` | `oklch(0.75 0.13 290)` | 연보라 |
| `--syntax-operator` | `oklch(0.76 0.13 65)` | 연황색 |
| `--syntax-string` | `oklch(0.73 0.14 145)` | 연녹색 |
| `--syntax-number` | `oklch(0.75 0.16 25)` | 연적색 |

### 사용 가이드

- AQL `AqlHighlighter` 컴포넌트에서만 사용한다. 일반 본문 텍스트에 사용 금지.
- 토큰 타입 → Tailwind 클래스 매핑. `KEYWORD` → `text-syntax-keyword`, `FIELD` → `text-syntax-field`, `OPERATOR` → `text-syntax-operator`, `STRING` → `text-syntax-string`, `NUMBER` → `text-syntax-number`.
- `PAREN` / `COMMA` / `PLAIN` 토큰은 `text-foreground`(기본 텍스트 색) 그대로.
- 위치 오류(SEARCH_SYNTAX_ERROR) underline은 `text-destructive` 토큰 재사용.
- 대비 검증 결과는 §10 접근성 참조(전 토큰 WCAG AA 4.5:1 이상, 최소 라이트 5.6:1 / 다크 7.1:1).

---

## 4. 프리미티브

### 위치·정책

`apps/web/src/components/ui/`. shadcn/ui는 소스를 프로젝트에 직접 복사하는 "vendoring" 방식이다 — 이 디렉토리의 파일을 직접 수정할 수 있다. 단, 수정 범위를 최소화하고(스타일 조정은 Tailwind 유틸리티 prop으로 먼저 시도), 수정 이유를 파일 상단 주석에 남긴다. shadcn 래퍼가 없는 컴포넌트(RadioGroup, Switch, Checkbox, Tabs 등)는 `radix-ui` 패키지를 직접 import해 BTS 토큰을 입힌 것이다 — 표의 "shadcn 매핑" 열에 "radix-ui 직접"으로 표기.

### 전수 목록 (25개 — `components/ui/` 24개 + 워크플로우 편집기 캔버스 1개, 테스트 파일 14개 제외)

`.test.tsx`가 붙은 파일(`avatar.test.tsx`, `badge.test.tsx`, `checkbox.test.tsx`, `command.test.tsx`, `dialog.test.tsx`, `empty-state.test.tsx`, `popover.test.tsx`, `radio-group.test.tsx`, `separator.test.tsx`, `sonner.test.tsx`, `switch.test.tsx`, `table.test.tsx`, `tabs.test.tsx`, `tooltip.test.tsx`)는 프리미티브 자체가 아니므로 제외했다.

| 컴포넌트 | 파일 | shadcn 매핑 | 소비 §A 토큰 | 소비 §7 토큰 | 비고 |
|---|---|---|---|---|---|
| Avatar | `avatar.tsx` | 없음(BTS 자체 설계 — blob 캐시·fallback 이니셜) | `muted`, `muted-foreground` | — | 인증 필요 이미지 blob fetch + 캐시버스트 |
| Badge | `badge.tsx` | shadcn Badge(cva 5-6 variant) | `foreground` | `bg-neutral`(default/neutral variant) | `blue`/`green`/`red`/`yellow` variant는 Tailwind 원색 팔레트(`bg-blue-100` 등) 직접 사용 — §A/§7/§C 미경유(기존 관례, 이 문서 범위 밖 부채) |
| Button | `button.tsx` | shadcn Button(cva 6 variant × 8 size) | `primary`/`primary-foreground`, `border`, `background`, `muted`, `secondary`/`secondary-foreground`, `destructive`, `ring` | — | |
| Card | `card.tsx` | shadcn Card | `card`/`card-foreground`, `muted-foreground`, `muted` | — | 그림자 대신 `ring-1 ring-foreground/10` 사용(§8 참조) |
| Checkbox | `checkbox.tsx` | radix-ui 직접 | `input`, `primary-foreground` | `border-focus`, `text-disabled`, `brand-hover` | 체크 아이콘 사각형 라운드는 `rounded-[4px]` 하드코딩(§7 라운드 스케일 `--radius-md` 4px과 값은 동일하나 토큰 미참조) |
| Command | `command.tsx` | shadcn Command(cmdk 기반) | `popover`/`popover-foreground`, `border`, `muted-foreground`, `foreground` | `bg-selected`, `text-selected`, `text-subtle` | |
| Dialog | `dialog.tsx` | shadcn Dialog(radix-ui) | `popover`/`popover-foreground`, `muted-foreground` | `bg-neutral-hover`(닫기 버튼 hover), `border-focus` | 오버레이는 `bg-black/50`(토큰 미경유, raw 값) |
| DropdownMenu | `dropdown-menu.tsx` | shadcn DropdownMenu(radix-ui) | `popover`/`popover-foreground`, `accent`/`accent-foreground`, `muted-foreground`, `border`, `destructive` | — | hover/focus는 `--accent`(§A) 경유 — `--bg-neutral-hover`(§7)와 값은 같음 |
| EmptyState | `empty-state.tsx` | 없음(BTS 자체 프리미티브) | — | `text-subtle` | |
| Form | `form.tsx` | shadcn Form(React Hook Form 래퍼) | `destructive`, `muted-foreground` | — | |
| Input | `input.tsx` | shadcn Input | `input`, `foreground`, `muted-foreground`, `ring`, `destructive` | — | 포커스는 `border-ring`+`ring-ring/50`(Textarea는 `outline-(--border-focus)` — 접근 방식은 다르나 색은 동일, §7 참조) |
| Label | `label.tsx` | shadcn Label(radix-ui) | — | — | 색 토큰 미사용(크기·굵기만, 비활성은 `opacity-50`) |
| Popover | `popover.tsx` | shadcn Popover(radix-ui) | `popover`/`popover-foreground` | `border-focus` | |
| RadioGroup | `radio-group.tsx` | radix-ui 직접 | `input` | `text-selected`, `border-focus`, `text-disabled` | |
| ScrollArea | `scroll-area.tsx` | shadcn ScrollArea(radix-ui) | `border`(스크롤바 thumb) | `border-focus` | |
| Select | `select.tsx` | shadcn Select(radix-ui) | `input`, `popover`/`popover-foreground`, `ring`, `muted-foreground`, `accent`/`accent-foreground`, `border` | — | |
| Separator | `separator.tsx` | shadcn Separator(radix-ui) | `border` | — | |
| Skeleton | `skeleton.tsx` | shadcn Skeleton | — | `bg-neutral` | |
| Toaster(sonner) | `sonner.tsx` | shadcn Sonner 래퍼 | — | — | `theme="light"` 하드코딩 — sonner 자체 `richColors` 팔레트를 쓰며 BTS 토큰을 경유하지 않음. 다크 모드 갱신은 후속 검토 대상(이 PR 범위 밖) |
| Switch | `switch.tsx` | radix-ui 직접 | `input`, `background`(thumb) | `border-focus`, `brand-hover`(checked) | |
| Table | `table.tsx` | shadcn Table | — | `bg-neutral`(hover/footer), `bg-selected`(선택 행), `text-subtle`(head/caption) | |
| Tabs | `tabs.tsx` | radix-ui 직접(같은 라우트 내 패널 전환 전용 — 라우트 이동은 nav+Link 사용) | `muted`/`muted-foreground`(리스트 기본) | `bg-neutral-hover`(hover), `bg-selected`/`text-selected`(active), `border-focus` | |
| Textarea | `textarea.tsx` | shadcn Textarea | `input`, `muted-foreground`, `destructive` | `border-focus`, `text-disabled` | |
| Tooltip | `tooltip.tsx` | shadcn Tooltip(radix-ui) | `popover`/`popover-foreground` | — | |
| WorkflowEditorCanvas | `components/workflow/editor/WorkflowEditorCanvas.tsx` | 없음(@xyflow/react 래퍼) | `muted`, `border`, `primary` | — | ★ `components/ui/` 밖에 있는 유일한 행이다 — 워크플로우 편집기 전용이라 vendoring 대상이 아니다. 노드 카테고리 색은 `WorkflowDiagram.tsx` 의 `classDef category_*` 3행(`:161~163`)을 **그대로** 쓴다 — TODO `--muted`/`--border` · IN_PROGRESS `--primary` 15%/`--primary` · DONE `--success` 15%/`--success`(`--success` 는 §C). 같은 워크플로우를 읽기 전용 `/workflows/{key}` 와 편집기 `/admin/workflows/{key}` 에서 번갈아 볼 때 같은 색이어야 한다. `@xyflow/react/dist/style.css` 가 자기 팔레트를 갖고 오므로 `--xy-*` **10개**를 BTS 토큰으로 덮어쓴다 — `--xy-background-color`→`--background` · `--xy-edge-stroke`→`--border` · `--xy-edge-stroke-selected`→`--primary` · `--xy-handle-background-color`→`--primary` · `--xy-handle-border-color`→`--background` · `--xy-controls-button-background-color`→`--card` · `--xy-controls-button-background-color-hover`→`--muted` · `--xy-controls-button-color`→`--foreground` · `--xy-controls-button-border-color`→`--border` · `--xy-attribution-background-color`→`transparent`. 값을 하드코딩하지 않고 전부 토큰을 경유해 라이트/다크가 자동으로 따라온다 |

---

## 5. 타이포그래피

> **⏳ 이행 예정 — 공통 정책 §2 · §11.4 (로드맵 Q2).** Geist → IBM Plex Sans KR(400 · 600) · IBM Plex Mono · IBM Plex Sans Condensed 600,
> 숫자는 `tabular-nums`, 글꼴 파일은 제품 안에. 🔒 `--font-mono` 는 AQL overlay 정렬을 잰 뒤에 푼다.

### 폰트 스택

| 역할 | 폰트 | 적용 방법 |
|---|---|---|
| 기본(영문/숫자) | `Geist Variable`(`@fontsource-variable/geist`) | `index.css` `@import` + `--font-sans: 'Geist Variable', sans-serif` |
| 한국어 fallback | `Apple SD Gothic Neo`(macOS/iOS), `Malgun Gothic`(Windows), `Pretendard`(웹 권장 — 별도 설치 시), `system-ui` | `sans-serif` 제네릭이 OS 시스템 폰트로 연결됨 |
| 제목(heading) | `--font-heading` → `--font-sans`와 동일 | 현재 분리 없음. 추후 별도 폰트 도입 시 이 변수 재정의 |
| 고정폭(mono) | `--font-mono` 토큰(🔒 동결, 아래 상세) | `index.css` `@theme inline` + `font-mono` Tailwind 유틸 |

> 한국어 fallback 선택 이유. Geist는 라틴 문자 전용이라 한국어 글리프가 없다. OS 기본 시스템 폰트(macOS: Apple SD Gothic Neo, Windows: Malgun Gothic)가 가장 빠르게 로드된다. Pretendard는 가독성이 뛰어나지만 별도 웹폰트 설치가 필요하므로 도입 여부는 별도 검토 대상이다.

### `--font-mono` 토큰 (🔒 동결 — FR-SR-02 AQL overlay 정렬 정본)

AQL syntax highlight에서 `textarea`와 overlay `<pre>`의 **폰트가 다르면 글자 폭이 어긋나 하이라이트가 밀린다.** 두 요소가 동일한 `--font-mono`를 참조해 정렬 문제를 원천 차단한다. 값 변경 금지.

```
--font-mono: 'D2Coding', 'Sarasa Mono K', 'Noto Sans Mono CJK KR',
             ui-monospace, 'Cascadia Code', 'Fira Code', 'Consolas',
             'Courier New', monospace;
```

| 순위 | 폰트 | 이유 |
|---|---|---|
| 1 | `D2Coding` | 한국 개발자 표준 한글 mono 폰트. ASCII + 완성형 한글 글리프 포함. |
| 2 | `Sarasa Mono K` | CJK(한/중/일) 지원 고품질 mono. 미설치 시 fallback. |
| 3 | `Noto Sans Mono CJK KR` | Google Fonts 계열 — CDN 가능. |
| 4 | `ui-monospace` | macOS San Francisco Mono(Retina 최적화). |
| 5 | `Cascadia Code` / `Fira Code` | Windows Terminal 기본 / 개발자 친화. |
| 6 | `Consolas` / `Courier New` | 최후 fallback(모든 OS 포함). |
| 7 | `monospace` | 브라우저 제네릭 최종 fallback. |

**사용 위치.** AQL `AqlHighlighter`(`textarea` + overlay `<pre>` 모두), 기타 코드 블록 컴포넌트(향후 추가 시 동일 토큰 재사용).

### 타입 스케일

| Tailwind 클래스 | 크기 | 한국어 권장 줄높이 | 주요 용도 |
|---|---|---|---|
| `text-xs` | 12px | `leading-4`(1.0rem) | 태그, 뱃지, 극소형 주석 |
| `text-sm` | 14px | `leading-5`(1.25rem) | label, 입력 필드 텍스트, 에러 메시지, 보조 설명 |
| `text-base` | 16px | `leading-6`(1.5rem) | 본문 기본값 |
| `text-lg` | 18px | `leading-7`(1.75rem) | 카드 제목, 섹션 소제목 |
| `text-xl` | 20px | `leading-7`(1.75rem) | 페이지 제목(소) |
| `text-2xl` | 24px | `leading-8`(2rem) | 페이지 제목(중) |
| `text-3xl` | 30px | `leading-9`(2.25rem) | 페이지 제목(대) |

> 한국어 권장 줄높이. 한글은 영문보다 글자 높이가 크다. 기본 줄높이(`leading-normal`, 1.5)를 유지하되, 작은 크기(`text-xs`, `text-sm`)에서는 `leading-4` / `leading-5`로 명시해 행간이 너무 넓어지지 않게 한다.

### 폰트 굵기

| Tailwind 클래스 | 용도 |
|---|---|
| `font-normal`(400) | 본문, 입력 필드 값 |
| `font-medium`(500) | label, 버튼, 강조 텍스트 |
| `font-semibold`(600) | 카드 제목, 섹션 헤딩 |
| `font-bold`(700) | 페이지 주 제목(드물게) |

### 사용 가이드

- 본문 = `text-sm` 또는 `text-base` + `font-normal`
- label = `text-sm font-medium text-foreground`
- 입력 placeholder = `text-sm text-muted-foreground`
- 에러 메시지 = `text-sm text-destructive`
- 카드 제목 = `text-lg font-semibold`
- 페이지 제목 = `text-2xl font-semibold` 또는 `text-3xl font-bold`
- **본문 최소 크기 = 14px(`text-sm`)**. 이보다 작은 크기는 한국어 가독성이 현저히 저하되므로 본문에 사용 금지.

---

## 6. 간격 (Spacing)

### Tailwind 기본 스케일

Tailwind v4 기본값. `1` unit = `0.25rem` = `4px`.

| 토큰 | rem | px | 용도 |
|---|---|---|---|
| `0` | 0 | 0 | 초기화 |
| `1` | 0.25rem | 4px | 아이콘 내부 미세 간격 |
| `2` | 0.5rem | 8px | 인라인 요소 간 간격 |
| `3` | 0.75rem | 12px | 버튼 상하 패딩, label~input 간격 |
| `4` | 1rem | 16px | 컴포넌트 내부 패딩, 섹션 내 요소 간격 |
| `6` | 1.5rem | 24px | 카드 패딩, 섹션 간 간격 |
| `8` | 2rem | 32px | 페이지 섹션 간 간격 |
| `12` | 3rem | 48px | 페이지 주요 블록 간 간격 |

### 폼 간격 가이드

- 카드/패널 패딩. `p-6`(24px, 폼 컨테이너 기준) 또는 `p-4`(16px, 밀도 높은 패널)
- label → input 간격. `space-y-2`(8px)
- 필드 그룹 간 간격. `space-y-4`(16px)
- 제출 버튼 상단 여백. `mt-6`(24px)

### 터치 타깃

- 모바일 터치 타깃 최소 44px × 44px.
- shadcn `<Button>` 기본 높이는 `h-8`(32px, ADS v2 이식 후 밀도 상향). 모바일 환경 또는 핵심 CTA는 `h-10`(40px) 이상 또는 패딩 보강으로 44px에 근접시킨다.

---

## 7. 라운드 (Border Radius)

> **✅ 이행 완료 — 공통 정책 §3 (로드맵 Q1, 2026-11-02).** 판(Card · Dialog · Popover) 8px, 버튼 · 입력 칸 6px, 칩은 알약(`rounded-full`).
> 행은 카드로 감싸지 않고 괘선으로 나눈다.

### 명시 스케일 (calc 파생 폐기)

`index.css`의 `@theme inline`에 5단계를 **각각 픽셀 리터럴로 직접 명시**한다. `--radius` 베이스 변수 + `calc()` 배율 파생 방식은 폐기했다.

| Tailwind 클래스 | CSS 변수 | 값 |
|---|---|---|
| `rounded-xs` | `--radius-xs` | 2px |
| `rounded-sm` | `--radius-sm` | 3px |
| `rounded-md` | `--radius-md` | 6px |
| `rounded-lg` | `--radius-lg` | 8px |
| `rounded-xl` | `--radius-xl` | 12px |

**calc 파생을 폐기한 이유.** 이전 shadcn 기본값은 `--radius: 0.625rem`(10px) 베이스에 `calc(var(--radius) * 0.6)` 같은 배율로 `rounded-sm`(6px), `rounded-2xl`(18px) 등을 파생시켰다. ADS v2는 라운드 기준 자체가 3px(`rounded-sm`)로 훨씬 작다 — 옛 배율 공식(예. base×0.6)을 새 기준에 그대로 적용하면 `3px × 0.6 = 1.8px` 같은 반픽셀 쓰레기 값이 나와 렌더링이 흐릿해지거나 브라우저마다 반올림이 갈린다. ADS v2가 실제 쓰는 값(2/3/4/8/12px)은 배율 관계가 아니라 각 용도별로 독립 결정된 정수 스텝이므로, calc 파생 대신 5개 값을 그대로 하드코딩하는 쪽이 정확하고 예측 가능하다.

### 실사용 패턴 (§4 프리미티브 관찰 결과)

| 값 | Tailwind 클래스 | 사용처 |
|---|---|---|
| 4px | `rounded-[4px]` | Checkbox(하드코딩 — §4 비고 참조) |
| 6px | `rounded-md` | Button, Input, Textarea, Select trigger, DropdownMenu/Select/Command 아이템 |
| 8px | `rounded-lg` | Card 컨테이너, Popover/DropdownMenu/Select content, Dialog content |
| — | `rounded-full` | Badge, Avatar, Switch thumb(라운드 스케일 미적용 — 원형/캡슐 형태는 별도) |

Button의 `xs`/`sm` size variant는 `rounded-[min(var(--radius-md),10px)]` 형태로 토큰을 참조하되 작은 버튼에서 과도하게 둥글어지지 않도록 상한을 씌운다 — 이는 calc 배율 파생이 아니라 토큰값 자체를 `min()`으로 클램프하는 것이라 폐기 대상이 아니다.

### 일관성 규칙

- 폼 컴포넌트(`<Input>`, `<Select>`, `<Button>`) 모두 `rounded-md`(6px) 기본.
- 판(`<Card>` · 창 · 팝오버)은 `rounded-lg`(8px). `rounded-xl`(12px)은 프리미티브에서 쓰지 않는다.
- 같은 폼 안에서 라운드 값이 섞이지 않도록 한다.

---

## 8. 그림자 (Shadow)

| Tailwind 클래스 | 용도 |
|---|---|
| `shadow-sm` | 카드, 폼 컨테이너(미세한 입체감) |
| `shadow-md` | 드롭다운 메뉴, 팝오버, 토스트 |
| `shadow-lg` | 모달, 대형 오버레이 |
| `shadow-none` | 플랫 섹션, 테두리만 있는 카드 |

**BTS 실제 관례.** Card/Dialog/Popover 등 표면(surface) 컴포넌트는 그림자 대신 `ring-1 ring-foreground/10`(반투명 미세 테두리)을 주로 쓴다 — ADS v2의 elevation이 그림자보다 미세한 링 강조에 가깝기 때문이다. `shadow-md`/`shadow-lg`는 Popover/DropdownMenu/Tooltip 등 부유(floating) 요소에 `ring-1`과 함께 병용한다(§4 프리미티브 표 참조).

---

## 9. 다크 모드 정책

> **색은 이행 완료(Q1, 2026-11-02) · 전환 움직임은 ⏳ 이행 예정(Q3).** 두 벌 값이 공통 토큰으로 바뀌었고, 다크에서 파랑 `--primary` 를 지키던 예외는 없어졌다.
> 전환은 화면 전체가 0.2초 겹쳐 바뀐다(View Transitions, Q3). `light` · `dark` · `system` 3종과 `class="dark"` 전략은 그대로 둔다.

다크 모드는 **1급 시민으로 활성 상태**다 — §2의 모든 컬러 토큰 표가 라이트/다크 값을 쌍으로 제공하는 이유가 이것이다.

### 구현 구성 요소

- **`apps/web/src/lib/theme.ts`** — `light`/`dark`/`system` 3종 테마 값 해석(`resolveTheme`), `<html>`에 `.dark` 클래스 토글(`applyTheme`), `localStorage`(`bts.theme` 키, 비민감 enum) 영속.
- **`apps/web/src/components/preferences/PreferencesProvider.tsx`** — 로그인 사용자의 `user.theme` 환경설정을 전역에 반영. `theme === 'system'`이면 OS `prefers-color-scheme` 변경을 실시간 구독해 즉시 재적용.
- **`apps/web/index.html`의 인라인 스크립트** — React 마운트 전, 첫 페인트 이전에 `localStorage`를 동기적으로 읽어 `.dark` 클래스를 미리 적용한다(FOUC — 첫 페인트 시 테마가 잠깐 깜빡이는 현상 — 방지). `next-themes` 등 표준 프리하이드레이션 패턴과 동일한 원리다.
- **`index.css`의 `@custom-variant dark (&:is(.dark *));`** — Tailwind v4가 `.dark` 클래스 하위 전체에 다크 변형을 적용하도록 정의.

### 전략

`class="dark"` 전략(shadcn 기본 방식)을 채택했다. `data-theme` 속성 방식은 쓰지 않는다.

### 다크에서 라이트와 다르게 동작하는 예외

- **`--primary`/`--primary-foreground`**. 예외 없음 — 라이트 · 다크 모두 `#FF7A1A` + `#1A0D02`(정책 §1). 옛 「다크도 Blue700」 예외(Maxi 결정 3)는 Q1 에서 없어졌다.
- **`--warning`**. 라이트 Yellow600(`#B38600`), 다크 Yellow400(`#E2B203`) — 같은 노랑 계열, 밝기만 다르다. 칠 위 글자는 두 모드 모두 어두운 글자.

---

## 10. 접근성 (WCAG AA)

### 색 대비 — 계산 결과

WCAG AA 기준. 본문 텍스트 4.5:1 이상, UI 컴포넌트/큰 텍스트(18px 이상 또는 14px bold 이상) 3:1 이상. 아래는 §2·§3에 등록된 실제 텍스트/배경 페어의 계산된 대비비다.

| 토큰 조합 | 라이트 대비 | 다크 대비 | 판정 | 비고 |
|---|---|---|---|---|
| 본문(`foreground` on `background`) | 16.58:1 | 16.12:1 | AA ✅ | |
| `muted-foreground` on `background` | 6.03:1 | 8.19:1 | AA ✅ | `text-subtle` · `text-subtlest` 도 같은 값 |
| `muted-foreground` on `muted`(well) | 5.88:1 | 7.09:1 | AA ✅ | |
| `foreground` on `bg-neutral-press`(line2) | 11.94:1 | 9.73:1 | AA ✅ | |
| `text-selected` on `bg-selected` | 4.63:1 | 6.90:1 | AA ✅ | 다크 `bg-selected` 는 판 위 합성 실효색 `#35261D` |
| `text-selected` on `bg-neutral-hover`(line) | **4.06:1** | 6.83:1 | ⚠️ 라이트 미달 | 고른 줄에 마우스를 올린 잠깐의 상태. 고름은 `bg-selected` 바탕으로도 전달된다 — 수용, 정책 원문 값 그대로 |
| `primary-foreground` on `primary` | 7.30:1 | 7.30:1 | AA ✅ | 양쪽 모드 동일 |
| warning 칠 위 텍스트 | 5.40:1(잉크 on `#B38600`) | 8.84:1 | AA ✅ | |
| success 칠 위 텍스트 | 5.44:1(잉크 on `#16A34A`) | 6.92:1 | AA ✅ | 흰 글자는 3.30:1 이라 잉크 |
| danger 칠 위 텍스트 | 4.97:1 | 4.57:1 | AA ✅ | |
| info 칠 위 텍스트 | 7.30:1 | 7.30:1 | AA ✅ | = 대표색 채움 |
| `success-text` on `bg-success/10` | 5.79:1 | 7.68:1 | AA ✅ | 최악 표면(well) 위 합성. 라이트는 편차 값 green-800(§2 §C) |
| `warning-text` on `bg-warning/10` | 4.85:1 | 8.78:1 | AA ✅ | 최악 표면(well) |
| `danger-text` on `bg-danger/10` | 4.95:1 | 6.67:1 | AA ✅ | 최악 표면(well) |
| `info-text` on `bg-info/10` | 6.03:1 | 6.70:1 | AA ✅ | 최악 표면(well). 라이트는 편차 값 orange-800 |
| `ring`/`border-focus` on `background` | 4.73:1 | 8.97:1 | AA ✅(UI 3:1 기준) | |
| `.mention`(`color: var(--brand-text)`) on 판 | 5.12:1 | 8.37:1 | AA ✅ | |
| 체크박스 글리프(`#1A0D02`) on `brand-hover` | 7.30:1 | 7.30:1 | AA ✅(UI 3:1 기준) | |
| 경고 테두리(`--warning`) vs 판 | 3.32:1 | 8.89:1 | AA ✅(UI 3:1 기준) | |
| `text-disabled`(faint) on `background` | 3.21:1 | 4.32:1 | 면제 | WCAG는 `disabled` 상태 텍스트에 대비 기준을 요구하지 않음 |

다크 바탕 단계는 Q1 에서 알파가 아닌 불투명 값이 됐다 — 합성 실효색 계산이 필요 없다(옛 「게이트 2 C1」 알파 처방은 없어졌다).
라이트 checked fill(`#FF7A1A`)과 흰 판의 경계 대비는 2.61:1 로 3:1 에 못 미치지만, checked 상태는 글리프 대비 7.30:1 과 체크박스 테두리(`--input`)로 전달되므로 의도적 수용이다.

### Syntax Highlight 토큰 대비 검증 (§3, WCAG AA 4.5:1 기준)

배경 기준. 라이트 `#FFFFFF`, 다크 `#161A1D`(Q1 전 `--background`). AQL 입력창은 판(`--card` `#FFFFFF` / `#17191D`) 위에 있어 Q1 뒤에도 근사값이 그대로다 — 값 재측정은 `--syntax-*` 동결을 푸는 Q2 몫.

**계산 방법.** WCAG 상대 밝기 공식 `(L1 + 0.05) / (L2 + 0.05)`. OKLCH L → CIE Y_rel 변환 식. `Y = ((L×100 + 16) / 116)^3`.

#### 라이트 모드

| 토큰 | OKLCH | 대비비 | 판정 |
|---|---|---|---|
| `--syntax-keyword` | `oklch(0.38 0.15 250)` | ≈7.2:1 | AA ✅ |
| `--syntax-field` | `oklch(0.36 0.14 290)` | ≈8.0:1 | AA ✅ |
| `--syntax-operator` | `oklch(0.44 0.13 55)` | ≈5.6:1 | AA ✅ |
| `--syntax-string` | `oklch(0.40 0.14 145)` | ≈6.4:1 | AA ✅ |
| `--syntax-number` | `oklch(0.44 0.17 25)` | ≈5.7:1 | AA ✅ |

#### 다크 모드

| 토큰 | OKLCH | 대비비 | 판정 |
|---|---|---|---|
| `--syntax-keyword` | `oklch(0.72 0.15 250)` | ≈7.1:1 | AA ✅ |
| `--syntax-field` | `oklch(0.75 0.13 290)` | ≈7.8:1 | AA ✅ |
| `--syntax-operator` | `oklch(0.76 0.13 65)` | ≈8.1:1 | AA ✅ |
| `--syntax-string` | `oklch(0.73 0.14 145)` | ≈7.4:1 | AA ✅ |
| `--syntax-number` | `oklch(0.75 0.16 25)` | ≈7.8:1 | AA ✅ |

> 최소 대비비. 라이트 5.6:1(`--syntax-operator`), 다크 7.1:1(`--syntax-keyword`). 전 토큰 WCAG AA 4.5:1 충족.

### 키보드 탐색

- Tab 순서는 시각적 레이아웃 순서(왼쪽→오른쪽, 위→아래)를 따른다. `Shift+Tab`으로 역방향 탐색 가능.
- `Enter` 키로 폼 제출(`button type="submit"`).
- `Esc` 키로 드롭다운/모달/팝오버 닫기(Radix 프리미티브 기본 동작).
- 포커스 표시. `focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50`(Input/Select/Button) 또는 `focus-visible:outline-2 focus-visible:outline-(--border-focus)`(Textarea/Checkbox/RadioGroup/Switch/Tabs/Dialog/Popover/ScrollArea) — 둘 다 §2에서 동일 색(`--ring` = `--border-focus`)을 쓰므로 시각적으로는 통일돼 있다. 절대 `outline-none`만으로 포커스를 제거하지 않는다.

### 입력 필드 접근성 필수 속성

모든 `<Input>`/`<Textarea>`/`<Select>`에 아래 패턴을 적용한다.

```
<label htmlFor="field-id">필드명</label>
<input
  id="field-id"
  aria-invalid={!!errors.field}
  aria-describedby="field-id-error"
/>
{errors.field && (
  <p id="field-id-error" role="alert" className="text-sm text-destructive">
    {errors.field.message}
  </p>
)}
```

- `aria-invalid="true"` — 검증 실패 시 스크린리더에 에러 상태 알림.
- `aria-describedby` — 에러 메시지 요소 ID를 연결해 스크린리더가 메시지를 읽는다.
- `role="alert"` — 에러 메시지가 동적으로 나타날 때 스크린리더에 즉시 알림.

### ARIA 레이블

- 아이콘 전용 버튼(텍스트 없음)에는 `aria-label`이 반드시 있어야 한다(예. `aria-label="닫기"`).
- 폼 전체는 `aria-label` 또는 `aria-labelledby`로 목적을 명시한다.
- `<Card>`는 의미 없는 장식 div가 아닌 경우 `role`을 생략하고 내부 heading으로 위계를 표현한다.

### 스크린리더 친화 label 텍스트

- "입력" 같은 추상적 label 금지. 구체적 명사로 명시한다.
- 버튼. "저장", "삭제", "취소" — 동사 + 명확한 목적어.
- placeholder는 label 대체 불가. label과 placeholder 모두 제공한다.
