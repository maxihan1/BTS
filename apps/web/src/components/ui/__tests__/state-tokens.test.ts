// index.css의 디자인 토큰이 ADS v2 정본 hex로 정의됐는지 전수 대조하는 회귀 가드 (FR-UX-06 누적)
// 커버 범위. §7 상태 12종 + §A 코어 18종 + 시맨틱 4쌍(PR3) + status-text 4종(PR4) +
//          사이드바 8종(PR11) + 차트 5종·범주 10종(PR22) + radius + 동결 계약(syntax 5종)
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * 3차 캠페인 Q1 팔레트 — 정본: docs/design/2026-10-30-qa-hub-shared-ui-policy.md §1 · §11 (QA Hub 공통 정책).
 * 대응 결정(DESIGN.md §2): 토큰 이름은 shadcn 그대로 · 대표색 오렌지 · well/line/line2 바탕 단계 ·
 * subtlest=muted(faint 3.5:1 AA 미달) · 경고 라이트=Yellow600 · ring=대표색 글자 값 · 다크 파랑 예외 삭제.
 * 값을 바꾸려면 정책 문서와 이 표를 같은 커밋에서 바꿔야 한다 — 값 지어내기 방지 가드.
 */
const STATE_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--bg-neutral', '#F2F3F6', '#1D2025'],
  ['--bg-neutral-hover', '#E3E5EA', '#272A31'],
  ['--bg-neutral-press', '#CFD3DA', '#363A43'],
  ['--bg-neutral-solid', '#F2F3F6', '#1D2025'],
  ['--bg-selected', '#FFF1E6', '#FF7A1A21'],
  ['--text-selected', '#B84D05', '#FF9A4D'],
  ['--text-subtle', '#575E6B', '#A6ABB5'],
  ['--text-subtlest', '#575E6B', '#A6ABB5'],
  ['--text-disabled', '#848A96', '#737985'],
  ['--border-focus', '#B84D05', '#FF9A4D'],
  ['--brand-hover', '#FF7A1A', '#FF7A1A'],
  ['--brand-text', '#B84D05', '#FF9A4D'],
] as const

/**
 * §A shadcn 코어 토큰 18종 — 게이트2 CRITICAL C1 재발 방지 행렬.
 * 다크 상태배경(muted/accent/secondary)이 표면(card/popover)과 동일 hex로
 * 되돌아가는 것을 차단 — [token, light, dark] 전수 대조.
 */
const CORE_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--background', '#F5F6F8', '#101114'],
  ['--foreground', '#15171C', '#ECEDEF'],
  ['--card', '#FFFFFF', '#17191D'],
  ['--card-foreground', '#15171C', '#ECEDEF'],
  ['--popover', '#FFFFFF', '#17191D'],
  ['--popover-foreground', '#15171C', '#ECEDEF'],
  ['--primary', '#FF7A1A', '#FF7A1A'],
  ['--primary-foreground', '#1A0D02', '#1A0D02'],
  ['--secondary', '#F2F3F6', '#1D2025'],
  ['--secondary-foreground', '#15171C', '#ECEDEF'],
  ['--muted', '#F2F3F6', '#1D2025'],
  ['--muted-foreground', '#575E6B', '#A6ABB5'],
  ['--accent', '#F2F3F6', '#1D2025'],
  ['--accent-foreground', '#15171C', '#ECEDEF'],
  ['--destructive', '#D4294F', '#EE3D63'],
  ['--border', '#E3E5EA', '#272A31'],
  ['--input', '#CFD3DA', '#363A43'],
  ['--ring', '#B84D05', '#FF9A4D'],
] as const

/** 시맨틱 foreground 4종 — 칠 위 글자. 라이트 통과 · 경고는 흰 글자 대비 미달이라 잉크 */
const SEMANTIC_FOREGROUND: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--warning-foreground', '#15171C', '#161A1D'],
  ['--success-foreground', '#15171C', '#161A1D'],
  ['--danger-foreground', '#FFFFFF', '#161A1D'],
  ['--info-foreground', '#1A0D02', '#1A0D02'],
] as const

/** 시맨틱 상태색 — 판정 색 칠 · 경고 노랑 쪽 · info = 진행 중 = 대표색 */
const SEMANTIC_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--warning', '#B38600', '#E2B203'],
  ['--success', '#16A34A', '#3FB871'],
  ['--danger', '#D4294F', '#EE3D63'],
  ['--info', '#FF7A1A', '#FF7A1A'],
] as const

