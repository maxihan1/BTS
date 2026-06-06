// 프로젝트 리드 API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 HTTP 가로채기 + Zod 파싱 검증 (FR-CM-04)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  fetchProjectLead,
  changeProjectLead,
  extractProjectLeadErrorCode,
} from './project-lead'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — ProjectLeadResponse 2 필드 (backend DTO 1:1)
// Zod 4.x uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────
const projectLeadFixture = {
  projectId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  leadUserId: 'b2c3d4e5-f6a7-4890-bcde-f01234567891',
}

const projectLeadNullFixture = {
  projectId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  leadUserId: null,
}

describe('fetchProjectLead', () => {
  it('{data:{...}} 래퍼를 언래핑해 ProjectLead 를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/lead', () =>
        HttpResponse.json({ data: projectLeadFixture }),
      ),
    )
    const result = await fetchProjectLead('ATLAS')
    expect(result.projectId).toBe(projectLeadFixture.projectId)
    expect(result.leadUserId).toBe(projectLeadFixture.leadUserId)
  })

  it('leadUserId 가 null 인 응답도 정상 파싱한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/lead', () =>
        HttpResponse.json({ data: projectLeadNullFixture }),
      ),
    )
    const result = await fetchProjectLead('ATLAS')
    expect(result.leadUserId).toBeNull()
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/lead', () =>
        HttpResponse.json({ errorCode: 'PROJECT_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(fetchProjectLead('ATLAS')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('changeProjectLead', () => {
  it('PATCH 응답을 파싱해 ProjectLead 를 반환한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/lead', async () =>
        HttpResponse.json({ data: projectLeadFixture }),
      ),
    )
    const result = await changeProjectLead('ATLAS', projectLeadFixture.leadUserId)
    expect(result.projectId).toBe(projectLeadFixture.projectId)
    expect(result.leadUserId).toBe(projectLeadFixture.leadUserId)
  })

  it('X-XSRF-TOKEN 헤더를 포함해 PATCH 요청을 보낸다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/lead', async ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ data: projectLeadFixture })
      }),
    )
    await changeProjectLead('ATLAS', projectLeadFixture.leadUserId)
    // readXsrfToken()이 반환하는 값이 헤더에 설정돼야 한다
    expect(capturedXsrf).not.toBeNull()
  })

  it('leadUserId null 로 리드를 해제할 수 있다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/lead', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: projectLeadNullFixture })
      }),
    )
    const result = await changeProjectLead('ATLAS', null)
    expect(capturedBody).toEqual({ leadUserId: null })
    expect(result.leadUserId).toBeNull()
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/lead', () =>
        HttpResponse.json({ errorCode: 'PROJECT_LEAD_NOT_FOUND' }, { status: 422 }),
      ),
    )
    await expect(changeProjectLead('ATLAS', 'b2c3d4e5-f6a7-4890-bcde-f01234567891')).rejects.toBeInstanceOf(ApiError)
  })
})

describe('extractProjectLeadErrorCode', () => {
  it('ApiError body 의 errorCode 를 string 으로 반환한다', () => {
    const err = new ApiError(422, { errorCode: 'PROJECT_LEAD_NOT_FOUND' })
    expect(extractProjectLeadErrorCode(err)).toBe('PROJECT_LEAD_NOT_FOUND')
  })

  it('ApiError 이지만 errorCode 가 없으면 null 을 반환한다', () => {
    const err = new ApiError(500, { message: 'Internal Server Error' })
    expect(extractProjectLeadErrorCode(err)).toBeNull()
  })

  it('ApiError 가 아니면 null 을 반환한다', () => {
    expect(extractProjectLeadErrorCode(new Error('network error'))).toBeNull()
    expect(extractProjectLeadErrorCode('string error')).toBeNull()
    expect(extractProjectLeadErrorCode(null)).toBeNull()
  })

  it('Zod 파싱 실패 시 ApiError 가 아니므로 null 을 반환한다', () => {
    const zodError = new Error('ZodError: invalid uuid')
    expect(extractProjectLeadErrorCode(zodError)).toBeNull()
  })
})
