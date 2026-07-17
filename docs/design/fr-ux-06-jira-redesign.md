<!-- FR-UX-06 UI/UX 전면 개편 디자인 스펙 — designer 산출물, frontend-engineer 구현 입력. ADS v2 토큰 정본 -->

# FR-UX-06 UI/UX 전면 개편 — 디자인 스펙 (Jira Cloud 방식)

> slug: `fr-ux-06-jira-redesign` · Plan PR1(문서) 산출물 · TDD 예외(코드/테스트 없음)
> 관련 문서. [플랜](../plans/2026-07-17-fr-ux-06-jira-redesign/plan.md) · [ADR](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) · [DESIGN.md](../../DESIGN.md)
> 시안(동작 프로토타입 8화면·라이트/다크). https://claude.ai/code/artifact/de59cb1e-4fae-4755-b18b-fe888ee96023
> 소비 Task. **PR2**(프리미티브 15종) · **PR3**(ADS 토큰 + DESIGN.md 재작성) · **PR11~13**(셸) · **PR18/19**(이슈 화면)
> **★ 동시 PR #277 PR-5(프로젝트 생성·목록·설정·아카이브 화면)의 입력이기도 하다** — 그 화면들은 본 스펙 위에 짓는다

## 핵심 결정 요약 (3줄)

1. **ADS v2 팔레트를 hex 그대로 이식한다.** `#0C66E4`(Blue700) primary. 널리 알려진 `#0052CC`는 **구세대 v1**이라 배제 — 2025 신형 네비를 택했으니 세대를 맞춘다. oklch로 변환하지 않는 이유는 **공식 문서와 대조 검증을 살리기 위해서**다.
2. **상태 로젠지 매핑에 발명이 없다.** `software-default.yaml`이 이미 Jira의 status category(TODO/IN_PROGRESS/DONE)와 1:1이라 그대로 색에 붙인다.
3. **폰트는 Jira가 실제로 쓴 시스템 스택.** Atlassian Sans는 CSP가 CDN을 막고 **한글 글리프도 없어** 그대로 못 베낀다. 시스템 스택이 정확하면서 로딩 비용 0이다. Pretendard는 **별도 PR**.

## 1. 목표

BTS의 UI를 Jira Cloud 2025 기준으로 개편할 때 **모든 색·크기·간격의 단일 출처**가 된다. `DESIGN.md` 재작성(PR3)의 입력이며, PR2/PR11~13/PR18/19와 #277 PR-5가 이 문서를 참조한다.

**목표가 아닌 것.** 화면별 상세 와이어프레임(시안 Artifact가 담당) · 구현 코드 · 컴포넌트 API 시그니처.

## 2. 레퍼런스 — 기존 자산 전수 조사 결과

| 참고 대상 | 재사용 포인트 |
|---|---|
| `src/index.css` | **유일한 CSS 파일.** `@theme inline` + `:root`/`.dark`. 토큰 교체가 국소적 — 여기만 고치면 된다 |
| `--sidebar-*` 8종 | shadcn init 산출물, **소비자 0**. 사이드바 PR에서 ADS 값으로 덮어쓴다 (신규 정의 불필요) |
| `--syntax-*` 5종 | **유일하게 대비 계산이 끝난 유채색.** `--font-mono`와 함께 AQL overlay 정렬 정본 → **건드리지 않는다** |
| `.mention` | `--primary`/`--accent` 직참조 → 팔레트 교체 후 **대비 재확인 필요** |
| `components/ui/` 7종 | avatar(자체 blob 구현)·button(CVA variant 6/size 8)·card·dropdown-menu·form·input·label·select·sonner |
| `radix-ui` 1.4.3 통합 패키지 | 개별 `@radix-ui/react-*` 추가 설치 **금지**. `import { Dialog as DialogPrimitive } from 'radix-ui'` 형태 |
| `workflows/software-default.yaml` | 상태 5종 + category 3종 — **Jira와 1:1** |
| `V003__issue_types.sql` | epic/story/task/subtask 시드 — Jira와 1:1 |
| `i18n/ko.ts` `priorityNames` | 1~5 = 가장 높음/높음/보통/낮음/가장 낮음 |
| `lib/theme.ts` + `PreferencesProvider` | 다크모드 **완전 동작 중** — 라이트/다크 both 설계 필수 |

