# FR-UX-06 PR22 확정 토큰 값 — 정본

> **이 문서가 값의 정본이다.** `apps/web/src/index.css`와
> `apps/web/src/components/ui/__tests__/state-tokens.test.ts`의 값을 바꾸려면
> **이 문서를 같은 커밋에서 함께 바꿔야 한다** — 값 지어내기 방지 가드.
> PR3 정본(`2026-07-19-fr-ux-06-pr3-ads-palette-values.md`)의 같은 규약을 승계한다.

- 대상 PR. #308 (FR-UX-06 Phase 5 PR22)
- 근거 ADR. `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` **§D7** — *"`--chart-1~5`는 실소비 PR에서 정의한다"*
- 출처 팔레트. `docs/design/fr-ux-06-jira-redesign.md` **§5.2 ADS v2 원시 팔레트** (PR3가 검증한 램프)
- 외부 조회 없음. `atlassian.design`은 JS 렌더링이라 PR3에서 전수 검증에 실패한 전력이 있어,
  **이미 검증을 마친 램프에서만 파생**한다 (Maxi 결정 D2, 2026-07-25).

## 1. 대비 계산 기준

| 항목 | 값 |
|---|---|
| 라이트 배경 | `--card` = `#FFFFFF` |
| 다크 배경 | `--card` = `#1D2125` |
| 판정 기준 (색 면적) | WCAG 2.1 **1.4.11 비텍스트 대비 3:1** |
| 판정 기준 (bold 위 글자) | WCAG 2.1 **1.4.3 AA 4.5:1** |
| 계산식 | WCAG relative luminance (sRGB, `c/12.92` / `((c+0.055)/1.055)^2.4`) |

## 2. 차트 토큰 5종 — recharts 가젯 6종이 소비

역할 배정 (의미 기반 — 구현 실측 후 확정).

| 토큰 | 역할 | 소비처 |
|---|---|---|
| `--chart-1` | **일반 주 지표** (완료/진행 의미가 없는 단일 지표) | Burndown 실측선 · CycleTime BoxPlot · CycleTime Histogram · Worklog 막대 |
| `--chart-2` | **강조 참조선** | Velocity 평균완료선 |
| `--chart-3` | **중립** (계획·범위·할 일) | Burndown 스코프선 · CFD todo · Velocity 계획막대 + 평균계획선 |
| `--chart-4` | **진행 중 / 이상선** | Burndown 이상선 · CFD inProgress |
| `--chart-5` | **완료** | CFD done · Velocity 완료막대 |

★ 초안은 `--chart-1`을 "주 시리즈"로 두고 완료 계열까지 흡수시켰는데, 그러면 **`--chart-5`를
아무도 소비하지 않는다** — 정의만 하고 안 쓰는 토큰이 생겨 ADR D7을 다시 어기게 된다.
구현 중 발견해 "완료 = `--chart-5`(초록)" 의미 매핑으로 정정했고, 그 결과 CFD와 Velocity에서
**"완료"가 같은 색으로 통일**되는 부수 효과도 얻었다. `chart-color-tokens.test.ts`의
"6종이 `--chart-1~5`를 모두 소비한다" 어서션이 이 미소비를 잡아낸 판별자다.

| 토큰 | 라이트 | 램프 | 대비 | 다크 | 램프 | 대비 |
|---|---|---|---|---|---|---|
| `--chart-1` | `#0C66E4` | Blue 700 | 5.20:1 | `#579DFF` | Blue 400 | 5.92:1 |
| `--chart-2` | `#6E5DC6` | Purple 700 | 5.19:1 | `#9F8FEF` | Purple 400 | 5.90:1 |
| `--chart-3` | `#758195` | Neutral 600 | 3.94:1 | `#8C9BAB` | DarkNeutral 800 | 5.70:1 |
| `--chart-4` | `#B65C02` | Orange 700 | 4.65:1 | `#FAA53D` | Orange 400 | 8.11:1 |
| `--chart-5` | `#1F845A` | Green 700 | 4.66:1 | `#4BCE97` | Green 400 | 8.16:1 |

### 2.1 교체 대상 — 현재 하드코딩 6종 → 토큰 5종

| 현재 hex | 라이트 대비 | 판정 | 새 토큰 | 쓰이던 곳 |
|---|---|---|---|---|
| `#6366f1` | 4.47:1 | OK | `--chart-1` | Burndown 실측 · BoxPlot accent · Histogram 막대 · Worklog 막대 |
| `#6366f1` | 4.47:1 | OK | `--chart-5` | CFD done · Velocity 완료 (완료 의미로 재배정) |
| `#f59e0b` | **2.15:1** | **미달** | `--chart-4` | Burndown 이상선 · CFD 진행중 |
| `#94a3b8` | **2.56:1** | **미달** | `--chart-3` | Burndown 스코프선 · Velocity 계획 |
| `#cbd5e1` | **1.48:1** | **미달** | `--chart-3` | CFD TODO |
| `#475569` | 7.58:1 | OK | `--chart-3` | Velocity 평균계획 |
| `#4338ca` | 7.90:1 | OK | `--chart-2` | Velocity 평균완료 |

★ **현재 6종 중 3종이 이미 1.4.11 미달**이다. 이 교체는 토큰화이자 **접근성 결함 수정**이다.

★ Velocity의 `commitment`(막대)와 `avgCommitment`(평균선)가 둘 다 `--chart-3`이 된다.
평균선은 `strokeDasharray`로 형태가 다르므로 1.4.1(색만으로 구분 금지)은 충족한다.
시각 구분을 위해 평균선의 `strokeWidth`는 유지한다.

