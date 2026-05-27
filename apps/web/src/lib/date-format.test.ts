// formatDate 유틸 단위 테스트 — KST(Asia/Seoul) 기준 날짜 변환 검증
import { describe, expect, it } from 'vitest'
import { formatDate } from './date-format'

describe('formatDate', () => {
  it('null 입력 → "—" 반환', () => {
    expect(formatDate(null)).toBe('—')
  })

  it('UTC 자정 근처 instant(2026-01-01T23:00:00Z)는 KST 기준 다음 날(2026-01-02)로 표시', () => {
    // UTC 2026-01-01T23:00:00Z = KST 2026-01-02T08:00:00+09:00
    // 현재 구현(UTC 기준)은 "2026-01-01 23:00" 반환 → 실패해야 함
    const result = formatDate('2026-01-01T23:00:00Z')
    expect(result).toMatch(/^2026-01-02/)
  })

  it('KST 정오(2026-05-27T03:00:00Z)는 2026-05-27 12:00로 표시', () => {
    // UTC 2026-05-27T03:00:00Z = KST 2026-05-27T12:00:00+09:00
    const result = formatDate('2026-05-27T03:00:00Z')
    expect(result).toBe('2026-05-27 12:00')
  })

  it('updatedAt 날짜(2026-01-03T11:00:00Z)는 KST 기준 2026-01-03 20:00로 표시', () => {
    // UTC 2026-01-03T11:00:00Z = KST 2026-01-03T20:00:00+09:00
    const result = formatDate('2026-01-03T11:00:00Z')
    expect(result).toBe('2026-01-03 20:00')
  })
})