**★ `components.json`의 `baseColor: "neutral"`이 무채색의 근원이다.** `style: "radix-nova"` 프리셋은 유지하되 토큰만 ADS로 덮어쓴다.

## 3. 레이아웃

### 3.1 앱 셸

```
┌────────────────────────────────────────────────────────┐
│ ☰  로고 Atlas   [ 🔍 검색 ]   [+ 만들기] 🔔 ❓ ⚙ 👤      │  48px 고정
├──────────────┬─────────────────────────────────────────┤
│ 사이드바      │  콘텐츠                                  │
│ 264px        │  (독립 스크롤)                            │
│ (독립 스크롤) │                                          │
└──────────────┴─────────────────────────────────────────┘
```

| 영역 | 크기 | 토큰 |
|---|---|---|
| 상단바 | `height: 48px` 고정 | `--surface` + `border-bottom: 1px --border` |
| 사이드바 | `width: 264px` 고정, 접기 가능 | `--surface-sunken` + `border-right: 1px --border` |
| 콘텐츠 | `flex: 1; min-width: 0` | `--surface` |

**상단바 좌→우.** 사이드바 토글(32px) · 로고 마크(24px `--r-sm` `--brand` 배경) + 제품명 · **검색**(`flex:1; max-width:560px`) · `만들기` 버튼(primary) · 알림 벨(뱃지) · 도움말 · 설정 · 아바타(28px, 상태 이모지 오버레이).

> 🔒 **`aria-label="검색"`은 상단바에만.** `Header.tsx:124`에 이미 있고 e2e 5건이 의존 → 사이드바 중복 시 Playwright strict mode 위반.

**사이드바 섹션 (위→아래).**

| 섹션 | 항목 | 비고 |
|---|---|---|
| 1 | 내 작업 · 최근 · 즐겨찾기 | |
| 2 | **프로젝트** (트리, 확장 시 요약/보드/백로그/타임라인/리포트/프로젝트 설정) | 데이터는 **#277의 `GET /api/v1/projects`** |
| 3 | 이슈 · 대시보드 · 캘린더 · 필터 | |
| 4 | **관리** (워크플로우 스킴·감사 로그·알림 정책·Webhook·Slack 연결) | 🔒 `isSystemAdmin === true` 게이팅 + **기본 펼침** |

> 🔒 **관리 섹션 기본 펼침 필수.** 접으면 `webhook.spec.ts:73`·`audit-logs.spec.ts:50`·`notification-policies.spec.ts:83`이 `adminNav.getByRole('link').click()`에서 **not visible → 클릭 실패**.
> 🔒 **`aria-label` 4종 보존** — `메인 메뉴` / `관리 메뉴` / `프로젝트 뷰 전환` / workflow-scheme `sidebar.nav`. `getByRole('navigation')` 18건의 유일한 계약.
> 🔒 **사이드바에 `<h1>` 금지.** e2e 34건이 `level:1` 단독을 어서션.

### 3.2 페이지 컨테이너 (`PageLayout` 3 variant)

현재 애드혹 3종을 그대로 흡수한다 — 억지로 하나로 합치지 않는다.

| variant | 현재 클래스 | 출현 | 용도 |
|---|---|---|---|
| `narrow` | `mx-auto max-w-2xl px-4 py-8` | 13회 | 설정 폼 |
| `form` | `p-8 space-y-6 max-w-2xl` | 11회 | 설정 상세 |
| `wide` | `mx-auto max-w-7xl px-4 py-8` | 4회 | 목록/보드 |

**`PageHeader`가 `<h1>`을 단독 소유한다.** 채택 시 페이지의 기존 h1은 **반드시 삭제** (e2e 34건, name은 글자 단위 보존).

### 3.3 이슈 상세 2컬럼

`grid-template-columns: minmax(0,1fr) 340px` — 현재 280px에서 확대(Jira는 ~340px). 활동 영역(댓글/히스토리/작업로그/연결)은 **탭으로 접는다** (현재는 2단 그리드 바깥에 세로 무한 적층).

## 4. 상태 · 타입 · 우선순위 매핑

### 4.1 상태 로젠지 — `software-default.yaml`과 1:1

