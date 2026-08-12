// FR-PM-02 프로젝트 권한 MSW 핸들러 Authorization 토큰 기반 동작 검증
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'

import {
  projectPermissionHandlers,
  E2E_FORCE_CREATE_FALSE_KEY,
} from '../project-permission-handlers'
import {
  adminProjectPermissions,
  nonMemberProjectPermissions,
} from '../project-permission-fixtures'
import { mockAccessToken } from '../auth-fixtures'

beforeEach(() => {
  server.use(...projectPermissionHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function getProjectPermissions(projectKey: string, token?: string): Promise<Response> {
  const headers: HeadersInit = token !== undefined
    ? { Authorization: `Bearer ${token}` }
    : {}
  return fetch(`/api/v1/users/me/project-permissions?projectKey=${projectKey}`, { headers })
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 기본 응답 구조
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/project-permissions', () => {
  it('S1-1: alice 토큰 + projectKey 포함 요청 → 200 + { projectKey, permissions } 구조', async () => {
    const res = await getProjectPermissions('ATLAS', mockAccessToken('alice'))

    expect(res.status).toBe(200)
    const body = await res.json() as { projectKey: string; permissions: Record<string, boolean> }
    expect(body).toHaveProperty('projectKey', 'ATLAS')
    expect(body).toHaveProperty('permissions')
    expect(typeof body.permissions).toBe('object')
  })

  it('S1-2: permissions 객체에 CREATE 필드 존재', async () => {
    const res = await getProjectPermissions('ATLAS', mockAccessToken('alice'))
    const body = await res.json() as { permissions: Record<string, unknown> }

    expect(body.permissions).toHaveProperty('CREATE')
  })

  it('S1-3: 응답 projectKey가 요청한 projectKey와 일치', async () => {
    const res = await getProjectPermissions('BTS', mockAccessToken('alice'))
    const body = await res.json() as { projectKey: string }

    expect(body.projectKey).toBe('BTS')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. alice (ADMIN) 권한 확인
// ─────────────────────────────────────────────────────────────────────────────

describe('alice 토큰 → ADMIN 권한 fixture', () => {
  it('S2-1: alice 토큰 → CREATE=true', async () => {
    const res = await getProjectPermissions('ATLAS', mockAccessToken('alice'))
    const body = await res.json() as { permissions: { CREATE: boolean } }

    expect(body.permissions.CREATE).toBe(adminProjectPermissions.CREATE)
    expect(body.permissions.CREATE).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. bob (MEMBER) 권한 확인
// ─────────────────────────────────────────────────────────────────────────────

describe('bob 토큰 → MEMBER 권한 fixture', () => {
  it('S3-1: bob 토큰 → CREATE=true (MEMBER도 이슈 생성 허용)', async () => {
    const res = await getProjectPermissions('ATLAS', mockAccessToken('bob'))
    const body = await res.json() as { permissions: { CREATE: boolean } }

    expect(body.permissions.CREATE).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 에러 분기 (백엔드와 동일: 401 → 400 → 200)
// ─────────────────────────────────────────────────────────────────────────────

describe('에러 분기', () => {
  it('S4-1: Authorization 헤더 없음 → 401', async () => {
    const res = await getProjectPermissions('ATLAS')

    expect(res.status).toBe(401)
  })

  it('S4-2: mock-access-token- prefix 아닌 토큰 → 401', async () => {
    const res = await getProjectPermissions('ATLAS', 'some-random-token')

    expect(res.status).toBe(401)
  })

  it('S4-3: 알려지지 않은 사용자 토큰 → 401', async () => {
    const res = await getProjectPermissions('ATLAS', mockAccessToken('unknown-user'))

    expect(res.status).toBe(401)
  })

  it('S4-4: projectKey 쿼리 없음 → 400', async () => {
    const res = await fetch('/api/v1/users/me/project-permissions', {
      headers: { Authorization: `Bearer ${mockAccessToken('alice')}` },
    })

    expect(res.status).toBe(400)
  })

  it('S4-5: projectKey 빈 문자열 → 400', async () => {
    const res = await fetch('/api/v1/users/me/project-permissions?projectKey=', {
      headers: { Authorization: `Bearer ${mockAccessToken('alice')}` },
    })

    expect(res.status).toBe(400)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. (C2) 비멤버 케이스 — 200 + CREATE:false (401 아님)
//
// 인증된 사용자이지만 프로젝트 비멤버/비활성 역할인 경우.
// 백엔드(Task 1)는 401이 아닌 200+CREATE:false를 반환한다.
// S2 E2E(Task 6)가 이 fixture 오버라이드로 버튼 비활성을 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('비멤버 fixture — nonMemberProjectPermissions', () => {
  it('S5-1: nonMemberProjectPermissions.CREATE는 false', () => {
    expect(nonMemberProjectPermissions.CREATE).toBe(false)
  })

  it('S5-2: 서버 오버라이드로 비멤버 응답(CREATE:false) 주입 시 200 + CREATE:false', async () => {
    server.use(
      http.get(
        '/api/v1/users/me/project-permissions',
        () =>
          HttpResponse.json({
            projectKey: 'ATLAS',
            permissions: nonMemberProjectPermissions,
          }),
      ),
    )

    const res = await getProjectPermissions('ATLAS', mockAccessToken('alice'))
    expect(res.status).toBe(200)
    const body = await res.json() as { permissions: { CREATE: boolean } }
    expect(body.permissions.CREATE).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. E2E_FORCE_CREATE_FALSE_KEY localStorage 플래그 분기
//
// 플래그가 'true'이면 인증된 alice 토큰이어도 CREATE:false 가 반환된다.
// Playwright addInitScript 패턴으로 goto 전에 플래그를 설정하는 E2E S2 시나리오의
// 단위 검증. 각 케이스 후 removeItem 으로 격리한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('E2E_FORCE_CREATE_FALSE_KEY 플래그 분기', () => {
  afterEach(() => {
    localStorage.removeItem(E2E_FORCE_CREATE_FALSE_KEY)
  })

  it('S6-1: 플래그 미설정 시 alice 토큰 → CREATE:true (기본 동작 유지)', async () => {
    const res = await getProjectPermissions('ATLAS', mockAccessToken('alice'))
    const body = await res.json() as { permissions: { CREATE: boolean } }

    expect(body.permissions.CREATE).toBe(true)
  })

  it('S6-2: 플래그 set 시 alice 토큰이어도 → 200 + CREATE:false', async () => {
    localStorage.setItem(E2E_FORCE_CREATE_FALSE_KEY, 'true')

    const res = await getProjectPermissions('ATLAS', mockAccessToken('alice'))
    expect(res.status).toBe(200)
    const body = await res.json() as { permissions: { CREATE: boolean } }
    expect(body.permissions.CREATE).toBe(false)
  })

  it('S6-3: 플래그 set 후 remove 시 alice 토큰 → CREATE:true (격리 확인)', async () => {
    localStorage.setItem(E2E_FORCE_CREATE_FALSE_KEY, 'true')
    localStorage.removeItem(E2E_FORCE_CREATE_FALSE_KEY)

    const res = await getProjectPermissions('ATLAS', mockAccessToken('alice'))
    const body = await res.json() as { permissions: { CREATE: boolean } }
    expect(body.permissions.CREATE).toBe(true)
  })
})
