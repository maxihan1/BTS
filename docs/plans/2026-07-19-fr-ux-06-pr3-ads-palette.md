# FR-UX-06 Phase 0 PR3 — ADS v2 팔레트 교체 + 시맨틱 토큰 + DESIGN.md 재작성

> slug: fr-ux-06-pr3-ads-palette
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-19
> 정본 plan: [docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md](2026-07-17-fr-ux-06-jira-redesign/plan.md) §PR3
> checklist: [docs/plans/2026-07-17-fr-ux-06-jira-redesign/checklist.md](2026-07-17-fr-ux-06-jira-redesign/checklist.md) Phase0 PR3
> **팔레트 값 표(정본): [2026-07-19-fr-ux-06-pr3-ads-palette-values.md](2026-07-19-fr-ux-06-pr3-ads-palette-values.md)** — impl은 이 파일의 hex를 그대로 쓴다(지어내기 금지)

## Brief

FR-UX-06 개편의 색을 완성하는 PR. `apps/web/src/index.css`의 무채색 shadcn 기본 팔레트를 **ADS v2**로 전면 교체하고, 시맨틱 토큰 4쌍을 신설하며, **PR2에서 alias로 배선한 §7 상태토큰 11종에 실제 ADS 값**을 부여한다. DESIGN.md 화석도 재작성한다.

**PR2와의 관계.** PR2가 §7 토큰을 `var(--muted)` 같은 alias로 배선했고, 프리미티브가 이미 그 토큰을 소비 중이다. PR3가 값만 ADS로 바꾸면 프리미티브 코드 변경 0으로 개편이 완성된다("배선 PR2 · 값 PR3").

## 팔레트 정본

`@atlaskit/tokens@1.4.2` (npm, Apache-2.0, 재현가능). 5점 앵커(Blue100 #E9F2FF·Blue1000 #082145·본문 #172B4D·Blue700 #0C66E4·v1 #0052CC 부재) 전부 교차검증 통과. 전체 33토큰 라이트+다크 값은 **팔레트 값 표 파일** 참조.

## Maxi 확정 4결정 (게이트 전 확정 — 재논의 방지)

| # | 결정 | 값 |
|---|---|---|
| 1 | **warning = ADS 정본 Orange** (plan "Yellow" 표기는 착오) | 라이트 Orange700 `#B65C02` · 다크 Yellow400 `#E2B203` |
| 2 | **text-subtle/subtlest/disabled = ADS 실제 매핑 스텝** (plan 지정보다 1~2스텝 어두워 대비 좋음) | impl이 `tokens-raw/atlassian-{light,dark}.js`의 `color.text.subtle/subtlest/disabled` 실제 라이트+다크 쌍 재확인. disabled는 알파→**솔리드 등가** |
| 3 | **primary 다크 = shadcn 관례 유지** (ADS 다크 Blue400+어두운글자 기각) | 다크 `--primary` `#0C66E4` + `--primary-foreground` `#FFFFFF` (PR2 프리미티브 호환) |
| 4 | **알파→솔리드 등가** (`--secondary`/`--border`/`--input`), **ring/border-focus 통일** | ring = border-focus = ADS Blue500 라이트 `#388BFF` / Blue300 다크 `#85B8FF` |

## checklist 필수 (정본 checklist Phase0 PR3)

- **oklch → hex 전환** — 현재 `:root`는 oklch, ADS는 hex. 공식문서 대조 가능하게 hex로.
- **`--radius` calc 파생 폐기** → `2/3/4/8/12px` 명시 나열 (현재 `--radius-sm: calc(var(--radius)*0.6)` → ADS 3px 넣으면 `1.8px` 쓰레기 파생)
- **라이트·다크 both** — 다크 이미 동작 중. 안 하면 대비 붕괴. `:root`와 `.dark` 양쪽.
- 🔒 **`--chart-1~5` 절대 미변경** (PR22 몫, 소비자 0)
- 🔒 **`--syntax-*` 5종·`--font-mono` 절대 미변경** (AQL 정렬 정본) · `.mention` 대비 PR3 후 재확인
- **DESIGN.md 재작성** — 화석(27KB, §10~12가 로그인폼 기준·커밋 #11/#190뿐). ADS 팔레트 + 프리미티브 15종(PR2) 기준으로 재작성.

## 🔒 검증 계약

- **state-tokens.test.ts 갱신 필요** — PR2가 §7 토큰을 `var(--…)` alias 형태로 **강제**한다. PR3가 실제 hex를 넣으면 이 테스트가 깨진다 → "실제 값(hex 또는 var) 허용, `:root`/`.dark` 양쪽 정의"로 갱신(TDD: 갱신을 RED로 먼저).
- **기존 test 회귀 0** — 프리미티브가 소비하는 §7 토큰 값이 바뀌어도 유닛 7226+ 전부 통과.
- **대비비(WCAG AA) 수동 체크** — 자동검증 불가 유일 PR. text on bg, 시맨틱 on 흰/검 페어링.

## 도메인 정리 (← /bts-domain, 스킵 — #279 ADR 재사용)

## 스펙 (← /bts-spec, 스킵 — 디자인 스펙 §5(색)·checklist PR3 재사용)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan, 스킵 — 정본 plan 이미 리뷰됨)