| 워크플로우 상태 | category | 로젠지 | 토큰 |
|---|---|---|---|
| Open | `TODO` | 회색 | `--bg-neutral-hover` / `--text-subtle` |
| In Progress | `IN_PROGRESS` | 파랑 | `--info-bg` / `--info-text` |
| In Review | `IN_PROGRESS` | 파랑 | `--info-bg` / `--info-text` |
| Done | `DONE` | 초록 | `--success-bg` / `--success-text` |
| Closed | `DONE` | 초록 | `--success-bg` / `--success-text` |

**로젠지 스타일.** `font-size:11px; font-weight:700; letter-spacing:.02em; text-transform:uppercase; padding:2px 6px; border-radius:3px`.

### 4.2 이슈 타입 (V003 시드)

| 타입 | 색 토큰 | 라이트 | 다크 | 글리프 |
|---|---|---|---|---|
| Epic | `--type-epic` | Purple700 `#6E5DC6` | Purple400 `#9F8FEF` | 번개 |
| Story | `--type-story` | Green600 `#22A06B` | Green400 `#4BCE97` | 북마크 |
| Task | `--type-task` | Blue700 `#0C66E4` | Blue400 `#579DFF` | 체크 |
| Bug | `--type-bug` | Red700 `#C9372C` | Red400 `#F87168` | 원 |
| Subtask | `--type-subtask` | Blue400 `#579DFF` | Blue300 `#85B8FF` | 겹친 사각 |

**규격.** 16×16 채운 라운드 사각(`--r-sm`) + 흰 글리프 — Jira 규격.

### 4.3 우선순위 (`i18n/ko.ts` `priorityNames` 1~5)

| # | 이름 | 토큰 | 라이트 | 글리프 |
|---|---|---|---|---|
| 1 | 가장 높음 | `--prio-highest` | Red700 | 이중 위 화살표 |
| 2 | 높음 | `--prio-high` | Red600 `#E34935` | 위 화살표 |
| 3 | 보통 | `--prio-medium` | Orange600 `#D97008` | 이중선 |
| 4 | 낮음 | `--prio-low` | Blue600 `#1D7AFC` | 아래 화살표 |
| 5 | 가장 낮음 | `--prio-lowest` | Blue500 `#388BFF` | 이중 아래 화살표 |

> 🔒 **색만으로 구분하지 않는다** (WCAG 1.4.1) — 우선순위/타입 모두 **글리프 형태가 다르다**.

## 5. 색상 · 타이포 · 간격 · 라운드 — 재사용 vs 신규 총정리

### 5.1 ★ 팔레트 정확도 — 정본이 아니다

`atlassian.design`이 JS 렌더링이라 **전수 검증 실패**. 4개 지점만 독립 확인했고 전부 일치했다.

| 값 | 역할 | 출처 |
|---|---|---|
| `#E9F2FF` | Blue100 (램프 최명부) | Atlassian 개발자 문서 — `color.background.information` 라이트 |
| `#082145` | Blue1000 (최암부) | 동 문서 다크 |
| `#172B4D` | Neutral 텍스트 | 검색 교차 확인 |
| `#0052CC` | **구세대 v1 B400** | 검색 — **쓰지 않는 근거** |

**램프 양 끝점이 검증됐으니 중간 값 신뢰도는 높지만 정본이 아니다.**
→ 🔴 **PR3에서 `atlassian.design/components/tokens/all-tokens` 전수 대조를 D단계 작업으로 넣는다.**

### 5.2 ADS v2 원시 팔레트 (hex 그대로)

