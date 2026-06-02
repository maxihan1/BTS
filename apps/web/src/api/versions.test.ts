// 버전 API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  fetchVersions,
  fetchVersion,
  createVersion,
  updateVersion,
  changeVersionDates,
  deleteVersion,
  extractVersionErrorCode,
} from './versions'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — VersionResponse 6 필드 (backend DTO 1:1, createdAt/updatedAt 없음)
// Zod 4.x uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// 날짜는 @JsonFormat yyyy-MM-dd 문자열
// ─────────────────────────────────────────────────────────────────────────────
const versionFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  name: 'v1.0.0',
  description: '첫 번째 정식 릴리스',
  startDate: '2026-01-01',
  releaseDate: '2026-06-30',
}

const versionFixtureNullFields = {
  ...versionFixture,
  id: 'c3d4e5f6-a7b8-4901-9def-012345678902',
  description: null,
  startDate: null,
  releaseDate: null,
}

describe('fetchVersions', () => {
  it('{data:[...]} 래퍼를 언래핑해 Version[] 를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/versions', () =>
        HttpResponse.json({ data: [versionFixture, versionFixtureNullFields] }),
      ),
    )
    const result = await fetchVersions('ATLAS')
    expect(result).toHaveLength(2)
    expect(result[0]).toMatchObject({ id: versionFixture.id, name: 'v1.0.0' })
    expect(result[1]).toMatchObject({ description: null, startDate: null, releaseDate: null })
  })
})

describe('fetchVersion', () => {
  it('{data: ...} 래퍼를 언래핑해 Version 단건을 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/versions/:id', () =>
        HttpResponse.json({ data: versionFixture }),
      ),
    )
    const result = await fetchVersion('ATLAS', versionFixture.id)
    expect(result.id).toBe(versionFixture.id)
    expect(result.startDate).toBe('2026-01-01')
  })
})

describe('createVersion', () => {
  it('201 응답을 파싱해 Version 을 반환한다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectIdOrKey/versions', async () =>
        HttpResponse.json({ data: versionFixture }, { status: 201 }),
      ),
    )
    const result = await createVersion('ATLAS', { name: 'v1.0.0' })
    expect(result.id).toBe(versionFixture.id)
    expect(result.name).toBe('v1.0.0')
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectIdOrKey/versions', async () =>
        HttpResponse.json({ errorCode: 'VERSION_NAME_DUPLICATE' }, { status: 409 }),
      ),
    )
    await expect(createVersion('ATLAS', { name: 'v1.0.0' })).rejects.toBeInstanceOf(ApiError)
  })
})

describe('updateVersion', () => {
  it('200 응답을 파싱해 수정된 Version 을 반환한다', async () => {
    const updated = { ...versionFixture, name: 'v1.0.1' }
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/versions/:id', async () =>
        HttpResponse.json({ data: updated }),
      ),
    )
    const result = await updateVersion('ATLAS', versionFixture.id, { name: 'v1.0.1' })
    expect(result.name).toBe('v1.0.1')
  })
})

describe('changeVersionDates', () => {
  it('날짜 지정 후 변경된 Version 을 반환한다', async () => {
    const updated = { ...versionFixture, startDate: '2026-03-01', releaseDate: '2026-09-30' }
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/versions/:id/dates', async () =>
        HttpResponse.json({ data: updated }),
      ),
    )
    const result = await changeVersionDates('ATLAS', versionFixture.id, {
      startDate: '2026-03-01',
      releaseDate: '2026-09-30',
    })
    expect(result.startDate).toBe('2026-03-01')
    expect(result.releaseDate).toBe('2026-09-30')
  })

  it('날짜 해제(null) 후 null 필드를 반환한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/versions/:id/dates', async () =>
        HttpResponse.json({ data: versionFixtureNullFields }),
      ),
    )
    const result = await changeVersionDates('ATLAS', versionFixture.id, {
      startDate: null,
      releaseDate: null,
    })
    expect(result.startDate).toBeNull()
    expect(result.releaseDate).toBeNull()
  })
})

describe('deleteVersion', () => {
  it('204 무바디 응답을 정상 처리하고 void 를 반환한다', async () => {
    server.use(
      http.delete('/api/v1/projects/:projectIdOrKey/versions/:id', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(deleteVersion('ATLAS', versionFixture.id)).resolves.toBeUndefined()
  })

  it('비-2xx 응답 시 ApiError 를 throw 한다', async () => {
    server.use(
      http.delete('/api/v1/projects/:projectIdOrKey/versions/:id', () =>
        HttpResponse.json({ errorCode: 'VERSION_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(deleteVersion('ATLAS', versionFixture.id)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('extractVersionErrorCode', () => {
  it('ApiError body 의 errorCode 를 string 으로 반환한다', () => {
    const err = new ApiError(409, { errorCode: 'VERSION_NAME_DUPLICATE' })
    expect(extractVersionErrorCode(err)).toBe('VERSION_NAME_DUPLICATE')
  })

  it('ApiError 이지만 errorCode 가 없으면 null 을 반환한다', () => {
    const err = new ApiError(500, { message: 'Internal Server Error' })
    expect(extractVersionErrorCode(err)).toBeNull()
  })

  it('ApiError 가 아니면 null 을 반환한다', () => {
    expect(extractVersionErrorCode(new Error('network error'))).toBeNull()
    expect(extractVersionErrorCode('string error')).toBeNull()
    expect(extractVersionErrorCode(null)).toBeNull()
  })
})
