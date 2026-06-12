// 이슈 템플릿 API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  fetchIssueTemplates,
  fetchIssueTemplate,
  createIssueTemplate,
  updateIssueTemplate,
  deleteIssueTemplate,
  extractIssueTemplateErrorCode,
} from '../issue-templates'
import { ApiError } from '../client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — IssueTemplateResponse (backend DTO 1:1)
// Zod 4.x uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────

const templateFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  issueTypeId: 1,
  name: '버그 리포트 템플릿',
  content: '## 재현 단계\n\n## 기대 결과\n\n## 실제 결과',
  createdAt: '2026-06-12T10:00:00Z',
  updatedAt: '2026-06-12T10:00:00Z',
}

const templateFixture2 = {
  id: 'b2c3d4e5-f6a7-4901-bcde-f01234567891',
  projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  issueTypeId: 2,
  name: '작업 기본 템플릿',
  content: '## 목표\n\n## 완료 조건',
  createdAt: '2026-06-12T11:00:00Z',
  updatedAt: '2026-06-12T11:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// fetchIssueTemplates
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchIssueTemplates', () => {
  it('{data:[...]} 래퍼를 언래핑해 IssueTemplate[] 를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/issue-templates', () =>
        HttpResponse.json({ data: [templateFixture, templateFixture2] }),
      ),
    )
    const result = await fetchIssueTemplates('ATLAS')
    expect(result).toHaveLength(2)
    expect(result[0]).toMatchObject({ id: templateFixture.id, issueTypeId: 1 })
    expect(result[1]).toMatchObject({ issueTypeId: 2 })
  })

  it('빈 목록이면 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/issue-templates', () =>
        HttpResponse.json({ data: [] }),
      ),
    )
    const result = await fetchIssueTemplates('ATLAS')
    expect(result).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchIssueTemplate
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchIssueTemplate', () => {
  it('{data: ...} 래퍼를 언래핑해 IssueTemplate 단건을 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/issue-templates/:templateId', () =>
        HttpResponse.json({ data: templateFixture }),
      ),
    )
    const result = await fetchIssueTemplate('ATLAS', templateFixture.id)
    expect(result.id).toBe(templateFixture.id)
    expect(result.issueTypeId).toBe(1)
    expect(result.content).toBe(templateFixture.content)
    expect(result.createdAt).toBe('2026-06-12T10:00:00Z')
    expect(result.updatedAt).toBe('2026-06-12T10:00:00Z')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createIssueTemplate
// ─────────────────────────────────────────────────────────────────────────────