```
Blue    100 #E9F2FF · 200 #CCE0FF · 300 #85B8FF · 400 #579DFF · 500 #388BFF
        600 #1D7AFC · 700 #0C66E4 · 800 #0055CC · 900 #09326C · 1000 #082145
Neutral   0 #FFFFFF · 100 #F7F8F9 · 200 #F1F2F4 · 300 #DCDFE4 · 400 #B3B9C4
        500 #8590A2 · 600 #758195 · 700 #626F86 · 800 #44546F · 900 #2C3E5D
       1000 #172B4D · 1100 #091E42
DarkNeutral 100 #161A1D · 150 #1D2125 · 200 #22272B · 250 #282E33 · 300 #2C333A
        350 #38414A · 400 #454F59 · 500 #596773 · 600 #738496 · 800 #8C9BAB
        850 #9FADBC · 900 #B6C2CF · 1000 #C7D1DB · 1100 #DEE4EA
Green   200 #BAF3DB · 300 #7EE2B8 · 400 #4BCE97 · 600 #22A06B · 700 #1F845A · 800 #216E4E · 900 #164B35
Red     200 #FFD5D2 · 300 #FF9C8F · 400 #F87168 · 600 #E34935 · 700 #C9372C · 800 #AE2E24 · 900 #601E16
Yellow  200 #F8E6A0 · 300 #F5CD47 · 400 #E2B203 · 600 #B38600 · 700 #946F00 · 800 #7F5F01 · 900 #533F04
Purple  200 #DFD8FD · 300 #B8ACF6 · 400 #9F8FEF · 600 #8270DB · 700 #6E5DC6 · 800 #5E4DB2 · 900 #352C63
Teal    400 #6CC3E0 · 600 #2898BD · 700 #227D9B · 900 #164555
Orange  400 #FAA53D · 600 #D97008 · 700 #B65C02 · 900 #5F3811
Magenta 400 #E774BB · 700 #AE4787       Lime 400 #94C748 · 700 #5B7F24
```

### 5.3 시맨틱 토큰 — 라이트 / 다크

| 토큰 | 라이트 | 다크 | 비고 |
|---|---|---|---|
| `--surface` | `#FFFFFF` | `#1D2125` | |
| `--surface-sunken` | `#F7F8F9` | `#161A1D` | **사이드바** |
| `--surface-raised` | `#FFFFFF` | `#22272B` | 카드 |
| `--surface-overlay` | `#FFFFFF` | `#282E33` | 모달/팝오버 |
| `--text` | `#172B4D` | `#B6C2CF` | |
| `--text-subtle` | `#44546F` | `#9FADBC` | |
| `--text-subtlest` | `#626F86` | `#8C9BAB` | 메타/라벨 |
| `--brand` | `#0C66E4` | `#579DFF` | **primary** |
| `--brand-text` | `#FFFFFF` | `#1D2125` | ★ 다크는 **어두운** 글자 |
| `--link` | `#0C66E4` | `#579DFF` | |
| `--border` | `#091E4224` | `#A6C5E229` | 알파 |
| `--bg-neutral` | `#091E420F` | `#A1BDD914` | |
| `--bg-neutral-hover` | `#091E4224` | `#A6C5E229` | |
| `--bg-selected` | `#E9F2FF` | `#1C2B41` | 사이드바 활성 |
| `--text-selected` | `#0C66E4` | `#579DFF` | |

**★ 신규 시맨틱 4쌍 — 하드코딩 색 141건의 착륙지점**

| 토큰 | 라이트 bg / text / border | 다크 bg / text / border |
|---|---|---|
| `--warning-*` | `#F8E6A0` / `#533F04` / `#E2B203` | `#533F04` / `#F5CD47` / `#946F00` |
| `--success-*` | `#BAF3DB` / `#216E4E` / `#4BCE97` | `#164B35` / `#7EE2B8` / `#1F845A` |
| `--danger-*` | `#FFD5D2` / `#AE2E24` / `#F87168` | `#601E16` / `#FF9C8F` / `#C9372C` |
| `--info-*` | `#E9F2FF` / `#0055CC` / `#579DFF` | `#092957` / `#85B8FF` / `#0C66E4` |
| `--discovery-*` | `#DFD8FD` / `#5E4DB2` | `#352C63` / `#B8ACF6` |

**amber 계열이 141건 중 ~70건(절반)**이라 사실상 `--warning` 하나가 대부분을 흡수한다. `bg-amber-950`/`text-amber-200` 같은 **손수 짠 `dark:` 페어**는 토큰이 다크를 자동 처리하므로 **`dark:` 변형 자체가 삭제**된다 → 순 LOC 감소.

### 5.4 타이포그래피

**폰트 스택 — Atlassian Sans를 쓰지 않는 이유.** ① Artifact/앱 CSP가 폰트 CDN을 차단 ② **한글 글리프가 없다.** Jira가 수년간 실제로 쓴 시스템 스택이 정확하면서 로딩 비용 0이다.

