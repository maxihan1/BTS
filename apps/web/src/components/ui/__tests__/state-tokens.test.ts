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
  return match?.[1]?.trim() ?? null
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
