// 전역 권한 부여 API 클라이언트 단위 테스트 — MSW 가로채기 + Zod 파싱 + CSRF 헤더 + error 코드 추출 검증 (FR-PM-10)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { ZodError } from 'zod'
import { server } from '@/test/server'
import { grantResponseSchema } from '../global-permissions.types'
import type { GrantResponse } from '../global-permissions.types'
import {
  fetchGlobalPermissions,
  createGlobalPermission,
  deleteGlobalPermission,
  extractGlobalPermissionErrorCode,
  ApiError,
  GLOBAL_PERMISSION_CODES,
  GLOBAL_PERMISSION_LABELS,
} from '../global-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — backend DTO 필드와 1:1
// ─────────────────────────────────────────────────────────────────────────────

const GRANT_ID = '11111111-1111-4111-a111-111111111111'
const GROUP_ID = '22222222-2222-4222-a222-222222222222'
const USER_ID = '33333333-3333-4333-a333-333333333333'
const ADMIN_ID = '44444444-4444-4444-a444-444444444444'

const grantFixture: GrantResponse = {
  id: GRANT_ID,
  permission: 'CREATE_PROJECT',
  granteeType: 'GROUP',
  granteeId: GROUP_ID,
  grantedBy: ADMIN_ID,
  createdAt: '2024-01-01T00:00:00Z',
}

const GLOBAL_PERMISSIONS_ENDPOINT = '/api/v1/admin/global-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// T-GP-1. grantResponseSchema — Zod 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('grantResponseSchema', () => {
  it('T-GP-1a: 정상 fixture(GROUP)를 파싱한다', () => {
    const result = grantResponseSchema.parse(grantFixture)
    expect(result.id).toBe(GRANT_ID)
    expect(result.permission).toBe('CREATE_PROJECT')
    expect(result.granteeType).toBe('GROUP')
    expect(result.granteeId).toBe(GROUP_ID)
    expect(result.grantedBy).toBe(ADMIN_ID)
    expect(result.createdAt).toBe('2024-01-01T00:00:00Z')
  })

  it('T-GP-1b: USER granteeType 파싱 성공', () => {
    const result = grantResponseSchema.parse({
      ...grantFixture,
      granteeType: 'USER',
      granteeId: USER_ID,
    })
    expect(result.granteeType).toBe('USER')
    expect(result.granteeId).toBe(USER_ID)
  })

  it('T-GP-1c: 알 수 없는 granteeType → ZodError', () => {
    expect(() =>
      grantResponseSchema.parse({ ...grantFixture, granteeType: 'ROLE' }),
    ).toThrow(ZodError)
  })

  it('T-GP-1d: id가 UUID 형식이 아니면 → ZodError', () => {
    expect(() =>
      grantResponseSchema.parse({ ...grantFixture, id: 'not-a-uuid' }),
    ).toThrow(ZodError)
  })

  it('T-GP-1e: 필수 필드 누락 → ZodError', () => {
    const withoutGrantedBy = { ...grantFixture, grantedBy: undefined }
    expect(() => grantResponseSchema.parse(withoutGrantedBy)).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-GP-2. fetchGlobalPermissions — GET /api/v1/admin/global-permissions (bare 배열)
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchGlobalPermissions', () => {
  it('T-GP-2a: 올바른 경로로 GET 요청을 보낸다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get(GLOBAL_PERMISSIONS_ENDPOINT, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([grantFixture])
      }),
    )

    await fetchGlobalPermissions()

    expect(capturedUrl).not.toBeNull()
    expect(new URL(capturedUrl as unknown as string).pathname).toBe(GLOBAL_PERMISSIONS_ENDPOINT)
  })

  it('T-GP-2b: bare 배열 응답을 GrantResponse[]로 파싱해 반환한다', async () => {
    server.use(
      http.get(GLOBAL_PERMISSIONS_ENDPOINT, () => HttpResponse.json([grantFixture])),
    )

    const result = await fetchGlobalPermissions()

    expect(result).toHaveLength(1)
    expect(result[0]?.id).toBe(GRANT_ID)
    expect(result[0]?.permission).toBe('CREATE_PROJECT')
  })

  it('T-GP-2c: 빈 배열 응답을 파싱해 반환한다', async () => {
    server.use(http.get(GLOBAL_PERMISSIONS_ENDPOINT, () => HttpResponse.json([])))

    const result = await fetchGlobalPermissions()
    expect(result).toHaveLength(0)
  })

  it('T-GP-2d: 응답 필드 누락 시 ZodError를 throw한다', async () => {
    server.use(
      http.get(GLOBAL_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json([{ id: GRANT_ID }]),
      ),
    )

    await expect(fetchGlobalPermissions()).rejects.toBeInstanceOf(ZodError)
  })

  it('T-GP-2e: 서버 403 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get(GLOBAL_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json({ error: 'forbidden' }, { status: 403 }),
      ),
    )

    const err = await fetchGlobalPermissions().catch((e: unknown) => e)
    expect((err as { status?: number }).status).toBe(403)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-GP-3. createGlobalPermission — POST /api/v1/admin/global-permissions
// ─────────────────────────────────────────────────────────────────────────────

