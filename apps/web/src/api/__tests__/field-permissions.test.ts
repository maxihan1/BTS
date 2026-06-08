// 필드 권한 규칙 + 그룹 API 클라이언트 단위 테스트 — MSW 가로채기 + Zod 파싱 + CSRF 헤더 검증 (FR-PM-07)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { ZodError } from 'zod'
import { server } from '@/test/server'
import {
  fieldPermissionResponseSchema,
  groupResponseSchema,
} from '../field-permissions.types'
import {
  fetchFieldPermissions,
  createFieldPermission,
  deleteFieldPermission,
} from '../field-permissions'
import { fetchGroups } from '../groups'
import type { FieldPermissionResponse, GroupResponse } from '../field-permissions.types'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — backend DTO 필드와 1:1
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const PERMISSION_ID = '11111111-1111-4111-a111-111111111111'
const GROUP_ID = '22222222-2222-4222-a222-222222222222'

const fieldPermissionFixture: FieldPermissionResponse = {
  id: PERMISSION_ID,
  fieldKind: 'CORE',
  fieldKey: 'summary',
  groupId: GROUP_ID,
  groupName: 'Developers',
  accessLevel: 'VIEW',
}

const groupFixture: GroupResponse = {
  id: GROUP_ID,
  name: 'Developers',
  description: 'Dev team',
  memberCount: 5,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-02T00:00:00Z',
}

const FIELD_PERMISSIONS_ENDPOINT = `/api/v1/projects/${PROJECT_KEY}/field-permissions`
const GROUPS_ENDPOINT = `/api/v1/groups`

