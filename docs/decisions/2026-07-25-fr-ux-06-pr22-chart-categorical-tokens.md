# ADR — FR-UX-06 PR22. 차트·범주색 토큰 정의와 "만들지 않은 토큰"

- **날짜**. 2026-07-25
- **상태**. 채택 (PR #308)
- **관련**. FR-UX-06 ADR §D7(소비자 0인 토큰 선채움 금지) · `DESIGN.md §C-2` · 디자인 스펙 `docs/design/fr-ux-06-jira-redesign.md` §4.2·§5.2 · 값 정본 `docs/plans/2026-07-25-fr-ux-06-pr22-token-values.md`

## 맥락

FR-UX-06 ADR **§D7**은 "소비자 0인 토큰을 미리 채워 넣지 않는다"를 못박았다. 그래서 PR3(ADS v2 팔레트)는
`--chart-1~5`를 shadcn init 산출 OKLCH **회색조 그대로 남기고** 실소비 PR로 넘겼고, `DESIGN.md`
§동결 토큰 표에 "차트/시각화 도입 PR(PR22) 몫"으로 적어 두었다.

PR22는 그 실소비 PR이다. 동시에 화면 전반의 하드코딩 범주색(이슈타입 막대·캘린더 칩·즐겨찾기 별)을
토큰으로 걷어내는 정리 작업을 맡았다.

## 결정

### D22-1. 차트 5종을 ADS v2 core 램프에서 파생해 정의한다

값 출처는 **디자인 스펙 §5.2 ADS 원시 팔레트**다. `atlassian.design`을 직접 조회하지 않았다 —
PR3에서 그 사이트가 JS 렌더링이라 전수 검증에 실패한 전력이 있어, **이미 검증된 사내 정본**에서만 파생했다.

| 토큰 | 라이트 | 다크 | 역할 |
|---|---|---|---|
| `--chart-1` | `#0C66E4` | `#579DFF` | 주 시리즈(Blue) |
| `--chart-2` | `#6E5DC6` | `#9F8FEF` | 보조 시리즈(Purple) |
| `--chart-3` | `#758195` | `#8C9BAB` | 중립·기준선(Neutral) |
| `--chart-4` | `#B65C02` | `#FAA53D` | 경고·지연(Orange) |
| `--chart-5` | `#1F845A` | `#4BCE97` | 완료(Green) |

소비처는 recharts 6종 — 번다운 · CFD(누적흐름도) · Velocity · 처리량 · 리드타임 · 산포도.

**`--chart-1` = `--primary` 동일값을 허용한다.** 차트 막대가 버튼처럼 보일 여지가 있으나,
Atlassian 제품군 관례이고 차트는 카드 경계 + 범례 텍스트로 맥락이 분리된다.
plan-design-review 이슈 3에서 검토 후 **유지** 결정.

**`--chart-5` = 완료(초록) 매핑은 가드가 적발해 정정한 것이다.** 초안의 역할 배정("1=주 시리즈")이
완료 계열까지 흡수해 `--chart-5` 소비처가 0이 됐다. `chart-color-tokens.test.ts`의
"6종이 `--chart-1~5`를 모두 소비한다" 어서션이 이를 잡았고, "완료 = `--chart-5`"로 의미 매핑을 정정했다.
부수 효과로 CFD와 Velocity의 "완료" 계열이 같은 색으로 통일됐다.

### D22-2. 범주색 8종을 정의한다

| 토큰 | 라이트 | 다크 | 소비처 |
|---|---|---|---|
| `--type-epic` | `#6E5DC6` | 동일 | `TimelineRow` 에픽 막대 |
| `--type-story` | `#22A06B` | 동일 | 스토리 막대 |
| `--type-task` | `#0C66E4` | 동일 | 태스크 막대 |
| `--type-bug` | `#C9372C` | 동일 | 버그 막대 |
| `--type-default` | `#758195` | 동일 | 매핑 없는 타입 폴백(Neutral600) |
| `--discovery` / `--discovery-foreground` | `#6E5DC6` / `#FFFFFF` | 동일 | 캘린더 Worklog 칩 |
| `--neutral-bold` / `--neutral-bold-foreground` | `#44546F` / `#FFFFFF` | `#9FADBC` / `#FFFFFF` | 캘린더 TODO 칩 |
| `--favorite` | `#B38600` | 동일 | 즐겨찾기 별 |

**`--neutral-bold` 신설은 기존 결정 역행을 막은 결과다.** 초안은 캘린더 TODO 칩을
`--bg-neutral-solid`(거의 흰색)로 매핑했다. 그런데 `WeekGrid.tsx:76` 주석이 이미
**"옅은 배지는 대비 미달로 배제하고 중간톤 solid fill을 채택했다"**(FR-CA-01 T5 §4.1)를 기록하고 있었다.
그대로 갔으면 흰 배경에서 칩이 소멸하며 과거 결정을 정면으로 되돌렸을 것이다.
plan-design-review가 **BLOCKER급**으로 잡아 `--neutral-bold`(라이트/다크 모두 **7.65:1**) 신설로 해소했다.

**`--favorite`는 ADS Yellow400을 기각했다.** Yellow400은 흰 배경에서 **1.98:1**로 WCAG 1.4.11(3:1) 미달이다.
Yellow600 `#B38600`을 채택했다.

### D22-3. `--prio-*` 5종과 `--type-subtask`는 **만들지 않는다**

초안 계획에는 있었으나 **실측 소비처가 0**이었다. 우선순위는 `routes/search.tsx:181`과
`IssueMetaPanel.tsx:271`에서 `text-muted-foreground` **글자**로만 표시되고, 색 배지가 아니다.
서브태스크 타입 막대도 소비처가 없다.

만들면 **PR22가 ADR §D7을 스스로 위반**한다. 그래서 정의하지 않고, `state-tokens.test.ts`의
`UNCONSUMED_TOKENS` **음성 테스트로 미정의를 고정**했다 — 누군가 나중에 선채움하면 테스트가 깨진다.

디자인 스펙 §4.2에는 `type-subtask` 라이트 후보값 `#579DFF`가 **2.74:1로 1.4.11 미달**임을
경고 주석으로 남겼다(미래 소비 PR이 그 값을 그대로 쓰지 않도록).

## 근거

- **§D7 자기준수.** "소비처 있는 것만 정의"를 이 PR 스스로 지켰고, 그 준수를 테스트로 고정했다.
- **값 지어내기 방지.** PR3 선례를 따라 값 정본 문서(`*-token-values.md`)를 먼저 쓰고 코드가 그걸 참조한다.
- **접근성 결함 동시 수정.** 교체 전 하드코딩 차트 색 6종 중 **3종이 이미 WCAG 1.4.11 미달**이었다
  (`#f59e0b` 2.15:1 · `#94a3b8` 2.56:1 · `#cbd5e1` 1.48:1). 토큰화가 결함 수정을 겸한다.
- **실브라우저 검증.** SVG presentation attribute가 CSS 변수를 해석하는지 확인했다
  (라이트 `rgb(12,102,228)` / `.dark` 스코프 `rgb(87,157,255)`). 계획에 있던 `fill-chart-N` 우회안은 불필요했다.

## 결과

- `apps/web/src/index.css` — `:root` / `.dark` / `@theme inline` 3곳에 토큰 배선
- 가드 4종 — `state-tokens.test.ts`(차트 5 + 범주 10 + 음성 6) · `chart-color-tokens.test.ts` ·
  `category-color-tokens.test.ts` · `skeleton-usage.test.ts`. 전부 **개수가 아니라 목록 전수 비교**
- `DESIGN.md` — §동결 토큰 표에서 `--chart-*` 행 해제, §C-2 절 신설
- 잔여 하드코딩 Tailwind 리터럴 색은 **사전존재 1건뿐** — `components/workflow/WorkflowDiagram.tsx`
  (주석 문자열이고 mermaid가 `var()` 미지원이라 PR4가 이미 OUT 판정)

## 대안 (기각)

| 대안 | 기각 사유 |
|---|---|
| `atlassian.design` 전수 조회로 값 확정 | PR3에서 JS 렌더링 때문에 실패한 전력. 사내 검증 정본(§5.2)이 이미 있다 |
| `--chart-1`을 `--primary`와 다른 파랑으로 분리 | Atlassian 관례 이탈 + 램프에서 근거 없는 값을 새로 만들어야 한다 |
| `--prio-*`를 미리 정의하고 나중에 소비 | ADR §D7 정면 위반. 소비 PR이 그때 정의하는 것이 규칙이다 |
| 캘린더 TODO 칩을 옅은 배지로 | `WeekGrid.tsx` 기존 결정 역행 + 흰 배경에서 칩 소멸 |