```css
--font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", "Apple SD Gothic Neo",
             "Malgun Gothic", Roboto, "Noto Sans KR", "Helvetica Neue", sans-serif;
/* --font-mono 는 현행 유지 — AQL overlay↔textarea 정렬 정본 */
```

| 역할 | 크기/행간 | 굵기 | 비고 |
|---|---|---|---|
| 페이지 제목 | 20 / 24 | 600 | `PageHeader` h1 |
| 이슈 요약 제목 | 24 / 30 | 600 | |
| 섹션 제목 | 16 / 20 | 600 | |
| 본문 | **14 / 20** | 400 | 기본 |
| 보조 | 12 / 16 | 400 | `--text-subtlest` |
| **섹션 라벨** | 11 / 16 | 700 | `letter-spacing:.08em; text-transform:uppercase` — **Atlassian 특유의 신호** |

### 5.5 라운드 — ★ calc 파생 폐기

현재 `--radius: 0.625rem` + `--radius-sm: calc(var(--radius) * 0.6)` 구조에 ADS 3px을 넣으면 **`1.8px` 쓰레기 파생**이 나온다. 명시 나열한다.

```css
--r-xs: 2px;  --r-sm: 3px;  --r-md: 4px;  --r-lg: 8px;  --r-xl: 12px;  --r-full: 9999px;
```

| 값 | 용도 |
|---|---|
| 3px | 버튼 · 로젠지 · 입력 |
| 4px | 카드 · 패널 |
| 8px | **사이드바 항목** (신형 네비 스타일) |
| full | 아바타 · 카운트 뱃지 |

### 5.6 간격 — 4px 그리드 (Tailwind 기본 유지, 커스텀 없음)

`2 / 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48px`

## 6. 컴포넌트 계층 (shadcn/Radix 매핑)

**PR2 신설 15종 — 소비자 0, 순수 추가라 위험 0.**

| 컴포넌트 | Radix primitive | 흡수 대상 |
|---|---|---|
| **`dialog`** | `Dialog` | **35~37파일의 직접 import + Overlay 문자열 복붙** |
| `table` | — (순수) | `<table>` 직접 작성 10파일 |
| `badge` (로젠지) | — | 상태/타입 표시 산재 |
| `tabs` | `Tabs` | 이슈 상세 활동(PR19) — **뷰 전환엔 쓰지 말 것** |
| `tooltip` | `Tooltip` | |
| `checkbox` | `Checkbox` | |
| `popover` | `Popover` | |
| `skeleton` | — | board.tsx·dashboards.tsx **인라인 중복 2곳** |
| `separator` | `Separator` | |
| `textarea` | — | 원시 `<textarea>` |
| `switch` | `Switch` | |
| `radio-group` | `RadioGroup` | |
| `scroll-area` | `ScrollArea` | |
| `empty-state` | — | `FilteredEmptyState` **중복 정의 2곳** + "…없습니다" 41파일 |
| `command` | `cmdk` | `CommandPalette`·`LabelAutocompleteInput` 직접 사용 |

> 🔒 **개별 `@radix-ui/react-*` 추가 설치 금지.** 통합 `radix-ui` 1.4.3에서 import.
> 🔒 **`role="dialog"` 보존** — e2e 147건이 `DialogPrimitive.Content`의 role에 의존. 래퍼가 같은 primitive를 감싸면 **DOM 계약 불변**이라 흡수는 안전하다.

## 7. 상태 매트릭스 (7종)

| 상태 | 표현 |
|---|---|
| default | 위 토큰 그대로 |
| hover | `--bg-neutral-hover` 배경. 버튼은 `--brand-hover`(라이트 Blue800 / 다크 Blue300) |
| active/pressed | `--bg-neutral-press` |
| focus-visible | `outline: 2px solid --border-focus; outline-offset: 2px` — **모든 대화형 요소 필수** |
| selected | `--bg-selected` + `--text-selected` + `font-weight:600` |
| disabled | `opacity: .5; cursor: not-allowed` + `--text-disabled` |
| loading | `skeleton` 프리미티브 (인라인 재정의 금지) |
| error | `--danger-*` + 텍스트 메시지 (색만으로 표현하지 않음) |
| empty | `empty-state` 프리미티브 |

