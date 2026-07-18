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

## §7 상태 토큰 alias 매핑 (Task 1의 정본)

프리미티브가 소비하는 상태 토큰을 `index.css` `:root`/`.dark`에 **alias 값**으로 신설한다. 값은 기존 shadcn 토큰을 가리키므로 시각적으로 현재와 동일하고, PR3이 이 이름들에 ADS 값을 부여한다. 여러 토큰이 같은 shadcn var를 가리키는 것은 **의도적** — 이름을 분리해둬야 PR3이 각각 다른 ADS 값을 줄 수 있다.

| §7 토큰 | alias 값 | 역할 | PR3 예정 값 |
|---|---|---|---|
| `--bg-neutral` | `var(--muted)` | 중립 표면 | ADS Neutral100 |
| `--bg-neutral-hover` | `var(--accent)` | hover 배경 | ADS Neutral200 |
| `--bg-neutral-press` | `var(--accent)` | press 배경 | ADS Neutral300 |
| `--bg-selected` | `var(--accent)` | 선택 배경 | ADS Blue100 |
| `--text-selected` | `var(--primary)` | 선택 텍스트 | ADS Blue700 |
| `--text-subtle` | `var(--muted-foreground)` | 옅은 텍스트 | ADS Neutral700 |
| `--text-subtlest` | `var(--muted-foreground)` | 가장 옅은 텍스트 | ADS Neutral500 |
| `--text-disabled` | `var(--muted-foreground)` | 비활성 텍스트 | ADS Neutral400 |
| `--border-focus` | `var(--ring)` | focus 아웃라인 | ADS Blue700 |
| `--brand-hover` | `var(--primary)` | 브랜드 hover | ADS Blue800/Blue300 |
| `--brand-text` | `var(--primary)` | 브랜드 텍스트 | ADS Blue700 |

**Tailwind 소비 문법.** `@theme` 등록은 하지 않는다(`bg-bg-neutral-hover` 같은 이름 중복 회피). 기존 `dropdown-menu.tsx`의 `max-h-(--radix-…)` 선례대로 CSS 변수 shorthand로 직접 참조한다 — `bg-(--bg-neutral-hover)`, `text-(--text-disabled)`, `outline-(--border-focus)`. focus-visible 표준은 `focus-visible:outline-2 focus-visible:outline-(--border-focus) focus-visible:outline-offset-2` (§7 명세: 모든 대화형 요소 필수).

## Plan

> **공통 규칙 (전 task).** ① 프리미티브는 `radix-ui` 통합 패키지에서 named import (`import { Dialog as DialogPrimitive } from "radix-ui"`). 개별 `@radix-ui/react-*` 설치·import 금지. ② 파일 L1에 한국어 역할 주석 (기존 `avatar.test.tsx` 선례). ③ `data-slot` 속성 관례 유지. ④ `cn()` from `@/lib/utils`. ⑤ 테스트는 `@testing-library/react` + vitest `describe/it/expect`, `screen.getByRole`. ⑥ 상태 클래스는 §7 토큰 소비 (위 표). ⑦ **소비자 0 유지** — 기존 화면/라우트 파일 수정 절대 금지, `components/ui/` 신규 파일 + `index.css`만.

### Task 1. §7 상태 토큰 11종 신설 (index.css)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/index.css`, `apps/web/src/components/ui/__tests__/state-tokens.test.ts`]
- depends-on: []

**RED**:
- 파일: `apps/web/src/components/ui/__tests__/state-tokens.test.ts`
- 테스트: `index.css` 원문을 fs로 읽어 11개 토큰이 `:root`와 `.dark` **양쪽**에 정의됐는지 정규식 어서션 (라이트/다크 both — 안 하면 다크 대비 붕괴). 각 토큰이 `var(--…)` alias 형태인지도 확인 (hex/oklch 직접 값이면 PR3 침범 → fail).
  ```ts
  const css = readFileSync('src/index.css', 'utf8')
  const TOKENS = ['--bg-neutral','--bg-neutral-hover','--bg-neutral-press','--bg-selected','--text-selected','--text-subtle','--text-subtlest','--text-disabled','--border-focus','--brand-hover','--brand-text']
  // :root 블록·.dark 블록 각각에서 11종 전부 매칭 + alias(var(--...)) 형태
  ```