// ─────────────────────────────────────────────────────────────────────────────
// T-FP-1. fieldPermissionResponseSchema — Zod 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('fieldPermissionResponseSchema', () => {
  it('T-FP-1a: 정상 fixture를 파싱한다', () => {
    const result = fieldPermissionResponseSchema.parse(fieldPermissionFixture)
    expect(result.id).toBe(PERMISSION_ID)
    expect(result.fieldKind).toBe('CORE')
    expect(result.fieldKey).toBe('summary')
    expect(result.groupId).toBe(GROUP_ID)
    expect(result.groupName).toBe('Developers')
    expect(result.accessLevel).toBe('VIEW')
  })

  it('T-FP-1b: CUSTOM fieldKind + EDIT accessLevel 파싱 성공', () => {
    const result = fieldPermissionResponseSchema.parse({
      ...fieldPermissionFixture,
      fieldKind: 'CUSTOM',
      accessLevel: 'EDIT',
    })
    expect(result.fieldKind).toBe('CUSTOM')
    expect(result.accessLevel).toBe('EDIT')
  })

  it('T-FP-1c: 알 수 없는 fieldKind → ZodError', () => {
    expect(() =>
      fieldPermissionResponseSchema.parse({ ...fieldPermissionFixture, fieldKind: 'UNKNOWN' }),
    ).toThrow(ZodError)
  })

  it('T-FP-1d: 알 수 없는 accessLevel → ZodError', () => {
    expect(() =>
      fieldPermissionResponseSchema.parse({ ...fieldPermissionFixture, accessLevel: 'ADMIN' }),
    ).toThrow(ZodError)
  })

  it('T-FP-1e: id가 UUID 형식이 아니면 → ZodError', () => {
    expect(() =>
      fieldPermissionResponseSchema.parse({ ...fieldPermissionFixture, id: 'not-a-uuid' }),
    ).toThrow(ZodError)
  })

  it('T-FP-1f: 필수 필드 누락 → ZodError', () => {
    const withoutGroupName = { ...fieldPermissionFixture, groupName: undefined }
    expect(() => fieldPermissionResponseSchema.parse(withoutGroupName)).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-FP-2. groupResponseSchema — Zod 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('groupResponseSchema', () => {
  it('T-FP-2a: 정상 fixture를 파싱한다', () => {
    const result = groupResponseSchema.parse(groupFixture)
    expect(result.id).toBe(GROUP_ID)
    expect(result.name).toBe('Developers')
    expect(result.memberCount).toBe(5)
  })

  it('T-FP-2b: description null 허용', () => {
    const result = groupResponseSchema.parse({ ...groupFixture, description: null })
    expect(result.description).toBeNull()
  })

  it('T-FP-2c: memberCount 누락 → ZodError', () => {
    const without = { ...groupFixture, memberCount: undefined }
    expect(() => groupResponseSchema.parse(without)).toThrow(ZodError)
  })

  it('T-FP-2d: id가 UUID 형식이 아니면 → ZodError', () => {
    expect(() =>
      groupResponseSchema.parse({ ...groupFixture, id: 'not-a-uuid' }),
    ).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-FP-3. fetchFieldPermissions — GET /api/v1/projects/{projectKey}/field-permissions
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchFieldPermissions', () => {
  it('T-FP-3a: 올바른 경로로 GET 요청을 보낸다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get(FIELD_PERMISSIONS_ENDPOINT, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: [fieldPermissionFixture] })
      }),
    )

    await fetchFieldPermissions(PROJECT_KEY)

    expect(capturedUrl).not.toBeNull()
    expect(new URL(capturedUrl as unknown as string).pathname).toBe(FIELD_PERMISSIONS_ENDPOINT)
  })

  it('T-FP-3b: 응답을 FieldPermissionResponse[] 로 파싱해 반환한다', async () => {
    server.use(
      http.get(FIELD_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json({ data: [fieldPermissionFixture] }),
      ),
    )

    const result = await fetchFieldPermissions(PROJECT_KEY)

    expect(result).toHaveLength(1)
    expect(result[0]?.id).toBe(PERMISSION_ID)
    expect(result[0]?.fieldKind).toBe('CORE')
  })

  it('T-FP-3c: 응답 필드 누락 시 ZodError를 throw한다', async () => {
    server.use(
      http.get(FIELD_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json({ data: [{ id: PERMISSION_ID }] }),
      ),
    )

    await expect(fetchFieldPermissions(PROJECT_KEY)).rejects.toBeInstanceOf(ZodError)
  })

  it('T-FP-3d: GET 요청에 X-XSRF-TOKEN 헤더를 포함하지 않는다', async () => {
    let xsrfHeader: string | null = null
    server.use(
      http.get(FIELD_PERMISSIONS_ENDPOINT, ({ request }) => {
        xsrfHeader = request.headers.get('x-xsrf-token')
        return HttpResponse.json({ data: [] })
      }),
    )

    await fetchFieldPermissions(PROJECT_KEY)

    expect(xsrfHeader).toBeNull()
  })

  it('T-FP-3e: 서버 403 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get(FIELD_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json({ error: 'forbidden' }, { status: 403 }),
      ),
    )

    const err = await fetchFieldPermissions(PROJECT_KEY).catch((e: unknown) => e)
    expect((err as { status?: number }).status).toBe(403)
  })

  it('T-FP-3f: 타입 컴파일 가드 — 반환값이 FieldPermissionResponse[] 에 할당 가능하다', async () => {
    server.use(
      http.get(FIELD_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json({ data: [fieldPermissionFixture] }),
      ),
    )

    const result: FieldPermissionResponse[] = await fetchFieldPermissions(PROJECT_KEY)
    expect(result[0]?.accessLevel).toBe('VIEW')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-FP-4. createFieldPermission — POST /api/v1/projects/{projectKey}/field-permissions
// ─────────────────────────────────────────────────────────────────────────────

describe('createFieldPermission', () => {
  const createInput = {
    fieldKind: 'CORE' as const,
    fieldKey: 'summary',
    groupId: GROUP_ID,
    accessLevel: 'VIEW' as const,
  }

  it('T-FP-4a: 올바른 경로로 POST 요청을 보낸다', async () => {
    let capturedMethod: string | null = null
    let capturedUrl: string | null = null
    server.use(
      http.post(FIELD_PERMISSIONS_ENDPOINT, ({ request }) => {
        capturedMethod = request.method
        capturedUrl = request.url
        return HttpResponse.json(fieldPermissionFixture, { status: 201 })
      }),
    )

    await createFieldPermission(PROJECT_KEY, createInput)

    expect(capturedMethod).toBe('POST')
    expect(new URL(capturedUrl as unknown as string).pathname).toBe(FIELD_PERMISSIONS_ENDPOINT)
  })

  it('T-FP-4b: POST 요청에 X-XSRF-TOKEN 헤더를 포함한다', async () => {
    let xsrfHeader: string | null = null
    server.use(
      http.post(FIELD_PERMISSIONS_ENDPOINT, ({ request }) => {
        xsrfHeader = request.headers.get('x-xsrf-token')
        return HttpResponse.json(fieldPermissionFixture, { status: 201 })
      }),
    )

    // XSRF 토큰 쿠키 설정
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await createFieldPermission(PROJECT_KEY, createInput)

    expect(xsrfHeader).toBe('test-csrf-token')
  })

  it('T-FP-4c: 응답을 FieldPermissionResponse로 파싱해 반환한다', async () => {
    server.use(
      http.post(FIELD_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json(fieldPermissionFixture, { status: 201 }),
      ),
    )

    const result = await createFieldPermission(PROJECT_KEY, createInput)

    expect(result.id).toBe(PERMISSION_ID)
    expect(result.fieldKind).toBe('CORE')
  })

  it('T-FP-4d: 서버 403 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.post(FIELD_PERMISSIONS_ENDPOINT, () =>
        HttpResponse.json({ error: 'forbidden' }, { status: 403 }),
      ),
    )

    const err = await createFieldPermission(PROJECT_KEY, createInput).catch((e: unknown) => e)
    expect((err as { status?: number }).status).toBe(403)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-FP-5. deleteFieldPermission — DELETE /api/v1/projects/{projectKey}/field-permissions/{id}
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteFieldPermission', () => {
  const DELETE_ENDPOINT = `${FIELD_PERMISSIONS_ENDPOINT}/${PERMISSION_ID}`

  it('T-FP-5a: 올바른 경로로 DELETE 요청을 보낸다', async () => {
    let capturedMethod: string | null = null
    let capturedUrl: string | null = null
    server.use(
      http.delete(DELETE_ENDPOINT, ({ request }) => {
        capturedMethod = request.method
        capturedUrl = request.url
        return new HttpResponse(null, { status: 204 })
      }),
    )

    await deleteFieldPermission(PROJECT_KEY, PERMISSION_ID)

    expect(capturedMethod).toBe('DELETE')
    expect(new URL(capturedUrl as unknown as string).pathname).toBe(DELETE_ENDPOINT)
  })

  it('T-FP-5b: DELETE 요청에 X-XSRF-TOKEN 헤더를 포함한다', async () => {
    let xsrfHeader: string | null = null
    server.use(
      http.delete(DELETE_ENDPOINT, ({ request }) => {
        xsrfHeader = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )

    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await deleteFieldPermission(PROJECT_KEY, PERMISSION_ID)

    expect(xsrfHeader).toBe('test-csrf-token')
  })

  it('T-FP-5c: 204 응답 시 void를 반환한다', async () => {
    server.use(
      http.delete(DELETE_ENDPOINT, () => new HttpResponse(null, { status: 204 })),
    )

    const result = await deleteFieldPermission(PROJECT_KEY, PERMISSION_ID)
    expect(result).toBeUndefined()
  })

  it('T-FP-5d: 서버 404 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.delete(DELETE_ENDPOINT, () =>
        HttpResponse.json({ error: 'not_found' }, { status: 404 }),
      ),
    )

    const err = await deleteFieldPermission(PROJECT_KEY, PERMISSION_ID).catch((e: unknown) => e)
    expect((err as { status?: number }).status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-FP-6. fetchGroups — GET /api/v1/groups
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchGroups', () => {
  it('T-FP-6a: 올바른 경로로 GET 요청을 보낸다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get(GROUPS_ENDPOINT, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([groupFixture])
      }),
    )

    await fetchGroups()

    expect(capturedUrl).not.toBeNull()
    expect(new URL(capturedUrl as unknown as string).pathname).toBe(GROUPS_ENDPOINT)
  })

  it('T-FP-6b: 응답을 GroupResponse[] 로 파싱해 반환한다', async () => {
    server.use(
      http.get(GROUPS_ENDPOINT, () => HttpResponse.json([groupFixture])),
    )

    const result = await fetchGroups()

    expect(result).toHaveLength(1)
    expect(result[0]?.id).toBe(GROUP_ID)
    expect(result[0]?.name).toBe('Developers')
    expect(result[0]?.memberCount).toBe(5)
  })

  it('T-FP-6c: 빈 배열 응답을 파싱해 반환한다', async () => {
    server.use(
      http.get(GROUPS_ENDPOINT, () => HttpResponse.json([])),
    )

    const result = await fetchGroups()
    expect(result).toHaveLength(0)
  })

  it('T-FP-6d: 응답 필드 누락 시 ZodError를 throw한다', async () => {
    server.use(
      http.get(GROUPS_ENDPOINT, () =>
        HttpResponse.json([{ id: GROUP_ID, name: 'Developers' }]),
      ),
    )

    await expect(fetchGroups()).rejects.toBeInstanceOf(ZodError)
  })

  it('T-FP-6e: GET 요청에 X-XSRF-TOKEN 헤더를 포함하지 않는다', async () => {
    let xsrfHeader: string | null = null
    server.use(
      http.get(GROUPS_ENDPOINT, ({ request }) => {
        xsrfHeader = request.headers.get('x-xsrf-token')
        return HttpResponse.json([])
      }),
    )

    await fetchGroups()

    expect(xsrfHeader).toBeNull()
  })

  it('T-FP-6f: 타입 컴파일 가드 — 반환값이 GroupResponse[] 에 할당 가능하다', async () => {
    server.use(
      http.get(GROUPS_ENDPOINT, () => HttpResponse.json([groupFixture])),
    )

    const result: GroupResponse[] = await fetchGroups()
    expect(result[0]?.memberCount).toBe(5)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-FP-7. extractFieldPermissionErrorCode — errorCode 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

describe('extractFieldPermissionErrorCode', () => {
  it('T-FP-7a: ApiError에 errorCode가 있으면 string을 반환한다', async () => {
    const { extractFieldPermissionErrorCode, ApiError } = await import('../field-permissions')
    const err = new ApiError(409, { errorCode: 'FIELD_PERMISSION_DUPLICATE' })
    expect(extractFieldPermissionErrorCode(err)).toBe('FIELD_PERMISSION_DUPLICATE')
  })

  it('T-FP-7b: ApiError에 errorCode가 없으면 null을 반환한다', async () => {
    const { extractFieldPermissionErrorCode, ApiError } = await import('../field-permissions')
    const err = new ApiError(403, { error: 'forbidden' })
    expect(extractFieldPermissionErrorCode(err)).toBeNull()
  })

  it('T-FP-7c: ApiError가 아니면 null을 반환한다', async () => {
    const { extractFieldPermissionErrorCode } = await import('../field-permissions')
    expect(extractFieldPermissionErrorCode(new Error('generic'))).toBeNull()
  })
})