/**
 * FR-UX-06 PR4 신설 status-text — tint(옅은 배경) 위 색 텍스트용.
 * bold 상태색(--warning 등)은 중간명도라 tint 위 글자로 쓰면 라이트 AA 미달(4.1:1) →
 * ADS color.text.<status>(라이트 -800 어두움 / 다크 -300 밝음)를 텍스트 토큰으로 신설.
 * 전 tint 표면(흰·muted·bg·card) AA≥4.9 실측(docs/specs/2026-07-19-fr-ux-06-pr4-color-tokens.md).
 */
const SEMANTIC_TEXT: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--warning-text', '#7F5F01', '#F5CD47'],
  ['--success-text', '#166534', '#6FD49A'],
  ['--danger-text', '#B81E42', '#FF8AA3'],
  ['--info-text', '#9A3412', '#FF9A4D'],
] as const

/**
 * FR-UX-06 PR11 신설 — `--sidebar-*` 8종(shadcn init 산출물, 이전엔 소비자 0이라 동결).
 * ADR D7 "소비되는 PR에서 정의" — 사이드바 PR(PR11)이 소비자이므로 여기서 ADS 값으로 덮어쓴다.
 * 값은 디자인 스펙(docs/design/fr-ux-06-jira-redesign.md) §5.3 시맨틱 토큰 표에서 그대로 가져온다 —
 * `--sidebar`=배경(§3.1 "사이드바"=`--surface-sunken`), `--sidebar-primary`/`-primary-foreground`=활성 항목
 * (`--bg-selected`/`--text-selected`, 표에 "사이드바 활성" 명기), `--sidebar-accent`=hover(`--bg-neutral-hover`),
 * `--sidebar-foreground`/`-accent-foreground`=텍스트(`--text`≡`--foreground`), `--sidebar-border`=테두리
 * (§3.1 "border-right: 1px --border"), `--sidebar-ring`=포커스 링(`--ring`, 앱 전역과 통일).
 */
const SIDEBAR_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--sidebar', '#F5F6F8', '#0D0E11'],
  ['--sidebar-foreground', '#15171C', '#ECEDEF'],
  ['--sidebar-primary', '#FFF1E6', '#FF7A1A21'],
  ['--sidebar-primary-foreground', '#B84D05', '#FF9A4D'],
  ['--sidebar-accent', '#F2F3F6', '#1D2025'],
  ['--sidebar-accent-foreground', '#15171C', '#ECEDEF'],
  ['--sidebar-border', '#E3E5EA', '#272A31'],
  ['--sidebar-ring', '#B84D05', '#FF9A4D'],
] as const

/**
 * FR-UX-06 PR22 신설 — `--chart-1~5` (PR3가 동결해 둔 무채색 placeholder를 실소비와 함께 확정).
 * ADR D7 "소비되는 PR에서 정의" 이행 — recharts 가젯 6종(Burndown·CFD·CycleTimeBoxPlot·
 * CycleTimeHistogram·Velocity·WorklogAggregate)이 이 PR에서 실제 소비자가 된다.
 * 값은 디자인 스펙 §5.2 ADS v2 원시 팔레트(PR3 검증분)에서 파생하고, 라이트는 700번대·
 * 다크는 400번대(중립만 Neutral600/DarkNeutral800)를 쓴다 — §4.2 타입색과 동일 규칙.
 * 전량 배경 대비 3:1 이상(WCAG 1.4.11 비텍스트 대비) 계산 검증.
 * 정본: docs/plans/2026-07-25-fr-ux-06-pr22-token-values.md
 */
const CHART_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--chart-1', '#0C66E4', '#579DFF'],
  ['--chart-2', '#6E5DC6', '#9F8FEF'],
  ['--chart-3', '#758195', '#8C9BAB'],
  ['--chart-4', '#B65C02', '#FAA53D'],
  ['--chart-5', '#1F845A', '#4BCE97'],
] as const

/**
 * FR-UX-06 PR22 신설 범주 토큰 — PR4가 `--chart-*` 동결 때문에 이연한 하드코딩 범주색의 착륙지점.
 * `--type-*` 4종+기본값은 타임라인 이슈타입 막대(TimelineRow), `--discovery`는 캘린더 Worklog 칩,
 * `--neutral-bold`는 캘린더 TODO 칩, `--favorite`는 즐겨찾기 별이 소비한다.
 * `--neutral-bold`는 WeekGrid.tsx의 기존 결정("옅은 배지 대비 미달로 배제, 중간톤 solid fill 채택")을
 * 지키기 위한 토큰이다 — 옅은 중립(`--bg-neutral-solid`)으로 매핑하면 그 결정을 되돌리게 된다.
 * `--favorite`가 ADS Yellow400(#E2B203)이 아니라 Yellow600인 이유: Yellow400은 흰 배경 1.98:1로 미달.
 */