## 8. 반응형

데스크탑 우선(사내 협업 도구). Tailwind 기본 브레이크포인트.

| 폭 | 동작 |
|---|---|
| ≥1024px | 기본 — 사이드바 264px 고정 + 이슈 상세 2컬럼 |
| <1024px | 이슈 상세 1컬럼(메타 패널이 본문 아래로) |
| <900px | 사이드바 오버레이(`sheet`)로 전환 |

**모든 폭에서 body 가로 스크롤 금지.** 테이블·보드는 자기 컨테이너에 `overflow-x: auto`.

## 9. 접근성 (WCAG 2.1 AA)

- **대비.** 본문 4.5:1 / 큰 텍스트 3:1. ADS 팔레트가 이 조합으로 검증돼 있으나 **PR3에서 실측 필수** — 특히 `--brand-text` 다크(어두운 글자 on Blue400)와 `.mention`(`--primary` on `--accent`).
- **색만으로 구분하지 않음** (1.4.1) — 우선순위/타입은 **글리프 형태**로도 구분(§4.2/4.3). 상태 로젠지는 **텍스트 라벨** 동반.
- **키보드.** 모든 대화형 요소에 `focus-visible` 링. 사이드바는 Tab 순서가 시각 순서와 일치. 사이드바 접기 단축키 제공.
- **ARIA.** 🔒 `aria-label` 4종 보존(§3.1) · `<h1>` 단독(e2e 34건) · `role="dialog"`(147건) · 아이콘 전용 버튼은 `aria-label` 필수.
- **`prefers-reduced-motion`** 존중.

## 10. 상호작용 요약

| 대상 | 동작 |
|---|---|
| 사이드바 프로젝트 트리 | 클릭 = 확장/축소, 항목 클릭 = 라우트 이동 |
| 뷰 전환 | 🔒 **`<Link>` — 라우트 이동. Tabs 아님** (뒤로가기 보존) |
| 이슈 상세 활동 | **Radix Tabs** — 같은 라우트 내 패널 전환 |
| 이슈 테이블 행 | 클릭 = 상세 이동. 체크박스는 이벤트 전파 차단 |
| 보드 카드 | @dnd-kit 드래그. `sortable` 도입 후 컬럼 내 순서 변경(PR21) |

## 11. i18n 카피

**기존 파일 재사용** — `i18n/ko.ts`(`priorityNames`) · `issue-filter-labels.ts`/`board-filter-labels.ts`(PR17에서 **일원화**).

신규 필요 (셸 — PR11에서 `i18n/nav-labels.ts` 신설).

```ts
export const navLabels = {
  mainNav: '메인 메뉴',        // 🔒 e2e 계약 — 글자 변경 금지
  adminNav: '관리 메뉴',       // 🔒 e2e 계약
  projectViewNav: '프로젝트 뷰 전환', // 🔒 e2e 계약
  search: '검색',              // 🔒 Header.tsx:124 단일. 사이드바 중복 금지
  create: '만들기',            // e2e 정확 일치 0건 — 안전
  myWork: '내 작업', recent: '최근', starred: '즐겨찾기',
  projects: '프로젝트', dashboards: '대시보드', calendar: '캘린더', filters: '필터',
  admin: '관리', more: '더 보기',
  collapseSidebar: '사이드바 접기', expandSidebar: '사이드바 펼치기',
} as const
```

## 12. DESIGN.md 패치안 (신규 토큰 — **별도 반영 필요, 본 작업에서는 미적용**)

> ⚠️ **이 관례는 한 번도 닫힌 적이 없는 루프다.** `fr-ca-01-calendar.md` §12가 패치안을 제시했지만 `grep 캘린더 DESIGN.md` → **0건**. **PR3에서 반드시 반영한다.**

