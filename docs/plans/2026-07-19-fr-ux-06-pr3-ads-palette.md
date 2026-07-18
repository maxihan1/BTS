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

> **For agentic workers:** 이 plan은 bts-impl이 task 메타(agent/files/depends-on)로 wave를 계산해 dispatch한다. 값은 전부 아래 §확정 팔레트 표가 정본 — **impl이 새 값을 지어내는 것 금지.** 개수("33토큰")를 믿지 말고 표를 전수 열거해 직접 셀 것.

**Goal.** `apps/web/src/index.css`의 무채색 shadcn 팔레트를 ADS v2 hex로 전면 교체하고(§7 상태토큰 11종 실제값 + 시맨틱 4종 신설 + radius 명시화), 검증 테스트를 정확-hex 가드로 강화하고, DESIGN.md를 재작성한다.

**Architecture.** 단일 파일(index.css) 값 교체이므로 TDD 축은 "state-tokens.test.ts를 정확 hex 어서션으로 강화(RED) → index.css 교체(GREEN)". 문서 2건(DESIGN.md·values.md/checklist)이 후행. 파일 겹침이 없어 wave 4개로 직렬 위주 진행(단일 파일 특성 + [[worktree-lint-staged-shared-git-stash-collision]] 완화).

**Tech Stack.** Tailwind v4 CSS-first(`@theme inline`), vitest(파일 정적 검증), `@atlaskit/tokens@1.4.2`(팔레트 정본).

---

### 확정 팔레트 (plan 단계에서 전수 확정 — impl은 이 표만 사용)

plan 단계에서 Maxi 결정 2의 "impl이 tokens-raw로 재확인" 지시를 **이행 완료**했다. `tokens-raw/atlassian-{light,dark}.js`(jsdelivr, 1.4.2) 실측 결과: `color.text.subtle` = Neutral800 `#44546F` / DarkNeutral800 `#9FADBC`, `color.text.subtlest` = Neutral700 `#626F86` / DarkNeutral700 `#8696A7`, `color.text.disabled` = Neutral400A(알파) → **솔리드 등가** Neutral400 `#B3B9C4` / DarkNeutral400 `#454F59`. values.md 부록 팔레트와 전수 일치.

**§A shadcn 18토큰** (라이트 / 다크)

| 토큰 | 라이트 | 다크 | 근거 |
|---|---|---|---|
| `--background` | `#FFFFFF` | `#161A1D` | elevation.surface |
| `--foreground` | `#172B4D` | `#C7D1DB` | color.text |
| `--card` | `#FFFFFF` | `#1D2125` | elevation.surface.raised |
| `--card-foreground` | `#172B4D` | `#C7D1DB` | color.text |
| `--popover` | `#FFFFFF` | `#22272B` | elevation.surface.overlay |
| `--popover-foreground` | `#172B4D` | `#C7D1DB` | color.text |
| `--primary` | `#0C66E4` | `#0C66E4` | **Maxi 결정 3** — 다크도 Blue700 유지 |
| `--primary-foreground` | `#FFFFFF` | `#FFFFFF` | **Maxi 결정 3** |
| `--secondary` | `#F1F2F4` | `#22272B` | 알파→솔리드 등가 (결정 4) |
| `--secondary-foreground` | `#172B4D` | `#C7D1DB` | color.text |
| `--muted` | `#F7F8F9` | `#1D2125` | Neutral100 / DarkNeutral100 |
| `--muted-foreground` | `#626F86` | `#8696A7` | color.text.subtlest 실측 |
| `--accent` | `#F1F2F4` | `#22272B` | Neutral200 / DarkNeutral200 |
| `--accent-foreground` | `#172B4D` | `#C7D1DB` | color.text |
| `--destructive` | `#CA3521` | `#F87462` | background.danger.bold |
| `--border` | `#DCDFE4` | `#2C333A` | 알파→솔리드 등가 (결정 4) |
| `--input` | `#DCDFE4` | `#2C333A` | 알파→솔리드 등가 (결정 4) |
| `--ring` | `#388BFF` | `#85B8FF` | **Maxi 결정 4** — border-focus와 통일 |

**§7 상태토큰 11종** (라이트 / 다크)

