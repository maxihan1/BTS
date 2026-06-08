// 커스텀 필드 API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  fetchCustomFields,
  fetchCustomField,
  createCustomField,
  updateCustomField,
  deleteCustomField,
  extractCustomFieldErrorCode,
} from './custom-fields'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — CustomFieldResponse (backend DTO 1:1)
// Zod 4.x uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// ─────────────────────────────────────────────────────────────────────────────

const customFieldFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  key: 'custom_priority',
  name: '우선순위',
  description: '커스텀 우선순위 필드',
  fieldType: 'SINGLE_SELECT' as const,
  required: false,
  displayOrder: 1,
  options: [
    { value: 'HIGH', label: '높음', displayOrder: 1 },
    { value: 'LOW', label: '낮음', displayOrder: 2 },
  ],
}

const customFieldFixtureNoDesc = {
  id: 'c3d4e5f6-a7b8-4901-9def-012345678902',
  projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  key: 'notes',
  name: '메모',
  description: null,
  fieldType: 'LONG_TEXT' as const,
  required: true,
  displayOrder: 2,
  options: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// fetchCustomFields
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchCustomFields', () => {
  it('{data:[...]} 래퍼를 언래핑해 CustomField[] 를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/custom-fields', () =>
        HttpResponse.json({ data: [customFieldFixture, customFieldFixtureNoDesc] }),
      ),
    )
    const result = await fetchCustomFields('ATLAS')
    expect(result).toHaveLength(2)
    expect(result[0]).toMatchObject({ id: customFieldFixture.id, key: 'custom_priority' })
    expect(result[1]).toMatchObject({ description: null, options: [] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchCustomField
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchCustomField', () => {
  it('{data: ...} 래퍼를 언래핑해 CustomField 단건을 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/custom-fields/:fieldId', () =>
        HttpResponse.json({ data: customFieldFixture }),
      ),
    )
    const result = await fetchCustomField('ATLAS', customFieldFixture.id)
    expect(result.id).toBe(customFieldFixture.id)
    expect(result.fieldType).toBe('SINGLE_SELECT')
    expect(result.options).toHaveLength(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createCustomField
// ─────────────────────────────────────────────────────────────────────────────

describe('createCustomField', () => {
  it('201 응답을 파싱해 CustomField 를 반환한다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectIdOrKey/custom-fields', async () =>
        HttpResponse.json({ data: customFieldFixture }, { status: 201 }),
      ),
    )
    const result = await createCustomField('ATLAS', {
      key: 'custom_priority',
      name: '우선순위',
      fieldType: 'SINGLE_SELECT',
    })
    expect(result.id).toBe(customFieldFixture.id)
    expect(result.key).toBe('custom_priority')
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectIdOrKey/custom-fields', async () =>
        HttpResponse.json({ errorCode: 'CUSTOM_FIELD_KEY_DUPLICATE' }, { status: 409 }),
      ),
    )
    await expect(
      createCustomField('ATLAS', { key: 'dup_key', name: '중복', fieldType: 'SHORT_TEXT' }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// updateCustomField
// ─────────────────────────────────────────────────────────────────────────────

describe('updateCustomField', () => {
  it('200 응답을 파싱해 수정된 CustomField 를 반환한다', async () => {
    const updated = { ...customFieldFixture, name: '우선순위(수정)' }
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/custom-fields/:fieldId', async () =>
        HttpResponse.json({ data: updated }),
      ),
    )
    const result = await updateCustomField('ATLAS', customFieldFixture.id, {
      name: '우선순위(수정)',
    })
    expect(result.name).toBe('우선순위(수정)')
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/custom-fields/:fieldId', async () =>
        HttpResponse.json({ errorCode: 'CUSTOM_FIELD_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(
      updateCustomField('ATLAS', customFieldFixture.id, { name: '없는필드' }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deleteCustomField
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteCustomField', () => {
  it('204 무바디 응답을 정상 처리하고 void 를 반환한다', async () => {
    server.use(
      http.delete('/api/v1/projects/:projectIdOrKey/custom-fields/:fieldId', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(deleteCustomField('ATLAS', customFieldFixture.id)).resolves.toBeUndefined()
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.delete('/api/v1/projects/:projectIdOrKey/custom-fields/:fieldId', () =>
        HttpResponse.json({ errorCode: 'CUSTOM_FIELD_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(deleteCustomField('ATLAS', customFieldFixture.id)).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// extractCustomFieldErrorCode
// ─────────────────────────────────────────────────────────────────────────────

describe('extractCustomFieldErrorCode', () => {
  it('ApiError body 의 errorCode 를 string 으로 반환한다', () => {
    const err = new ApiError(409, { errorCode: 'CUSTOM_FIELD_KEY_DUPLICATE' })
    expect(extractCustomFieldErrorCode(err)).toBe('CUSTOM_FIELD_KEY_DUPLICATE')
  })

  it('ApiError 이지만 errorCode 가 없으면 null 을 반환한다', () => {
    const err = new ApiError(500, { message: 'Internal Server Error' })
    expect(extractCustomFieldErrorCode(err)).toBeNull()
  })

  it('ApiError 가 아니면 null 을 반환한다', () => {
    expect(extractCustomFieldErrorCode(new Error('network error'))).toBeNull()
    expect(extractCustomFieldErrorCode('string error')).toBeNull()
    expect(extractCustomFieldErrorCode(null)).toBeNull()
  })
})
