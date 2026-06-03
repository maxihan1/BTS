// 버전 MSW 핸들러 단위 테스트 — stateful CRUD + RFC 7807 에러 구조 검증 (FR-VR-01)
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { resetVersionStore, versionHandlers } from './version-handlers'

const server = setupServer(...versionHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetVersionStore()
})
afterAll(() => server.close())

const BASE_URL = '/api/v1/projects/ATLAS/versions'

// ─────────────────────────────────────────────────────────────────────────────
// GET 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectIdOrKey/versions', () => {
  it('활성 버전 목록을 200으로 반환한다', async () => {
    const res = await fetch(BASE_URL)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(Array.isArray(body.data)).toBe(true)
  })

  it('버전 목록이 name 오름차순으로 정렬된다', async () => {
    await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'v2.0.0' }),
    })
    await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'v1.0.0' }),
    })

    const res = await fetch(BASE_URL)
    const body = await res.json() as { data: Array<{ name: string }> }
    const names = body.data.map((v) => v.name)
    const sorted = [...names].sort((a, b) => a.localeCompare(b))
    expect(names).toEqual(sorted)
  })

  it('삭제된 버전은 목록에 포함되지 않는다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'To Be Deleted Version' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })

    const listRes = await fetch(BASE_URL)
    const listBody = await listRes.json() as { data: Array<{ id: string }> }
    expect(listBody.data.some((v) => v.id === id)).toBe(false)
  })

  it('전역 GET 목록은 PROJECT_NOT_FOUND를 반환하지 않고 항상 200을 반환한다', async () => {
    const res = await fetch('/api/v1/projects/NOT_EXISTING_KEY/versions')
    expect(res.status).toBe(200)
    const body = await res.json() as { data: unknown[] }
    expect(Array.isArray(body.data)).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/projects/:projectIdOrKey/versions', () => {
  it('버전을 성공적으로 생성하면 201을 반환한다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'v1.0.0', description: '첫 번째 릴리즈' }),
    })
    expect(res.status).toBe(201)
    const body = await res.json() as { data: { name: string; description: string | null } }
    expect(body.data.name).toBe('v1.0.0')
    expect(body.data.description).toBe('첫 번째 릴리즈')
  })

  it('startDate, releaseDate와 함께 생성하면 201을 반환하고 날짜를 포함한다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'v2.0.0',
        startDate: '2026-01-01',
        releaseDate: '2026-06-01',
      }),
    })
    expect(res.status).toBe(201)
    const body = await res.json() as { data: { startDate: string | null; releaseDate: string | null } }
    expect(body.data.startDate).toBe('2026-01-01')
    expect(body.data.releaseDate).toBe('2026-06-01')
  })

  it('같은 프로젝트에 활성 버전의 이름이 중복되면 409 + VERSION_NAME_DUPLICATE errorCode를 반환한다', async () => {
    await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Duplicate Version' }),
    })

    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Duplicate Version' }),
    })

    expect(res.status).toBe(409)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('VERSION_NAME_DUPLICATE')
    // RFC 7807 ProblemDetail: message 필드 절대 금지, detail을 사용한다
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
    expect(typeof body['detail']).toBe('string')
  })

  it('삭제된 버전과 동명이라도 활성 중복이 없으면 201로 생성된다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Reusable Name' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    await fetch(`${BASE_URL}/${created.data.id}`, { method: 'DELETE' })

    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Reusable Name' }),
    })
    expect(res.status).toBe(201)
  })

  it('생성된 버전의 id는 RFC4122 v4 UUID 형식이다', async () => {
    const res = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'UUID Check Version' }),
    })
    const body = await res.json() as { data: { id: string } }
    // RFC4122 v4: 3번째 그룹이 4로 시작, 4번째 그룹이 8/9/a/b로 시작
    expect(body.data.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET 단건
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectIdOrKey/versions/:id', () => {
  it('존재하는 버전을 200으로 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Single Version' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}`)
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { id: string; name: string } }
    expect(body.data.id).toBe(id)
    expect(body.data.name).toBe('Single Version')
  })

  it('존재하지 않는 id는 404 + VERSION_NOT_FOUND errorCode를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`)
    expect(res.status).toBe(404)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('VERSION_NOT_FOUND')
    expect(Object.prototype.hasOwnProperty.call(body, 'message')).toBe(false)
    expect(typeof body['detail']).toBe('string')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /:id (name/description 수정)
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/projects/:projectIdOrKey/versions/:id', () => {
  it('name을 수정하면 200 + 갱신된 버전을 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Old Version Name' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'New Version Name' }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { name: string } }
    expect(body.data.name).toBe('New Version Name')
  })

  it('description을 수정하면 200 + 갱신된 버전을 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Desc Version', description: '원래 설명' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: '새 설명' }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { description: string | null } }
    expect(body.data.description).toBe('새 설명')
  })

  it('PATCH 후 GET 목록 refetch 시 수정된 값이 반환된다(stateful 영속)', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Stateful Version' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    await fetch(`${BASE_URL}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Updated Stateful Version' }),
    })

    const listRes = await fetch(BASE_URL)
    const listBody = await listRes.json() as { data: Array<{ id: string; name: string }> }
    const found = listBody.data.find((v) => v.id === id)
    expect(found?.name).toBe('Updated Stateful Version')
  })

  it('존재하지 않는 id는 404 + VERSION_NOT_FOUND를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Any Name' }),
    })
    expect(res.status).toBe(404)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('VERSION_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /:id/dates (날짜 변경)
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/projects/:projectIdOrKey/versions/:id/dates', () => {
  it('startDate/releaseDate를 설정하면 200 + 갱신된 버전을 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Date Version' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}/dates`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ startDate: '2026-01-01', releaseDate: '2026-12-31' }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { startDate: string | null; releaseDate: string | null } }
    expect(body.data.startDate).toBe('2026-01-01')
    expect(body.data.releaseDate).toBe('2026-12-31')
  })

  it('null로 보내면 날짜가 해제된다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Clearable Date Version', startDate: '2026-01-01', releaseDate: '2026-12-31' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}/dates`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ startDate: null, releaseDate: null }),
    })
    expect(res.status).toBe(200)
    const body = await res.json() as { data: { startDate: string | null; releaseDate: string | null } }
    expect(body.data.startDate).toBeNull()
    expect(body.data.releaseDate).toBeNull()
  })

  it('PATCH /dates 후 GET 목록 refetch 시 날짜가 반영된다(stateful 영속)', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'Date Stateful Version' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    await fetch(`${BASE_URL}/${id}/dates`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ startDate: '2026-03-01', releaseDate: '2026-09-01' }),
    })

    const listRes = await fetch(BASE_URL)
    const listBody = await listRes.json() as { data: Array<{ id: string; startDate: string | null }> }
    const found = listBody.data.find((v) => v.id === id)
    expect(found?.startDate).toBe('2026-03-01')
  })

  it('존재하지 않는 id는 404 + VERSION_NOT_FOUND를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001/dates`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ startDate: null, releaseDate: null }),
    })
    expect(res.status).toBe(404)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('VERSION_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/projects/:projectIdOrKey/versions/:id', () => {
  it('존재하는 버전을 삭제하면 204 No Content를 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'To Delete Version' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const id = created.data.id

    const res = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })
    expect(res.status).toBe(204)

    // 삭제 후 목록에서 제외됐는지 확인 (stateful 영속)
    const listRes = await fetch(BASE_URL)
    const listBody = await listRes.json() as { data: Array<{ id: string }> }
    expect(listBody.data.some((v) => v.id === id)).toBe(false)
  })

  it('존재하지 않는 id를 삭제하면 404 + VERSION_NOT_FOUND를 반환한다', async () => {
    const res = await fetch(`${BASE_URL}/00000000-0000-4000-8000-000000000001`, { method: 'DELETE' })
    expect(res.status).toBe(404)
    const body = await res.json() as Record<string, unknown>
    expect(body['errorCode']).toBe('VERSION_NOT_FOUND')
  })
})