## 3. 범주 토큰 10종 — 실소비처가 있는 것만

| 토큰 | 라이트 | 램프 | 다크 | 램프 | 소비처 |
|---|---|---|---|---|---|
| `--type-epic` | `#6E5DC6` | Purple 700 | `#9F8FEF` | Purple 400 | `TimelineRow` epic 막대 |
| `--type-story` | `#22A06B` | Green 600 | `#4BCE97` | Green 400 | `TimelineRow` story 막대 |
| `--type-task` | `#0C66E4` | Blue 700 | `#579DFF` | Blue 400 | `TimelineRow` task 막대 |
| `--type-bug` | `#C9372C` | Red 700 | `#F87168` | Red 400 | `TimelineRow` bug 막대 |
| `--type-default` | `#758195` | Neutral 600 | `#8C9BAB` | DarkNeutral 800 | `TimelineRow` 미지 타입 폴백 |
| `--discovery` | `#6E5DC6` | Purple 700 | `#9F8FEF` | Purple 400 | `WeekGrid` Worklog 칩 |
| `--discovery-foreground` | `#FFFFFF` | — | `#161A1D` | — | 같은 칩의 글자 |
| `--neutral-bold` | `#44546F` | Neutral 800 | `#9FADBC` | DarkNeutral 850 | `WeekGrid` TODO 칩 |
| `--neutral-bold-foreground` | `#FFFFFF` | — | `#161A1D` | — | 같은 칩의 글자 |
| `--favorite` | `#B38600` | Yellow 600 | `#F5CD47` | Yellow 300 | `FavoriteButton` 별 채움 |

`--type-*` 4종은 디자인 스펙 **§4.2 표와 정확히 일치**한다.

### 3.1 대비 실측

| 토큰 | 라이트 | 다크 | 기준 |
|---|---|---|---|
| `--type-epic` | 5.19:1 | 5.90:1 | 1.4.11 (3:1) |
| `--type-story` | 3.33:1 | 8.16:1 | 1.4.11 |
| `--type-task` | 5.20:1 | 5.92:1 | 1.4.11 |
| `--type-bug` | 5.16:1 | 5.83:1 | 1.4.11 |
| `--type-default` | 3.94:1 | 5.70:1 | 1.4.11 |
| `--discovery` + foreground | 5.19:1 | 6.38:1 | **1.4.3 (4.5:1)** — bold 위 글자 |
| `--neutral-bold` + foreground | 7.65:1 | 7.65:1 | **1.4.3** — bold 위 글자 |
| `--favorite` | 3.32:1 | 10.57:1 | 1.4.11 |

### 3.2 값 선정에서 갈린 두 지점

**`--neutral-bold` (캘린더 TODO 칩).** 초안은 `--bg-neutral-solid`(라이트 `#F7F8F9`)로 매핑했으나,
`WeekGrid.tsx`의 기존 결정 주석 *"옅은 배지 대비 미달로 배제, 중간톤 solid fill 채택"*
(FR-CA-01 T5 §4.1)을 **정면으로 되돌리는 것**이었다. plan-design-review 이슈 1로 적발.
중간톤 solid를 지키는 Neutral 800으로 정정했고, 흰 글자 대비 **7.65:1**은 현재
`bg-slate-600`(7.58:1)과 동등하다.

**`--favorite` (즐겨찾기 별).** ADS Yellow400 `#E2B203`은 흰 배경 **1.98:1**로 미달이다
(현재 `text-yellow-400` `#FACC15`는 1.53:1로 더 나쁘다). 별의 채움 상태가 의미를 가지므로
1.4.11 대상이라 판단해 **Yellow600 `#B38600`(3.32:1)** 을 채택했다.

## 4. 일부러 만들지 않은 토큰 — ADR D7 자기준수

| 토큰 | 스펙 근거 | 미신설 사유 |
|---|---|---|
| `--prio-highest` ~ `--prio-lowest` (5종) | 디자인 스펙 §4.3에 값 확정됨 | **소비처 0.** 우선순위는 `routes/search.tsx:181`·`IssueMetaPanel.tsx:271` 등에서 `text-muted-foreground` 글자로만 표시되고 색 구분이 없다 |
| `--type-subtask` | 디자인 스펙 §4.2에 값 확정됨 | **소비처 0.** subtask 타입에 색을 쓰는 코드가 없다. ★ 게다가 스펙의 라이트값 `#579DFF`는 **2.74:1로 1.4.11 미달**이라 실소비 PR에서 값 재검토가 필요하다 |

*"소비자가 없는 토큰을 미리 채우면 그게 PoC"* (ADR §D7). PR22가 그 규칙을 세운 당사자이므로
스스로 어기지 않는다. `state-tokens.test.ts`의 `UNCONSUMED_TOKENS` 음성 테스트가 이를 고정한다.

## 5. 동결 상태 변경

| 토큰군 | PR3~PR21b | PR22 | 사유 |
|---|---|---|---|
| `--chart-1~5` | 🔒 동결 (oklch 무채색) | **해제 → 확정 hex** | 실소비자(recharts 6종) 확보. ADR D7 이행 |
| `--syntax-*` 5종 | 🔒 동결 | 🔒 **동결 유지** | AQL textarea와 overlay `<pre>`가 같은 값을 참조해야 정렬이 깨지지 않는다 (`DESIGN.md` 동결 계약) |
| `--sidebar-*` 8종 | PR11에서 해제됨 | 변경 없음 | — |
