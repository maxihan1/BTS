// CfdChart 데이터 변환 순수 함수 toCfdSeries 단위 테스트
/**
 * recharts ResponsiveContainer 가 jsdom 에서 width/height=0 이라 차트 내부가 렌더되지 않으므로
 * 순수 변환 함수(toCfdSeries)만 단위 테스트한다.
 * 실 렌더(시각 검증)는 e2e 스펙에 위임한다 (FR-RP-01/02 D6/D7 선례).
 */
import { describe, it, expect } from 'vitest'
import type { CfdResponse } from '@/api/cfd'
import { toCfdSeries } from './CfdChart'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const cfdFixture: CfdResponse = {
  projectKey: 'BTS',
  from: '2026-06-01',
  to: '2026-06-03',
  points: [
    { date: '2026-06-01', todoCount: 10, inProgressCount: 2, doneCount: 0 },
    { date: '2026-06-02', todoCount: 8, inProgressCount: 3, doneCount: 1 },
    { date: '2026-06-03', todoCount: 6, inProgressCount: 4, doneCount: 3 },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// toCfdSeries — 순수 함수 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('toCfdSeries', () => {
  it('points를 {date, todo, inProgress, done} 배열로 변환한다', () => {
    const result = toCfdSeries(cfdFixture)
    expect(result).toHaveLength(3)
    expect(result[0]).toEqual({ date: '2026-06-01', todo: 10, inProgress: 2, done: 0 })
    expect(result[1]).toEqual({ date: '2026-06-02', todo: 8, inProgress: 3, done: 1 })
    expect(result[2]).toEqual({ date: '2026-06-03', todo: 6, inProgress: 4, done: 3 })
  })

  it('points가 빈 배열이면 빈 배열을 반환한다', () => {
    const empty: CfdResponse = { ...cfdFixture, points: [] }
    expect(toCfdSeries(empty)).toEqual([])
  })
})
