// 프로젝트 권한 조회 API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { projectPermissionsSchema, fetchProjectPermissions } from './project-permissions'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — ProjectPermissionsResponse (백엔드 Task 1 + Task 11a 응답 계약)
// permissions 키는 IssuePermission enum name과 1:1 대응
// Task 11a에서 UPDATE 권한 추가 — 이슈 재정렬/스프린트 할당·해제 게이팅용
// ─────────────────────────────────────────────────────────────────────────────
const projectPermissionsFixture = {
  projectKey: 'ATLAS',
  permissions: {
    CREATE: true,
    UPDATE: true,
    MANAGE_COMPONENTS: true,
    MANAGE_VERSIONS: true,
    MANAGE_CUSTOM_FIELDS: true,
    MANAGE_FIELD_PERMISSIONS: true,
    MANAGE_TEMPLATES: true,
  },
}

const projectPermissionsFixtureFalse = {
  projectKey: 'ATLAS',
  permissions: {
    CREATE: false,
    UPDATE: false,
    MANAGE_COMPONENTS: false,
    MANAGE_VERSIONS: false,
    MANAGE_CUSTOM_FIELDS: false,
    MANAGE_FIELD_PERMISSIONS: false,
    MANAGE_TEMPLATES: false,
  },
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('projectPermissionsSchema', () => {
  it('유효한 응답을 파싱한다', () => {
    const result = projectPermissionsSchema.parse(projectPermissionsFixture)
    expect(result.projectKey).toBe('ATLAS')
    expect(result.permissions.CREATE).toBe(true)
  })

  it('MANAGE_TEMPLATES:true를 파싱한다', () => {
    const result = projectPermissionsSchema.parse(projectPermissionsFixture)
    expect(result.permissions.MANAGE_TEMPLATES).toBe(true)
  })

  it('MANAGE_TEMPLATES:false를 파싱한다', () => {
    const result = projectPermissionsSchema.parse(projectPermissionsFixtureFalse)
    expect(result.permissions.MANAGE_TEMPLATES).toBe(false)
  })

  it('CREATE가 false인 경우도 파싱한다', () => {
    const result = projectPermissionsSchema.parse(projectPermissionsFixtureFalse)
    expect(result.permissions.CREATE).toBe(false)
  })

  it('permissions.CREATE 필드 누락 시 ZodError를 던진다', () => {
    expect(() =>
      projectPermissionsSchema.parse({
        projectKey: 'ATLAS',
        permissions: {},
      }),
    ).toThrow()
  })

  it('projectKey 필드 누락 시 ZodError를 던진다', () => {
    expect(() =>
      projectPermissionsSchema.parse({
        permissions: { CREATE: true },
      }),
    ).toThrow()
  })

  it('permissions.CREATE가 boolean이 아니면 ZodError를 던진다', () => {
    expect(() =>
      projectPermissionsSchema.parse({
        projectKey: 'ATLAS',
        permissions: { CREATE: 'yes' },
      }),
    ).toThrow()
  })
})

describe('fetchProjectPermissions', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', ({ request }) => {
        const url = new URL(request.url)
        const projectKey = url.searchParams.get('projectKey')
        if (projectKey === 'ATLAS') {
          return HttpResponse.json(projectPermissionsFixture)
        }
        return HttpResponse.json({ error: 'Project not found' }, { status: 404 })
      }),
    )
  })

  it('ATLAS 프로젝트 권한을 반환한다', async () => {
    const result = await fetchProjectPermissions('ATLAS')
    expect(result.projectKey).toBe('ATLAS')
    expect(result.permissions.CREATE).toBe(true)
  })

  it('projectKey를 URL 인코딩해서 요청한다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/users/me/project-permissions', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(projectPermissionsFixture)
      }),
    )
    await fetchProjectPermissions('ATLAS')
    expect(capturedUrl).toContain('projectKey=ATLAS')
  })

  it('응답 스키마 불일치 시 ZodError를 던진다', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () => {
        return HttpResponse.json({ projectKey: 'ATLAS', permissions: {} })
      }),
    )
    await expect(fetchProjectPermissions('ATLAS')).rejects.toThrow()
  })

  it('401 응답 시 ApiError를 던진다', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () => {
        return HttpResponse.json({ error: 'Unauthorized' }, { status: 401 })
      }),
    )
    await expect(fetchProjectPermissions('ATLAS')).rejects.toBeInstanceOf(ApiError)
  })

  it('400 응답 시 ApiError를 던진다', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () => {
        return HttpResponse.json({ error: 'Bad Request' }, { status: 400 })
      }),
    )
    await expect(fetchProjectPermissions('ATLAS')).rejects.toBeInstanceOf(ApiError)
  })
})
