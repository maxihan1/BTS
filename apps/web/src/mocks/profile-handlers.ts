// 사용자 프로필 BC MSW 핸들러 — stateful GET/PATCH/POST/DELETE + 동료 아바타 조회 (FR-PR-01 D6 Task 4)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'
import { PROFILE_FIXTURES } from './profile-fixtures'
import type { ProfileFixture } from './profile-fixtures'
import type { AvatarUploadResponse, ProfileResponse } from '@/api/profile'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 — userId → ProfileFixture Map (msw-derived-behavior-shared-store 선례)
// ─────────────────────────────────────────────────────────────────────────────

function seedProfileStore(): Map<string, ProfileFixture> {
  const map = new Map<string, ProfileFixture>()
  for (const fixture of PROFILE_FIXTURES) {
    map.set(fixture.userId, { ...fixture })
  }
  return map
}

let profileStore: Map<string, ProfileFixture> = seedProfileStore()

/**
 * 프로필 저장소를 초기 시드 상태(`PROFILE_FIXTURES`)로 리셋한다 — 각 테스트 afterEach에서 호출.
 * 테스트 간 state leak을 방지한다(favorite-handlers.resetFavoriteStore 선례).
 */
export function resetProfileStore(): void {
  profileStore = seedProfileStore()
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — Authorization Bearer 토큰 파싱 (favorite-handlers.ts와 동일 규약)
// ─────────────────────────────────────────────────────────────────────────────

/** mock access token prefix — auth-fixtures.mockAccessToken과 동일 형식 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

/**
 * Authorization Bearer 헤더에서 현재 사용자 userId를 도출한다.
 * 토큰 형식. `mock-access-token-<username>`. 미인증/미인식 시 null.
 */
function resolveUserIdFromRequest(request: Request): string | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null

  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MOCK_TOKEN_PREFIX)) return null

  const username = token.slice(MOCK_TOKEN_PREFIX.length)
  const user = AUTH_USERS[username]
  return user?.userId ?? null
}

/** 아바타 다운로드 경로 조립 — 백엔드 UserProfileController.avatarUrlFor 미러 */
function avatarUrlFor(userId: string): string {
  return `/api/v1/users/${userId}/avatar`
}

/** 백엔드 `{code, message}` 에러 봉투 생성 */
function errorBody(code: string, message: string): { code: string; message: string } {
  return { code, message }
}

/**
 * ProfileFixture(store 레코드) → {@link ProfileResponse}(백엔드 `ProfileResponse` DTO와
 * 1:1인 api/profile.ts의 Zod 추론 타입) 변환. 반환 타입을 실 계약 타입으로 고정해
 * 필드 drift가 생기면 컴파일 시점에 즉시 드러난다(attachment-handlers.ts 선례).
 */
