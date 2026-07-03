// 초 단위 소요 시간을 한국어 문자열로 변환하는 포맷터 단위 테스트

import { describe, expect, it } from 'vitest'
import { formatDuration, formatDurationRange } from './format-duration'

describe('formatDuration', () => {
  it('0초 → "0초"', () => {
    expect(formatDuration(0)).toBe('0초')
  })

  it('59초 → "59초" (초 단위 상한)', () => {
    expect(formatDuration(59)).toBe('59초')
  })

  it('60초 → "1분" (분 단위 시작)', () => {
    expect(formatDuration(60)).toBe('1분')
  })

  it('3599초 → "59분" (분 단위 상한 — 잔여 59초는 버림)', () => {
    expect(formatDuration(3599)).toBe('59분')
  })

  it('3600초 → "1시간" (시간 단위 시작, 0분이면 분 생략)', () => {
    expect(formatDuration(3600)).toBe('1시간')
  })

  it('3660초 → "1시간 1분"', () => {
    expect(formatDuration(3660)).toBe('1시간 1분')
  })

  it('7200초 → "2시간" (0분이면 분 생략)', () => {
    expect(formatDuration(7200)).toBe('2시간')
  })

  it('86400초 → "1일" (일 단위 시작, 0시간이면 시간 생략)', () => {
    expect(formatDuration(86400)).toBe('1일')
  })

  it('90000초 → "1일 1시간"', () => {
    expect(formatDuration(90000)).toBe('1일 1시간')
  })

  it('200000초(큰 값) → "2일 7시간"', () => {
    expect(formatDuration(200000)).toBe('2일 7시간')
  })

  it('음수는 0으로 방어 처리 → "0초"', () => {
    expect(formatDuration(-100)).toBe('0초')
  })
})

describe('formatDurationRange', () => {
  it('두 formatDuration 결과를 "~"로 연결한다', () => {
    expect(formatDurationRange(0, 3600)).toBe('0초 ~ 1시간')
  })

  it('일 단위 구간도 동일하게 연결한다', () => {
    expect(formatDurationRange(86400, 200000)).toBe('1일 ~ 2일 7시간')
  })
})
