// 프로젝트 멤버 API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 + 에러코드 보존 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { ZodError } from 'zod'
import { server } from '@/test/server'
import {
  memberResponseSchema,
  membersListSchema,
  roleEnum,
  fetchProjectMembers,
  addMember,
  changeRole,
  removeMember,
  ProjectMemberApiError,
} from '../project-members'
import type { ProjectMember, ProjectMembersList } from '../project-members'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — backend DTO 필드와 1:1 (camelCase)
// ProjectMemberResponse: projectId/userId/role/createdAt/updatedAt/displayName/username
// ─────────────────────────────────────────────────────────────────────────────

const memberFixture = {
  projectId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
  userId: '11111111-2222-3333-4444-555555555555',
  role: 'PROJECT_ADMIN' as const,
  createdAt: '2026-06-01T09:00:00Z',
  updatedAt: '2026-06-01T09:00:00Z',
  displayName: 'Maxi Han',
  username: 'maxi.han',
}

const memberFixtureNullDisplay = {
  projectId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
  userId: '66666666-7777-8888-9999-aaaaaaaaaaaa',
  role: 'MEMBER' as const,
  createdAt: '2026-06-01T10:00:00Z',
  updatedAt: '2026-06-01T10:00:00Z',
  displayName: null,
  username: null,
}

const PROJECT_KEY = 'ATLAS'
const BASE_URL = `/api/v1/projects/${PROJECT_KEY}/members`