**DESIGN.md는 재작성 대상이다** — 279 PR 중 커밋 **2건**(#11 최초, #190 AQL). §10~12가 전부 **로그인 폼 1개 기준**이고 `> 본 PR`이 여전히 #11을 가리킨다. 아래는 최소 패치가 아니라 **PR3 재작성의 골격**이다.

| DESIGN.md 절 | 조치 |
|---|---|
| 헤더/서문 | `v0.1` → `v0.2`. "PR #11에 필요한 토큰만" 문구 삭제. `> 본 PR` 지시어 **전부 제거** |
| §2 컬러 토큰 | **전면 교체** — oklch 표 → 본 문서 §5.2/5.3 (hex). 시맨틱 4쌍 신규 등록 |
| §3 타이포 | 폰트 스택 교체(§5.4). Pretendard는 "별도 PR" 명시 |
| §5 라운드 | calc 파생 → 명시 스케일(§5.5) |
| §7 다크 모드 정책 | 🔴 **"본 PR 미지원"이 거짓** — 이미 완전 동작 중. 현행 반영 |
| §9 shadcn 가이드 | "설치 5종" → **22종**(7+15). radix-nova soft destructive 반영 |
| §10~12 | 로그인 폼 기준 서술 전면 재작성. §12 out-of-scope 9개 중 **다크모드·차트·아이콘·모바일**은 상태가 바뀜 |

**§2 뒤에 추가할 신규 섹션 초안.**

```markdown
### 시맨틱 상태 토큰 (FR-UX-06)

하드코딩 Tailwind 팔레트 색(141건/29파일)을 흡수하기 위한 4쌍. amber 계열이 절반(~70건)이라
사실상 `--warning` 하나가 대부분을 흡수한다.

| 토큰 | 라이트 (bg/text/border) | 다크 (bg/text/border) | 용도 |
|---|---|---|---|
| `--warning-*` | #F8E6A0 / #533F04 / #E2B203 | #533F04 / #F5CD47 / #946F00 | 경고 배너·주의 |
| `--success-*` | #BAF3DB / #216E4E / #4BCE97 | #164B35 / #7EE2B8 / #1F845A | 완료 로젠지 |
| `--danger-*`  | #FFD5D2 / #AE2E24 / #F87168 | #601E16 / #FF9C8F / #C9372C | 오류·삭제 |
| `--info-*`    | #E9F2FF / #0055CC / #579DFF | #092957 / #85B8FF / #0C66E4 | 진행 중 로젠지 |

**사용 가이드.** 손수 짠 `dark:` 페어를 쓰지 않는다 — 토큰이 다크를 자동 처리한다.
`bg-amber-50 dark:bg-amber-950` 같은 코드는 `bg-warning`으로 대체하고 `dark:` 변형은 삭제한다.
```

## 13. frontend-engineer 핸드오프 체크리스트

- [x] 모든 색이 토큰으로 정의됨 (임의 색 0)
- [x] 라이트/다크 both 값 제시
- [x] 기존 컴포넌트 재사용 지점 명시 (§2)
- [x] 신규 프리미티브 목록 + Radix 매핑 (§6)
- [x] 상태 매트릭스 7종 (§7)
- [x] 반응형 브레이크포인트 (§8)
- [x] WCAG AA 근거 + **미검증 항목 명시** (§9 — `--brand-text` 다크, `.mention`)
- [x] i18n 키 + **e2e 계약 문자열 표시** (§11)
- [x] DESIGN.md 패치안 (§12)
- [x] 팔레트 **정본 아님** 고지 + 전수 대조 작업 지정 (§5.1)
- [x] 시안 링크 (동작 프로토타입)

## 14. Out of scope / 후속

| 항목 | 사유 |
|---|---|
| **`--chart-1~5` 정의** | 소비자 0 — **실소비 PR22에서** 정의. 미리 채우면 PoC (ADR D7) |
| **Pretendard 한글 웹폰트** | **토큰 PR과 엮지 말 것.** 섞으면 "팔레트 탓"과 "폰트 탓" 구분 불가 |
| **댓글 UI** | 별도 FR (ADR D6) — 백엔드가 읽기 전용이라 온전한 FR 사이클 필요 |
| **프로젝트 생성·목록·설정 화면** | **#277 PR-5**에 양보 (ADR D8). 단 **본 스펙 위에 지어야 한다** |
| 화면별 상세 와이어프레임 | 시안 Artifact가 담당 |
| 모션/애니메이션 가이드 | 후속. 현재는 `prefers-reduced-motion` 존중만 |
| Storybook | 후속 |
