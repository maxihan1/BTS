// status-expiry 유틸 단위 테스트 — 프리셋별 만료 시각 계산 검증
import { describe, it, expect } from 'vitest'
import { resolveExpiry, EXPIRY_PRESETS } from './status-expiry'

describe('EXPIRY_PRESETS', () => {
  it('명세의 6개 프리셋을 모두 포함한다', () => {
    expect(EXPIRY_PRESETS).toEqual(['none', '30m', '1h', '4h', 'today', 'week'])
  })
})

describe('resolveExpiry', () => {
  const now = new Date('2026-07-06T05:30:00Z')

  it('none은 만료 없음(null)을 반환한다', () => {
    expect(resolveExpiry('none', now)).toBeNull()
  })

  it('30m은 now + 30분의 ISO Instant를 반환한다', () => {
    const result = resolveExpiry('30m', now)
    expect(result).toBe(new Date(now.getTime() + 30 * 60 * 1000).toISOString())
  })

  it('1h은 now + 1시간의 ISO Instant를 반환한다', () => {
    const result = resolveExpiry('1h', now)
    expect(result).toBe(new Date(now.getTime() + 60 * 60 * 1000).toISOString())
  })

  it('4h은 now + 4시간의 ISO Instant를 반환한다', () => {
    const result = resolveExpiry('4h', now)
    expect(result).toBe(new Date(now.getTime() + 4 * 60 * 60 * 1000).toISOString())
  })

  it('today는 사용자 로컬 기준 오늘 23:59:59.999의 ISO Instant를 반환한다', () => {
    const result = resolveExpiry('today', now)
    const expected = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59, 999)
    expect(result).toBe(expected.toISOString())
  })

  it('week는 사용자 로컬 기준 이번 주 일요일 23:59:59.999의 ISO Instant를 반환한다(월요일 시작 주)', () => {
    const result = resolveExpiry('week', now)
    const daysUntilSunday = (7 - now.getDay()) % 7
    const expected = new Date(
      now.getFullYear(),
      now.getMonth(),
      now.getDate() + daysUntilSunday,
      23,
      59,
      59,
      999,
    )
    expect(result).toBe(expected.toISOString())
  })

  it('week — 로컬 기준 일요일 당일이면 당일 23:59:59.999를 반환한다(daysUntilSunday=0 경계)', () => {
    // 로컬 컴포넌트로 직접 생성 — TZ 변환 왕복 없이 getDay()가 결정적으로 일요일(0)이 되도록 고정
    const sunday = new Date(2026, 6, 5, 12, 0, 0)
    expect(sunday.getDay()).toBe(0)

    const result = resolveExpiry('week', sunday)
    const expected = new Date(2026, 6, 5, 23, 59, 59, 999)
    expect(result).toBe(expected.toISOString())
  })
})