const CATEGORY_TOKENS: ReadonlyArray<readonly [token: string, light: string, dark: string]> = [
  ['--type-epic', '#6E5DC6', '#9F8FEF'],
  ['--type-story', '#22A06B', '#4BCE97'],
  ['--type-task', '#0C66E4', '#579DFF'],
  ['--type-bug', '#C9372C', '#F87168'],
  ['--type-default', '#758195', '#8C9BAB'],
  ['--discovery', '#6E5DC6', '#9F8FEF'],
  ['--discovery-foreground', '#FFFFFF', '#161A1D'],
  ['--neutral-bold', '#44546F', '#9FADBC'],
  ['--neutral-bold-foreground', '#FFFFFF', '#161A1D'],
  ['--favorite', '#B38600', '#F5CD47'],
] as const

/**
 * 소비처가 없어 **일부러 만들지 않은** 토큰 — ADR D7 자기준수 가드.
 * 디자인 스펙 §4.3(우선순위 5색)·§4.2(서브태스크)는 값을 확정해 뒀지만, 실측 결과
 * 우선순위는 텍스트로만 표시되고(routes/search.tsx·IssueMetaPanel) 서브태스크 색 소비처도 0이다.
 * "소비자가 없는 토큰을 미리 채우면 그게 PoC"(ADR D7)이므로 PR22가 스스로 그 규칙을 어기지 않도록
 * 미정의를 테스트로 고정한다. 미래에 실소비 화면이 생기는 PR에서 이 목록에서 빼고 값을 넣을 것.
 */
const UNCONSUMED_TOKENS = [
  '--prio-highest',
  '--prio-high',
  '--prio-medium',
  '--prio-low',
  '--prio-lowest',
  '--type-subtask',
] as const

/** calc 파생 폐기 후 명시 나열된 radius 스케일 (ADS 기본 3px 포함) */
const RADIUS_TOKENS: ReadonlyArray<readonly [token: string, value: string]> = [
  ['--radius-xs', '2px'],
  ['--radius-sm', '3px'],
  ['--radius-md', '6px'],
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

/** css 원문에서 주석을 제거한다 — 주석 속 선언이 가드를 속이는 것을 차단한다. */
function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '')
}

/**
 * block 안에서 token 선언의 우변을 반환한다. 선언이 정확히 1개가 아니면 throw —
 * CSS cascade는 마지막 선언이 이기므로, 중복 선언은 첫 선언만 검사하는 가드를 우회한다.
 * 앞 경계 검사로 부분 매칭 차단 — `color`가 `background-color`에, `--warning`이
 * 다른 토큰 꼬리에 걸리지 않게 한다.
 */
function declarationOf(block: string, token: string): string {
  const matches = [...block.matchAll(new RegExp(`(?:^|[^-a-zA-Z])${token}\\s*:\\s*([^;]+);`, 'g'))]
  if (matches.length !== 1) {
    throw new Error(`"${token}" 선언이 ${matches.length}개 — 정확히 1개여야 한다`)
  }
  return matches[0]?.[1]?.trim() ?? ''
}

