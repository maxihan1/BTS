// FR-PM-02 이슈 권한 MSW 핸들러 Authorization 토큰 기반 동작 검증
import { server } from '@/test/server'
import { describe, expect, it } from 'vitest'

import { issuePermissionHandlers } from '../issue-permission-handlers'
import { adminPermissionsFixture, memberPermissionsFixture } from '../issue-permission-fixtures'
import { mockAccessToken } from '../auth-fixtures'

beforeEach(() => {
  server.use(...issuePermissionHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function getPermissions(issueKey: string, token?: string): Promise<Response> {
  const headers: HeadersInit = token !== undefined
    ? { Authorization: `Bearer ${token}` }
    : {}
  return fetch(`/api/v1/users/me/issue-permissions?issueKey=${issueKey}`, { headers })
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 기본 응답 구조
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/issue-permissions', () => {
  it('S1-1: alice 토큰 + issueKey 포함 요청 → 200 + { issueKey, permissions } 구조', async () => {
    const res = await getPermissions('ATLAS-1', mockAccessToken('alice'))

    expect(res.status).toBe(200)
    const body = await res.json() as { issueKey: string; permissions: Record<string, boolean> }
    expect(body).toHaveProperty('issueKey', 'ATLAS-1')
    expect(body).toHaveProperty('permissions')
    expect(typeof body.permissions).toBe('object')
  })

  it('S1-2: permissions 객체에 UPDATE/SOFT_DELETE/TRANSITION 필드 모두 존재', async () => {
    const res = await getPermissions('ATLAS-1', mockAccessToken('alice'))
    const body = await res.json() as { permissions: Record<string, unknown> }

    expect(body.permissions).toHaveProperty('UPDATE')
    expect(body.permissions).toHaveProperty('SOFT_DELETE')
    expect(body.permissions).toHaveProperty('TRANSITION')
  })

  it('S1-3: 응답 issueKey가 요청한 issueKey와 일치', async () => {
    const res = await getPermissions('BTS-42', mockAccessToken('alice'))
    const body = await res.json() as { issueKey: string }

    expect(body.issueKey).toBe('BTS-42')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. alice (ADMIN) 권한 확인
// ─────────────────────────────────────────────────────────────────────────────

describe('alice 토큰 → ADMIN 권한 fixture', () => {
  it('S2-1: alice 토큰 → UPDATE/SOFT_DELETE/TRANSITION 모두 true', async () => {
    const res = await getPermissions('ATLAS-1', mockAccessToken('alice'))
    const body = await res.json() as { permissions: { UPDATE: boolean; SOFT_DELETE: boolean; TRANSITION: boolean } }

    expect(body.permissions.UPDATE).toBe(adminPermissionsFixture.UPDATE)
    expect(body.permissions.SOFT_DELETE).toBe(adminPermissionsFixture.SOFT_DELETE)
    expect(body.permissions.TRANSITION).toBe(adminPermissionsFixture.TRANSITION)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. bob (MEMBER) 권한 확인
// ─────────────────────────────────────────────────────────────────────────────

describe('bob 토큰 → MEMBER 권한 fixture', () => {
  it('S3-1: bob 토큰 → SOFT_DELETE=false', async () => {
    const res = await getPermissions('ATLAS-1', mockAccessToken('bob'))
    const body = await res.json() as { permissions: { SOFT_DELETE: boolean } }

    expect(body.permissions.SOFT_DELETE).toBe(memberPermissionsFixture.SOFT_DELETE)
  })

  it('S3-2: bob 토큰 → UPDATE/TRANSITION은 여전히 true (MEMBER도 허용)', async () => {
    const res = await getPermissions('ATLAS-1', mockAccessToken('bob'))
    const body = await res.json() as { permissions: { UPDATE: boolean; TRANSITION: boolean } }

    expect(body.permissions.UPDATE).toBe(memberPermissionsFixture.UPDATE)
    expect(body.permissions.TRANSITION).toBe(memberPermissionsFixture.TRANSITION)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 에러 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('에러 분기', () => {
  it('S4-1: Authorization 헤더 없음 → 401', async () => {
    const res = await getPermissions('ATLAS-1')

    expect(res.status).toBe(401)
  })

  it('S4-2: mock-access-token- prefix 아닌 토큰 → 401', async () => {
    const res = await getPermissions('ATLAS-1', 'some-random-token')

    expect(res.status).toBe(401)
  })

  it('S4-3: 알려지지 않은 사용자 토큰 → 401', async () => {
    const res = await getPermissions('ATLAS-1', mockAccessToken('unknown-user'))

    expect(res.status).toBe(401)
  })

  it('S4-4: issueKey 쿼리 없음 → 400', async () => {
    const res = await fetch('/api/v1/users/me/issue-permissions', {
      headers: { Authorization: `Bearer ${mockAccessToken('alice')}` },
    })

    expect(res.status).toBe(400)
  })

  it('S4-5: issueKey 빈 문자열 → 400', async () => {
    const res = await fetch('/api/v1/users/me/issue-permissions?issueKey=', {
      headers: { Authorization: `Bearer ${mockAccessToken('alice')}` },
    })

    expect(res.status).toBe(400)
  })
})
