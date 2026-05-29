// PATCH /api/v1/issues/:key — typeId 처리 + expectedVersion 필드 정합 단위 테스트
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { issueHandlers, resetIssueState } from '../issue-handlers'

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

async function patchIssue(
  key: string,
  body: Record<string, unknown>,
): Promise<Response> {
  return fetch(`/api/v1/issues/${key}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 분기 (1) — 이슈 not-found → 404
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key — 이슈 not-found', () => {
  it('존재하지 않는 key → 404 반환', async () => {
    const res = await patchIssue('ATLAS-999', { expectedVersion: 0 })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 분기 (2) — typeId 검증 실패 → 404
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key — typeId 검증', () => {
  it('존재하지 않는 typeId → 404 반환', async () => {
    const res = await patchIssue('ATLAS-1', { typeId: 9999, expectedVersion: 0 })
    expect(res.status).toBe(404)
  })

  it('유효한 typeId → 200 + typeId/typeKey/typeName 갱신', async () => {
    // issue-type-fixtures: id=3 은 task (key: 'task', name: '작업')
    const res = await patchIssue('ATLAS-1', { typeId: 3, expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { typeId: number; typeKey: string; typeName: string } }
    expect(body.data.typeId).toBe(3)
    expect(body.data.typeKey).toBe('task')
    expect(body.data.typeName).toBe('작업')
  })

  it('typeId 미전달 시 기존 타입 유지', async () => {
    // ATLAS-1 초기 typeId=1 (bug)
    const res = await patchIssue('ATLAS-1', { summary: '수정된 요약', expectedVersion: 0 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { typeId: number; typeKey: string; typeName: string } }
    expect(body.data.typeId).toBe(1)
    expect(body.data.typeKey).toBe('bug')
    expect(body.data.typeName).toBe('버그')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 분기 (3) — version 충돌 → 409
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key — version 충돌', () => {
  it('MOCK_CONFLICT_TRIGGER summary → 409 VERSION_CONFLICT', async () => {
    // 기존 E2E-5 회귀 가드 — summary 트리거 방식은 유지
    const { MOCK_CONFLICT_TRIGGER } = await import('../issue-handlers')
    const res = await patchIssue('ATLAS-1', {
      summary: MOCK_CONFLICT_TRIGGER,
      expectedVersion: 0,
    })
    expect(res.status).toBe(409)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('VERSION_CONFLICT')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 분기 (4) — 성공 케이스 종합
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/issues/:key — 성공', () => {
  it('summary + typeId 동시 수정 → 200 + version+1 + 두 필드 모두 갱신', async () => {
    // issue-type-fixtures: id=4 은 epic (key: 'epic', name: '에픽')
    const res = await patchIssue('ATLAS-2', {
      summary: '수정된 요약',
      typeId: 4,
      expectedVersion: 1,
    })
    expect(res.status).toBe(200)
    const body = await res.json() as {
      data: {
        summary: string
        typeId: number
        typeKey: string
        typeName: string
        version: number
      }
    }
    expect(body.data.summary).toBe('수정된 요약')
    expect(body.data.typeId).toBe(4)
    expect(body.data.typeKey).toBe('epic')
    expect(body.data.typeName).toBe('에픽')
    expect(body.data.version).toBe(2) // ATLAS-2 초기 version=1, +1
  })

  it('expectedVersion 필드로 이슈 조회 가능 — version 필드 하위호환 없음', async () => {
    // updateIssue API 함수가 expectedVersion 을 전송함을 MSW 핸들러가 올바르게 수신하는지 검증
    const res = await patchIssue('ATLAS-3', { expectedVersion: 2 })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { version: number } }
    expect(body.data.version).toBe(3) // ATLAS-3 초기 version=2, +1
  })
})