| 토큰 | 라이트 | 다크 | 근거 |
|---|---|---|---|
| `--bg-neutral` | `#F7F8F9` | `#1D2125` | Neutral100 / DarkNeutral100 |
| `--bg-neutral-hover` | `#F1F2F4` | `#22272B` | Neutral200 / DarkNeutral200 |
| `--bg-neutral-press` | `#DCDFE4` | `#2C333A` | Neutral300 / DarkNeutral300 |
| `--bg-selected` | `#E9F2FF` | `#082145` | Blue100 / Blue1000 |
| `--text-selected` | `#0C66E4` | `#579DFF` | Blue700 / Blue400 |
| `--text-subtle` | `#44546F` | `#9FADBC` | **결정 2** — ADS 실측 Neutral800 / DarkNeutral800 |
| `--text-subtlest` | `#626F86` | `#8696A7` | **결정 2** — ADS 실측 Neutral700 / DarkNeutral700 |
| `--text-disabled` | `#B3B9C4` | `#454F59` | **결정 2** — 알파→솔리드 Neutral400 / DarkNeutral400 |
| `--border-focus` | `#388BFF` | `#85B8FF` | **결정 4** — ring과 동일값 통일 |
| `--brand-hover` | `#0055CC` | `#85B8FF` | Blue800 / Blue300 |
| `--brand-text` | `#0C66E4` | `#579DFF` | Blue700 / Blue400 |

**§C 시맨틱 4종 신설** (라이트 / 다크) — `@theme inline`에 `--color-*` 배선 포함(PR4가 `bg-warning` 등으로 소비)

| 토큰 | 라이트 | 다크 | 근거 |
|---|---|---|---|
| `--warning` | `#B65C02` | `#E2B203` | **Maxi 결정 1** — ADS Orange700 / Yellow400 |
| `--success` | `#1F845A` | `#4BCE97` | background.success.bold |
| `--danger` | `#CA3521` | `#F87462` | background.danger.bold |
| `--info` | `#0C66E4` | `#579DFF` | background.information.bold |

**radius** — calc 파생 폐기, `@theme inline`에 명시 나열. `--radius-xs: 2px` · `--radius-sm: 3px` · `--radius-md: 4px` · `--radius-lg: 8px` · `--radius-xl: 12px`. `--radius-2xl/3xl/4xl`은 **삭제**(소비자 0 — `rounded-2xl|3xl|4xl` grep 0건 확인), `:root`의 `--radius: 0.625rem`도 **삭제**(calc 폐기 후 소비자 0). `button.tsx`의 `rounded-[min(var(--radius-md),10px)]`은 md 8px→4px로 min 결과 4px — ADS 버튼 라운드에 근접(의도된 시각 변화).

**미변경(🔒)** — `--chart-1~5`·`--syntax-*` 5종·`--font-mono`는 그대로. `--sidebar-*` 8종도 **미변경** — 소비자 0이라 chart와 동일 논리(지금 채우면 죽은 값), PR11(사이드바)에서 ADS 값과 함께 채택.

### 대비 검증 (plan 단계 계산 완료 — WCAG AA)

전 페어 계산 결과(상대휘도 공식, 소스는 세션 스크립트) — **`.mention` 다크만 미달, 나머지 전부 통과.**

- ❌ `.mention` 다크: `--primary`(`#0C66E4`) on `--accent`(`#22272B`) = **2.90:1** (결정 3의 다크 primary 유지가 원인)
- ✅ 처방: `.mention`의 `color: var(--primary)` → **`color: var(--brand-text)`** — 라이트는 동일값(`#0C66E4`, 4.64:1)이라 불변, 다크는 `#579DFF`로 **5.51:1** 통과. 의미상으로도 "브랜드색 텍스트" 토큰이 정확.
- ✅ 통과 확인된 주요 페어: 본문 14.10/11.31, muted-fg 5.08/5.78, text-subtle 7.65/7.65, text-subtlest 5.08/5.78, text-selected on bg-selected 4.61/5.84, primary 버튼 흰글자 5.20(양모드), warning 흰글자 on `#B65C02` 4.65 · 다크 `#161A1D` on `#E2B203` 8.84, success 4.66/8.81, danger 5.19/6.39, info 5.20/6.40, ring 3.33/8.58(UI 3:1 기준).
- ℹ️ `--text-disabled` 1.97/2.10 — WCAG 면제 대상(disabled). `--border` 1.34 — 비텍스트 장식 경계.
- 시맨틱 bold 텍스트 페어링 관례(DESIGN.md에 기록할 것): **라이트 bold=흰 글자, 다크 bold=어두운 글자(`#161A1D`)**. foreground 토큰 신설은 스코프 밖(PR4에서 소비 시 적용).

