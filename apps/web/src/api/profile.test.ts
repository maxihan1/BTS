// identity-access BC 사용자 프로필 API client 단위 테스트 — MSW + Zod 파싱 + 3-state 매핑 검증
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  profileResponseSchema,
  avatarUploadResponseSchema,
  getProfile,
  patchProfile,
  uploadAvatar,
  deleteAvatar,
  fetchAvatarBlob,
  buildPatchBody,
} from './profile'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — 뮤테이션 X-XSRF-TOKEN 검증용
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — ProfileResponse (백엔드 ProfileResponse DTO 1:1, RFC4122 v4 UUID)
// ─────────────────────────────────────────────────────────────────────────────
const PROFILE_FIXTURE_FULL = {
  userId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  username: 'alice',
  email: 'alice@example.com',
  displayName: '김앨리스',
  avatarUrl: '/api/v1/users/a1b2c3d4-e5f6-4890-abcd-ef1234567890/avatar',
  timezone: 'Asia/Seoul',
  department: '플랫폼팀',
}

const PROFILE_FIXTURE_MINIMAL = {
  userId: 'b2c3d4e5-f6a7-4891-bcde-ef2345678901',
  username: 'bob',
  email: null,
  displayName: 'bob',
  avatarUrl: null,
  timezone: 'UTC',
  department: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-PR-S. profileResponseSchema — Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('profileResponseSchema', () => {
  it('T-PR-S-1: 모든 필드가 있는 ProfileResponse를 파싱한다', () => {
    const result = profileResponseSchema.parse(PROFILE_FIXTURE_FULL)
    expect(result.userId).toBe(PROFILE_FIXTURE_FULL.userId)
    expect(result.username).toBe('alice')
    expect(result.email).toBe('alice@example.com')
    expect(result.displayName).toBe('김앨리스')
    expect(result.avatarUrl).toBe(PROFILE_FIXTURE_FULL.avatarUrl)
    expect(result.timezone).toBe('Asia/Seoul')
    expect(result.department).toBe('플랫폼팀')
  })

  it('T-PR-S-2: email/avatarUrl/department이 null이어도 파싱 성공한다', () => {
    const result = profileResponseSchema.parse(PROFILE_FIXTURE_MINIMAL)
    expect(result.email).toBeNull()
    expect(result.avatarUrl).toBeNull()
    expect(result.department).toBeNull()
  })

  it('T-PR-S-3: userId가 UUID 형식이 아니면 ZodError를 throw한다', () => {
    expect(() => profileResponseSchema.parse({ ...PROFILE_FIXTURE_FULL, userId: 'not-a-uuid' })).toThrow()
  })

  it('T-PR-S-4: username 필드 누락 시 ZodError를 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { username: _username, ...without } = PROFILE_FIXTURE_FULL
    expect(() => profileResponseSchema.parse(without)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PR-AS. avatarUploadResponseSchema — Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('avatarUploadResponseSchema', () => {
  it('T-PR-AS-1: 유효한 avatarUrl을 파싱한다', () => {
    const result = avatarUploadResponseSchema.parse({ avatarUrl: PROFILE_FIXTURE_FULL.avatarUrl })
    expect(result.avatarUrl).toBe(PROFILE_FIXTURE_FULL.avatarUrl)
  })

  it('T-PR-AS-2: avatarUrl 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => avatarUploadResponseSchema.parse({})).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PR-1. getProfile — GET /api/v1/users/me/profile
// ─────────────────────────────────────────────────────────────────────────────
describe('getProfile', () => {
  it('T-PR-1-1: 200 응답을 ProfileResponse로 파싱해 반환한다', async () => {
    server.use(
      http.get('/api/v1/users/me/profile', () => HttpResponse.json(PROFILE_FIXTURE_FULL)),
    )
    const result = await getProfile()
    expect(result.username).toBe('alice')
    expect(result.department).toBe('플랫폼팀')
  })

  it('T-PR-1-2: 401 응답 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/users/me/profile', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(getProfile()).rejects.toBeInstanceOf(ApiError)
    await expect(getProfile()).rejects.toMatchObject({ status: 401 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PR-2. patchProfile — PATCH /api/v1/users/me/profile (3-state body)
// ─────────────────────────────────────────────────────────────────────────────
describe('patchProfile', () => {
  it('T-PR-2-1: PATCH 후 갱신된 ProfileResponse를 반환한다', async () => {
    server.use(
      http.patch('/api/v1/users/me/profile', () =>
        HttpResponse.json({ ...PROFILE_FIXTURE_FULL, displayName: '김맥시' }),
      ),
    )
    const result = await patchProfile({ displayName: '김맥시' })
    expect(result.displayName).toBe('김맥시')
  })

  it('T-PR-2-2: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch('/api/v1/users/me/profile', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(PROFILE_FIXTURE_FULL)
      }),
    )
    await patchProfile({ displayName: '김맥시' })
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-PR-2-3: department를 명시 null로 보내면 요청 바디에 department:null이 그대로 실린다', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/profile', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ ...PROFILE_FIXTURE_FULL, department: null })
      }),
    )
    await patchProfile({ department: null })
    expect(capturedBody).toEqual({ department: null })
  })

  it('T-PR-2-4: 미변경 필드는 요청 바디에 키 자체가 없다(부재)', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/profile', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(PROFILE_FIXTURE_FULL)
      }),
    )
    await patchProfile({ timezone: 'Asia/Seoul' })
    expect(capturedBody).toEqual({ timezone: 'Asia/Seoul' })
    expect(capturedBody).not.toHaveProperty('displayName')
    expect(capturedBody).not.toHaveProperty('department')
  })

  it('T-PR-2-5: 400 PROFILE_VALIDATION_FAILED → ApiError(400) throw', async () => {
    server.use(
      http.patch('/api/v1/users/me/profile', () =>
        HttpResponse.json({ code: 'PROFILE_VALIDATION_FAILED', message: '표시 이름은 비워둘 수 없습니다.' }, { status: 400 }),
      ),
    )
    let thrown: unknown
    try {
      await patchProfile({ displayName: '' })
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
    expect((thrown.body as { code?: string } | null)?.code).toBe('PROFILE_VALIDATION_FAILED')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PR-3. uploadAvatar — POST /api/v1/users/me/profile/avatar (multipart part=file)
// ─────────────────────────────────────────────────────────────────────────────
describe('uploadAvatar', () => {
  it('T-PR-3-1: multipart part명 "file"로 전송 후 avatarUploadResponseSchema로 파싱한다', async () => {
    let capturedFile: File | null = null
    server.use(
      http.post('/api/v1/users/me/profile/avatar', async ({ request }) => {
        const fd = await request.formData()
        capturedFile = fd.get('file') as File | null
        return HttpResponse.json({ avatarUrl: PROFILE_FIXTURE_FULL.avatarUrl })
      }),
    )
    const file = new File(['avatar-bytes'], 'avatar.png', { type: 'image/png' })
    const result = await uploadAvatar(file)
    expect(capturedFile).not.toBeNull()
    expect(result.avatarUrl).toBe(PROFILE_FIXTURE_FULL.avatarUrl)
  })

  it('T-PR-3-2: Content-Type을 수동으로 application/json 설정하지 않는다(FormData 분기)', async () => {
    let capturedContentType: string | null = null
    server.use(
      http.post('/api/v1/users/me/profile/avatar', ({ request }) => {
        capturedContentType = request.headers.get('content-type')
        return HttpResponse.json({ avatarUrl: PROFILE_FIXTURE_FULL.avatarUrl })
      }),
    )
    const file = new File(['avatar-bytes'], 'avatar.png', { type: 'image/png' })
    await uploadAvatar(file)
    expect(capturedContentType).not.toContain('application/json')
  })

  it('T-PR-3-3: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/users/me/profile/avatar', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json({ avatarUrl: PROFILE_FIXTURE_FULL.avatarUrl })
      }),
    )
    const file = new File(['avatar-bytes'], 'avatar.png', { type: 'image/png' })
    await uploadAvatar(file)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-PR-3-4: 400 AVATAR_VALIDATION_FAILED → ApiError(400) throw', async () => {
    server.use(
      http.post('/api/v1/users/me/profile/avatar', () =>
        HttpResponse.json({ code: 'AVATAR_VALIDATION_FAILED', message: '아바타 파일이 너무 큽니다.' }, { status: 400 }),
      ),
    )
    const file = new File(['x'], 'huge.png', { type: 'image/png' })
    await expect(uploadAvatar(file)).rejects.toMatchObject({ status: 400 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PR-4. deleteAvatar — DELETE /api/v1/users/me/profile/avatar
// ─────────────────────────────────────────────────────────────────────────────
describe('deleteAvatar', () => {
  it('T-PR-4-1: 204 응답 → undefined 반환', async () => {
    let called = false
    server.use(
      http.delete('/api/v1/users/me/profile/avatar', () => {
        called = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const result = await deleteAvatar()
    expect(called).toBe(true)
    expect(result).toBeUndefined()
  })

  it('T-PR-4-2: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/users/me/profile/avatar', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await deleteAvatar()
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-PR-4-3: 404 AVATAR_NOT_FOUND → ApiError(404) throw (멱등 삭제 실패는 호출측 책임 밖)', async () => {
    server.use(
      http.delete('/api/v1/users/me/profile/avatar', () =>
        HttpResponse.json({ code: 'AVATAR_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(deleteAvatar()).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PR-5. fetchAvatarBlob — GET /api/v1/users/{userId}/avatar → blob
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchAvatarBlob', () => {
  const avatarPath = '/api/v1/users/a1b2c3d4-e5f6-4890-abcd-ef1234567890/avatar'

  it('T-PR-5-1: 200 이미지 바이너리 → Blob 반환', async () => {
    server.use(
      http.get(avatarPath, () =>
        HttpResponse.arrayBuffer(new ArrayBuffer(8), { headers: { 'Content-Type': 'image/png' } }),
      ),
    )
    const blob = await fetchAvatarBlob(avatarPath)
    expect(blob).toBeDefined()
    expect(blob.size).toBeGreaterThan(0)
    expect(typeof blob.arrayBuffer).toBe('function')
  })

  it('T-PR-5-2: 404 AVATAR_NOT_FOUND → ApiError(404) throw (EC8 — 호출측이 이니셜 폴백 처리)', async () => {
    server.use(
      http.get(avatarPath, () => HttpResponse.json({ code: 'AVATAR_NOT_FOUND' }, { status: 404 })),
    )
    await expect(fetchAvatarBlob(avatarPath)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchAvatarBlob(avatarPath)).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-PR-6. buildPatchBody — 3-state 매핑 헬퍼 단위 테스트 (MSW 불필요)
// ─────────────────────────────────────────────────────────────────────────────
describe('buildPatchBody', () => {
  const original = {
    displayName: '김앨리스',
    timezone: 'Asia/Seoul',
    department: '플랫폼팀',
  }

  it('T-PR-6-1: 변경 없으면 빈 객체를 반환한다', () => {
    const result = buildPatchBody(original, { ...original })
    expect(result).toEqual({})
  })

  it('T-PR-6-2: displayName만 변경되면 displayName만 포함한다', () => {
    const result = buildPatchBody(original, { ...original, displayName: '김맥시' })
    expect(result).toEqual({ displayName: '김맥시' })
  })

  it('T-PR-6-3: timezone만 변경되면 timezone만 포함한다', () => {
    const result = buildPatchBody(original, { ...original, timezone: 'UTC' })
    expect(result).toEqual({ timezone: 'UTC' })
  })

  it('T-PR-6-4: department를 빈 문자열로 비우면 명시 null을 포함한다', () => {
    const result = buildPatchBody(original, { ...original, department: '' })
    expect(result).toEqual({ department: null })
  })

  it('T-PR-6-5: department를 공백만으로 채우면 trim 후 명시 null을 포함한다', () => {
    const result = buildPatchBody(original, { ...original, department: '   ' })
    expect(result).toEqual({ department: null })
  })

  it('T-PR-6-6: 이미 department가 null인데 폼도 빈 문자열이면 department 키를 생략한다', () => {
    const withoutDept = { ...original, department: null }
    const result = buildPatchBody(withoutDept, { ...original, department: '' })
    expect(result).toEqual({})
  })

  it('T-PR-6-7: department가 앞뒤 공백만 다르고 trim 시 동일하면 키를 생략한다', () => {
    const result = buildPatchBody(original, { ...original, department: `  ${original.department}  ` })
    expect(result).toEqual({})
  })

  it('T-PR-6-8: department에 새 값을 입력하면 trim된 값을 포함한다', () => {
    const withoutDept = { ...original, department: null }
    const result = buildPatchBody(withoutDept, { ...original, department: '  신규팀  ' })
    expect(result).toEqual({ department: '신규팀' })
  })

  it('T-PR-6-9: 여러 필드가 동시에 변경되면 모두 포함한다', () => {
    const result = buildPatchBody(original, {
      displayName: '김맥시',
      timezone: 'UTC',
      department: '',
    })
    expect(result).toEqual({ displayName: '김맥시', timezone: 'UTC', department: null })
  })
})
