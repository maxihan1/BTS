<!-- BTS 디자인 시스템 — ADS v2 팔레트 + 프리미티브 정본 (FR-UX-06) -->

# BTS 디자인 시스템 v2.0 (ADS v2)

BTS(Project Atlas)는 사내 1,000명 규모 협업 워크스페이스다. 이 문서는 **shadcn/ui radix-nova 프리셋** + **Tailwind v4 CSS-first `@theme`** 위에 구축된 디자인 토큰·프리미티브의 단일 출처(Single Source of Truth)다. 컬러 팔레트는 Atlassian Design System v2(이하 **ADS v2**, Jira/Confluence가 쓰는 오픈소스 디자인 토큰 체계)의 값을 이식했다 — 사내 협업 도구로서 검증된 정보 밀도·색 대비 기준을 그대로 물려받기 위함이다.

정본 CSS 구현. `apps/web/src/index.css`. 이 문서의 모든 표는 그 파일의 `:root` / `.dark` 블록과 1:1 대응해야 한다. 값 도출 과정·앵커 검증은 `docs/plans/2026-07-19-fr-ux-06-pr3-ads-palette-values.md` 참조.

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

### 출처

`apps/web/src/index.css`의 `:root`(라이트) / `.dark`(다크) 블록. 값은 모두 **hex**(sRGB)다 — OKLCH가 아니다(이전 shadcn init 산출물은 OKLCH였으나 ADS v2 이식 과정에서 hex로 전량 교체됐다).

다크 상태배경은 알파(`#RRGGBBAA`)다 — 게이트 2 리뷰에서 솔리드 등가의 상태-표면 충돌(C1)이 발견되어 ADS 정본 알파로 전환했다.

정본 소스는 npm 패키지 **`@atlaskit/tokens@1.4.2`**(Apache-2.0, 재현·재검증 가능)다. 이 세대를 고른 이유는 BTS가 독립 확인한 5개 앵커 값(Blue100 `#E9F2FF`, Blue1000 `#082145`, 본문 텍스트 `#172B4D`, Blue700 `#0C66E4`, 그리고 구세대 v1 Blue400 `#0052CC`의 부재)과 완전히 일치하는 유일한 세대이기 때문이다. `#0052CC`는 구세대 v1 값이라 기각됐다 — v5.0.0 이상은 앵커값이 어긋나고, v8/v13/v16의 `palette.js`는 브랜드 리프레시로 Blue700이 `#1868DB`로 바뀌어 있다. 상세 도출 과정. `docs/plans/2026-07-19-fr-ux-06-pr3-ads-palette-values.md`.

### §A. shadcn 베이스 토큰 18종 (`--color-*` Tailwind 유틸로 완전 배선됨)

`@theme inline` 블록이 이 18종 전부를 `--color-*`로 연결한다. `bg-primary`, `text-muted-foreground`, `border-input` 같은 표준 Tailwind 유틸리티로 바로 쓸 수 있다.

