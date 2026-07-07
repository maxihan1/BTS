// ooo-datetime 유틸 단위 테스트 — datetime-local↔ISO Instant 변환, 활성 판정, 복귀일 표시 검증
import { describe, it, expect } from 'vitest'
import { toInstant, toLocalInputValue, formatOooReturnDate } from './ooo-datetime'

describe('toInstant', () => {
  it('datetime-local bare 값을 로컬 기준으로 해석해 ISO Instant 문자열로 변환한다', () => {
    const local = '2026-07-10T09:00'
    const result = toInstant(local)
    expect(result).toBe(new Date(local).toISOString())
  })

  it('빈 문자열은 null을 반환한다', () => {
    expect(toInstant('')).toBeNull()
  })

  it('파싱 불가능한 문자열은 null을 반환한다', () => {
    expect(toInstant('not-a-date')).toBeNull()
  })
})

describe('toLocalInputValue', () => {
  it('null은 빈 문자열을 반환한다', () => {
    expect(toLocalInputValue(null)).toBe('')
  })

  it('ISO Instant를 datetime-local input이 요구하는 "YYYY-MM-DDTHH:mm" 형태로 변환한다', () => {
    const instant = new Date(2026, 6, 10, 9, 0, 0).toISOString()
    const result = toLocalInputValue(instant)
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
    // 왕복 변환 시 분 단위까지 원래 로컬 시각과 일치해야 한다
    expect(toInstant(result)).toBe(new Date(2026, 6, 10, 9, 0, 0).toISOString())
  })
})

describe('formatOooReturnDate', () => {
  it('null이면 null을 반환한다', () => {
    expect(formatOooReturnDate(null)).toBeNull()
  })

  it('ISO Instant를 로컬 기준 "YYYY-MM-DD"로 포맷한다', () => {
    const local = new Date(2026, 6, 14, 15, 0, 0)
    expect(formatOooReturnDate(local.toISOString())).toBe('2026-07-14')
  })

  it('파싱 불가능한 문자열이면 null을 반환한다', () => {
    expect(formatOooReturnDate('not-a-date')).toBeNull()
  })
})
