// 프로젝트 CRUD API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 HTTP 가로채기 + Zod 파싱 검증 (FR-PJ PR-5 Task 3)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  projectSchema,
  listProjects,
  createProject,
  getProject,
  updateProjectName,
  archiveProject,
  unarchiveProject,
  extractProjectErrorCode,
} from './projects'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — ProjectResponse 4 필드 (backend DTO 1:1, id/key/name/archived)
// Zod 4.x uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────
const projectFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  key: 'ATLAS',
  name: 'Atlas 프로젝트',
  archived: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// projectSchema — archived 필드 (BE-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('projectSchema', () => {
  it('id/key/name/archived 4필드를 파싱한다', () => {
    const result = projectSchema.safeParse(projectFixture)
    expect(result.success).toBe(true)
  })

  it('archived:true 응답도 파싱한다', () => {
    const result = projectSchema.safeParse({ ...projectFixture, archived: true })
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.archived).toBe(true)
    }
  })

  it('archived 키가 없어도 parse 성공한다 (하위호환 — 기존 mocks/project-list-handlers.ts 인라인 fixture, mock fanout 방어)', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { archived: _archived, ...withoutArchived } = projectFixture
    const result = projectSchema.safeParse(withoutArchived)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.archived).toBeUndefined()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// listProjects — 기존 함수 재사용, archived 스키마 반영 확인만
// ─────────────────────────────────────────────────────────────────────────────

describe('listProjects', () => {
  it('{data:[...]} 래퍼를 언래핑해 archived 필드를 포함한 Project[] 를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects', () => HttpResponse.json({ data: [projectFixture] })),
    )
    const result = await listProjects(false)
    expect(result).toHaveLength(1)
    expect(result[0]?.archived).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// getProject — 단건 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('getProject', () => {
  it('{data:{...}} 래퍼를 언래핑해 Project 를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:idOrKey', () => HttpResponse.json({ data: projectFixture })),
    )
    const result = await getProject('ATLAS')
    expect(result.key).toBe('ATLAS')
    expect(result.archived).toBe(false)
  })

  it('404 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.get('/api/v1/projects/:idOrKey', () =>
        HttpResponse.json({ errorCode: 'ISSUE_PROJECT_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(getProject('UNKNOWN')).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createProject — 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('createProject', () => {
  it('201 응답을 파싱해 Project 를 반환한다', async () => {
    server.use(
      http.post('/api/v1/projects', async ({ request }) => {
        const body = await request.json()
        expect(body).toEqual({ key: 'NOVA', name: 'Nova 프로젝트' })
        return HttpResponse.json(
          { data: { id: projectFixture.id, key: 'NOVA', name: 'Nova 프로젝트', archived: false } },
          { status: 201 },
        )
      }),
    )
    const result = await createProject('NOVA', 'Nova 프로젝트')
    expect(result.key).toBe('NOVA')
    expect(result.archived).toBe(false)
  })

  it('X-XSRF-TOKEN 헤더를 포함해 POST 요청을 보낸다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/projects', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ data: projectFixture }, { status: 201 })
      }),
    )
    await createProject('ATLAS', 'Atlas 프로젝트')
    expect(capturedXsrf).not.toBeNull()
  })

  it('key 중복(409) 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.post('/api/v1/projects', () =>
        HttpResponse.json({ errorCode: 'ISSUE_PROJECT_KEY_ALREADY_EXISTS' }, { status: 409 }),
      ),
    )
    await expect(createProject('ATLAS', 'Atlas 프로젝트')).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// updateProjectName — 204 No Content
// ─────────────────────────────────────────────────────────────────────────────

describe('updateProjectName', () => {
  it('204 무바디 응답을 정상 처리하고 void 를 반환한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:idOrKey', async ({ request }) => {
        const body = await request.json()
        expect(body).toEqual({ name: '새 이름' })
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await expect(updateProjectName('ATLAS', '새 이름')).resolves.toBeUndefined()
  })

  it('X-XSRF-TOKEN 헤더를 포함해 PATCH 요청을 보낸다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch('/api/v1/projects/:idOrKey', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await updateProjectName('ATLAS', '새 이름')
    expect(capturedXsrf).not.toBeNull()
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:idOrKey', () =>
        HttpResponse.json({ errorCode: 'ISSUE_PROJECT_SETTINGS_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(updateProjectName('UNKNOWN', '새 이름')).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// archiveProject / unarchiveProject
// ─────────────────────────────────────────────────────────────────────────────

describe('archiveProject', () => {
  it('200 응답을 파싱해 archivedAt non-null 결과를 반환한다', async () => {
    server.use(
      http.post('/api/v1/projects/:idOrKey/archive', () =>
        HttpResponse.json({
          data: { projectId: projectFixture.id, projectKey: 'ATLAS', archivedAt: '2026-07-20T00:00:00Z' },
        }),
      ),
    )
    const result = await archiveProject('ATLAS')
    expect(result.projectKey).toBe('ATLAS')
    expect(result.archivedAt).not.toBeNull()
  })

  it('X-XSRF-TOKEN 헤더를 포함해 POST 요청을 보낸다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/projects/:idOrKey/archive', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({
          data: { projectId: projectFixture.id, projectKey: 'ATLAS', archivedAt: '2026-07-20T00:00:00Z' },
        })
      }),
    )
    await archiveProject('ATLAS')
    expect(capturedXsrf).not.toBeNull()
  })

  it('404 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.post('/api/v1/projects/:idOrKey/archive', () =>
        HttpResponse.json({ errorCode: 'ISSUE_PROJECT_ARCHIVE_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(archiveProject('UNKNOWN')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('unarchiveProject', () => {
  it('200 응답을 파싱해 archivedAt null 결과를 반환한다', async () => {
    server.use(
      http.post('/api/v1/projects/:idOrKey/unarchive', () =>
        HttpResponse.json({
          data: { projectId: projectFixture.id, projectKey: 'ATLAS', archivedAt: null },
        }),
      ),
    )
    const result = await unarchiveProject('ATLAS')
    expect(result.archivedAt).toBeNull()
  })

  it('404 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.post('/api/v1/projects/:idOrKey/unarchive', () =>
        HttpResponse.json({ errorCode: 'ISSUE_PROJECT_ARCHIVE_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(unarchiveProject('UNKNOWN')).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// extractProjectErrorCode
// ─────────────────────────────────────────────────────────────────────────────

describe('extractProjectErrorCode', () => {
  it('ApiError body 의 errorCode 를 string 으로 반환한다', () => {
    const err = new ApiError(409, { errorCode: 'ISSUE_PROJECT_KEY_ALREADY_EXISTS' })
    expect(extractProjectErrorCode(err)).toBe('ISSUE_PROJECT_KEY_ALREADY_EXISTS')
  })

  it('ApiError 이지만 errorCode 가 없으면 null 을 반환한다', () => {
    const err = new ApiError(500, { message: 'Internal Server Error' })
    expect(extractProjectErrorCode(err)).toBeNull()
  })

  it('ApiError 가 아니면 null 을 반환한다', () => {
    expect(extractProjectErrorCode(new Error('network error'))).toBeNull()
    expect(extractProjectErrorCode('string error')).toBeNull()
    expect(extractProjectErrorCode(null)).toBeNull()
  })
})
