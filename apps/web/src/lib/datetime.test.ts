// datetime 유틸 단위 테스트 — formatDateTime ISO→iso 프리셋(YYYY-MM-DD HH:mm) 위임 검증 (FR-PF-01 Task 8)
import { describe, it, expect } from 'vitest'
import { formatDateTime } from './datetime'

describe('formatDateTime', () => {
  it('ISO 8601 문자열을 iso 프리셋(YYYY-MM-DD HH:mm, Asia/Seoul) 형식으로 변환한다', () => {
    // UTC 2026-05-01T00:00:00Z = KST(Asia/Seoul, UTC+9) 2026-05-01T09:00:00+09:00
    expect(formatDateTime('2026-05-01T00:00:00Z')).toBe('2026-05-01 09:00')
  })

  it('UTC 자정 기준 instant도 KST 기준 날짜+시각 문자열로 반환된다', () => {
    // UTC 2026-06-01T10:00:00Z = KST 2026-06-01T19:00:00+09:00
    expect(formatDateTime('2026-06-01T10:00:00Z')).toBe('2026-06-01 19:00')
  })

  it('시각 부분(HH:mm, 24시간제)이 포함된다', () => {
    // UTC 2026-05-29T10:00:00Z = KST 2026-05-29T19:00:00+09:00
    expect(formatDateTime('2026-05-29T10:00:00Z')).toBe('2026-05-29 19:00')
  })

  it('연도가 4자리 숫자로 포함된다', () => {
    // UTC 2026-06-15T10:00:00Z = KST 2026-06-15T19:00:00+09:00
    expect(formatDateTime('2026-06-15T10:00:00Z')).toBe('2026-06-15 19:00')
  })
})
