// 컴포넌트 API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  fetchComponents,
  createComponent,
  deleteComponent,
  extractComponentErrorCode,
} from './components'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — ComponentResponse 5 필드 (backend DTO 1:1)
// Zod 4.x uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────
const componentFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  name: '인증 모듈',
  description: '로그인 / 회원가입 관련 컴포넌트',
  leadUserId: 'b2c3d4e5-f6a7-4890-bcde-f01234567891',
}

const componentFixtureNullFields = {
  ...componentFixture,
  id: 'c3d4e5f6-a7b8-4901-9def-012345678902',
  description: null,
  leadUserId: null,
}

describe('fetchComponents', () => {
  it('{data:[...]} 래퍼를 언래핑해 Component[] 를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/components', () =>
        HttpResponse.json({ data: [componentFixture, componentFixtureNullFields] }),
      ),
    )
    const result = await fetchComponents('ATLAS')
    expect(result).toHaveLength(2)
    expect(result[0]).toMatchObject({ id: componentFixture.id, name: '인증 모듈' })
    expect(result[1]).toMatchObject({ description: null, leadUserId: null })
  })
})

describe('createComponent', () => {
  it('201 응답을 파싱해 Component 를 반환한다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectIdOrKey/components', async () =>
        HttpResponse.json({ data: componentFixture }, { status: 201 }),
      ),
    )
    const result = await createComponent('ATLAS', { name: '인증 모듈' })
    expect(result.id).toBe(componentFixture.id)
    expect(result.name).toBe('인증 모듈')
  })
})

describe('deleteComponent', () => {
  it('204 무바디 응답을 정상 처리하고 void 를 반환한다', async () => {
    server.use(
      http.delete('/api/v1/projects/:projectIdOrKey/components/:id', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(deleteComponent('ATLAS', componentFixture.id)).resolves.toBeUndefined()
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.delete('/api/v1/projects/:projectIdOrKey/components/:id', () =>
        HttpResponse.json({ errorCode: 'COMPONENT_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(deleteComponent('ATLAS', componentFixture.id)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('extractComponentErrorCode', () => {
  it('ApiError body 의 errorCode 를 string 으로 반환한다', () => {
    const err = new ApiError(409, { errorCode: 'COMPONENT_NAME_DUPLICATE' })
    expect(extractComponentErrorCode(err)).toBe('COMPONENT_NAME_DUPLICATE')
  })

  it('ApiError 이지만 errorCode 가 없으면 null 을 반환한다', () => {
    const err = new ApiError(500, { message: 'Internal Server Error' })
    expect(extractComponentErrorCode(err)).toBeNull()
  })

  it('ApiError 가 아니면 null 을 반환한다', () => {
    expect(extractComponentErrorCode(new Error('network error'))).toBeNull()
    expect(extractComponentErrorCode('string error')).toBeNull()
    expect(extractComponentErrorCode(null)).toBeNull()
  })
})
