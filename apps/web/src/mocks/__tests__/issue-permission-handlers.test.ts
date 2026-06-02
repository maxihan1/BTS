// FR-PM-02 이슈 권한 MSW 핸들러 stateful 동작 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'

import { issuePermissionHandlers } from '../issue-permission-handlers'
import { adminPermissionsFixture, memberPermissionsFixture } from '../issue-permission-fixtures'

const server = setupServer(...issuePermissionHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function getPermissions(issueKey: string): Promise<Response> {
  return fetch(`/api/v1/users/me/issue-permissions?issueKey=${issueKey}`)
}

async function resetToAdmin(issueKey: string): Promise<Response> {
  return fetch(`/api/v1/users/me/issue-permissions?issueKey=${issueKey}`, {
    headers: { 'X-MSW-Reset-Permissions': 'admin' },
  })
}

async function resetToMember(issueKey: string): Promise<Response> {
  return fetch(`/api/v1/users/me/issue-permissions?issueKey=${issueKey}`, {
    headers: { 'X-MSW-Reset-Permissions': 'member' },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 기본 응답 구조
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/issue-permissions', () => {
  it('S1-1: issueKey 포함 요청 → 200 + { issueKey, permissions } 구조', async () => {
    const res = await getPermissions('ATLAS-1')

    expect(res.status).toBe(200)
    const body = await res.json() as { issueKey: string; permissions: Record<string, boolean> }
    expect(body).toHaveProperty('issueKey', 'ATLAS-1')
    expect(body).toHaveProperty('permissions')
    expect(typeof body.permissions).toBe('object')
  })

  it('S1-2: permissions 객체에 UPDATE/SOFT_DELETE/TRANSITION 필드 모두 존재', async () => {
    const res = await getPermissions('ATLAS-1')
    const body = await res.json() as { permissions: Record<string, unknown> }

    expect(body.permissions).toHaveProperty('UPDATE')
    expect(body.permissions).toHaveProperty('SOFT_DELETE')
    expect(body.permissions).toHaveProperty('TRANSITION')
  })

  it('S1-3: 응답 issueKey가 요청한 issueKey와 일치', async () => {
    const res = await getPermissions('BTS-42')
    const body = await res.json() as { issueKey: string }

    expect(body.issueKey).toBe('BTS-42')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 기본값 (ADMIN) 권한 확인
// ─────────────────────────────────────────────────────────────────────────────

describe('기본 ADMIN 권한 fixture', () => {
  it('S2-1: 기본 응답은 ADMIN fixture — UPDATE/SOFT_DELETE/TRANSITION 모두 true', async () => {
    const res = await getPermissions('ATLAS-1')
    const body = await res.json() as { permissions: { UPDATE: boolean; SOFT_DELETE: boolean; TRANSITION: boolean } }

    expect(body.permissions.UPDATE).toBe(adminPermissionsFixture.UPDATE)
    expect(body.permissions.SOFT_DELETE).toBe(adminPermissionsFixture.SOFT_DELETE)
    expect(body.permissions.TRANSITION).toBe(adminPermissionsFixture.TRANSITION)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. stateful 역할 전환 — X-MSW-Reset-Permissions 헤더
// ─────────────────────────────────────────────────────────────────────────────

describe('X-MSW-Reset-Permissions 헤더 — stateful 역할 전환', () => {
  it('S3-1: X-MSW-Reset-Permissions: member → SOFT_DELETE=false 응답', async () => {
    const res = await resetToMember('ATLAS-1')
    const body = await res.json() as { permissions: { SOFT_DELETE: boolean } }

    expect(body.permissions.SOFT_DELETE).toBe(memberPermissionsFixture.SOFT_DELETE)
  })

  it('S3-2: member 전환 후 일반 GET도 MEMBER 권한 유지 (stateful)', async () => {
    await resetToMember('ATLAS-1')
    const res = await getPermissions('ATLAS-1')
    const body = await res.json() as { permissions: { SOFT_DELETE: boolean } }

    expect(body.permissions.SOFT_DELETE).toBe(false)
  })

  it('S3-3: admin으로 복원 후 SOFT_DELETE=true 반환', async () => {
    await resetToMember('ATLAS-1')
    await resetToAdmin('ATLAS-1')
    const res = await getPermissions('ATLAS-1')
    const body = await res.json() as { permissions: { SOFT_DELETE: boolean } }

    expect(body.permissions.SOFT_DELETE).toBe(true)
  })

  it('S3-4: MEMBER 전환 시 UPDATE/TRANSITION은 여전히 true (MEMBER도 허용)', async () => {
    await resetToMember('ATLAS-1')
    const res = await getPermissions('ATLAS-1')
    const body = await res.json() as { permissions: { UPDATE: boolean; TRANSITION: boolean } }

    expect(body.permissions.UPDATE).toBe(memberPermissionsFixture.UPDATE)
    expect(body.permissions.TRANSITION).toBe(memberPermissionsFixture.TRANSITION)
  })
})