| CSS 변수 | 라이트 | 다크 | 한국어 역할 |
|---|---|---|---|
| `--background` | `#FFFFFF` | `#161A1D` | 페이지 배경 |
| `--foreground` | `#172B4D` | `#C7D1DB` | 기본 텍스트 |
| `--card` | `#FFFFFF` | `#1D2125` | 카드 배경 |
| `--card-foreground` | `#172B4D` | `#C7D1DB` | 카드 내부 텍스트 |
| `--popover` | `#FFFFFF` | `#22272B` | 팝오버/드롭다운/다이얼로그 배경 |
| `--popover-foreground` | `#172B4D` | `#C7D1DB` | 팝오버 텍스트 |
| `--primary` | `#0C66E4` | `#0C66E4` | 브랜드 블루(ADS Blue700) — 핵심 CTA 배경. 다크에서도 동일 값 유지(Maxi 결정, §다크 모드 예외 참조) |
| `--primary-foreground` | `#FFFFFF` | `#FFFFFF` | CTA 위 흰 텍스트 |
| `--secondary` | `#F1F2F4` | `#A1BDD914` | 보조 액션 배경(Neutral200 / DarkNeutral200A — 다크는 알파 토큰. ADS 알파 뉴트럴은 어떤 표면 위에서도 한 스텝 구분되도록 설계됐다 — 게이트 2 리뷰 C1 해소) |
| `--secondary-foreground` | `#172B4D` | `#C7D1DB` | 보조 액션 텍스트 |
| `--muted` | `#F7F8F9` | `#BCD6F00A` | 비활성 영역 배경(Neutral100 / DarkNeutral100A — 다크는 알파 토큰) |
| `--muted-foreground` | `#626F86` | `#8696A7` | 보조 텍스트(Neutral700 / DarkNeutral700) |
| `--accent` | `#F1F2F4` | `#A1BDD914` | 강조/hover 영역 배경(Neutral200 / DarkNeutral200A — `--bg-neutral-hover`와 동일 스텝, 다크는 알파 토큰) |
| `--accent-foreground` | `#172B4D` | `#C7D1DB` | 강조 영역 텍스트 |
| `--destructive` | `#CA3521` | `#F87462` | 위험 액션·에러(Red700 / Red400) |
| `--border` | `#DCDFE4` | `#2C333A` | 경계선(Neutral300 / DarkNeutral300) |
| `--input` | `#DCDFE4` | `#2C333A` | 입력 필드 테두리(Neutral300 / DarkNeutral300) |
| `--ring` | `#388BFF` | `#85B8FF` | 포커스 링(Blue500 / Blue300) |

> **`--accent` ≈ `--bg-neutral-hover`.** 라이트 `#F1F2F4`, 다크 `#A1BDD914`로 두 토큰의 값이 완전히 같다(같은 팔레트 스텝 Neutral200/DarkNeutral200A를 가리킨다). DropdownMenu/Select는 `focus:bg-accent`를, Tabs/Dialog는 `hover:bg-(--bg-neutral-hover)`를 쓴다 — 값은 같지만 배선 경로(§A vs §7)가 다르다는 점에 유의(Command는 `focus:bg-accent`를 쓰지 않는다 — §4 표 참조). 신규 컴포넌트 작성 시 어느 쪽을 쓸지는 "shadcn 표준 hover/focus 패턴이면 `--accent`, ADS §7 상태 토큰 문맥이면 `--bg-*`"로 판단한다.

### §7. 상태 토큰 11종 (Tailwind 유틸 미배선 — 임의값 문법으로 소비)

이 11종은 `@theme inline`에 `--color-*`로 별칭되지 **않는다**. `bg-bg-neutral` 같은 유틸리티 클래스는 존재하지 않는다. 대신 Tailwind v4의 CSS 변수 임의값 문법 `(--var-name)`으로 직접 참조한다 — 예. `bg-(--bg-neutral)`, `text-(--text-subtle)`, `outline-(--border-focus)`. `apps/web/src/components/ui/` 전 프리미티브가 이 패턴을 쓴다(§4 표 참조).