describe('createIssueTemplate', () => {
  it('201 응답을 파싱해 IssueTemplate 를 반환한다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectIdOrKey/issue-templates', async () =>
        HttpResponse.json({ data: templateFixture }, { status: 201 }),
      ),
    )
    const result = await createIssueTemplate('ATLAS', {
      issueTypeId: 1,
      name: '버그 리포트 템플릿',
      content: '## 재현 단계',
    })
    expect(result.id).toBe(templateFixture.id)
    expect(result.issueTypeId).toBe(1)
  })

  it('요청에 X-XSRF-TOKEN 헤더가 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/projects/:projectIdOrKey/issue-templates', async ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ data: templateFixture }, { status: 201 })
      }),
    )
    await createIssueTemplate('ATLAS', {
      issueTypeId: 1,
      name: '버그 리포트 템플릿',
      content: '## 재현 단계',
    })
    // readXsrfToken은 sessionStorage에서 읽음 — 테스트 환경에선 빈 문자열이 반환된다
    expect(capturedXsrf).not.toBeNull()
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectIdOrKey/issue-templates', async () =>
        HttpResponse.json({ errorCode: 'ISSUE_TEMPLATE_DUPLICATE' }, { status: 409 }),
      ),
    )
    await expect(
      createIssueTemplate('ATLAS', { issueTypeId: 1, name: '중복', content: '내용' }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// updateIssueTemplate
// ─────────────────────────────────────────────────────────────────────────────

describe('updateIssueTemplate', () => {
  it('200 응답을 파싱해 수정된 IssueTemplate 를 반환한다', async () => {
    const updated = { ...templateFixture, name: '버그 리포트 템플릿(수정)' }
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/issue-templates/:templateId', async () =>
        HttpResponse.json({ data: updated }),
      ),
    )
    const result = await updateIssueTemplate('ATLAS', templateFixture.id, {
      name: '버그 리포트 템플릿(수정)',
    })
    expect(result.name).toBe('버그 리포트 템플릿(수정)')
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/issue-templates/:templateId', async () =>
        HttpResponse.json({ errorCode: 'ISSUE_TEMPLATE_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(
      updateIssueTemplate('ATLAS', templateFixture.id, { name: '없는템플릿' }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deleteIssueTemplate
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteIssueTemplate', () => {
  it('204 무바디 응답을 정상 처리하고 void 를 반환한다', async () => {
    server.use(
      http.delete('/api/v1/projects/:projectIdOrKey/issue-templates/:templateId', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(deleteIssueTemplate('ATLAS', templateFixture.id)).resolves.toBeUndefined()
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.delete('/api/v1/projects/:projectIdOrKey/issue-templates/:templateId', () =>
        HttpResponse.json({ errorCode: 'ISSUE_TEMPLATE_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(deleteIssueTemplate('ATLAS', templateFixture.id)).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// extractIssueTemplateErrorCode
// ─────────────────────────────────────────────────────────────────────────────

describe('extractIssueTemplateErrorCode', () => {
  it('ApiError body 의 errorCode 를 string 으로 반환한다', () => {
    const err = new ApiError(409, { errorCode: 'ISSUE_TEMPLATE_DUPLICATE' })
    expect(extractIssueTemplateErrorCode(err)).toBe('ISSUE_TEMPLATE_DUPLICATE')
  })

  it('ISSUE_TEMPLATE_NOT_FOUND errorCode 를 추출한다', () => {
    const err = new ApiError(404, { errorCode: 'ISSUE_TEMPLATE_NOT_FOUND' })
    expect(extractIssueTemplateErrorCode(err)).toBe('ISSUE_TEMPLATE_NOT_FOUND')
  })

  it('ISSUE_TEMPLATE_PROJECT_NOT_FOUND errorCode 를 추출한다', () => {
    const err = new ApiError(404, { errorCode: 'ISSUE_TEMPLATE_PROJECT_NOT_FOUND' })
    expect(extractIssueTemplateErrorCode(err)).toBe('ISSUE_TEMPLATE_PROJECT_NOT_FOUND')
  })

  it('ISSUE_TEMPLATE_ACCESS_DENIED errorCode 를 추출한다', () => {
    const err = new ApiError(403, { errorCode: 'ISSUE_TEMPLATE_ACCESS_DENIED' })
    expect(extractIssueTemplateErrorCode(err)).toBe('ISSUE_TEMPLATE_ACCESS_DENIED')
  })

  it('ISSUE_TEMPLATE_INVALID errorCode 를 추출한다', () => {
    const err = new ApiError(422, { errorCode: 'ISSUE_TEMPLATE_INVALID' })
    expect(extractIssueTemplateErrorCode(err)).toBe('ISSUE_TEMPLATE_INVALID')
  })

  it('VALIDATION_FAILED errorCode 를 추출한다', () => {
    const err = new ApiError(400, { errorCode: 'VALIDATION_FAILED' })
    expect(extractIssueTemplateErrorCode(err)).toBe('VALIDATION_FAILED')
  })

  it('ApiError 이지만 errorCode 가 없으면 null 을 반환한다', () => {
    const err = new ApiError(500, { message: 'Internal Server Error' })
    expect(extractIssueTemplateErrorCode(err)).toBeNull()
  })

  it('ApiError 가 아니면 null 을 반환한다', () => {
    expect(extractIssueTemplateErrorCode(new Error('network error'))).toBeNull()
    expect(extractIssueTemplateErrorCode('string error')).toBeNull()
    expect(extractIssueTemplateErrorCode(null)).toBeNull()
  })
})
