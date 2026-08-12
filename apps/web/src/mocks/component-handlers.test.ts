// 컴포넌트 MSW 핸들러 단위 테스트 — stateful CRUD + RFC 7807 에러 구조 검증 (FR-CM-01)
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { componentHandlers, resetComponentStore, getStoredComponentsByIds } from './component-handlers'

beforeEach(() => {
  server.use(...componentHandlers)
})
afterEach(() => {
  resetComponentStore()
  localStorage.clear()
})

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

// ─────────────────────────────────────────────────────────────────────────────
// getStoredComponentsByIds — createIssue 자동배정 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

describe('getStoredComponentsByIds', () => {
  it('빈 store에서 호출하면 빈 배열을 반환한다', () => {
    const result = getStoredComponentsByIds(['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'])
    expect(result).toEqual([])
  })

  it('store에 있는 id만 반환하고 없는 id는 제외한다', async () => {
    // POST로 컴포넌트 생성해 store에 추가
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '헬퍼 테스트 컴포넌트', leadUserId: '00000000-0000-4000-8000-000000000001' }),
    })
    const created = await createRes.json() as { data: { id: string; name: string; leadUserId: string | null } }
    const existingId = created.data.id
    const nonExistingId = 'ffffffff-ffff-4fff-8fff-ffffffffffff'

    const result = getStoredComponentsByIds([existingId, nonExistingId])
    expect(result).toHaveLength(1)
    expect(result[0]).toMatchObject({
      id: existingId,
      name: '헬퍼 테스트 컴포넌트',
      leadUserId: '00000000-0000-4000-8000-000000000001',
    })
  })

  it('ids 배열이 빈 배열이면 빈 배열을 반환한다', () => {
    const result = getStoredComponentsByIds([])
    expect(result).toEqual([])
  })

  it('leadUserId가 null인 컴포넌트도 반환한다', async () => {
    const createRes = await fetch(BASE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '리드 없는 컴포넌트' }),
    })
    const created = await createRes.json() as { data: { id: string } }
    const result = getStoredComponentsByIds([created.data.id])
    expect(result).toHaveLength(1)
    expect(result[0]?.leadUserId).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createIssue 자동배정 — componentStore 기반 name→id tiebreak 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('createIssue 자동배정 — componentStore 기반 (issue-handlers 통합)', () => {
  it('name이 같을 때 id 오름차순 tiebreak으로 첫 번째 리드가 자동배정된다', async () => {
    // 같은 name, id만 다른 두 컴포넌트를 store에 추가
    // id 사전순: 'aaa...' < 'bbb...' — 따라서 'aaa...' 리드가 먼저 배정되어야 함
    const projectKey = 'TIEBREAK'
    const tiebreakUrl = `/api/v1/projects/${projectKey}/components`
    const seedHeader = encodeURIComponent(JSON.stringify([
      { id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', name: '같은이름', leadUserId: 'bbbb0000-0000-4000-8000-000000000002' },
      { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: '같은이름', leadUserId: 'aaaa0000-0000-4000-8000-000000000001' },
    ]))
    // X-MSW-Seed-Components 헤더로 componentStore 시드
    await fetch(tiebreakUrl, { headers: { 'X-MSW-Seed-Components': seedHeader } })

    const result = getStoredComponentsByIds([
      'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    ])

    // 정렬: name 동률 → id 오름차순 — 'aaa...' 가 첫 번째여야 함
    const sorted = [...result]
      .filter((c) => c.leadUserId !== null)
      .sort((a, b) => {
        const nameCmp = a.name.localeCompare(b.name)
        return nameCmp !== 0 ? nameCmp : a.id.localeCompare(b.id)
      })
    expect(sorted[0]?.leadUserId).toBe('aaaa0000-0000-4000-8000-000000000001')
  })
})