- 실패 메시지 (예상): 토큰 미정의로 매칭 0

**GREEN**:
- 파일: `apps/web/src/index.css`
- `:root`와 `.dark` **양쪽**에 11종을 위 표의 alias 값으로 추가. 다크에서도 alias가 shadcn var를 가리키므로 두 블록에 **같은 alias 텍스트**를 넣으면 된다 (shadcn var 자체가 `.dark`에서 다른 값이라 자동 반영). 각 토큰 옆에 `/* PR3서 ADS X로 교체 */` 주석.
- 🔒 `--syntax-*` 5종·`--font-mono`·`--chart-1~5` 절대 미변경 (계약).

**REFACTOR**:
- 토큰을 §7 역할 순서(bg → text → border → brand)로 정렬 + 블록 헤더 주석.

**검증**: `cd apps/web && pnpm test -- state-tokens`

### Task 2. dialog 프리미티브 (role="dialog" 계약 핵심)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/dialog.tsx`, `apps/web/src/components/ui/dialog.test.tsx`]
- depends-on: [1]

**RED**:
- 파일: `apps/web/src/components/ui/dialog.test.tsx`
- 테스트: 열린 Dialog가 **`role="dialog"`를 노출**하는지 (`screen.getByRole('dialog')`) + `DialogTitle`이 accessible name으로 연결되는지 (`getByRole('dialog', { name: '제목' })`) + `DialogClose`가 닫는지. **이 role 어서션이 e2e 147건 계약의 단위 대응물.**
- 실패 메시지 (예상): `dialog.tsx` 없음

**GREEN**:
- 파일: `apps/web/src/components/ui/dialog.tsx`
- `radix-ui`의 `Dialog` primitive 래핑. export: `Dialog`(Root), `DialogTrigger`, `DialogPortal`, `DialogOverlay`, `DialogContent`, `DialogClose`, `DialogHeader`, `DialogFooter`, `DialogTitle`, `DialogDescription`.
- 🔒 `DialogContent`는 `DialogPrimitive.Content`를 그대로 감싼다 — Radix가 `role="dialog"` 부여. 래퍼가 role을 덮어쓰지 않음.
- Overlay 클래스는 기존 35~37파일이 복붙하던 문자열을 표준화 (dropdown-menu의 애니메이션 data-attr 패턴 참조).
- focus-visible → `--border-focus`, close 버튼 hover → `--bg-neutral-hover`.

**REFACTOR**: Header/Footer 레이아웃 유틸 정리 + KDoc.

**검증**: `cd apps/web && pnpm test -- dialog`

### Task 3. 순수 프리미티브 5종 (table · badge · skeleton · textarea · empty-state)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/table.tsx`, `apps/web/src/components/ui/badge.tsx`, `apps/web/src/components/ui/skeleton.tsx`, `apps/web/src/components/ui/textarea.tsx`, `apps/web/src/components/ui/empty-state.tsx`, `apps/web/src/components/ui/table.test.tsx`, `apps/web/src/components/ui/badge.test.tsx`, `apps/web/src/components/ui/empty-state.test.tsx`]
- depends-on: [1]

**RED** (Radix 무의존, 순수 HTML/CSS):
- `table.test.tsx`: `<Table>`가 native `role="table"` 노출 + `TableCaption`·`TableHead` 렌더.
- `badge.test.tsx`: variant별 (`neutral`/`blue`/`green`/`red`/`yellow`) 클래스 적용 (로젠지 색). `cva` 사용.
- `empty-state.test.tsx`: title·description·action 슬롯 렌더 + 아이콘 슬롯. description은 `text-(--text-subtle)`.
- skeleton·textarea는 렌더 스모크만 (별도 test 파일 불요, table/badge 테스트에 포함하거나 생략 — 순수 div/textarea).