describe('FR-UX-06 ADS 팔레트 — index.css', () => {
  const cssPath = resolve(import.meta.dirname, '../../../index.css')
  const css = stripComments(readFileSync(cssPath, 'utf-8'))
  const rootBlock = extractBlock(css, ':root')
  const darkBlock = extractBlock(css, '.dark')
  const themeInline = extractBlock(css, '@theme inline')

  describe('§7 상태 토큰 12종 — ADS 정본 hex', () => {
    it.each(STATE_TOKENS)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(STATE_TOKENS)('%s — .dark 확정값 일치', (token, _light, dark) => {
      expect(declarationOf(darkBlock, token)).toBe(dark)
    })
  })

  describe('§A shadcn 토큰 18종 — ADS 정본 hex (Maxi 결정 3·4 가드 포함)', () => {
    it.each(CORE_TOKENS)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(CORE_TOKENS)('%s — .dark 확정값 일치', (token, _light, dark) => {
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

  describe('시맨틱 foreground 4종 — 신설 + Tailwind 배선', () => {
    it.each(SEMANTIC_FOREGROUND)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(SEMANTIC_FOREGROUND)('%s — .dark 확정값 일치', (token, _light, dark) => {
      expect(declarationOf(darkBlock, token)).toBe(dark)
    })
    it.each(SEMANTIC_FOREGROUND)('%s — @theme inline에 --color-* 배선', (token) => {
      const colorToken = token.replace('--', '--color-')
      expect(declarationOf(themeInline, colorToken)).toBe(`var(${token})`)
    })
  })

  describe('status-text 4종 (PR4) — tint 위 색 텍스트, ADS color.text.<status>', () => {
    it.each(SEMANTIC_TEXT)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(SEMANTIC_TEXT)('%s — .dark 확정값 일치', (token, _light, dark) => {
      expect(declarationOf(darkBlock, token)).toBe(dark)
    })
    it.each(SEMANTIC_TEXT)('%s — @theme inline에 --color-* 배선', (token) => {
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

  describe('🔒 동결 계약 — AQL 하이라이터 전용 syntax 토큰 5종', () => {
    // PR22가 `--chart-*` 5종만 동결 해제했다(실소비 = recharts 6종). `--syntax-*`는
    // AQL textarea와 overlay <pre>가 같은 값을 참조해야 정렬이 깨지지 않으므로
    // 동결 유지 — DESIGN.md §"AQL syntax 토큰"의 동결 계약 근거 그대로.
    const frozen = ['--syntax-keyword', '--syntax-field', '--syntax-operator', '--syntax-string', '--syntax-number']
    it.each(frozen)('%s — :root/.dark 모두 oklch 원값 유지', (token) => {
      expect(declarationOf(rootBlock, token)).toMatch(/^oklch\(/)
      expect(declarationOf(darkBlock, token)).toMatch(/^oklch\(/)
    })
  })

  describe('차트 토큰 5종 (PR22, ADR D7 이행) — 동결 해제 + ADS 정본 hex', () => {
    it.each(CHART_TOKENS)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(CHART_TOKENS)('%s — .dark 확정값 일치', (token, _light, dark) => {
      expect(declarationOf(darkBlock, token)).toBe(dark)
    })
    it.each(CHART_TOKENS)('%s — @theme inline 배선', (token) => {
      const colorToken = token.replace('--', '--color-')
      expect(declarationOf(themeInline, colorToken)).toBe(`var(${token})`)
    })
  })

  describe('범주 토큰 10종 (PR22) — 실소비처가 있는 토큰만 정의한다', () => {
    it.each(CATEGORY_TOKENS)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(CATEGORY_TOKENS)('%s — .dark 확정값 일치', (token, _light, dark) => {
      expect(declarationOf(darkBlock, token)).toBe(dark)
    })
    it.each(CATEGORY_TOKENS)('%s — @theme inline 배선', (token) => {
      const colorToken = token.replace('--', '--color-')
      expect(declarationOf(themeInline, colorToken)).toBe(`var(${token})`)
    })
  })

  describe('소비처 0인 토큰은 만들지 않는다 (ADR D7 자기준수)', () => {
    it.each(UNCONSUMED_TOKENS)('%s — :root에 미정의', (token) => {
      expect(rootBlock).not.toMatch(new RegExp(`(?:^|[^-a-zA-Z])${token}\\s*:`))
    })
    it.each(UNCONSUMED_TOKENS)('%s — .dark에 미정의', (token) => {
      expect(darkBlock).not.toMatch(new RegExp(`(?:^|[^-a-zA-Z])${token}\\s*:`))
    })
  })

  describe('사이드바 토큰 8종 (PR11, D7) — ADS 정본 hex, 이전 동결 해제', () => {
    it.each(SIDEBAR_TOKENS)('%s — :root=%s', (token, light) => {
      expect(declarationOf(rootBlock, token)).toBe(light)
    })
    it.each(SIDEBAR_TOKENS)('%s — .dark 확정값 일치', (token, _light, dark) => {
      expect(declarationOf(darkBlock, token)).toBe(dark)
    })
  })

  describe('.mention 대비 처방 (다크 AA 미달 해소)', () => {
    it('color가 --brand-text를 참조한다 (다크 #579DFF → 5.51:1)', () => {
      const mentionBlock = extractBlock(css, '.mention')
      expect(declarationOf(mentionBlock, 'color')).toBe('var(--brand-text)')
    })
  })
})
