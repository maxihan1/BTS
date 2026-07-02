// VelocityChart 데이터 변환 순수 함수 toVelocitySeries 단위 테스트
/**
 * recharts ResponsiveContainer 가 jsdom 에서 width/height=0 이라 차트 내부가 렌더되지 않으므로
 * 순수 변환 함수(toVelocitySeries)만 단위 테스트한다.
 * 실 렌더(시각 검증)는 e2e/project-velocity.spec.ts 에 위임한다 (FR-RP-01 D6/D7 선례).
 */
import { describe, it, expect } from 'vitest'
import type { VelocityResponse } from '@/api/velocity'
import { toVelocitySeries } from './VelocityChart'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const velocityFixture: VelocityResponse = {
  projectKey: 'BTS',
  averageCommitmentSeconds: 30600,
  averageCompletedSeconds: 25200,
  sprints: [
    {
      sprintId: '11111111-1111-4111-8111-111111111111',
      name: 'Sprint 1',
      startDate: '2026-06-01',
      endDate: '2026-06-14',
      commitmentSeconds: 36000,
      completedSeconds: 28800,
    },
    {
      sprintId: '22222222-2222-4222-8222-222222222222',
      name: 'Sprint 2',
      startDate: null,
      endDate: null,
      commitmentSeconds: 25200,
      completedSeconds: 21600,
    },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// toVelocitySeries — 순수 함수 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('toVelocitySeries', () => {
  it('sprints를 {name, commitment, completed} 배열로 변환한다', () => {
    const result = toVelocitySeries(velocityFixture)
    expect(result).toHaveLength(2)
    expect(result[0]).toEqual({ name: 'Sprint 1', commitment: 36000, completed: 28800 })
    expect(result[1]).toEqual({ name: 'Sprint 2', commitment: 25200, completed: 21600 })
  })

  it('sprints가 빈 배열이면 빈 배열을 반환한다', () => {
    const empty: VelocityResponse = { ...velocityFixture, sprints: [] }
    expect(toVelocitySeries(empty)).toEqual([])
  })

  it('startDate/endDate가 null인 스프린트도 name 기준으로 정상 매핑한다', () => {
    const result = toVelocitySeries(velocityFixture)
    const sprint2 = result[1]
    expect(sprint2).toBeDefined()
    expect(sprint2).toEqual({ name: 'Sprint 2', commitment: 25200, completed: 21600 })
  })
})