**GREEN**:
- `table.tsx`: `Table`/`TableHeader`/`TableBody`/`TableFooter`/`TableRow`/`TableHead`/`TableCell`/`TableCaption` — native `<table>` 래퍼. overflow 컨테이너 포함.
- `badge.tsx`: `cva`로 로젠지 variant. 상태 category(TODO/IN_PROGRESS/DONE) 매핑은 소비처(PR18) 몫, 여기선 색 variant만.
- `skeleton.tsx`: `animate-pulse` + `bg-(--bg-neutral)` div. (board.tsx·dashboards.tsx 인라인 중복 흡수 대상이나 **이 PR은 추가만**.)
- `textarea.tsx`: native `<textarea>` 래퍼 + focus-visible `--border-focus`.
- `empty-state.tsx`: 아이콘/title/description/action 슬롯 구조. `FilteredEmptyState` 중복 2곳 흡수 대상이나 이 PR은 추가만.

**REFACTOR**: badge cva variant 상수화 + 각 파일 KDoc.

**검증**: `cd apps/web && pnpm test -- table badge empty-state`

### Task 4. Radix 오버레이 3종 (tooltip · popover · scroll-area)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/tooltip.tsx`, `apps/web/src/components/ui/popover.tsx`, `apps/web/src/components/ui/scroll-area.tsx`, `apps/web/src/components/ui/tooltip.test.tsx`, `apps/web/src/components/ui/popover.test.tsx`]
- depends-on: [1]

**RED**:
- `tooltip.test.tsx`: hover 시 `role="tooltip"` 콘텐츠 노출 (`TooltipProvider` 래핑 필수).
- `popover.test.tsx`: trigger 클릭 시 콘텐츠 열림 + `getByRole('dialog')` 또는 콘텐츠 텍스트 확인 (Radix Popover Content).
- scroll-area는 렌더 스모크.

**GREEN**:
- `tooltip.tsx`: `Tooltip` primitive — `TooltipProvider`/`Tooltip`/`TooltipTrigger`/`TooltipContent`.
- `popover.tsx`: `Popover`/`PopoverTrigger`/`PopoverContent`/`PopoverAnchor`.
- `scroll-area.tsx`: `ScrollArea`/`ScrollBar` (Viewport/Scrollbar/Thumb/Corner 구성).
- 콘텐츠 배경 `bg-popover`, 애니메이션 data-attr는 dropdown-menu 패턴 재사용.

**REFACTOR**: 공통 애니메이션 클래스 정리 + KDoc.

**검증**: `cd apps/web && pnpm test -- tooltip popover`

### Task 5. Radix 폼 컨트롤 3종 (checkbox · switch · radio-group)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/checkbox.tsx`, `apps/web/src/components/ui/switch.tsx`, `apps/web/src/components/ui/radio-group.tsx`, `apps/web/src/components/ui/checkbox.test.tsx`, `apps/web/src/components/ui/switch.test.tsx`, `apps/web/src/components/ui/radio-group.test.tsx`]
- depends-on: [1]

**RED**:
- `checkbox.test.tsx`: `role="checkbox"` + 클릭 시 `aria-checked` 토글.
- `switch.test.tsx`: `role="switch"` + `aria-checked` 토글.
- `radio-group.test.tsx`: `role="radiogroup"` + 항목 `role="radio"` + 단일 선택.