| CSS 변수 | 라이트 | 다크 | 한국어 역할 | Tailwind 사용 예 |
|---|---|---|---|---|
| `--bg-neutral` | `#F7F8F9` | `#BCD6F00A` | 뉴트럴 배경(테이블 hover/footer, 스켈레톤. 다크는 DarkNeutral100A 알파 토큰) | `bg-(--bg-neutral)` |
| `--bg-neutral-hover` | `#F1F2F4` | `#A1BDD914` | 뉴트럴 hover 배경(다크는 DarkNeutral200A 알파 토큰) | `hover:bg-(--bg-neutral-hover)` |
| `--bg-neutral-press` | `#DCDFE4` | `#A6C5E229` | 뉴트럴 press(active) 배경(다크는 DarkNeutral300A 알파 토큰) | `active:bg-(--bg-neutral-press)` |
| `--bg-selected` | `#E9F2FF` | `#082145` | 선택 상태 배경(Blue100 / Blue1000) | `data-[state=selected]:bg-(--bg-selected)` |
| `--text-selected` | `#0C66E4` | `#579DFF` | 선택 상태 텍스트(Blue700 / Blue400) | `data-[state=active]:text-(--text-selected)` |
| `--text-subtle` | `#44546F` | `#9FADBC` | 보조 텍스트(Neutral800 / DarkNeutral800, `muted-foreground`보다 한 단계 진함) | `text-(--text-subtle)` |
| `--text-subtlest` | `#626F86` | `#8696A7` | 최약 텍스트(`--muted-foreground`와 동일 값) | `text-(--text-subtlest)` |
| `--text-disabled` | `#B3B9C4` | `#454F59` | 비활성 텍스트 | `disabled:text-(--text-disabled)` |
| `--border-focus` | `#388BFF` | `#85B8FF` | 포커스 아웃라인(`--ring`과 동일 값 — 색 통일) | `focus-visible:outline-(--border-focus)` |
| `--brand-hover` | `#0055CC` | `#0055CC` | 브랜드 hover/체크 상태 배경(Blue800 — 라이트/다크 동일값. ADS 다크 Blue300은 흰 글리프 대비 2.04:1로 미달해 기각, 결정 3과 대칭으로 라이트값을 다크에도 유지) | `data-[state=checked]:bg-(--brand-hover)` |
| `--brand-text` | `#0C66E4` | `#579DFF` | 브랜드 텍스트(멘션 등) | `.mention { color: var(--brand-text) }` |

> **`--border-focus` = `--ring`.** 라이트 `#388BFF`(Blue500), 다크 `#85B8FF`(Blue300)로 완전히 동일한 값이다 — 포커스 시각 신호를 팔레트 전역에서 하나로 통일하기 위한 의도적 설계다.

### §C. 시맨틱 상태색 4종 + foreground 4종 (Tailwind 유틸로 완전 배선됨)

`color.background.<status>.bold`(고강조 배경) 스텝을 채택했다. `--color-*`로 배선되어 `bg-warning`, `text-danger` 등 표준 유틸로 쓸 수 있다.

| CSS 변수 | 라이트 | 다크 | ADS 팔레트 스텝 |
|---|---|---|---|
| `--warning` | `#B65C02` | `#E2B203` | Orange700 / Yellow400 |
| `--success` | `#1F845A` | `#4BCE97` | Green700 / Green400 |
| `--danger` | `#CA3521` | `#F87462` | Red700 / Red400(=`--destructive`와 동일 값) |
| `--info` | `#0C66E4` | `#579DFF` | Blue700 / Blue400(=`--primary` 라이트값과 동일) |
| `--warning-foreground` | `#FFFFFF` | `#161A1D` | 흰색 / DarkNeutral0(`--warning` bold 배경 위 텍스트) |
| `--success-foreground` | `#FFFFFF` | `#161A1D` | 흰색 / DarkNeutral0(`--success` bold 배경 위 텍스트) |
| `--danger-foreground` | `#FFFFFF` | `#161A1D` | 흰색 / DarkNeutral0(`--danger` bold 배경 위 텍스트) |
| `--info-foreground` | `#FFFFFF` | `#161A1D` | 흰색 / DarkNeutral0(`--info` bold 배경 위 텍스트) |

**텍스트 페어링.** `--warning-foreground`/`--success-foreground`/`--danger-foreground`/`--info-foreground` 4종이 토큰으로 제공된다(`text-warning-foreground` 등 Tailwind 유틸로 바로 쓸 수 있다) — 라이트는 흰 글자, 다크는 어두운 글자(`#161A1D`)로 고정된다. warning이 대표 사례 — 라이트는 진오렌지+흰 글자, 다크는 밝은 노랑+검은 글자로 페어링이 뒤집힌다(§10 대비표 참조).

**현재 소비처.** §C 4종은 `apps/web/src/components/ui/`(프리미티브)에서는 아직 직접 소비되지 않는다 — 실제로 §C 토큰을 CSS 클래스로 소비하는 곳은 `components/backlog/BacklogBoard.tsx`(백로그 절단 경고 배너, `border-warning bg-warning/10`) 1건뿐이다. `components/workflow/WorkflowDiagram.tsx`는 완료 상태를 하드코딩 `oklch()` 값으로 그리고 있어 §C 토큰으로 이관할 후보이고, `components/automation/RuleConflictWarningModal.tsx`·`components/automation/AutomationRuleFormDialog.tsx`는 변수/라벨 이름에 "warning" 문자열만 있을 뿐 §C 토큰을 실제로 소비하지 않는다.