beforeEach(() => {
  server.use(
    // GET /api/v1/projects/{projectKey}/members → { members: [...] }
    http.get(BASE_URL, () => {
      return HttpResponse.json({ members: [memberFixture, memberFixtureNullDisplay] })
    }),

    // POST /api/v1/projects/{projectKey}/members → 201 ProjectMemberResponse
    http.post(BASE_URL, async ({ request }) => {
      const body = await request.json() as { userId: string; role: string }
      return HttpResponse.json(
        { ...memberFixture, userId: body.userId, role: body.role },
        { status: 201 },
      )
    }),

    // PATCH /api/v1/projects/{projectKey}/members/{userId}
    http.patch(`${BASE_URL}/:userId`, async ({ request, params }) => {
      const body = await request.json() as { role: string }
      return HttpResponse.json({
        ...memberFixture,
        userId: params['userId'] as string,
        role: body.role,
      })
    }),

    // DELETE /api/v1/projects/{projectKey}/members/{userId} → 204
    http.delete(`${BASE_URL}/:userId`, ({ request }) => {
      const xsrf = request.headers.get('X-XSRF-TOKEN')
      if (xsrf === null || xsrf === '') {
        return new HttpResponse(null, { status: 403 })
      }
      return new HttpResponse(null, { status: 204 })
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PM-1. roleEnum — 허용 값 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('roleEnum', () => {
  it('T-PM-1a: PROJECT_ADMIN을 파싱한다', () => {
    expect(roleEnum.parse('PROJECT_ADMIN')).toBe('PROJECT_ADMIN')
  })

  it('T-PM-1b: MEMBER를 파싱한다', () => {
    expect(roleEnum.parse('MEMBER')).toBe('MEMBER')
  })

  it('T-PM-1c: 허용되지 않은 역할 문자열은 ZodError를 throw한다', () => {
    expect(() => roleEnum.parse('OWNER')).toThrow(ZodError)
    expect(() => roleEnum.parse('')).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PM-2. memberResponseSchema — 7 필드 + nullable displayName/username
// ─────────────────────────────────────────────────────────────────────────────
describe('memberResponseSchema', () => {
  it('T-PM-2a: 7 필드 모두 있는 멤버 응답을 파싱한다', () => {
    const result = memberResponseSchema.parse(memberFixture)

    expect(result.projectId).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
    expect(result.userId).toBe('11111111-2222-3333-4444-555555555555')
    expect(result.role).toBe('PROJECT_ADMIN')
    expect(result.displayName).toBe('Maxi Han')
    expect(result.username).toBe('maxi.han')
  })

  it('T-PM-2b: displayName/username이 null인 경우도 파싱 성공한다 (orphan 멤버십)', () => {
    const result = memberResponseSchema.parse(memberFixtureNullDisplay)

    expect(result.displayName).toBeNull()
    expect(result.username).toBeNull()
  })

  it('T-PM-2c: 필수 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => memberResponseSchema.parse({ projectId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' })).toThrow(ZodError)
  })

  it('T-PM-2d: 허용되지 않은 role 값이면 ZodError를 throw한다', () => {
    expect(() => memberResponseSchema.parse({ ...memberFixture, role: 'OWNER' })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PM-3. membersListSchema — { members: [...] } 래퍼 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('membersListSchema', () => {
  it('T-PM-3a: { members: [...] } 래퍼를 파싱한다', () => {
    const result = membersListSchema.parse({ members: [memberFixture] })
    expect(result.members).toHaveLength(1)
    expect(result.members[0]?.role).toBe('PROJECT_ADMIN')
  })

  it('T-PM-3b: 빈 배열도 파싱 성공한다', () => {
    const result = membersListSchema.parse({ members: [] })
    expect(result.members).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PM-4. fetchProjectMembers — GET /api/v1/projects/{key}/members
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchProjectMembers', () => {
  it('T-PM-4a: members 배열을 반환하며 displayName/username/role을 포함한다', async () => {
    const result = await fetchProjectMembers(PROJECT_KEY)

    expect(result).toHaveLength(2)
    expect(result[0]?.displayName).toBe('Maxi Han')
    expect(result[0]?.username).toBe('maxi.han')
    expect(result[0]?.role).toBe('PROJECT_ADMIN')
    expect(result[1]?.displayName).toBeNull()
    expect(result[1]?.username).toBeNull()
    expect(result[1]?.role).toBe('MEMBER')
  })

  it('T-PM-4b: 서버 404 응답(비멤버) 시 ProjectMemberApiError(404)를 throw한다', async () => {
    server.use(
      http.get(BASE_URL, () => {
        return HttpResponse.json({ error: 'project_not_found' }, { status: 404 })
      }),
    )
    const err = await fetchProjectMembers(PROJECT_KEY).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ProjectMemberApiError)
    expect((err as ProjectMemberApiError).status).toBe(404)
    expect((err as ProjectMemberApiError).errorCode).toBe('project_not_found')
  })

  it('T-PM-4c: 서버 401 응답 시 ProjectMemberApiError(401)를 throw한다', async () => {
    server.use(
      http.get(BASE_URL, () => {
        return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
      }),
    )
    await expect(fetchProjectMembers(PROJECT_KEY)).rejects.toBeInstanceOf(ProjectMemberApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PM-5. addMember — POST + X-XSRF-TOKEN 헤더 + 201 응답 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('addMember', () => {
  it('T-PM-5a: 201 응답을 ProjectMember로 파싱해 반환한다', async () => {
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    const result = await addMember(PROJECT_KEY, {
      userId: '11111111-2222-3333-4444-555555555555',
      role: 'MEMBER',
    })

    expect(result.role).toBe('MEMBER')
    expect(result.userId).toBe('11111111-2222-3333-4444-555555555555')
  })

  it('T-PM-5b: POST 요청에 X-XSRF-TOKEN 헤더가 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post(BASE_URL, async ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        const body = await request.json() as { userId: string; role: string }
        return HttpResponse.json({ ...memberFixture, role: body.role }, { status: 201 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=captured-xsrf'
    await addMember(PROJECT_KEY, { userId: memberFixture.userId, role: 'MEMBER' })
    expect(capturedXsrf).toBe('captured-xsrf')
  })

  it('T-PM-5c: 409(membership_already_exists) 응답 시 에러코드를 보존해 throw한다', async () => {
    server.use(
      http.post(BASE_URL, () => {
        return HttpResponse.json({ error: 'membership_already_exists' }, { status: 409 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    const err = await addMember(PROJECT_KEY, { userId: memberFixture.userId, role: 'MEMBER' }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ProjectMemberApiError)
    expect((err as ProjectMemberApiError).status).toBe(409)
    expect((err as ProjectMemberApiError).errorCode).toBe('membership_already_exists')
  })

  it('T-PM-5d: 403(not_project_admin) 응답 시 에러코드를 보존해 throw한다', async () => {
    server.use(
      http.post(BASE_URL, () => {
        return HttpResponse.json({ error: 'not_project_admin' }, { status: 403 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    const err = await addMember(PROJECT_KEY, { userId: memberFixture.userId, role: 'MEMBER' }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ProjectMemberApiError)
    expect((err as ProjectMemberApiError).errorCode).toBe('not_project_admin')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PM-6. changeRole — PATCH + X-XSRF-TOKEN 헤더 + 200 응답 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('changeRole', () => {
  it('T-PM-6a: 200 응답을 ProjectMember로 파싱해 반환한다', async () => {
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    const result = await changeRole(PROJECT_KEY, memberFixture.userId, 'MEMBER')

    expect(result.userId).toBe(memberFixture.userId)
    expect(result.role).toBe('MEMBER')
  })

  it('T-PM-6b: PATCH 요청에 X-XSRF-TOKEN 헤더가 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch(`${BASE_URL}/:userId`, async ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        const body = await request.json() as { role: string }
        return HttpResponse.json({ ...memberFixture, role: body.role })
      }),
    )
    document.cookie = 'XSRF-TOKEN=patch-xsrf'
    await changeRole(PROJECT_KEY, memberFixture.userId, 'MEMBER')
    expect(capturedXsrf).toBe('patch-xsrf')
  })

  it('T-PM-6c: 409(last_admin_protected) 응답 시 에러코드를 보존해 throw한다', async () => {
    server.use(
      http.patch(`${BASE_URL}/:userId`, () => {
        return HttpResponse.json({ error: 'last_admin_protected' }, { status: 409 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    const err = await changeRole(PROJECT_KEY, memberFixture.userId, 'MEMBER').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ProjectMemberApiError)
    expect((err as ProjectMemberApiError).status).toBe(409)
    expect((err as ProjectMemberApiError).errorCode).toBe('last_admin_protected')
  })

  it('T-PM-6d: 422(invalid_role) 응답 시 에러코드를 보존해 throw한다', async () => {
    server.use(
      http.patch(`${BASE_URL}/:userId`, () => {
        return HttpResponse.json({ error: 'invalid_role' }, { status: 422 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    const err = await changeRole(PROJECT_KEY, memberFixture.userId, 'MEMBER').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ProjectMemberApiError)
    expect((err as ProjectMemberApiError).errorCode).toBe('invalid_role')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PM-7. removeMember — DELETE + X-XSRF-TOKEN 헤더 + 204
// ─────────────────────────────────────────────────────────────────────────────
describe('removeMember', () => {
  it('T-PM-7a: 204 응답 시 void를 반환한다', async () => {
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    await expect(removeMember(PROJECT_KEY, memberFixture.userId)).resolves.toBeUndefined()
  })

  it('T-PM-7b: DELETE 요청에 X-XSRF-TOKEN 헤더가 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete(`${BASE_URL}/:userId`, ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=delete-xsrf'
    await removeMember(PROJECT_KEY, memberFixture.userId)
    expect(capturedXsrf).toBe('delete-xsrf')
  })

  it('T-PM-7c: 404(member_not_found) 응답 시 에러코드를 보존해 throw한다', async () => {
    server.use(
      http.delete(`${BASE_URL}/:userId`, () => {
        return HttpResponse.json({ error: 'member_not_found' }, { status: 404 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    const err = await removeMember(PROJECT_KEY, memberFixture.userId).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ProjectMemberApiError)
    expect((err as ProjectMemberApiError).status).toBe(404)
    expect((err as ProjectMemberApiError).errorCode).toBe('member_not_found')
  })

  it('T-PM-7d: 409(last_admin_protected) 응답 시 에러코드를 보존해 throw한다', async () => {
    server.use(
      http.delete(`${BASE_URL}/:userId`, () => {
        return HttpResponse.json({ error: 'last_admin_protected' }, { status: 409 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-xsrf-token'
    const err = await removeMember(PROJECT_KEY, memberFixture.userId).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ProjectMemberApiError)
    expect((err as ProjectMemberApiError).errorCode).toBe('last_admin_protected')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PM-8. 타입 컴파일 가드
// ─────────────────────────────────────────────────────────────────────────────
describe('타입 컴파일 가드', () => {
  it('T-PM-8a: ProjectMember 타입이 7 필드를 가진다', () => {
    const member: ProjectMember = memberFixture
    expect(member.projectId).toBe('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
  })

  it('T-PM-8b: ProjectMembersList 타입이 members 배열을 가진다', () => {
    const list: ProjectMembersList = { members: [memberFixture] }
    expect(list.members).toHaveLength(1)
  })
})
