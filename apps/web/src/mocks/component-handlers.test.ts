// 컴포넌트 MSW 핸들러 단위 테스트 — stateful CRUD + RFC 7807 에러 구조 검증 (FR-CM-01)
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { componentHandlers, resetComponentStore } from './component-handlers'

const server = setupServer(...componentHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetComponentStore()
  localStorage.clear()
})
afterAll(() => server.close())

const BASE_URL = '/api/v1/projects/ATLAS/components'

// ─────────────────────────────────────────────────────────────────────────────
// GET 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectIdOrKey/components', () => {
  it('활성 컴포넌트 목록을 200으로 반환한다', async () => {
    const res = await fetch(BASE_URL)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(Array.isArray(body.data)).toBe(true)
  })

  it('컴포넌트 목록이 name 오름차순으로 정렬된다', async () => {
    // 두 컴포넌트를 역순으로 추가
    await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Zebra Module' }),
    })
    await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Alpha Module' }),
    })

    const res = await fetch(BASE_URL)
    const body = await res.json() as { data: Array<{ name: string }> }
    const names = body.data.map((c) => c.name)
    const sorted = [...names].sort((a, b) => a.localeCompare(b))
    expect(names).toEqual(sorted)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/projects/:projectIdOrKey/components', () => {
  it('컴포넌트를 성공적으로 생성하면 201을 반환한다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Backend Service', description: '백엔드 서비스 컴포넌트' }),
    })
    expect(res.status).toBe(201)
    const body = await res.json() as { data: { name: string; description: string | null } }
    expect(body.data.name).toBe('Backend Service')
    expect(body.data.description).toBe('백엔드 서비스 컴포넌트')
  })

  it('같은 프로젝트에 이름이 중복되면 409 + COMPONENT_NAME_DUPLICATE errorCode를 반환한다', async () => {
    await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Duplicate Name' }),
    })

    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Duplicate Name' }),
    })

    expect(res.status).toBe(409)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('COMPONENT_NAME_DUPLICATE')
    // RFC 7807 ProblemDetail: message 필드 절대 금지, detail을 사용한다
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
    expect(typeof body['detail']).toBe('string')
  })

  it('리드 422 플래그가 켜진 상태에서 leadUserId로 생성하면 422 + COMPONENT_LEAD_NOT_FOUND를 반환한다', async () => {
    // 백엔드 create는 leadUserId 실재 검증 → 미존재 시 422 (PATCH /lead와 동일 토글)
    localStorage.setItem('msw-component-lead-422', 'true')
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Lead Missing Component',
        leadUserId: '00000000-0000-4000-8000-000000000099',
      }),
    })

    expect(res.status).toBe(422)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('COMPONENT_LEAD_NOT_FOUND')
    // RFC 7807 ProblemDetail: message 필드 금지, detail 사용
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
    expect(typeof body['detail']).toBe('string')
  })

  it('리드 422 플래그가 꺼진 상태에서는 leadUserId로 정상 생성한다(201)', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Lead OK Component',
        leadUserId: '00000000-0000-4000-8000-000000000001',
      }),
    })
    expect(res.status).toBe(201)
    const body = await res.json() as { data: { leadUserId: string | null } }
    expect(body.data.leadUserId).toBe('00000000-0000-4000-8000-000000000001')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET 단건
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectIdOrKey/components/:id', () => {
  it('존재하는 컴포넌트를 200으로 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Single Component' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { id: string; name: string } }
    expect(body.data.id).toBe(id)
    expect(body.data.name).toBe('Single Component')
  })

  it('존재하지 않는 id는 404 + COMPONENT_NOT_FOUND errorCode를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`)
    expect(res.status).toBe(404)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('COMPONENT_NOT_FOUND')
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /:id (name/description 수정)
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/projects/:projectIdOrKey/components/:id', () => {
  it('name을 수정하면 200 + 갱신된 컴포넌트를 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Old Name' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'New Name' }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { name: string } }
    expect(body.data.name).toBe('New Name')
  })

  it('존재하지 않는 id는 404를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Any Name' }),
    })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /:id/lead
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/projects/:projectIdOrKey/components/:id/lead', () => {
  it('leadUserId를 설정하면 200 + 갱신된 컴포넌트를 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Lead Component' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const leadUserId = '00000000-0000-4000-8000-000000000001'
    const res = await fetch(`${BASE_URL}/${id}/lead`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ leadUserId }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { leadUserId: string | null } }
    expect(body.data.leadUserId).toBe(leadUserId)
  })

  it('leadUserId를 null로 설정하면 200 + leadUserId가 null인 컴포넌트를 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Lead Clear Component', leadUserId: '00000000-0000-4000-8000-000000000001' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}/lead`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ leadUserId: null }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { leadUserId: string | null } }
    expect(body.data.leadUserId).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/projects/:projectIdOrKey/components/:id', () => {
  it('존재하는 컴포넌트를 삭제하면 204 No Content를 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'To Delete' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })
    expect(res.status).toBe(204)

    // 삭제 후 목록에서 제외됐는지 확인
    const listRes = await fetch(BASE_URL)
    const listBody = await listRes.json() as { data: Array<{ id: string }> }
    expect(listBody.data.some((c) => c.id === id)).toBe(false)
  })

  it('존재하지 않는 id를 삭제하면 404를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`, { method: 'DELETE' })
    expect(res.status).toBe(404)
  })
})
