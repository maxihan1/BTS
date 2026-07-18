// FR-PM-10 D6 전역 권한 부여/회수 MSW 핸들러 stateful 동작 검증 — TDD RED 단계
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'

import { globalPermissionHandlers, resetGlobalPermissionStore } from '../global-permission-handlers'

const server = setupServer(...globalPermissionHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => resetGlobalPermissionStore())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const BASE_URL = '/api/v1/admin/global-permissions'

interface GrantResponse {
  id: string
  permission: string
  granteeType: 'USER' | 'GROUP'
  granteeId: string
  grantedBy: string
  createdAt: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function listGrants(headers?: HeadersInit): Promise<Response> {
  return fetch(BASE_URL, { headers })
}

async function createGrant(body: {
  permission: string
  granteeType: 'USER' | 'GROUP'
  granteeId: string
}): Promise<Response> {
  return fetch(BASE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'test-token' },
    body: JSON.stringify(body),
  })
}

async function deleteGrant(id: string): Promise<Response> {
  return fetch(`${BASE_URL}/${id}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': 'test-token' },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. GET 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/admin/global-permissions', () => {
  it('S1-1: 초기 상태 → 200 + 빈 배열 (bare, 래퍼 없음)', async () => {
    const res = await listGrants()

    expect(res.status).toBe(200)
    const body = (await res.json()) as unknown
    expect(Array.isArray(body)).toBe(true)
    expect(body).toHaveLength(0)
  })

  it('S1-2: X-MSW-Seed-GlobalPermissions 헤더로 시드 주입 → 목록에 반영', async () => {
    const seed = [
      {
        id: 'seed-grant-0001',
        permission: 'CREATE_PROJECT',
        granteeType: 'USER',
        granteeId: '00000000-0000-4000-8000-000000000002',
        grantedBy: '00000000-0000-4000-8000-000000000001',
        createdAt: '2026-01-01T00:00:00Z',
      },
    ]
    const res = await listGrants({
      'X-MSW-Seed-GlobalPermissions': encodeURIComponent(JSON.stringify(seed)),
    })

    expect(res.status).toBe(200)
    const body = (await res.json()) as GrantResponse[]
    expect(body).toHaveLength(1)
    expect(body[0]?.id).toBe('seed-grant-0001')
    expect(body[0]?.granteeType).toBe('USER')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. POST — 부여 + stateful 영속 + 중복 409
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/admin/global-permissions', () => {
  it('S2-1: 유효 입력 → 201 + GrantResponse 전 필드 반환', async () => {
    const res = await createGrant({
      permission: 'CREATE_PROJECT',
      granteeType: 'GROUP',
      granteeId: '11111111-0000-4000-8000-000000000001',
    })

    expect(res.status).toBe(201)
    const body = (await res.json()) as GrantResponse
    expect(body).toHaveProperty('id')
    expect(body.permission).toBe('CREATE_PROJECT')
    expect(body.granteeType).toBe('GROUP')
    expect(body.granteeId).toBe('11111111-0000-4000-8000-000000000001')
    expect(body).toHaveProperty('grantedBy')
    expect(body).toHaveProperty('createdAt')
  })

  it('S2-2: POST 후 GET refetch → 추가된 grant가 목록에 반영됨 (stateful 영속)', async () => {
    await createGrant({
      permission: 'CREATE_PROJECT',
      granteeType: 'USER',
      granteeId: '00000000-0000-4000-8000-000000000002',
    })

    const listRes = await listGrants()
    const body = (await listRes.json()) as GrantResponse[]
    expect(body.some((g) => g.granteeId === '00000000-0000-4000-8000-000000000002')).toBe(true)
  })

  it('S2-3: 동일 (permission, granteeType, granteeId) 조합 재부여 → 409 + { error: "grant_already_exists" }', async () => {
    const input = {
      permission: 'CREATE_PROJECT' as const,
      granteeType: 'GROUP' as const,
      granteeId: '11111111-0000-4000-8000-000000000002',
    }
    const first = await createGrant(input)
    expect(first.status).toBe(201)

    const second = await createGrant(input)
    expect(second.status).toBe(409)
    const body = (await second.json()) as { error: string }
    expect(body.error).toBe('grant_already_exists')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. DELETE — 회수 + stateful 영속 + 미존재 404
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/admin/global-permissions/:grantId', () => {
  it('S3-1: 존재하는 grant 삭제 → 204 No Content', async () => {
    const created = await createGrant({
      permission: 'CREATE_PROJECT',
      granteeType: 'USER',
      granteeId: '00000000-0000-4000-8000-000000000003',
    })
    const { id } = (await created.json()) as GrantResponse

    const res = await deleteGrant(id)
    expect(res.status).toBe(204)
  })

  it('S3-2: DELETE 후 GET refetch → 제거된 grant가 목록에서 사라짐 (stateful 영속)', async () => {
    const created = await createGrant({
      permission: 'CREATE_PROJECT',
      granteeType: 'USER',
      granteeId: '00000000-0000-4000-8000-000000000004',
    })
    const { id } = (await created.json()) as GrantResponse

    await deleteGrant(id)

    const listRes = await listGrants()
    const body = (await listRes.json()) as GrantResponse[]
    expect(body.some((g) => g.id === id)).toBe(false)
  })

  it('S3-3: 미존재 grantId 삭제 → 404 + { error: "grant_not_found" }', async () => {
    const res = await deleteGrant('non-existent-grant-id')

    expect(res.status).toBe(404)
    const body = (await res.json()) as { error: string }
    expect(body.error).toBe('grant_not_found')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. resetGlobalPermissionStore — 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

describe('resetGlobalPermissionStore', () => {
  it('S4-1: POST로 추가한 grant가 reset 후 사라짐', async () => {
    await createGrant({
      permission: 'CREATE_PROJECT',
      granteeType: 'USER',
      granteeId: '00000000-0000-4000-8000-000000000099',
    })

    resetGlobalPermissionStore()

    const res = await listGrants()
    const body = (await res.json()) as GrantResponse[]
    expect(body).toHaveLength(0)
  })
})
