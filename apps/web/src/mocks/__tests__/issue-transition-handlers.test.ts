// GET /api/v1/issues/:key/transitions + POST /api/v1/issues/:key/transition MSW 핸들러 단위 테스트
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { issueHandlers, resetIssueState, MOCK_NO_WORKFLOW_TRIGGER } from '../issue-handlers'
import { issueAtlasNoWorkflowFixture } from '../issue-fixtures'

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

async function getTransitions(key: string): Promise<Response> {
  return fetch(`/api/v1/issues/${key}/transitions`)
}

async function postTransition(
  key: string,
  body: Record<string, unknown>,
): Promise<Response> {
  return fetch(`/api/v1/issues/${key}/transition`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/issues/:key/transitions
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/issues/:key/transitions', () => {
  it('존재하지 않는 key → 404 반환', async () => {
    const res = await getTransitions('ATLAS-999')
    expect(res.status).toBe(404)
  })

  it('ATLAS-1 (open) → Start Work + Cancel 2개 반환', async () => {
    const res = await getTransitions('ATLAS-1')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: { key: string; name: string; fromStateKey: string; toStateKey: string }[] } }
    const { transitions } = body.data
    expect(transitions).toHaveLength(2)
    const keys = transitions.map(t => t.key)
    expect(keys).toContain('open__in_progress')
    expect(keys).toContain('open__closed')
    const names = transitions.map(t => t.name)
    expect(names).toContain('Start Work')
    expect(names).toContain('Cancel')
  })

  it('ATLAS-2 (in_progress) → Submit for Review 1개 반환', async () => {
    const res = await getTransitions('ATLAS-2')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: { key: string; name: string }[] } }
    const { transitions } = body.data
    expect(transitions).toHaveLength(1)
    expect(transitions[0]?.key).toBe('in_progress__in_review')
    expect(transitions[0]?.name).toBe('Submit for Review')
  })

  it('ATLAS-3 (done) → Close 1개 반환', async () => {
    const res = await getTransitions('ATLAS-3')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: { key: string; name: string }[] } }
    const { transitions } = body.data
    expect(transitions).toHaveLength(1)
    expect(transitions[0]?.key).toBe('done__closed')
    expect(transitions[0]?.name).toBe('Close')
  })

  it('closed 상태 이슈 → 빈 배열 반환', async () => {
    // issueAtlas4ClosedFixture (closed 상태, ATLAS-4)
    const res = await getTransitions('ATLAS-4')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { transitions: unknown[] } }
    expect(body.data.transitions).toHaveLength(0)
  })

  it('ATLAS-NOWF (워크플로우 미설정) → 422 반환 + errorCode=workflow_not_configured', async () => {
    const res = await getTransitions(issueAtlasNoWorkflowFixture.key)
    expect(res.status).toBe(422)
    const body = await res.json() as { errorCode: string; message: string }
    expect(body.errorCode).toBe('workflow_not_configured')
  })

  it('ATLAS-NOWF GET 단건 → 200 정상 반환 (상세 진입은 가능)', async () => {
    const res = await fetch(`/api/v1/issues/${issueAtlasNoWorkflowFixture.key}`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { key: string } }
    expect(body.data.key).toBe(issueAtlasNoWorkflowFixture.key)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/issues/:key/transition
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/issues/:key/transition — 분기(1) 404 미존재', () => {
  it('존재하지 않는 key → 404 반환', async () => {
    const res = await postTransition('ATLAS-999', { toStatusKey: 'in_progress', expectedVersion: 0 })
    expect(res.status).toBe(404)
  })
})

describe('POST /api/v1/issues/:key/transition — 분기(2) 422 워크플로우 미설정', () => {
  it('MOCK_NO_WORKFLOW_TRIGGER toStatusKey → 422 반환', async () => {
    const res = await postTransition('ATLAS-1', {
      toStatusKey: MOCK_NO_WORKFLOW_TRIGGER,
      expectedVersion: 0,
    })
    expect(res.status).toBe(422)
  })
})

describe('POST /api/v1/issues/:key/transition — 분기(3) 409', () => {
  it('expectedVersion 불일치 → 409 version_conflict', async () => {
    // ATLAS-1 현재 version=0, 9999 전달해 불일치 유발
    const res = await postTransition('ATLAS-1', {
      toStatusKey: 'in_progress',
      expectedVersion: 9999,
    })
    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('version_conflict')
  })

  it('MOCK_CONFLICT_TRIGGER toStatusKey → 409 transition_not_allowed', async () => {
    const { MOCK_CONFLICT_TRIGGER } = await import('../issue-handlers')
    const res = await postTransition('ATLAS-1', {
      toStatusKey: MOCK_CONFLICT_TRIGGER,
      expectedVersion: 0,
    })
    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('transition_not_allowed')
  })
})

describe('POST /api/v1/issues/:key/transition — 분기(4) 성공 + stateful', () => {
  it('open→in_progress 전이 성공 → 200 + currentStateKey=in_progress + version+1', async () => {
    const res = await postTransition('ATLAS-1', {
      toStatusKey: 'in_progress',
      expectedVersion: 0,
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { currentStateKey: string; version: number } }
    expect(body.data.currentStateKey).toBe('in_progress')
    expect(body.data.version).toBe(1) // ATLAS-1 초기 version=0, +1
  })

  it('전이 후 GET /:key 새 상태 반영', async () => {
    await postTransition('ATLAS-1', { toStatusKey: 'in_progress', expectedVersion: 0 })
    const res = await fetch('/api/v1/issues/ATLAS-1')
    const body = await res.json() as { data: { currentStateKey: string } }
    expect(body.data.currentStateKey).toBe('in_progress')
  })

  it('전이 후 GET /transitions 새 가용전이 반영 (in_progress → Submit for Review)', async () => {
    await postTransition('ATLAS-1', { toStatusKey: 'in_progress', expectedVersion: 0 })
    const res = await getTransitions('ATLAS-1')
    const body = await res.json() as { data: { transitions: { key: string }[] } }
    expect(body.data.transitions).toHaveLength(1)
    expect(body.data.transitions[0]?.key).toBe('in_progress__in_review')
  })

  it('resetIssueState() 후 상태 초기화 — ATLAS-1 다시 open', async () => {
    await postTransition('ATLAS-1', { toStatusKey: 'in_progress', expectedVersion: 0 })
    resetIssueState()
    const res = await fetch('/api/v1/issues/ATLAS-1')
    const body = await res.json() as { data: { currentStateKey: string } }
    expect(body.data.currentStateKey).toBe('open')
  })
})