### 🔒 동결 토큰 — 이 문서에서 다루지 않음

아래 토큰은 `index.css`에 이미 정의돼 있지만 **다른 PR의 소관**이다. 값을 임의로 바꾸지 말 것.

| 토큰 | 개수 | 현재 상태 | 소관 |
|---|---|---|---|
| `--chart-1` ~ `--chart-5` | 5 | OKLCH 회색조(그대로 유지, 색 구분 없음) | 차트/시각화 도입 PR(PR22) 몫 |
| `--syntax-keyword/field/operator/string/number` (+`.dark`) | 5 | OKLCH, WCAG AA 검증 완료(§3 참조) | AQL overlay 정렬 정본 — 값 변경 금지, §3에서 별도 관리 |
| `--font-mono` | 1 | D2Coding 기반 한글 mono 스택 | AQL overlay 정렬 정본 — §5에서 별도 관리, 값 변경 금지 |
| `--sidebar` / `--sidebar-foreground` / `--sidebar-primary` / `--sidebar-primary-foreground` / `--sidebar-accent` / `--sidebar-accent-foreground` / `--sidebar-border` / `--sidebar-ring` | 8 | shadcn init 산출 OKLCH 그레이스케일(ADS v2 미이식) | 사이드바 리디자인 PR(PR11) 몫 — 그때 ADS v2 값으로 교체 |

### 사용 가이드

- **primary** — 페이지당 한 개의 핵심 CTA에만 쓴다.
- **destructive / danger** — 되돌릴 수 없는 위험 액션 또는 에러 메시지. `--destructive`(§A)와 `--danger`(§C)는 라이트/다크 값이 동일하다 — 폼 검증 에러는 `--destructive`(shadcn 관례 유지), 상태 배지·알림 배너는 `--danger`(§C, 다른 시맨틱 3종과 대칭)를 쓴다.
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

### 전수 목록 (24개, 테스트 파일 14개 제외)

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

---

## 5. 타이포그래피

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

### 명시 스케일 (calc 파생 폐기)

`index.css`의 `@theme inline`에 5단계를 **각각 픽셀 리터럴로 직접 명시**한다. `--radius` 베이스 변수 + `calc()` 배율 파생 방식은 폐기했다.

| Tailwind 클래스 | CSS 변수 | 값 |
|---|---|---|
| `rounded-xs` | `--radius-xs` | 2px |
| `rounded-sm` | `--radius-sm` | 3px |
| `rounded-md` | `--radius-md` | 4px |
| `rounded-lg` | `--radius-lg` | 8px |
| `rounded-xl` | `--radius-xl` | 12px |

**calc 파생을 폐기한 이유.** 이전 shadcn 기본값은 `--radius: 0.625rem`(10px) 베이스에 `calc(var(--radius) * 0.6)` 같은 배율로 `rounded-sm`(6px), `rounded-2xl`(18px) 등을 파생시켰다. ADS v2는 라운드 기준 자체가 3px(`rounded-sm`)로 훨씬 작다 — 옛 배율 공식(예. base×0.6)을 새 기준에 그대로 적용하면 `3px × 0.6 = 1.8px` 같은 반픽셀 쓰레기 값이 나와 렌더링이 흐릿해지거나 브라우저마다 반올림이 갈린다. ADS v2가 실제 쓰는 값(2/3/4/8/12px)은 배율 관계가 아니라 각 용도별로 독립 결정된 정수 스텝이므로, calc 파생 대신 5개 값을 그대로 하드코딩하는 쪽이 정확하고 예측 가능하다.

### 실사용 패턴 (§4 프리미티브 관찰 결과)

