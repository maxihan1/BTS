// MSW AQL 핸들러가 쿼리를 실제로 검사하는지 검증 — 가짜 그린 제거 (FR-UX-12 F4 T4 · FR11)
import { describe, it, expect } from 'vitest'
import { isSyntacticallyValidAql } from './search-handlers'

describe('isSyntacticallyValidAql — bare 텍스트 거부', () => {
  it('★필드/연산자 없는 bare 텍스트는 무효다 (선재 결함의 정체)', () => {
    expect(isSyntacticallyValidAql('로그인 버그')).toBe(false)
  })

  it('단어 하나만 있어도 무효다', () => {
    expect(isSyntacticallyValidAql('로그인')).toBe(false)
  })

  it('text ~ "…" 는 유효하다', () => {
    expect(isSyntacticallyValidAql('text ~ "로그인 버그"')).toBe(true)
  })

  it('status = open 은 유효하다', () => {
    expect(isSyntacticallyValidAql('status = open')).toBe(true)
  })

  it('priority IN (1, 2) 는 유효하다', () => {
    expect(isSyntacticallyValidAql('priority IN (1, 2)')).toBe(true)
  })

  it('빈 문자열은 무효다', () => {
    expect(isSyntacticallyValidAql('')).toBe(false)
  })
})
