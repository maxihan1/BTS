// preference-aware 날짜 포맷터(date-preferences.ts) 단위 테스트 — 프리셋별 순수 포맷 검증 (FR-PF-01)
import { describe, expect, it } from 'vitest'
import { formatDateByPreset, formatDateTimeByPreset, isDatePreset } from './date-preferences'

// UTC 2026-07-07T03:00:00Z = KST(Asia/Seoul, UTC+9) 2026-07-07T12:00:00+09:00 (정오, 자정 근처 아님 — 날짜 안전지대)
const KST_2026_07_07_NOON = '2026-07-07T03:00:00Z'
// UTC 2026-07-08T03:00:00Z = KST 2026-07-08T12:00:00+09:00 — 일(day)=08 ≠ 월(month)=07이라 us/eu 순서 구분에 사용
const KST_2026_07_08_NOON = '2026-07-08T03:00:00Z'

describe('formatDateByPreset', () => {
  it('null 입력 → "—" 반환', () => {
    expect(formatDateByPreset(null, 'iso')).toBe('—')
  })

  it('iso 프리셋 → YYYY-MM-DD', () => {
    expect(formatDateByPreset(KST_2026_07_07_NOON, 'iso')).toBe('2026-07-07')
  })

  it('kr 프리셋 → "YYYY. MM. DD."', () => {
    expect(formatDateByPreset(KST_2026_07_07_NOON, 'kr')).toBe('2026. 07. 07.')
  })

  it('us 프리셋 → MM/DD/YYYY', () => {
    expect(formatDateByPreset(KST_2026_07_08_NOON, 'us')).toBe('07/08/2026')
  })

  it('eu 프리셋 → DD/MM/YYYY (us와 월/일 순서가 반대)', () => {
    expect(formatDateByPreset(KST_2026_07_08_NOON, 'eu')).toBe('08/07/2026')
  })

  it('타임존은 기본 Asia/Seoul 고정 — UTC 자정 근처 instant도 KST 기준 날짜로 표시', () => {
    // UTC 2026-01-01T23:00:00Z = KST 2026-01-02T08:00:00+09:00 (하루 밀림 확인)
    expect(formatDateByPreset('2026-01-01T23:00:00Z', 'iso')).toBe('2026-01-02')
  })

  it('tz 인자를 명시하면 해당 타임존 기준으로 포맷한다', () => {
    // UTC 2026-01-01T23:00:00Z를 UTC 기준으로 포맷하면 그대로 2026-01-01
    expect(formatDateByPreset('2026-01-01T23:00:00Z', 'iso', 'UTC')).toBe('2026-01-01')
  })
})

describe('formatDateTimeByPreset', () => {
  it('null 입력 → "—" 반환', () => {
    expect(formatDateTimeByPreset(null, 'iso')).toBe('—')
  })

  it('날짜 프리셋 + 공백 + HH:mm(24시간제)로 포맷한다', () => {
    // UTC 2026-07-07T13:30:00Z = KST 2026-07-07T22:30:00+09:00
    expect(formatDateTimeByPreset('2026-07-07T13:30:00Z', 'iso')).toBe('2026-07-07 22:30')
  })

  it('자정(00:00)은 "24:00"이 아니라 "00:00"으로 표시한다', () => {
    // UTC 2026-07-06T15:00:00Z = KST 2026-07-07T00:00:00+09:00
    expect(formatDateTimeByPreset('2026-07-06T15:00:00Z', 'iso')).toBe('2026-07-07 00:00')
  })

  it('kr 프리셋도 날짜부만 프리셋을 따르고 시각부는 동일하게 HH:mm', () => {
    expect(formatDateTimeByPreset('2026-07-07T13:30:00Z', 'kr')).toBe('2026. 07. 07. 22:30')
  })
})

describe('isDatePreset', () => {
  it('지원 프리셋(iso/kr/us/eu) → true', () => {
    expect(isDatePreset('iso')).toBe(true)
    expect(isDatePreset('kr')).toBe(true)
    expect(isDatePreset('us')).toBe(true)
    expect(isDatePreset('eu')).toBe(true)
  })

  it('지원하지 않는 값 → false', () => {
    expect(isDatePreset('jp')).toBe(false)
    expect(isDatePreset('')).toBe(false)
  })
})
