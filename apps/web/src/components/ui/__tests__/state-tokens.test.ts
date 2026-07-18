// FR-UX-06 §7 상태 토큰 11종이 index.css의 :root/.dark 블록에 alias로 정의됐는지 검증하는 테스트
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * FR-UX-06 §7에서 정의한 상태 토큰 11종.
 * PR3에서 각 토큰에 ADS(Atlassian Design System) 팔레트 값을 부여할 예정 —
 * 이 PR(PR2)에서는 기존 shadcn 토큰을 가리키는 alias로만 신설한다.
 */
const STATE_TOKENS = [
  '--bg-neutral',
  '--bg-neutral-hover',
  '--bg-neutral-press',
  '--bg-selected',
  '--text-selected',
  '--text-subtle',
  '--text-subtlest',
  '--text-disabled',
  '--border-focus',
  '--brand-hover',
  '--brand-text',
] as const

/**
 * css 원문에서 `${selector} { ... }` 블록의 본문(중괄호 안)을 추출한다.
 * :root/.dark 블록은 커스텀 프로퍼티 평면 나열이라 중첩 `{`가 없으므로
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
 * block 안에서 token이 `var(--...)` alias 형태로 정의되어 있는지 확인한다.
 * hex/oklch 같은 직접 값이면 매칭되지 않는다 — PR3가 부여할 ADS 팔레트 값 침범 방지.
 */
function hasAliasDeclaration(block: string, token: string): boolean {
  const pattern = new RegExp(`${token}\\s*:\\s*var\\(--[a-zA-Z0-9-]+\\)\\s*;`)
  return pattern.test(block)
}

describe('FR-UX-06 §7 상태 토큰 alias — index.css', () => {
  const cssPath = resolve(import.meta.dirname, '../../../index.css')
  const css = readFileSync(cssPath, 'utf-8')
  const rootBlock = extractBlock(css, ':root')
  const darkBlock = extractBlock(css, '.dark')

  it.each(STATE_TOKENS)('%s — :root 블록에 var(--...) alias로 정의되어 있다', (token) => {
    expect(hasAliasDeclaration(rootBlock, token)).toBe(true)
  })

  it.each(STATE_TOKENS)('%s — .dark 블록에 var(--...) alias로 정의되어 있다', (token) => {
    expect(hasAliasDeclaration(darkBlock, token)).toBe(true)
  })
})
