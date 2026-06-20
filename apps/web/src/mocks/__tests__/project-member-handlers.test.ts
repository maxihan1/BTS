// FR-PM-01 프로젝트 멤버 MSW 핸들러 stateful 동작 검증 — TDD RED 단계
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'

import { projectMemberHandlers } from '../project-member-handlers'
import { userHandlers } from '../user-handlers'

const server = setupServer(...projectMemberHandlers, ...userHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

/**
 * 테스트마다 멤버 상태를 초기 fixture 상태로 되돌린다.
 * X-MSW-Reset-Members: true 헤더를 GET 목록 요청에 포함하면 상태 초기화 후 목록을 반환한다.
 */
async function resetAndList(projectKey: string): Promise<unknown> {
  const res = await fetch(`/api/v1/projects/${projectKey}/members`, {
    headers: { 'X-MSW-Reset-Members': 'true' },
  })
  return res.json()
}

beforeEach(async () => {
  await resetAndList('ATLAS')
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function listMembers(projectKey: string): Promise<Response> {
  return fetch(`/api/v1/projects/${projectKey}/members`)
}

async function addMember(
  projectKey: string,
  body: { userId: string; role: string },
): Promise<Response> {
  return fetch(`/api/v1/projects/${projectKey}/members`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function changeRole(
  projectKey: string,
  userId: string,
  role: string,
): Promise<Response> {
  return fetch(`/api/v1/projects/${projectKey}/members/${userId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  })
}

async function removeMember(projectKey: string, userId: string): Promise<Response> {
  return fetch(`/api/v1/projects/${projectKey}/members/${userId}`, {
    method: 'DELETE',
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. GET 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectKey/members', () => {
  it('S1-1: 존재하는 프로젝트 → 200 + { members: [...] } 구조', async () => {
    const res = await listMembers('ATLAS')

    expect(res.status).toBe(200)
    const body = await res.json() as { members: unknown[] }
    expect(body).toHaveProperty('members')
    expect(Array.isArray(body.members)).toBe(true)
    expect(body.members.length).toBeGreaterThan(0)
  })

  it('S1-2: 각 멤버에 7 필드가 모두 존재 (projectId/userId/role/createdAt/updatedAt/displayName/username)', async () => {
    const res = await listMembers('ATLAS')
    const body = await res.json() as { members: Record<string, unknown>[] }
    const member = body.members[0]

    expect(member).toBeDefined()
    expect(member).toHaveProperty('projectId')
    expect(member).toHaveProperty('userId')
    expect(member).toHaveProperty('role')
    expect(member).toHaveProperty('createdAt')
    expect(member).toHaveProperty('updatedAt')
    expect(member).toHaveProperty('displayName')
    expect(member).toHaveProperty('username')
  })

  it('S1-3: 미존재 프로젝트 → 404 + { error: "project_not_found" }', async () => {
    const res = await listMembers('UNKNOWN')

    expect(res.status).toBe(404)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('project_not_found')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. POST — 멤버 추가 + stateful 영속
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/projects/:projectKey/members', () => {
  it('S2-1: 비멤버 userId 추가 → 201 + ProjectMember 단건 반환', async () => {
    const res = await addMember('ATLAS', { userId: 'new-user-uuid-0001', role: 'MEMBER' })

    expect(res.status).toBe(201)
    const body = await res.json() as Record<string, unknown>
    expect(body).toHaveProperty('userId', 'new-user-uuid-0001')
    expect(body).toHaveProperty('role', 'MEMBER')
  })

  it('S2-2: POST 후 GET refetch → 추가된 멤버가 목록에 반영됨 (stateful 영속)', async () => {
    await addMember('ATLAS', { userId: 'new-user-uuid-0002', role: 'MEMBER' })

    const listRes = await listMembers('ATLAS')
    const body = await listRes.json() as { members: Array<{ userId: string }> }
    const found = body.members.find((m) => m.userId === 'new-user-uuid-0002')
    expect(found).toBeDefined()
  })

  it('S2-3: 이미 멤버인 userId 추가 → 409 + { error: "membership_already_exists" }', async () => {
    // fixture의 alice는 이미 ATLAS 멤버
    const res = await addMember('ATLAS', { userId: '00000000-0000-4000-8000-000000000001', role: 'MEMBER' })

    expect(res.status).toBe(409)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('membership_already_exists')
  })

  it('S2-4: 미존재 프로젝트 → 404 + { error: "project_not_found" }', async () => {
    const res = await addMember('UNKNOWN', { userId: 'any-uuid', role: 'MEMBER' })

    expect(res.status).toBe(404)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('project_not_found')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. PATCH — 역할 변경 + stateful 영속
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/projects/:projectKey/members/:userId', () => {
  it('S3-1: 멤버 역할 변경 → 200 + 변경된 ProjectMember 반환', async () => {
    const res = await changeRole('ATLAS', '00000000-0000-4000-8000-000000000002', 'PROJECT_ADMIN')

    expect(res.status).toBe(200)
    const body = await res.json() as { role: string }
    expect(body.role).toBe('PROJECT_ADMIN')
  })

  it('S3-2: PATCH 후 GET refetch → 변경된 역할이 목록에 반영됨 (stateful 영속)', async () => {
    await changeRole('ATLAS', '00000000-0000-4000-8000-000000000002', 'PROJECT_ADMIN')

    const listRes = await listMembers('ATLAS')
    const body = await listRes.json() as { members: Array<{ userId: string; role: string }> }
    const bob = body.members.find((m) => m.userId === '00000000-0000-4000-8000-000000000002')
    expect(bob?.role).toBe('PROJECT_ADMIN')
  })

  it('S3-3: 마지막 admin 강등 시도 → 409 + { error: "last_admin_protected" }', async () => {
    // alice 만 PROJECT_ADMIN — bob은 MEMBER. alice를 MEMBER로 강등하면 admin 0명
    const res = await changeRole('ATLAS', '00000000-0000-4000-8000-000000000001', 'MEMBER')

    expect(res.status).toBe(409)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('last_admin_protected')
  })

  it('S3-4: 미존재 멤버 PATCH → 404 + { error: "member_not_found" }', async () => {
    const res = await changeRole('ATLAS', 'non-existent-uuid', 'MEMBER')

    expect(res.status).toBe(404)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('member_not_found')
  })

  it('S3-5: 미존재 프로젝트 → 404 + { error: "project_not_found" }', async () => {
    const res = await changeRole('UNKNOWN', '00000000-0000-4000-8000-000000000001', 'MEMBER')

    expect(res.status).toBe(404)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('project_not_found')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. DELETE — 멤버 제거 + stateful 영속
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/projects/:projectKey/members/:userId', () => {
  it('S4-1: 멤버 삭제 → 204 No Content', async () => {
    const res = await removeMember('ATLAS', '00000000-0000-4000-8000-000000000002')

    expect(res.status).toBe(204)
  })

  it('S4-2: DELETE 후 GET refetch → 제거된 멤버가 목록에서 사라짐 (stateful 영속)', async () => {
    await removeMember('ATLAS', '00000000-0000-4000-8000-000000000002')

    const listRes = await listMembers('ATLAS')
    const body = await listRes.json() as { members: Array<{ userId: string }> }
    const found = body.members.find((m) => m.userId === '00000000-0000-4000-8000-000000000002')
    expect(found).toBeUndefined()
  })

  it('S4-3: 마지막 admin 삭제 시도 → 409 + { error: "last_admin_protected" }', async () => {
    // alice만 PROJECT_ADMIN
    const res = await removeMember('ATLAS', '00000000-0000-4000-8000-000000000001')

    expect(res.status).toBe(409)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('last_admin_protected')
  })

  it('S4-4: 미존재 멤버 삭제 → 404 + { error: "member_not_found" }', async () => {
    const res = await removeMember('ATLAS', 'non-existent-uuid')

    expect(res.status).toBe(404)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('member_not_found')
  })

  it('S4-5: 미존재 프로젝트 → 404 + { error: "project_not_found" }', async () => {
    const res = await removeMember('UNKNOWN', '00000000-0000-4000-8000-000000000001')

    expect(res.status).toBe(404)
    const body = await res.json() as { error: string }
    expect(body.error).toBe('project_not_found')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. X-MSW-Reset-Members 헤더 — 상태 초기화
// ─────────────────────────────────────────────────────────────────────────────

describe('X-MSW-Reset-Members 헤더', () => {
  it('S5-1: POST로 추가한 멤버가 reset 후 사라짐', async () => {
    await addMember('ATLAS', { userId: 'temp-user-uuid-0099', role: 'MEMBER' })

    // reset
    const resetRes = await fetch('/api/v1/projects/ATLAS/members', {
      headers: { 'X-MSW-Reset-Members': 'true' },
    })
    expect(resetRes.status).toBe(200)

    const body = await resetRes.json() as { members: Array<{ userId: string }> }
    const found = body.members.find((m) => m.userId === 'temp-user-uuid-0099')
    expect(found).toBeUndefined()
  })

  it('S5-2: reset 후 fixture 초기 alice, bob 멤버가 복원됨', async () => {
    await removeMember('ATLAS', '00000000-0000-4000-8000-000000000002')

    const resetBody = await resetAndList('ATLAS') as { members: Array<{ userId: string }> }
    const bob = resetBody.members.find((m) => m.userId === '00000000-0000-4000-8000-000000000002')
    expect(bob).toBeDefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. GET /api/v1/users?query= — 사용자 디렉토리 검색
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users?query=', () => {
  it('S6-1: query 없음 → 200 + 배열 (래퍼 없음)', async () => {
    const res = await fetch('/api/v1/users?query=')

    expect(res.status).toBe(200)
    const body = await res.json() as unknown[]
    expect(Array.isArray(body)).toBe(true)
  })

  it('S6-2: query substring 필터 → 매칭 사용자만 반환', async () => {
    const res = await fetch('/api/v1/users?query=alice')

    expect(res.status).toBe(200)
    const body = await res.json() as Array<{ username: string }>
    expect(body.length).toBeGreaterThan(0)
    expect(body.every((u) => u.username.includes('alice') || (u as { displayName?: string }).displayName?.includes('alice'))).toBe(true)
  })

  it('S6-3: 매칭 없는 query → 200 + 빈 배열', async () => {
    const res = await fetch('/api/v1/users?query=zzznomatch999')

    expect(res.status).toBe(200)
    const body = await res.json() as unknown[]
    expect(body).toHaveLength(0)
  })

  it('S6-4: 각 사용자에 id/username/displayName/email 필드 포함', async () => {
    const res = await fetch('/api/v1/users?query=')
    const body = await res.json() as Array<Record<string, unknown>>
    const user = body[0]

    expect(user).toBeDefined()
    expect(user).toHaveProperty('id')
    expect(user).toHaveProperty('username')
    expect(user).toHaveProperty('displayName')
    expect(user).toHaveProperty('email')
  })
})
