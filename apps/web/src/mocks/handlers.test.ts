// 통합 handlers 배열 검증 — issueHandlers 포함 여부 + PATCH/DELETE 처리 가능 확인
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { handlers } from './handlers'

const server = setupServer(...handlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('handlers 통합 배열 — issue-tracking BC 엔드포인트 처리 여부', () => {
  it('GET /api/v1/issues — 이슈 목록 응답(content 배열 포함)', async () => {
    const res = await fetch('/api/v1/issues')
    expect(res.status).toBe(200)
    const body = await res.json() as { content: unknown[] }
    expect(Array.isArray(body.content)).toBe(true)
  })

  it('GET /api/v1/issues/ATLAS-1 — 단건 조회({ data: IssueResponse })', async () => {
    const res = await fetch('/api/v1/issues/ATLAS-1')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { key: string } }
    expect(body.data.key).toBe('ATLAS-1')
  })

  it('POST /api/v1/issues — 이슈 생성(201 + { data: ... })', async () => {
    const res = await fetch('/api/v1/issues', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectKey: 'ATLAS', summary: '통합 테스트 이슈' }),
    })
    expect(res.status).toBe(201)
    const body = await res.json() as { data: { key: string } }
    expect(typeof body.data.key).toBe('string')
  })

  it('PATCH /api/v1/issues/ATLAS-1 — 이슈 수정(200 + { data: ... } version+1)', async () => {
    const res = await fetch('/api/v1/issues/ATLAS-1', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ summary: '수정된 요약', version: 0 }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { key: string; version: number } }
    expect(body.data.key).toBe('ATLAS-1')
    expect(body.data.version).toBe(1)
  })

  it('DELETE /api/v1/issues/ATLAS-1 — 이슈 삭제(204 No Content)', async () => {
    const res = await fetch('/api/v1/issues/ATLAS-1', {
      method: 'DELETE',
    })
    expect(res.status).toBe(204)
  })
})