**GREEN**:
- `checkbox.tsx`: `Checkbox` primitive + `CheckIcon` Indicator. focus-visible `--border-focus`, disabled `--text-disabled`.
- `switch.tsx`: `Switch` primitive + Thumb. checked 배경 `--brand-hover` 계열.
- `radio-group.tsx`: `RadioGroup`/`RadioGroupItem` + Indicator. selected `--text-selected`.

**REFACTOR**: 공통 disabled/focus 상태 클래스 정리 + KDoc.

**검증**: `cd apps/web && pnpm test -- checkbox switch radio-group`

### Task 6. Radix 구조 2종 (tabs · separator)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/tabs.tsx`, `apps/web/src/components/ui/separator.tsx`, `apps/web/src/components/ui/tabs.test.tsx`, `apps/web/src/components/ui/separator.test.tsx`]
- depends-on: [1]

**RED**:
- `tabs.test.tsx`: `role="tablist"` + `role="tab"` + `role="tabpanel"`, 탭 전환 시 패널 교체. **★ 프리미티브 자체는 정상 — "뷰 전환에 쓰지 말라"는 소비처 규칙(정본 plan §163)이지 프리미티브 결함이 아니다.** PR19 이슈 상세 활동 탭이 정당한 소비처.
- `separator.test.tsx`: `role="separator"` (비장식) 또는 `aria-orientation` 확인.

**GREEN**:
- `tabs.tsx`: `Tabs`/`TabsList`/`TabsTrigger`/`TabsContent`. active trigger `--bg-selected`+`--text-selected`, hover `--bg-neutral-hover`.
- `separator.tsx`: `Separator` primitive. `bg-border`.

**REFACTOR**: KDoc + tabs 방향(horizontal/vertical) prop 정리.

**검증**: `cd apps/web && pnpm test -- tabs separator`

### Task 7. command 프리미티브 (cmdk core-only)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/command.tsx`, `apps/web/src/components/ui/command.test.tsx`]
- depends-on: [1]

**RED**:
- `command.test.tsx`: `Command` 렌더 + `CommandInput` 입력 시 `CommandItem` 필터링 + 매치 0건 시 `CommandEmpty` 노출.
- 실패 메시지 (예상): `command.tsx` 없음

**GREEN**:
- 파일: `apps/web/src/components/ui/command.tsx`
- `cmdk`(이미 의존성) 기반: `Command`/`CommandInput`/`CommandList`/`CommandEmpty`/`CommandGroup`/`CommandItem`/`CommandSeparator`/`CommandShortcut`.
- 🚫 **`CommandDialog`는 제외 (core-only)** — dialog 결합은 소비처(`CommandPalette`) 몫. FR-IS-06 clone core-only 관례 원용. depends-on을 [1]로 유지해 wave 병렬 보존 (dialog Task 2에 묶지 않음).
- selected item `--bg-selected`, shortcut `--text-subtle`.

**REFACTOR**: KDoc + cmdk 스타일 클래스 정리.

**검증**: `cd apps/web && pnpm test -- command`

## Plan 메타

- task 수: 7 (프리미티브 15종 + 토큰 11종을 파일 겹침 0으로 그루핑)
- wave 예상: **2** — wave1 = [Task 1 토큰], wave2 = [Task 2·3·4·5·6·7 병렬] (전부 depends-on:[1], 상호 파일 겹침 0)
- TDD 강제: yes (각 프리미티브 role/aria 스모크를 RED 선행, dialog는 role="dialog" 필수)
- 새 의존성: **0개** (`radix-ui@1.4.3`·`cmdk@1.1.1` 이미 존재)
- 추가 검증: `pnpm verify` (lint+typecheck+test+build) · E2E는 소비자 0이라 신규 spec 없음, 기존 122 spec 회귀 0 확인
- 🔒 계약: `role="dialog"` 보존 · `--syntax-*`/`--font-mono`/`--chart-1~5` 미변경 · 기존 화면 파일 미수정

## 리뷰 결과 (← /bts-review-plan, 스킵 — 정본 plan 이미 리뷰됨)