describe('createGlobalPermission', () => {
  const createInput = {
    permission: 'CREATE_PROJECT',
    granteeType: 'GROUP' as const,
    granteeId: GROUP_ID,
  }

  it('T-GP-3a: 올바른 경로/메서드로 POST 요청을 보낸다', async () => {
    let capturedMethod: string | null = null
    let capturedUrl: string | null = null
    server.use(
      http.post(GLOBAL_PERMISSIONS_ENDPOINT, ({ request }) => {
        capturedMethod = request.method
        capturedUrl = request.url
        return HttpResponse.json(grantFixture, { status: 201 })
      }),
    )

    await createGlobalPermission(createInput)

    expect(capturedMethod).toBe('POST')
    expect(new URL(capturedUrl as unknown as string).pathname).toBe(GLOBAL_PERMISSIONS_ENDPOINT)
  })

  it('T-GP-3b: POST 요청에 X-XSRF-TOKEN 헤더를 포함한다', async () => {
    let xsrfHeader: string | null = null
    server.use(
      http.post(GLOBAL_PERMISSIONS_ENDPOINT, ({ request }) => {
        xsrfHeader = request.headers.get('x-xsrf-token')
        return HttpResponse.json(grantFixture, { status: 201 })
      }),
    )

    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await createGlobalPermission(createInput)

    expect(xsrfHeader).toBe('test-csrf-token')
  })

  it('T-GP-3c: 201 bare 응답을 GrantResponse로 파싱해 반환한다', async () => {
    server.use(
      http.post(GLOBAL_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json(grantFixture, { status: 201 }),
      ),
    )

    const result = await createGlobalPermission(createInput)

    expect(result.id).toBe(GRANT_ID)
    expect(result.granteeType).toBe('GROUP')
  })

  it('T-GP-3d: 서버 409 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.post(GLOBAL_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json({ error: 'grant_already_exists' }, { status: 409 }),
      ),
    )

    const err = await createGlobalPermission(createInput).catch((e: unknown) => e)
    expect((err as { status?: number }).status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-GP-4. deleteGlobalPermission — DELETE /api/v1/admin/global-permissions/{grantId}
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteGlobalPermission', () => {
  const DELETE_ENDPOINT = `${GLOBAL_PERMISSIONS_ENDPOINT}/${GRANT_ID}`

  it('T-GP-4a: 올바른 경로로 DELETE 요청을 보낸다', async () => {
    let capturedMethod: string | null = null
    let capturedUrl: string | null = null
    server.use(
      http.delete(DELETE_ENDPOINT, ({ request }) => {
        capturedMethod = request.method
        capturedUrl = request.url
        return new HttpResponse(null, { status: 204 })
      }),
    )

    await deleteGlobalPermission(GRANT_ID)

    expect(capturedMethod).toBe('DELETE')
    expect(new URL(capturedUrl as unknown as string).pathname).toBe(DELETE_ENDPOINT)
  })

  it('T-GP-4b: DELETE 요청에 X-XSRF-TOKEN 헤더를 포함한다', async () => {
    let xsrfHeader: string | null = null
    server.use(
      http.delete(DELETE_ENDPOINT, ({ request }) => {
        xsrfHeader = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )

    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await deleteGlobalPermission(GRANT_ID)

    expect(xsrfHeader).toBe('test-csrf-token')
  })

  it('T-GP-4c: 204 응답 시 void를 반환한다', async () => {
    server.use(http.delete(DELETE_ENDPOINT, () => new HttpResponse(null, { status: 204 })))

    const result = await deleteGlobalPermission(GRANT_ID)
    expect(result).toBeUndefined()
  })

  it('T-GP-4d: 서버 404 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.delete(DELETE_ENDPOINT, () =>
        HttpResponse.json({ error: 'grant_not_found' }, { status: 404 }),
      ),
    )

    const err = await deleteGlobalPermission(GRANT_ID).catch((e: unknown) => e)
    expect((err as { status?: number }).status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-GP-5. extractGlobalPermissionErrorCode — `error` 키 추출 (snake_case, errorCode 아님)
// ─────────────────────────────────────────────────────────────────────────────

describe('extractGlobalPermissionErrorCode', () => {
  it('T-GP-5a: ApiError body.error가 있으면 string을 반환한다', () => {
    const err = new ApiError(409, { error: 'grant_already_exists' })
    expect(extractGlobalPermissionErrorCode(err)).toBe('grant_already_exists')
  })

  it('T-GP-5b: ApiError body.error가 없으면 null을 반환한다', () => {
    const err = new ApiError(500, {})
    expect(extractGlobalPermissionErrorCode(err)).toBeNull()
  })

  it('T-GP-5c: ApiError가 아니면 null을 반환한다', () => {
    expect(extractGlobalPermissionErrorCode(new Error('generic'))).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-GP-6. GLOBAL_PERMISSION_CODES / GLOBAL_PERMISSION_LABELS — 권한 코드 화이트리스트
// ─────────────────────────────────────────────────────────────────────────────

describe('GLOBAL_PERMISSION_CODES / GLOBAL_PERMISSION_LABELS', () => {
  it('T-GP-6a: GLOBAL_PERMISSION_CODES는 CREATE_PROJECT 단일이다', () => {
    expect(GLOBAL_PERMISSION_CODES).toEqual(['CREATE_PROJECT'])
  })

  it('T-GP-6b: GLOBAL_PERMISSION_LABELS.CREATE_PROJECT는 "프로젝트 생성"이다', () => {
    expect(GLOBAL_PERMISSION_LABELS['CREATE_PROJECT']).toBe('프로젝트 생성')
  })
})
