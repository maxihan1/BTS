// 목록 안 순서 이동 4종(맨 위로·위로·아래로·맨 아래로)의 이웃 계산 · 비활성 판정 테스트 (JB-B-2 · JCB-17)
import { describe, it, expect, vi } from 'vitest'
import { buildInListMoves } from './issue-move-actions-context'

/** 라벨 → { disabled, 고르면 받는 이웃 } */
function run(keys: string[], issueKey: string) {
  const rerank = vi.fn()
  const moves = buildInListMoves(keys, issueKey, rerank)
  return Object.fromEntries(
    moves.map((m) => {
      rerank.mockClear()
      m.onSelect()
      return [m.label, { disabled: m.disabled, neighbors: rerank.mock.calls[0]?.[0] }]
    }),
  )
}

describe('buildInListMoves', () => {
  it('가운데 이슈 — 4종 모두 활성이고 이웃이 맞다', () => {
    const r = run(['A', 'B', 'C', 'D'], 'C')
    expect(r['맨 위로']).toEqual({ disabled: false, neighbors: { previousIssueKey: undefined, nextIssueKey: 'A' } })
    expect(r['위로']).toEqual({ disabled: false, neighbors: { previousIssueKey: 'A', nextIssueKey: 'B' } })
    expect(r['아래로']).toEqual({ disabled: false, neighbors: { previousIssueKey: 'D', nextIssueKey: undefined } })
    expect(r['맨 아래로']).toEqual({ disabled: false, neighbors: { previousIssueKey: 'D', nextIssueKey: undefined } })
  })

  it('맨 위 이슈 — 맨 위로 · 위로가 비활성', () => {
    const r = run(['A', 'B', 'C'], 'A')
    expect(r['맨 위로']?.disabled).toBe(true)
    expect(r['위로']?.disabled).toBe(true)
    expect(r['아래로']).toEqual({ disabled: false, neighbors: { previousIssueKey: 'B', nextIssueKey: 'C' } })
  })

  it('맨 아래 이슈 — 아래로 · 맨 아래로가 비활성', () => {
    const r = run(['A', 'B', 'C'], 'C')
    expect(r['아래로']?.disabled).toBe(true)
    expect(r['맨 아래로']?.disabled).toBe(true)
  })

  it('혼자인 목록 — 전부 비활성', () => {
    const r = run(['A'], 'A')
    expect(Object.values(r).every((m) => m.disabled)).toBe(true)
  })
})
