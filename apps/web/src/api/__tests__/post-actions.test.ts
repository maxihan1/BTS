// post-action API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  postActionResponseSchema,
  postActionRequestSchema,
  listPostActions,
  createPostAction,
  updatePostAction,
  deletePostAction,
  PostActionApiError,
} from '../post-actions'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — PostActionResponse (backend DTO 1:1)
// Zod 4.x uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────

const postActionFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  type: 'CALL_WEBHOOK',
  config: { url: 'https://example.com/hook', method: 'POST' },
  displayOrder: 0,
}

const postActionFixture2 = {
  id: 'b2c3d4e5-f6a7-4901-bcde-f01234567891',
  type: 'SET_FIELD',
  config: { fieldKey: 'status', value: 'done' },
  displayOrder: 1,
}

const BASE = '/api/v1/workflows/default/transitions/TODO__IN_PROGRESS/post-actions'

// ─────────────────────────────────────────────────────────────────────────────
// T1-1. postActionResponseSchema — 4 필드 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('postActionResponseSchema', () => {
  it('T1-1a: 4 필드가 모두 있는 PostActionResponse를 파싱한다', () => {
    const result = postActionResponseSchema.parse(postActionFixture)

    expect(result.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result.type).toBe('CALL_WEBHOOK')
    expect(result.config).toEqual({ url: 'https://example.com/hook', method: 'POST' })
    expect(result.displayOrder).toBe(0)
  })

  it('T1-1b: config가 빈 객체이어도 파싱 성공한다', () => {
    const result = postActionResponseSchema.parse({ ...postActionFixture, config: {} })
    expect(result.config).toEqual({})
  })

  it('T1-1c: type이 CALL_WEBHOOK 외 다른 값이어도 파싱 성공한다 (관대한 string)', () => {
    const result = postActionResponseSchema.parse({ ...postActionFixture, type: 'SET_FIELD' })
    expect(result.type).toBe('SET_FIELD')
  })

  it('T1-1d: 필수 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => postActionResponseSchema.parse({ id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890' })).toThrow(ZodError)
  })

  it('T1-1e: id가 UUID 형식이 아니면 ZodError를 throw한다', () => {
    expect(() => postActionResponseSchema.parse({ ...postActionFixture, id: 'not-a-uuid' })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-2. postActionRequestSchema — 요청 DTO 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('postActionRequestSchema', () => {
  it('T1-2a: 모든 필드를 파싱한다', () => {
    const result = postActionRequestSchema.parse({
      type: 'CALL_WEBHOOK',
      config: { url: 'https://example.com' },
      displayOrder: 2,
    })
    expect(result.type).toBe('CALL_WEBHOOK')
    expect(result.displayOrder).toBe(2)
  })

  it('T1-2b: config와 displayOrder가 기본값을 가진다', () => {
    const result = postActionRequestSchema.parse({ type: 'CALL_WEBHOOK' })
    expect(result.config).toEqual({})
    expect(result.displayOrder).toBe(0)
  })

  it('T1-2c: type이 빈 문자열이면 ZodError를 throw한다', () => {
    expect(() => postActionRequestSchema.parse({ type: '' })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-3. listPostActions — GET → { data: PostActionResponse[] } 언래핑
// ─────────────────────────────────────────────────────────────────────────────

describe('listPostActions', () => {
  it('T1-3a: {data:[...]} 래퍼를 언래핑해 PostActionResponse[] 를 반환한다', async () => {
    server.use(
      http.get(BASE, () =>
        HttpResponse.json({ data: [postActionFixture, postActionFixture2] }),
      ),
    )
    const result = await listPostActions('default', 'TODO__IN_PROGRESS')
    expect(result).toHaveLength(2)
    expect(result[0]).toMatchObject({ id: postActionFixture.id, type: 'CALL_WEBHOOK' })
    expect(result[1]).toMatchObject({ type: 'SET_FIELD' })
  })

  it('T1-3b: 빈 목록이면 빈 배열을 반환한다', async () => {
    server.use(
      http.get(BASE, () =>
        HttpResponse.json({ data: [] }),
      ),
    )
    const result = await listPostActions('default', 'TODO__IN_PROGRESS')
    expect(result).toHaveLength(0)
  })

  it('T1-3c: 403 응답 시 PostActionApiError(status=403, code=WORKFLOW_SCHEME_ACCESS_DENIED)를 throw한다', async () => {
    server.use(
      http.get(BASE, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_SCHEME_ACCESS_DENIED', message: '권한 없음' } },
          { status: 403 },
        ),
      ),
    )
    const err = await listPostActions('default', 'TODO__IN_PROGRESS').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(PostActionApiError)
    const apiErr = err as PostActionApiError
    expect(apiErr.status).toBe(403)
    expect(apiErr.errorCode).toBe('WORKFLOW_SCHEME_ACCESS_DENIED')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-4. createPostAction — POST → 201 → { data: PostActionResponse }
// ─────────────────────────────────────────────────────────────────────────────

describe('createPostAction', () => {
  it('T1-4a: 201 응답을 파싱해 PostActionResponse를 반환한다', async () => {
    server.use(
      http.post(BASE, async () =>
        HttpResponse.json({ data: postActionFixture }, { status: 201 }),
      ),
    )
    const result = await createPostAction('default', 'TODO__IN_PROGRESS', {
      type: 'CALL_WEBHOOK',
      config: { url: 'https://example.com/hook', method: 'POST' },
      displayOrder: 0,
    })
    expect(result.id).toBe(postActionFixture.id)
    expect(result.type).toBe('CALL_WEBHOOK')
  })

  it('T1-4b: 400 응답 시 PostActionApiError(code=WORKFLOW_POST_ACTION_INVALID)를 throw한다', async () => {
    server.use(
      http.post(BASE, async () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_POST_ACTION_INVALID', message: '유효하지 않은 요청' } },
          { status: 400 },
        ),
      ),
    )
    const err = await createPostAction('default', 'TODO__IN_PROGRESS', {
      type: 'CALL_WEBHOOK',
      config: {},
      displayOrder: 0,
    }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(PostActionApiError)
    const apiErr = err as PostActionApiError
    expect(apiErr.status).toBe(400)
    expect(apiErr.errorCode).toBe('WORKFLOW_POST_ACTION_INVALID')
    expect(apiErr.message).toContain('WORKFLOW_POST_ACTION_INVALID')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-5. updatePostAction — PUT /{id} → 200 → { data: PostActionResponse }
// ─────────────────────────────────────────────────────────────────────────────

describe('updatePostAction', () => {
  it('T1-5a: 200 응답을 파싱해 수정된 PostActionResponse를 반환한다', async () => {
    const updated = { ...postActionFixture, displayOrder: 5 }
    server.use(
      http.put(`${BASE}/${postActionFixture.id}`, async () =>
        HttpResponse.json({ data: updated }),
      ),
    )
    const result = await updatePostAction('default', 'TODO__IN_PROGRESS', postActionFixture.id, {
      type: 'CALL_WEBHOOK',
      config: { url: 'https://example.com/hook', method: 'POST' },
      displayOrder: 5,
    })
    expect(result.displayOrder).toBe(5)
  })

  it('T1-5b: 404 응답 시 PostActionApiError(code=WORKFLOW_POST_ACTION_NOT_FOUND)를 throw한다', async () => {
    server.use(
      http.put(`${BASE}/${postActionFixture.id}`, async () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_POST_ACTION_NOT_FOUND', message: '없음' } },
          { status: 404 },
        ),
      ),
    )
    const err = await updatePostAction('default', 'TODO__IN_PROGRESS', postActionFixture.id, {
      type: 'CALL_WEBHOOK',
      config: {},
      displayOrder: 0,
    }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(PostActionApiError)
    const apiErr = err as PostActionApiError
    expect(apiErr.status).toBe(404)
    expect(apiErr.errorCode).toBe('WORKFLOW_POST_ACTION_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-6. deletePostAction — DELETE /{id} → 204 빈 바디
// ─────────────────────────────────────────────────────────────────────────────

describe('deletePostAction', () => {
  it('T1-6a: 204 무바디 응답을 정상 처리하고 void를 반환한다', async () => {
    server.use(
      http.delete(`${BASE}/${postActionFixture.id}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(
      deletePostAction('default', 'TODO__IN_PROGRESS', postActionFixture.id),
    ).resolves.toBeUndefined()
  })

  it('T1-6b: 403 응답 시 PostActionApiError를 throw한다', async () => {
    server.use(
      http.delete(`${BASE}/${postActionFixture.id}`, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_SCHEME_ACCESS_DENIED', message: '권한 없음' } },
          { status: 403 },
        ),
      ),
    )
    const err = await deletePostAction('default', 'TODO__IN_PROGRESS', postActionFixture.id).catch(
      (e: unknown) => e,
    )
    expect(err).toBeInstanceOf(PostActionApiError)
    const apiErr = err as PostActionApiError
    expect(apiErr.status).toBe(403)
    expect(apiErr.errorCode).toBe('WORKFLOW_SCHEME_ACCESS_DENIED')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-7. PostActionApiError — 에러 봉투 중첩 형태 { error: { code, message } } 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionApiError', () => {
  it('T1-7a: status, errorCode, message를 보존한다', () => {
    const err = new PostActionApiError(400, 'WORKFLOW_POST_ACTION_INVALID', '유효하지 않음')
    expect(err.status).toBe(400)
    expect(err.errorCode).toBe('WORKFLOW_POST_ACTION_INVALID')
    expect(err.message).toContain('WORKFLOW_POST_ACTION_INVALID')
    expect(err).toBeInstanceOf(PostActionApiError)
    expect(err).toBeInstanceOf(Error)
  })

  it('T1-7b: name이 PostActionApiError이다', () => {
    const err = new PostActionApiError(404, 'WORKFLOW_POST_ACTION_NOT_FOUND', '없음')
    expect(err.name).toBe('PostActionApiError')
  })
})
