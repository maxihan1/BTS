// navLabels.breadcrumb 신규 키 존재 + 기존 e2e 계약 문자열과 substring 비충돌 검증 — FR-UX-06 PR13 Task 1 (PL-9)
import { describe, it, expect } from 'vitest'
import { navLabels } from './nav-labels'

describe('navLabels.breadcrumb — 신규 키 (FR-UX-06 PR13 PL-3)', () => {
  it("breadcrumb 값은 '탐색 경로'다", () => {
    expect(navLabels.breadcrumb).toBe('탐색 경로')
  })
})

describe('navLabels.breadcrumb — e2e 계약 문자열 substring 비충돌 (PL-9)', () => {
  const contractLabels: ReadonlyArray<readonly [string, string]> = [
    ['mainNav', navLabels.mainNav],
    ['adminNav', navLabels.adminNav],
    ['projectNav', navLabels.projectNav],
    ['projectViewNav', navLabels.projectViewNav],
    ['search', navLabels.search],
  ]

  it.each(contractLabels)("계약 문자열 %s('%s')는 '탐색 경로'의 substring이 아니다", (_key, value) => {
    expect(navLabels.breadcrumb.includes(value)).toBe(false)
  })

  it.each(contractLabels)("'탐색 경로'는 계약 문자열 %s('%s')의 substring이 아니다", (_key, value) => {
    expect(value.includes(navLabels.breadcrumb)).toBe(false)
  })
})