### all-tokens 전수 대조의 이행 방식

checklist의 "atlassian.design all-tokens 전수 대조"는 **웹사이트가 아니라 values.md 표와 대조**로 이행한다 — 현행 atlassian.design은 브랜드 리프레시(Blue700=`#1868DB`) 세대라 v2와 어긋나며, values.md가 이미 `@atlaskit/tokens@1.4.2` 앵커 5점 교차검증을 통과한 정본이다. **impl이 웹사이트 값으로 덮어쓰는 것 금지.** Task 5에서 index.css 최종본 ↔ 위 확정표를 토큰 단위로 전수 대조한다.

---

### Task 1. state-tokens.test.ts를 정확-hex 가드로 강화 (RED)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/__tests__/state-tokens.test.ts`]
- depends-on: []

**RED**:
- 파일: `apps/web/src/components/ui/__tests__/state-tokens.test.ts`
- 기존 "alias 형태 강제" 테스트를 아래 전문으로 **교체**한다. §7 11종+시맨틱 4종 × `:root`/`.dark` 행렬 전수 + radius 5종 + `@theme inline` 시맨틱 배선 + 🔒 동결 가드 + `.mention` 대비 처방 가드.

```ts
// FR-UX-06 §7 상태 토큰 11종 + 시맨틱 토큰 4종이 index.css에 ADS v2 정본 hex로 정의됐는지 검증하는 테스트
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * FR-UX-06 PR3 확정 팔레트 — 정본: docs/plans/2026-07-19-fr-ux-06-pr3-ads-palette-values.md
 * (@atlaskit/tokens@1.4.2) + Maxi 확정 4결정(warning=Orange, text 스텝=ADS 실측,
 * 다크 primary=shadcn 관례, ring=border-focus 통일).
 * 값을 바꾸려면 values.md 정본과 이 표를 같은 커밋에서 바꿔야 한다 — 값 지어내기 방지 가드.
 */
const STATE_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--bg-neutral', '#F7F8F9', '#1D2125'],
  ['--bg-neutral-hover', '#F1F2F4', '#22272B'],
  ['--bg-neutral-press', '#DCDFE4', '#2C333A'],
  ['--bg-selected', '#E9F2FF', '#082145'],
  ['--text-selected', '#0C66E4', '#579DFF'],
  ['--text-subtle', '#44546F', '#9FADBC'],
  ['--text-subtlest', '#626F86', '#8696A7'],
  ['--text-disabled', '#B3B9C4', '#454F59'],
  ['--border-focus', '#388BFF', '#85B8FF'],
  ['--brand-hover', '#0055CC', '#85B8FF'],
  ['--brand-text', '#0C66E4', '#579DFF'],
] as const

/** FR-UX-06 PR3 신설 시맨틱 상태색 — ADS color.background.<status>.bold */
const SEMANTIC_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--warning', '#B65C02', '#E2B203'],
  ['--success', '#1F845A', '#4BCE97'],
  ['--danger', '#CA3521', '#F87462'],
  ['--info', '#0C66E4', '#579DFF'],
] as const

/** calc 파생 폐기 후 명시 나열된 radius 스케일 (ADS 기본 3px 포함) */
const RADIUS_TOKENS: ReadonlyArray<readonly [token: string, value: string]> = [
  ['--radius-xs', '2px'],
  ['--radius-sm', '3px'],
  ['--radius-md', '4px'],
  ['--radius-lg', '8px'],
  ['--radius-xl', '12px'],
] as const

/**
 * css 원문에서 `${selector} { ... }` 블록의 본문(중괄호 안)을 추출한다.
 * 대상 블록은 커스텀 프로퍼티 평면 나열이라 중첩 `{`가 없으므로
 * 여는 중괄호 다음 첫 `}`가 항상 해당 블록의 끝이다.
 */
function extractBlock(css: string, selector: string): string {
  const openIndex = css.indexOf(`${selector} {`)
  if (openIndex === -1) {
    throw new Error(`selector "${selector}"를 index.css에서 찾을 수 없다`)
  }
  const braceIndex = css.indexOf('{', openIndex)
  const closeIndex = css.indexOf('}', braceIndex)
  if (closeIndex === -1) {
    throw new Error(`selector "${selector}"의 닫는 중괄호를 찾을 수 없다`)
  }
  return css.slice(braceIndex + 1, closeIndex)
}

/**
 * block 안에서 token 선언의 우변(세미콜론 전)을 반환한다. 없으면 null.
 * 앞 경계 검사로 부분 매칭 차단 — `color`가 `background-color`에, `--warning`이
 * 다른 토큰 꼬리에 걸리지 않게 한다.
 */
function declarationOf(block: string, token: string): string | null {
  const match = block.match(new RegExp(`(?:^|[^-a-zA-Z])${token}\\s*:\\s*([^;]+);`))
  return match ? match[1].trim() : null
}

describe('FR-UX-06 PR3 ADS 팔레트 — index.css', () => {
  const cssPath = resolve(import.meta.dirname, '../../../index.css')
  const css = readFileSync(cssPath, 'utf-8')
  const rootBlock = extractBlock(css, ':root')
  const darkBlock = extractBlock(css, '.dark')
  const themeInline = extractBlock(css, '@theme inline')

  describe('§7 상태 토큰 11종 — ADS 정본 hex', () => {
    it.each(STATE_TOKENS)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(STATE_TOKENS)('%s — .dark 확정값 일치', (token, _light, dark) => {
      expect(declarationOf(darkBlock, token)).toBe(dark)
    })
  })

  describe('시맨틱 토큰 4종 — 신설 + Tailwind 배선', () => {
    it.each(SEMANTIC_TOKENS)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(SEMANTIC_TOKENS)('%s — .dark 확정값 일치', (token, _light, dark) => {
      expect(declarationOf(darkBlock, token)).toBe(dark)
    })
    it.each(SEMANTIC_TOKENS)('%s — @theme inline에 --color-* 배선', (token) => {
      const colorToken = token.replace('--', '--color-')
      expect(declarationOf(themeInline, colorToken)).toBe(`var(${token})`)
    })
  })

  describe('radius — calc 파생 폐기, px 명시 나열', () => {
    it.each(RADIUS_TOKENS)('%s = %s', (token, value) => {
      expect(declarationOf(themeInline, token)).toBe(value)
    })
    it('calc(var(--radius) 파생이 @theme inline에 남아 있지 않다', () => {
      expect(themeInline).not.toContain('calc(var(--radius)')
    })
  })

  describe('🔒 동결 계약 — PR3가 건드리면 안 되는 토큰', () => {
    const frozen = ['--chart-1', '--chart-2', '--chart-3', '--chart-4', '--chart-5',
      '--syntax-keyword', '--syntax-field', '--syntax-operator', '--syntax-string', '--syntax-number']
    it.each(frozen)('%s — :root/.dark 모두 oklch 원값 유지', (token) => {
      expect(declarationOf(rootBlock, token)).toMatch(/^oklch\(/)
      expect(declarationOf(darkBlock, token)).toMatch(/^oklch\(/)
    })
  })

  describe('.mention 대비 처방 (다크 AA 미달 해소)', () => {
    it('color가 --brand-text를 참조한다 (다크 #579DFF → 5.51:1)', () => {
      const mentionBlock = extractBlock(css, '.mention')
      expect(declarationOf(mentionBlock, 'color')).toBe('var(--brand-text)')
    })
  })
})
```

- 실행: `cd apps/web && pnpm vitest run src/components/ui/__tests__/state-tokens.test.ts`
- 실패 메시지 (예상): §7·시맨틱·radius·`.mention` 어서션 다수 FAIL — index.css가 아직 alias/oklch/calc 상태이므로. **동결 계약 10건은 이 시점에도 PASS여야 정상** (기존 oklch 그대로라서).

**GREEN**: 없음 — 이 task는 RED 커밋까지만. GREEN은 Task 2.

**REFACTOR**: 없음.

**커밋**: `test: FR-UX-06 PR3 — state-tokens 테스트를 ADS 정본 hex 가드로 강화 (RED)` (TDD 검증을 위해 반드시 Task 2의 `feat:`보다 먼저 커밋)

**검증**: 위 vitest 실행에서 신규 어서션 FAIL + 동결 계약 PASS 확인.

### Task 2. index.css ADS v2 전면 교체 (GREEN)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/index.css`]
- depends-on: [1]

**RED**: Task 1이 이미 RED 상태를 만들었다 (같은 사이클의 GREEN phase).

**GREEN**:
- 파일: `apps/web/src/index.css`
- 변경 ① `@theme inline` — radius 블록 교체(기존 `--radius-sm`~`--radius-4xl` calc 7줄 삭제) + 시맨틱 배선 4줄 추가. 나머지 `--color-*` 매핑·`--font-*`는 그대로.

```css
  --color-warning: var(--warning);

  --color-success: var(--success);

  --color-danger: var(--danger);

  --color-info: var(--info);

  --radius-xs: 2px;

  --radius-sm: 3px;

  --radius-md: 4px;

  --radius-lg: 8px;

  --radius-xl: 12px;
```

- 변경 ② `:root` — shadcn 18토큰을 §A 라이트 열 hex로, §7 11종을 alias에서 §7 라이트 열 hex로, 시맨틱 4종 신설(§C 라이트 열). `--radius: 0.625rem` 삭제. 🔒 `--chart-1~5`·`--syntax-*`·`--sidebar-*`는 **바이트 단위로 그대로 둔다**. 최종 `:root` 값부(동결 토큰 제외).

```css
:root {
  /* ADS v2 팔레트 — 정본: docs/plans/2026-07-19-fr-ux-06-pr3-ads-palette-values.md (@atlaskit/tokens@1.4.2) */
  --background: #FFFFFF;
  --foreground: #172B4D;
  --card: #FFFFFF;
  --card-foreground: #172B4D;
  --popover: #FFFFFF;
  --popover-foreground: #172B4D;
  --primary: #0C66E4;
  --primary-foreground: #FFFFFF;
  --secondary: #F1F2F4;
  --secondary-foreground: #172B4D;
  --muted: #F7F8F9;
  --muted-foreground: #626F86;
  --accent: #F1F2F4;
  --accent-foreground: #172B4D;
  --destructive: #CA3521;
  --border: #DCDFE4;
  --input: #DCDFE4;
  --ring: #388BFF;

  /* (🔒 --chart-1~5 · --syntax-* 5종 — 기존 oklch 그대로, 이 task에서 손대지 않음) */
  /* (--sidebar-* 8종 — 기존 그대로, PR11 몫) */

  /* FR-UX-06 §7 상태 토큰 — ADS v2 실제값 (PR2 alias → PR3 확정) */
  --bg-neutral: #F7F8F9;
  --bg-neutral-hover: #F1F2F4;
  --bg-neutral-press: #DCDFE4;
  --bg-selected: #E9F2FF;
  --text-selected: #0C66E4;
  --text-subtle: #44546F;
  --text-subtlest: #626F86;
  --text-disabled: #B3B9C4;
  --border-focus: #388BFF;
  --brand-hover: #0055CC;
  --brand-text: #0C66E4;

  /* FR-UX-06 시맨틱 상태색 — ADS color.background.<status>.bold */
  --warning: #B65C02;
  --success: #1F845A;
  --danger: #CA3521;
  --info: #0C66E4;
}
```

- 변경 ③ `.dark` — 동일 구조, 다크 열 hex. `--border`/`--input`의 기존 알파(oklch `/ 10%`)도 솔리드 등가로.

```css
.dark {
  --background: #161A1D;
  --foreground: #C7D1DB;
  --card: #1D2125;
  --card-foreground: #C7D1DB;
  --popover: #22272B;
  --popover-foreground: #C7D1DB;
  --primary: #0C66E4;            /* Maxi 결정 3 — 다크도 Blue700 + 흰 글자 (ADS Blue400 기각) */
  --primary-foreground: #FFFFFF;
  --secondary: #22272B;
  --secondary-foreground: #C7D1DB;
  --muted: #1D2125;
  --muted-foreground: #8696A7;
  --accent: #22272B;
  --accent-foreground: #C7D1DB;
  --destructive: #F87462;
  --border: #2C333A;
  --input: #2C333A;
  --ring: #85B8FF;

  /* (🔒 --chart-1~5 · --syntax-* 5종 — 기존 oklch 그대로) */
  /* (--sidebar-* 8종 — 기존 그대로, PR11 몫) */

  /* FR-UX-06 §7 상태 토큰 — ADS v2 실제값 */
  --bg-neutral: #1D2125;
  --bg-neutral-hover: #22272B;
  --bg-neutral-press: #2C333A;
  --bg-selected: #082145;
  --text-selected: #579DFF;
  --text-subtle: #9FADBC;
  --text-subtlest: #8696A7;
  --text-disabled: #454F59;
  --border-focus: #85B8FF;
  --brand-hover: #85B8FF;
  --brand-text: #579DFF;

  /* FR-UX-06 시맨틱 상태색 */
  --warning: #E2B203;
  --success: #4BCE97;
  --danger: #F87462;
  --info: #579DFF;
}
```

- 변경 ④ `.mention` — `color: var(--primary)` → `color: var(--brand-text)` (다크 2.90:1 → 5.51:1, §대비 검증 참조). 주석도 갱신: `/* 브랜드색 텍스트 토큰 — 다크에서 Blue400으로 AA 확보 (PR3 대비 계산) */`
- 실행: `cd apps/web && pnpm vitest run src/components/ui/__tests__/state-tokens.test.ts`
- 예상: 전 어서션 PASS.

**REFACTOR**: 파일 상단 L1 주석을 "ADS v2 팔레트" 기준으로 갱신(예: `/* 전역 스타일 — Tailwind v4 CSS-first + ADS v2 팔레트 (FR-UX-06 PR3) */`).

**커밋**: `feat: FR-UX-06 PR3 — index.css ADS v2 팔레트 교체 + 시맨틱 4종 + radius 명시화 (GREEN)`

**검증**: `cd apps/web && pnpm vitest run src/components/ui/__tests__/state-tokens.test.ts` 전건 PASS.

### Task 3. 전체 회귀 검증 + 화석 주석 정리 (REFACTOR)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/sonner.tsx`]
- depends-on: [2]

**RED**: 해당 없음 (회귀 검증 task — 기존 테스트 스위트가 판별자).

**GREEN**: 해당 없음.

**REFACTOR**:
- `apps/web/src/components/ui/sonner.tsx:28` 주석 `// rounded-lg = --radius-lg = var(--radius) = 0.625rem (DESIGN.md 토큰)` — `--radius` 삭제로 거짓이 됨 → `// rounded-lg = --radius-lg = 8px (ADS 스케일, DESIGN.md 토큰)` 으로 갱신. 코드 변경 없음.

**검증** (전부 통과해야 완료 — 실패 시 원인 파악 후 수정, 값 문제면 확정표와 대조):
```bash
cd apps/web && pnpm verify   # lint + typecheck + test + build 통합
```
- 기대: 유닛 전체(7226+) PASS — §7 토큰 값이 바뀌어도 프리미티브는 `bg-(--bg-neutral)` 변수 참조라 회귀 0이어야 정상.

**커밋**: `chore: FR-UX-06 PR3 — 전체 회귀 통과 확인 + radius 화석 주석 갱신`

### Task 4. DESIGN.md 재작성 (ADS v2 + 프리미티브 15종 기준)

**메타**.
- agent: `designer` (Bash 미보유 — 검증·커밋은 impl 오케스트레이터가 수행)
- files: [`DESIGN.md`]
- depends-on: [2]

**RED/GREEN**: 해당 없음 (문서 task).

**작업**: 현행 DESIGN.md(27KB 화석 — §10~12 로그인폼 기준, "본 PR (#11)" 지시 잔존, 다크 "미활성" 오기)를 **전면 재작성**. 새 구조와 각 절의 내용 소스.

1. **디자인 원칙** — 기존 원칙 유지 + ADS v2 참조 명시
2. **컬러 토큰** — 본 plan §확정 팔레트 3표(§A 18 + §7 11 + 시맨틱 4)를 라이트·다크로 전재. 정본 소스(`@atlaskit/tokens@1.4.2`)와 values.md 링크. 🔒 동결 토큰(chart/syntax/font-mono/sidebar) 및 사유 명시
3. **Syntax Highlight 토큰** — 기존 §(FR-SR-02) 그대로 이관 (동결 계약)
4. **프리미티브 15종 (PR2)** — 각 컴포넌트가 소비하는 §7 토큰 매핑 표 (`apps/web/src/components/ui/` 실물 grep 기준)
5. **타이포그래피** — 기존 Geist 스택·타입 스케일 이관, `--font-mono` 동결 명시
6. **간격** — 기존 이관 (로그인폼 한정 문구 제거)
7. **라운드** — `2/3/4/8/12px` 명시 스케일 + calc 폐기 사유
8. **그림자** — 기존 이관
9. **다크 모드 정책** — "본 PR 미활성" 화석 제거, **활성** 상태 기준 서술 (`lib/theme.ts`·FOUC 스크립트 경로)
10. **접근성 (WCAG AA)** — 본 plan §대비 검증의 계산 결과 표 전재 (`.mention` 처방 포함)

파일 L1에 한국어 헤더 주석 유지. "본 PR" 같은 특정 PR 지시문 금지 — 정본 문서로 서술.

**검증**: `grep -c "0052CC\|본 PR (#11)\|미활성" DESIGN.md` → 0건. §2 표의 hex가 본 plan 확정표와 전수 일치(오케스트레이터가 대조).

**커밋**: `docs: FR-UX-06 PR3 — DESIGN.md를 ADS v2 + 프리미티브 15종 기준으로 재작성`

### Task 5. 정본 문서 동기화 + 전수 대조 (D단계)

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plans/2026-07-19-fr-ux-06-pr3-ads-palette-values.md`, `docs/plans/2026-07-17-fr-ux-06-jira-redesign/checklist.md`]
- depends-on: [4]   # 코드 의존성은 [2]뿐이나, 커밋 race 회피 위해 T4 뒤 직렬화 ([[worktree-lint-staged-shared-git-stash-collision]])

**RED/GREEN**: 해당 없음 (문서 task).

**작업**:
1. values.md에 `## 확정 (2026-07-19 Maxi 4결정 + tokens-raw 실측 반영)` 절 추가 — §B ⚠️ 4건(text-subtle/subtlest/disabled·border-focus)과 §A ⚠️(primary 다크·알파/솔리드)·§C ⚠️(warning)의 **해소 결과**를 본 plan §확정 팔레트 표로 기록. 기존 ⚠️ 주석은 이력으로 보존(수정하지 않음).
2. checklist.md의 PR3 하위 체크박스 6개(전수 대조·oklch→hex·radius·라이트다크·chart 미변경·syntax 미변경·DESIGN.md)를 `[x]`로. PR3 본 항목도 `[x]`. ("머지 후 #277 공유" 항목은 미체크 유지 — bts-merge 몫)
3. **전수 대조 (all-tokens D단계 이행)**: index.css 최종본에서 `:root`/`.dark`의 §A+§7+§C 토큰을 **하나씩 열거**하며 본 plan 확정표와 hex 대조. 개수 비교가 아니라 토큰 단위 대조 — 불일치 발견 시 BLOCKED 보고(임의 수정 금지). 대조 로그를 task 보고에 포함.

**검증**: `grep -c "\[x\]" docs/plans/2026-07-17-fr-ux-06-jira-redesign/checklist.md`가 기존보다 +7. 전수 대조 로그 33행(§A 18+§7 11+§C 4) 불일치 0.

**커밋**: `docs: FR-UX-06 PR3 — values.md 확정 절 + checklist 체크 + 전수 대조 로그`

## Plan 메타

- task 수: 5
- 예상 시간: 직렬 기준 약 25분 (T2·T4가 큼). wave 4개 — W1=[T1] → W2=[T2] → W3=[T3, T4 병렬] → W4=[T5]
- TDD 강제: yes — T1 `test:` 커밋이 T2 `feat:` 커밋보다 먼저 (bts-impl git log 검증 대상)
- 병렬 dispatch: 파일 겹침 없음(각 task 상이 파일). W3만 2-병렬 — 6-병렬 사고([[worktree-lint-staged-shared-git-stash-collision]]) 대비 폭 축소, 커밋은 자기 파일만 stage
- 추가 검증: T3에서 `pnpm verify` (lint+typecheck+유닛 전체+build). E2E 불필요 — 색 변경은 E2E 어서션 무관(정본 plan §PR3), 대비는 plan 단계 계산 + 머지 전 수동 확인으로 갈음
- 수동 확인(게이트 2 전 Maxi): 라이트/다크 주요 화면 육안 대비 확인 — 자동검증 불가 유일 PR

## 리뷰 결과 (← /bts-review-plan, 스킵 — 정본 plan 이미 리뷰됨)