| 값 | Tailwind 클래스 | 사용처 |
|---|---|---|
| 4px | `rounded-md` | DropdownMenu/Select/Command 아이템, Checkbox(단, `rounded-[4px]` 하드코딩 — §4 비고 참조) |
| 8px | `rounded-lg` | Button, Input, Textarea, Select trigger, Card 내부 요소, Popover/DropdownMenu/Select content, Dialog content |
| 12px | `rounded-xl` | Card 컨테이너(가장 큰 컨테이너 단위) |
| — | `rounded-full` | Badge, Avatar, Switch thumb(라운드 스케일 미적용 — 원형/캡슐 형태는 별도) |

Button의 `xs`/`sm` size variant는 `rounded-[min(var(--radius-md),10px)]` 형태로 토큰을 참조하되 작은 버튼에서 과도하게 둥글어지지 않도록 상한을 씌운다 — 이는 calc 배율 파생이 아니라 토큰값 자체를 `min()`으로 클램프하는 것이라 폐기 대상이 아니다.

### 일관성 규칙

- 폼 컴포넌트(`<Input>`, `<Select>`, `<Button>`) 모두 `rounded-lg`(8px) 기본.
- `<Card>` 컴포넌트는 `rounded-xl`(12px) — 컨테이너 단위는 한 단계 크게.
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

다크 모드는 **1급 시민으로 활성 상태**다 — §2의 모든 컬러 토큰 표가 라이트/다크 값을 쌍으로 제공하는 이유가 이것이다.

### 구현 구성 요소

- **`apps/web/src/lib/theme.ts`** — `light`/`dark`/`system` 3종 테마 값 해석(`resolveTheme`), `<html>`에 `.dark` 클래스 토글(`applyTheme`), `localStorage`(`bts.theme` 키, 비민감 enum) 영속.
- **`apps/web/src/components/preferences/PreferencesProvider.tsx`** — 로그인 사용자의 `user.theme` 환경설정을 전역에 반영. `theme === 'system'`이면 OS `prefers-color-scheme` 변경을 실시간 구독해 즉시 재적용.
- **`apps/web/index.html`의 인라인 스크립트** — React 마운트 전, 첫 페인트 이전에 `localStorage`를 동기적으로 읽어 `.dark` 클래스를 미리 적용한다(FOUC — 첫 페인트 시 테마가 잠깐 깜빡이는 현상 — 방지). `next-themes` 등 표준 프리하이드레이션 패턴과 동일한 원리다.
- **`index.css`의 `@custom-variant dark (&:is(.dark *));`** — Tailwind v4가 `.dark` 클래스 하위 전체에 다크 변형을 적용하도록 정의.

### 전략

`class="dark"` 전략(shadcn 기본 방식)을 채택했다. `data-theme` 속성 방식은 쓰지 않는다.

### 다크에서 라이트와 다르게 동작하는 예외

- **`--primary`/`--primary-foreground`**. ADS v2 정본은 다크 브랜드색을 밝은 파랑 Blue400(`#579DFF`)+어두운 글자로 정의하지만, BTS는 Maxi 결정으로 다크에서도 `#0C66E4`(Blue700)+흰 글자를 유지한다(shadcn 관례 우선). §2 §A 표의 `--primary` 다크 열이 이 예외를 반영한다.
- **`--warning`**. 라이트는 Orange700(진오렌지+흰 글자), 다크는 Yellow400(밝은 노랑+검은 글자)으로 색상 계열 자체가 바뀐다(§2 §C 참조).

---

## 10. 접근성 (WCAG AA)

### 색 대비 — 계산 결과

WCAG AA 기준. 본문 텍스트 4.5:1 이상, UI 컴포넌트/큰 텍스트(18px 이상 또는 14px bold 이상) 3:1 이상. 아래는 §2·§3에 등록된 실제 텍스트/배경 페어의 계산된 대비비다.

