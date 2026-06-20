// 작업 시간(초) ↔ 시간/분 변환·표시 순수 함수 단위 테스트

import { describe, expect, it } from 'vitest'
import { formatSeconds, parseHm } from './duration'

describe('parseHm', () => {
  it('시간과 분을 초로 변환한다 — 2h 30m → 9000', () => {
    expect(parseHm({ hours: 2, minutes: 30 })).toBe(9000)
  })

  it('0h 0m → 0', () => {
    expect(parseHm({ hours: 0, minutes: 0 })).toBe(0)
  })

  it('음수 hours는 0으로 floor 처리한다', () => {
    expect(parseHm({ hours: -1, minutes: 30 })).toBe(1800)
  })

  it('음수 minutes는 0으로 floor 처리한다', () => {
    expect(parseHm({ hours: 1, minutes: -10 })).toBe(3600)
  })

  it('hours만 있을 때 — 1h 0m → 3600', () => {
    expect(parseHm({ hours: 1, minutes: 0 })).toBe(3600)
  })
})

describe('formatSeconds', () => {
  it('9000초 → "2h 30m"', () => {
    expect(formatSeconds(9000)).toBe('2h 30m')
  })

  it('2700초(45분) → "45m"', () => {
    expect(formatSeconds(2700)).toBe('45m')
  })

  it('0초 → "0m"', () => {
    expect(formatSeconds(0)).toBe('0m')
  })

  it('3600초 → "1h 0m"', () => {
    expect(formatSeconds(3600)).toBe('1h 0m')
  })

  it('360000초 → "100h 0m" (일/주 단위 변환 없음)', () => {
    expect(formatSeconds(360000)).toBe('100h 0m')
  })

  it('음수 초는 0으로 floor 처리 → "0m"', () => {
    expect(formatSeconds(-100)).toBe('0m')
  })
})
