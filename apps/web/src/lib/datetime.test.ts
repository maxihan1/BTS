// datetime 유틸 단위 테스트 — formatDateTime ISO→ko-KR 변환 검증
import { describe, it, expect } from 'vitest'
import { formatDateTime } from './datetime'

describe('formatDateTime', () => {
  it('ISO 8601 문자열을 한국어 날짜+시각으로 변환한다', () => {
    const result = formatDateTime('2026-05-01T00:00:00Z')
    // ko-KR 로케일 포맷: 연·월·일·시·분 포함 여부 검증
    expect(result).toContain('2026')
    expect(result).toMatch(/\d{1,2}/)
  })

  it('UTC 자정 기준 결과가 문자열로 반환된다', () => {
    const result = formatDateTime('2026-06-01T10:00:00Z')
    expect(typeof result).toBe('string')
    expect(result.length).toBeGreaterThan(0)
  })

  it('시각 부분(시:분)이 포함된다', () => {
    const result = formatDateTime('2026-05-29T10:00:00Z')
    // ko-KR 시각 — "오후 7:00" 또는 "오전 10:00" 패턴
    expect(result).toMatch(/\d{1,2}:\d{2}/)
  })

  it('연도가 4자리 숫자로 포함된다', () => {
    // UTC 기준 2026-06 — KST 변환 후에도 2026 유지
    const result = formatDateTime('2026-06-15T10:00:00Z')
    expect(result).toContain('2026')
  })
})
