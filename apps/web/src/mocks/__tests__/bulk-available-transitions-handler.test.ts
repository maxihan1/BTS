// POST /api/v1/issues/bulk-transitions/available MSW 핸들러 단위 테스트
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { issueHandlers, resetIssueState } from '../issue-handlers'
import { bulkAvailableTransitionsSchema } from '@/api/issues'

const server = setupServer(...issueHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetIssueState()
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function postBulkAvailableTransitions(issueKeys: string[]): Promise<Response> {
  return fetch('/api/v1/issues/bulk-transitions/available', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ issueKeys }),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/issues/bulk-transitions/available
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/issues/bulk-transitions/available — 공통 전이 교집합', () => {
  it('ATLAS-1(open) + ATLAS-3(done) 공통 toStateKey=closed 전이 반환', async () => {
    const res = await postBulkAvailableTransitions(['ATLAS-1', 'ATLAS-3'])
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: { toStateKey: string }[]; unresolvedIssueKeys: string[] } }
    const { transitions, unresolvedIssueKeys } = body.data
    // ATLAS-1(open): open__in_progress, open__closed
    // ATLAS-3(done): done__closed
    // 교집합(toStateKey): closed → done__closed 는 교집합 아님, open__closed 도 교집합 아님
    // open의 closed 전이 toStateKey=closed, done의 closed 전이 toStateKey=closed → toStateKey 기준 교집합
    const toKeys = transitions.map((t) => t.toStateKey)
    expect(toKeys).toContain('closed')
    expect(unresolvedIssueKeys).toEqual([])
  })

  it('존재하지 않는 키 포함 → unresolvedIssueKeys에 추가', async () => {
    const res = await postBulkAvailableTransitions(['ATLAS-1', 'ATLAS-9999'])
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: unknown[]; unresolvedIssueKeys: string[] } }
    expect(body.data.unresolvedIssueKeys).toContain('ATLAS-9999')
  })

  it('워크플로우 미설정 이슈(ATLAS-NOWF) 포함 → unresolvedIssueKeys에 추가', async () => {
    const res = await postBulkAvailableTransitions(['ATLAS-1', 'ATLAS-NOWF'])
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: unknown[]; unresolvedIssueKeys: string[] } }
    expect(body.data.unresolvedIssueKeys).toContain('ATLAS-NOWF')
  })

  it('소프트 삭제된 이슈 포함 → unresolvedIssueKeys에 추가', async () => {
    // 먼저 ATLAS-1 소프트 삭제
    await fetch('/api/v1/issues/ATLAS-1', { method: 'DELETE' })
    const res = await postBulkAvailableTransitions(['ATLAS-1', 'ATLAS-3'])
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: unknown[]; unresolvedIssueKeys: string[] } }
    expect(body.data.unresolvedIssueKeys).toContain('ATLAS-1')
  })

  it('교집합이 0인 이슈 조합 → transitions=[]', async () => {
    // ATLAS-2(in_progress): in_progress__in_review (toStateKey=in_review)
    // ATLAS-3(done): done__closed (toStateKey=closed)
    // 공통 toStateKey 없음 → transitions=[]
    const res = await postBulkAvailableTransitions(['ATLAS-2', 'ATLAS-3'])
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: unknown[]; unresolvedIssueKeys: string[] } }
    expect(body.data.transitions).toHaveLength(0)
  })

  it('응답이 bulkAvailableTransitionsSchema.parse 통과', async () => {
    const res = await postBulkAvailableTransitions(['ATLAS-1', 'ATLAS-3'])
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown }
    expect(() => bulkAvailableTransitionsSchema.parse(body.data)).not.toThrow()
  })
})

describe('POST /api/v1/issues/bulk-transitions/available — 단일 이슈', () => {
  it('단일 이슈 ATLAS-1(open) → open에서의 전이 모두 반환', async () => {
    const res = await postBulkAvailableTransitions(['ATLAS-1'])
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: { key: string }[] } }
    const keys = body.data.transitions.map((t) => t.key)
    expect(keys).toContain('open__in_progress')
    expect(keys).toContain('open__closed')
  })
})

describe('POST /api/v1/issues/bulk-transitions/available — 전체 미해결', () => {
  it('모든 이슈가 미존재인 경우 → transitions=[], unresolvedIssueKeys에 모두 포함', async () => {
    const res = await postBulkAvailableTransitions(['ATLAS-8888', 'ATLAS-9999'])
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: unknown[]; unresolvedIssueKeys: string[] } }
    expect(body.data.transitions).toHaveLength(0)
    expect(body.data.unresolvedIssueKeys).toContain('ATLAS-8888')
    expect(body.data.unresolvedIssueKeys).toContain('ATLAS-9999')
  })
})
