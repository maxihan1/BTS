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
  changeVersionStatus,
  getReleaseNotes,
  extractVersionErrorCode,
} from './versions'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — VersionResponse 8 필드 (backend DTO 1:1, createdAt/updatedAt 없음)
// Zod 4.x uuid() 검증 통과를 위해 RFC4122 v4 형식 UUID 사용
// 날짜는 @JsonFormat yyyy-MM-dd 문자열
// status: VersionStatus enum, releasedAt: @JsonInclude(NON_NULL) ISO-8601 문자열
// ─────────────────────────────────────────────────────────────────────────────
const versionFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  name: 'v1.0.0',
  description: '첫 번째 정식 릴리스',
  startDate: '2026-01-01',
  releaseDate: '2026-06-30',
  status: 'UNRELEASED' as const,
  // releasedAt 생략 — @JsonInclude(NON_NULL) → null이면 필드 자체 없음
}

const versionFixtureNullFields = {
  ...versionFixture,
  id: 'c3d4e5f6-a7b8-4901-9def-012345678902',
  description: null,
  startDate: null,
  releaseDate: null,
  status: 'UNRELEASED' as const,
}

const versionFixtureReleased = {
  ...versionFixture,
  id: 'd4e5f6a7-b8c9-4012-adef-123456789003',
  status: 'RELEASED' as const,
  releasedAt: '2026-06-10T12:00:00Z',
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

// ─────────────────────────────────────────────────────────────────────────────
// changeVersionStatus — FR-VR-02 Task 5 RED
// ─────────────────────────────────────────────────────────────────────────────

describe('changeVersionStatus', () => {
  it('PATCH /status 호출 후 200 응답을 파싱해 Version을 반환한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/versions/:id/status', async () =>
        HttpResponse.json({ data: versionFixtureReleased }),
      ),
    )
    const result = await changeVersionStatus('ATLAS', versionFixture.id, 'RELEASED')
    expect(result.status).toBe('RELEASED')
    expect(result.releasedAt).toBe('2026-06-10T12:00:00Z')
  })

  it('UNRELEASED 상태로 전이 시 releasedAt이 없는 응답을 처리한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/versions/:id/status', async () =>
        HttpResponse.json({ data: versionFixture }),
      ),
    )
    const result = await changeVersionStatus('ATLAS', versionFixture.id, 'UNRELEASED')
    expect(result.status).toBe('UNRELEASED')
    expect(result.releasedAt).toBeUndefined()
  })

  it('불허 전이(409) 시 ApiError를 throw한다', async () => {
    server.use(
      http.patch('/api/v1/projects/:projectIdOrKey/versions/:id/status', async () =>
        HttpResponse.json(
          { errorCode: 'VERSION_TRANSITION_NOT_ALLOWED' },
          { status: 409 },
        ),
      ),
    )
    await expect(
      changeVersionStatus('ATLAS', versionFixture.id, 'RELEASED'),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// getReleaseNotes — FR-VR-04 Task 5 RED
// ─────────────────────────────────────────────────────────────────────────────

const releaseNotesFixture = {
  versionId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectKey: 'ATLAS',
  versionName: 'v1.0.0',
  versionStatus: 'RELEASED' as const,
  releaseDate: '2026-06-10',
  issueCount: 3,
  generatedAt: '2026-06-10T12:00:00Z',
  markdown: '## v1.0.0\n\n### Bug Fixes\n\n- ATL-1: 로그인 버그 수정',
}

describe('getReleaseNotes', () => {
  it('{data: ...} 래퍼를 언래핑해 ReleaseNotes를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/versions/:versionId/release-notes', () =>
        HttpResponse.json({ data: releaseNotesFixture }),
      ),
    )
    const result = await getReleaseNotes('ATLAS', releaseNotesFixture.versionId)
    expect(result.versionId).toBe(releaseNotesFixture.versionId)
    expect(result.projectKey).toBe('ATLAS')
    expect(result.versionName).toBe('v1.0.0')
    expect(result.versionStatus).toBe('RELEASED')
    expect(result.releaseDate).toBe('2026-06-10')
    expect(result.issueCount).toBe(3)
    expect(result.generatedAt).toBe('2026-06-10T12:00:00Z')
    expect(result.markdown).toContain('## v1.0.0')
  })

  it('releaseDate가 null이어도 파싱에 성공한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/versions/:versionId/release-notes', () =>
        HttpResponse.json({ data: { ...releaseNotesFixture, releaseDate: null } }),
      ),
    )
    const result = await getReleaseNotes('ATLAS', releaseNotesFixture.versionId)
    expect(result.releaseDate).toBeNull()
  })

  it('404 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectIdOrKey/versions/:versionId/release-notes', () =>
        HttpResponse.json({ errorCode: 'VERSION_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(getReleaseNotes('ATLAS', '00000000-0000-4000-8000-000000000001')).rejects.toBeInstanceOf(ApiError)
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