function toProfileResponse(record: ProfileFixture): ProfileResponse {
  return {
    userId: record.userId,
    username: record.username,
    email: record.email,
    displayName: record.displayName,
    avatarUrl: record.avatarObjectKey !== null ? avatarUrlFor(record.userId) : null,
    timezone: record.timezone,
    department: record.department,
    displayNameSource: record.displayNameSource,
    ldapLinked: record.ldapLinked,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/profile
// ─────────────────────────────────────────────────────────────────────────────

const getMyProfileHandler = http.get('/api/v1/users/me/profile', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const record = profileStore.get(userId)
  if (record === undefined) {
    return HttpResponse.json(
      errorBody('PROFILE_NOT_FOUND', '사용자를 찾을 수 없습니다.'),
      { status: 404 },
    )
  }

  return HttpResponse.json(toProfileResponse(record))
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/users/me/profile — 3-state 부분 수정
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PATCH `/api/v1/users/me/profile` — 3-state 부분 수정(부재=미변경, department는 명시 null=삭제).
 *
 * displayName이 키로 존재하는데 공백(trim 후 빈 문자열)이면 400 PROFILE_VALIDATION_FAILED를
 * 반환하고 store를 변경하지 않는다(백엔드 UserProfileService.validateIfPresent 미러).
 */
const patchMyProfileHandler = http.patch('/api/v1/users/me/profile', async ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const current = profileStore.get(userId)
  if (current === undefined) {
    return HttpResponse.json(
      errorBody('PROFILE_NOT_FOUND', '사용자를 찾을 수 없습니다.'),
      { status: 404 },
    )
  }

  let body: Record<string, unknown>
  try {
    body = (await request.json()) as Record<string, unknown>
  } catch {
    return HttpResponse.json(
      errorBody('PROFILE_VALIDATION_FAILED', '잘못된 요청 본문입니다.'),
      { status: 400 },
    )
  }

  const next: ProfileFixture = { ...current }

  if ('displayName' in body) {
    const value = body['displayName']
    if (typeof value !== 'string' || value.trim().length === 0) {
      return HttpResponse.json(
        errorBody('PROFILE_VALIDATION_FAILED', '표시 이름은 공백일 수 없습니다.'),
        { status: 400 },
      )
    }
    next.displayName = value
    // FR-PR-04 — 표시 이름을 직접 편집하면 출처가 USER로 전환된다
    // (백엔드 UserRepository.updateDisplayName의 display_name_source='USER' 갱신 미러).
    next.displayNameSource = 'USER'
  }

  if ('timezone' in body) {
    const value = body['timezone']
    if (typeof value === 'string' && value.trim().length > 0) {
      next.timezone = value
    }
  }

  if ('department' in body) {
    const value = body['department']
    next.department = typeof value === 'string' ? value : null
  }

  profileStore.set(userId, next)
  return HttpResponse.json(toProfileResponse(next))
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/users/me/profile/display-name/resync — LDAP 값으로 재설정 (FR-PR-04)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 표시 이름 필드 출처를 LDAP로 되돌린다(source: USER → LDAP).
 *
 * 외부 IdP 연결이 없는 사용자(`ldapLinked=false`)는 409 `DISPLAY_NAME_NOT_LDAP_LINKED`를
 * 반환한다(백엔드 UserProfileService.resyncDisplayName의 DisplayNameNotLdapLinkedException 미러).
 * 표시 이름 값 자체는 이 요청으로 바뀌지 않는다 — 다음 로그인(LDAP JIT 동기화) 시 cn으로
 * 갱신되는 지연 semantics다(ADR 2026-07-07 D4).
 */
const resyncDisplayNameHandler = http.post(
  '/api/v1/users/me/profile/display-name/resync',
  ({ request }) => {
    const userId = resolveUserIdFromRequest(request)
    if (userId === null) return new HttpResponse(null, { status: 401 })

    const current = profileStore.get(userId)
    if (current === undefined) {
      return HttpResponse.json(
        errorBody('PROFILE_NOT_FOUND', '사용자를 찾을 수 없습니다.'),
        { status: 404 },
      )
    }

    if (!current.ldapLinked) {
      return HttpResponse.json(
        errorBody('DISPLAY_NAME_NOT_LDAP_LINKED', '연결된 LDAP 계정이 없습니다.'),
        { status: 409 },
      )
    }

    const next: ProfileFixture = { ...current, displayNameSource: 'LDAP' }
    profileStore.set(userId, next)
    return HttpResponse.json(toProfileResponse(next))
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// 아바타 검증 정책 — AvatarTypePolicy.kt(backend) 미러
// ─────────────────────────────────────────────────────────────────────────────

/** 아바타 업로드 허용 MIME — 백엔드 AvatarTypePolicy.ALLOWED_MIME과 동일 */
const ALLOWED_AVATAR_MIME: ReadonlySet<string> = new Set([
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
])

/** 아바타 최대 크기 — 백엔드 AvatarTypePolicy.MAX_BYTES(5MiB)와 동일 */
const AVATAR_MAX_BYTES = 5 * 1024 * 1024

/**
 * 아바타 MIME/크기가 백엔드 `AvatarTypePolicy.validate` 정책을 통과하는지 판정한다.
 *
 * HTTP 핸들러 로직과 분리한 순수 함수 — jsdom 테스트 환경에서는 `File`이 `.stream()`을
 * 구현하지 않아 실제 멀티파트 전송 바이트가 손상되므로(환경 한계, 코드 결함 아님),
 * 대용량 파일 크기 검증을 단위 테스트로 직접 검증하려면 이 함수를 호출한다.
 *
 * @param contentType 업로드된 파일의 MIME.
 * @param sizeBytes 업로드된 파일의 바이트 수.
 * @returns 위반 시 `{code, message}` 에러 봉투, 통과 시 `null`.
 */
export function validateAvatarUpload(
  contentType: string,
  sizeBytes: number,
): { code: string; message: string } | null {
  if (!ALLOWED_AVATAR_MIME.has(contentType)) {
    return errorBody('AVATAR_VALIDATION_FAILED', '지원하지 않는 이미지 형식입니다.')
  }
  if (sizeBytes > AVATAR_MAX_BYTES) {
    return errorBody('AVATAR_VALIDATION_FAILED', '이미지 크기는 5MB 이하여야 합니다.')
  }
  return null
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/users/me/profile/avatar — 업로드
// ─────────────────────────────────────────────────────────────────────────────

const uploadAvatarHandler = http.post(
  '/api/v1/users/me/profile/avatar',
  async ({ request }) => {
    const userId = resolveUserIdFromRequest(request)
    if (userId === null) return new HttpResponse(null, { status: 401 })

    const current = profileStore.get(userId)
    if (current === undefined) {
      return HttpResponse.json(
        errorBody('PROFILE_NOT_FOUND', '사용자를 찾을 수 없습니다.'),
        { status: 404 },
      )
    }

    let file: File | null = null
    try {
      const formData = await request.formData()
      const part = formData.get('file')
      // `FormData.get()`은 `File | string | null`만 반환한다. `instanceof File`은
      // Node(undici)가 재구성한 File과 jsdom 전역 File 클래스가 서로 다른 realm이라
      // 항상 false로 판정되므로(cross-realm), 문자열/null이 아니면 File로 간주한다.
      file = typeof part === 'string' || part === null ? null : part
    } catch {
      file = null
    }

    if (file === null) {
      return HttpResponse.json(
        errorBody('AVATAR_VALIDATION_FAILED', '업로드할 파일이 없습니다.'),
        { status: 400 },
      )
    }

    const validationError = validateAvatarUpload(file.type, file.size)
    if (validationError !== null) {
      return HttpResponse.json(validationError, { status: 400 })
    }

    const next: ProfileFixture = { ...current, avatarObjectKey: `avatars/${userId}/${file.name}` }
    profileStore.set(userId, next)

    const response: AvatarUploadResponse = { avatarUrl: avatarUrlFor(userId) }
    return HttpResponse.json(response)
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/users/me/profile/avatar — 삭제(멱등)
// ─────────────────────────────────────────────────────────────────────────────

const deleteAvatarHandler = http.delete('/api/v1/users/me/profile/avatar', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const current = profileStore.get(userId)
  if (current !== undefined) {
    profileStore.set(userId, { ...current, avatarObjectKey: null })
  }

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// 아바타 바이트 — 1x1 투명 PNG 상수 (attachment-handlers.minimalPngBytes 선례)
// ─────────────────────────────────────────────────────────────────────────────

/** 1×1 투명 PNG 최소 바이트(base64) — `<img>` 태그가 실제로 렌더 가능한 유효 이미지 바이트. */
const MINIMAL_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='

/** {@link MINIMAL_PNG_BASE64}를 디코딩해 Uint8Array 바이트로 반환한다. */
function minimalPngBytes(): Uint8Array {
  const binary = atob(MINIMAL_PNG_BASE64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/:userId/avatar — 아바타 바이너리 조회(동료 것도 조회 가능)
// ─────────────────────────────────────────────────────────────────────────────

const getAvatarHandler = http.get('/api/v1/users/:userId/avatar', ({ params, request }) => {
  const requesterId = resolveUserIdFromRequest(request)
  if (requesterId === null) return new HttpResponse(null, { status: 401 })

  const targetUserId = params['userId'] as string
  const record = profileStore.get(targetUserId)

  if (record === undefined || record.avatarObjectKey === null) {
    return HttpResponse.json(
      errorBody('AVATAR_NOT_FOUND', '아바타를 찾을 수 없습니다.'),
      { status: 404 },
    )
  }

  return new HttpResponse(minimalPngBytes(), {
    status: 200,
    headers: { 'Content-Type': 'image/png' },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 사용자 프로필 BC MSW 핸들러 배열 */
export const profileHandlers = [
  getMyProfileHandler,
  patchMyProfileHandler,
  resyncDisplayNameHandler,
  uploadAvatarHandler,
  deleteAvatarHandler,
  getAvatarHandler,
]
