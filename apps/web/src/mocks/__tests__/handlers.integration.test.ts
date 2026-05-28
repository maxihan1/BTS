// handlers 통합 배열 회귀 + schemeHandlers/issueTypeHandlers spread 포함 여부 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { handlers } from '../handlers'

const server = setupServer(...handlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 기존 핸들러 회귀 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('handlers 통합 배열 — 기존 BC 엔드포인트 회귀 검증', () => {
  it('GET /api/v1/issues — issue-tracking BC 응답 유지(200 + content 배열)', async () => {
    const res = await fetch('/api/v1/issues')
    expect(res.status).toBe(200)
    const body = await res.json() as { content: unknown[] }
    expect(Array.isArray(body.content)).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// schemeHandlers spread 포함 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('handlers 통합 배열 — schemeHandlers spread 포함', () => {
  it('GET /api/v1/workflow-schemes — 200 + data 배열 반환', async () => {
    const res = await fetch('/api/v1/workflow-schemes')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(Array.isArray(body.data)).toBe(true)
    expect((body.data as unknown[]).length).toBeGreaterThanOrEqual(1)
  })

  it('GET /api/v1/workflow-schemes/:schemeKey — 존재하는 키 조회 시 200 반환', async () => {
    const res = await fetch('/api/v1/workflow-schemes/software-default-scheme')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { schemeKey: string } }
    expect(body.data.schemeKey).toBe('software-default-scheme')
  })

  it('GET /api/v1/projects/:projectKey/workflow-scheme — 할당 조회 200 반환', async () => {
    const res = await fetch('/api/v1/projects/ATLAS/workflow-scheme')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { projectKey: string } }
    expect(body.data.projectKey).toBe('ATLAS')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// issueTypeHandlers spread 포함 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('handlers 통합 배열 — issueTypeHandlers spread 포함', () => {
  it('GET /api/v1/issue-types — 200 + data 배열(5개) 반환', async () => {
    const res = await fetch('/api/v1/issue-types')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(Array.isArray(body.data)).toBe(true)
    expect(body.data).toHaveLength(5)
  })

  it('GET /api/v1/issue-types — bug/story/task/epic/subtask 모두 포함', async () => {
    const res = await fetch('/api/v1/issue-types')
    const body = await res.json() as { data: Array<{ key: string }> }
    const keys = body.data.map((t) => t.key)
    expect(keys).toContain('bug')
    expect(keys).toContain('story')
    expect(keys).toContain('task')
    expect(keys).toContain('epic')
    expect(keys).toContain('subtask')
  })
})