| 토큰 조합 | 라이트 대비 | 다크 대비 | 판정 | 비고 |
|---|---|---|---|---|
| 본문(`foreground` on `background`) | 14.10:1 | 11.31:1 | AA ✅ | |
| `muted-foreground` on `background` | 5.08:1 | 5.78:1 | AA ✅ | |
| `text-subtle` on `background` | 7.65:1 | 7.65:1 | AA ✅ | |
| `text-subtlest` on `background` | 5.08:1 | 5.78:1 | AA ✅ | `muted-foreground`와 동일 hex라 대비값도 동일 |
| `text-selected` on `bg-selected` | 4.61:1 | 5.84:1 | AA ✅ | |
| `primary-foreground`(흰 글자) on `primary` | 5.20:1 | 5.20:1 | AA ✅ | 양쪽 모드 동일(다크도 Blue700 유지) |
| warning bold 위 텍스트 | 4.65:1(흰 글자 on `#B65C02`) | 8.84:1(`#161A1D` on `#E2B203`) | AA ✅ | 다크는 어두운 글자로 페어링 반전(§9 참조) |
| success bold 위 텍스트 | 4.66:1 | 8.81:1 | AA ✅ | |
| danger bold 위 텍스트 | 5.19:1 | 6.39:1 | AA ✅ | |
| info bold 위 텍스트 | 5.20:1 | 6.40:1 | AA ✅ | |
| `ring`/`border-focus` on `background` | 3.33:1 | 8.58:1 | AA ✅(UI 3:1 기준) | 포커스 링은 텍스트가 아니라 UI 컴포넌트라 3:1 기준 적용 |
| `.mention`(`color: var(--brand-text)`) | 4.64:1 | 5.51:1 | AA ✅ | |
| `text-disabled` on `background` | 1.97:1 | 2.10:1 | 면제 | WCAG는 `disabled` 상태 텍스트에 대비 기준을 요구하지 않음 |

**다크 상태배경(알파) 텍스트 대비 — 합성 실효색 기준(최악 표면 popover).** 게이트 2 리뷰 C1로 다크 `--secondary`/`--muted`/`--accent`/`--bg-neutral*`가 솔리드에서 알파(`#RRGGBBAA`)로 바뀌면서, 텍스트 대비는 알파를 실제로 겹칠 표면(카드 `#1D2125` / 팝오버 `#22272B`) 위에 합성한 실효색으로 계산해야 한다. 두 표면 중 팝오버가 항상 더 어두운 합성 결과를 내는 최악 케이스라 아래 값은 전부 팝오버 합성 기준이다(카드 합성은 더 밝아 대비가 더 여유롭다).

| 토큰 조합 | 라이트 대비 | 다크 대비(팝오버 합성 실효색) | 판정 | 비고 |
|---|---|---|---|---|
| `muted-foreground` on `muted` | 4.77:1 | 4.54:1 | AA ✅ | 다크 `muted`=DarkNeutral100A(`#BCD6F00A`), 팝오버 위 합성 실효색 `#282E33` |
| `accent-foreground`/`secondary-foreground` on `accent`/`secondary` | 12.59:1 | 8.30:1 | AA ✅ | 다크 `accent`/`secondary`=DarkNeutral200A(`#A1BDD914`), 팝오버 위 합성 실효색 `#2C3339` |
| `foreground` on `bg-neutral-press` | 10.55:1 | 6.78:1 | AA ✅ | 다크 `bg-neutral-press`=DarkNeutral300A(`#A6C5E229`), 팝오버 위 합성 실효색 `#374048` |
| `text-selected` on `bg-neutral-hover`(알파 표면 위에 놓이는 최악 케이스) | 4.65:1 | 4.69:1 | AA ✅ | 다크 `bg-neutral-hover`=DarkNeutral200A, 팝오버 위 합성 실효색 `#2C3339`(위 accent-foreground 행과 동일 합성면) |
| 체크박스 글리프(흰색) on `brand-hover`(checked 상태) | 6.62:1 | 6.62:1 | AA ✅(UI 3:1 기준) | 라이트/다크 동일값(`#0055CC`) — 게이트 2 리뷰 C2, ADS 다크 Blue300(`#85B8FF`)은 2.04:1로 미달해 기각 |

### Syntax Highlight 토큰 대비 검증 (§3, WCAG AA 4.5:1 기준)

배경 기준. 라이트 = `--background`(`#FFFFFF`), 다크 = `--background`(`#161A1D`).

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
