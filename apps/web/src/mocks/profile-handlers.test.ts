// 사용자 프로필 MSW 핸들러 stateful 동작 단위 테스트 (FR-PR-01 D6 Task 4)
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { profileHandlers, resetProfileStore, validateAvatarUpload } from './profile-handlers'
import { mockAccessToken } from './auth-fixtures'
import { ALICE_PROFILE_FIXTURE, BOB_PROFILE_FIXTURE } from './profile-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — 전역 공유 server(@/test/server)에 매 테스트 server.use()로 등록한다.
// 개별 setupServer 인스턴스를 별도로 띄우면 전역 setup.ts의 서버와 동시에 두 인터셉터가
// 활성화되어 동일 요청(특히 FormData 업로드)이 이중 디스패치되는 환경 결함이 있다
// (import-handlers.test.ts 선례 — "Body is unusable: Body has already been read").
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  resetProfileStore()
  server.use(...profileHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 / 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_TOKEN = mockAccessToken('alice')
const ALICE_ID = ALICE_PROFILE_FIXTURE.userId
const BOB_ID = BOB_PROFILE_FIXTURE.userId

interface ProfileResponseBody {
  userId: string
  username: string
  email: string | null
  displayName: string
  avatarUrl: string | null
  timezone: string
  department: string | null
  displayNameSource: 'LDAP' | 'USER'
  ldapLinked: boolean
}

interface ErrorBody {
  code: string
  message: string
}

function authHeaders(token: string, extra: HeadersInit = {}): HeadersInit {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra }
}

function getProfile(token: string = ALICE_TOKEN): Promise<Response> {
  return fetch('/api/v1/users/me/profile', { headers: authHeaders(token) })
}

function patchProfile(body: Record<string, unknown>, token: string = ALICE_TOKEN): Promise<Response> {
  return fetch('/api/v1/users/me/profile', {
    method: 'PATCH',
    headers: authHeaders(token),
    body: JSON.stringify(body),
  })
}

function uploadAvatar(file: File, token: string = ALICE_TOKEN): Promise<Response> {
  const formData = new FormData()
  formData.append('file', file)
  return fetch('/api/v1/users/me/profile/avatar', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: formData,
  })
}

function deleteAvatar(token: string = ALICE_TOKEN): Promise<Response> {
  return fetch('/api/v1/users/me/profile/avatar', {
    method: 'DELETE',
    headers: authHeaders(token),
  })
}

function getAvatarBytes(userId: string, token: string = ALICE_TOKEN): Promise<Response> {
  return fetch(`/api/v1/users/${userId}/avatar`, { headers: authHeaders(token) })
}

function resyncDisplayName(token: string = ALICE_TOKEN): Promise<Response> {
  return fetch('/api/v1/users/me/profile/display-name/resync', {
    method: 'POST',
    headers: authHeaders(token),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/profile
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/profile', () => {
  it('시드값(ALICE_PROFILE_FIXTURE)을 그대로 반환한다', async () => {
    const res = await getProfile()
    expect(res.status).toBe(200)
    const body = (await res.json()) as ProfileResponseBody
    expect(body.userId).toBe(ALICE_ID)
    expect(body.username).toBe('alice')
    expect(body.displayName).toBe(ALICE_PROFILE_FIXTURE.displayName)
    expect(body.timezone).toBe('Asia/Seoul')
    expect(body.department).toBe('플랫폼팀')
    expect(body.avatarUrl).toBeNull()
    // FR-PR-04 — alice는 LDAP 연결 + 동기화 상태 대표 픽스처
    expect(body.displayNameSource).toBe('LDAP')
    expect(body.ldapLinked).toBe(true)
  })

  it('미인증(Authorization 헤더 없음)이면 401을 반환한다', async () => {
    const res = await fetch('/api/v1/users/me/profile')
    expect(res.status).toBe(401)
  })

  it('bob으로 조회하면 bob의 시드값(department null)을 반환한다', async () => {
    const res = await getProfile(mockAccessToken('bob'))
    const body = (await res.json()) as ProfileResponseBody
    expect(body.userId).toBe(BOB_ID)
    expect(body.department).toBeNull()
    expect(body.timezone).toBe('UTC')
    // FR-PR-04 — bob은 LDAP 미연결(로컬 전용) 대표 픽스처
    expect(body.ldapLinked).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/users/me/profile — 3-state 부분 수정
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/users/me/profile', () => {
  it('displayName만 변경하면 이후 GET에서 갱신값이 반영된다', async () => {
    const patchRes = await patchProfile({ displayName: '김맥시' })
    expect(patchRes.status).toBe(200)
    const patchBody = (await patchRes.json()) as ProfileResponseBody
    expect(patchBody.displayName).toBe('김맥시')

    const getRes = await getProfile()
    const getBody = (await getRes.json()) as ProfileResponseBody
    expect(getBody.displayName).toBe('김맥시')
    // 미변경 필드는 유지
    expect(getBody.timezone).toBe('Asia/Seoul')
    expect(getBody.department).toBe('플랫폼팀')
  })

  it('FR-PR-04 — displayName을 직접 편집하면 displayNameSource가 USER로 전환된다', async () => {
    const patchRes = await patchProfile({ displayName: '김맥시' })
    const patchBody = (await patchRes.json()) as ProfileResponseBody
    expect(patchBody.displayNameSource).toBe('USER')

    const getRes = await getProfile()
    const getBody = (await getRes.json()) as ProfileResponseBody
    expect(getBody.displayNameSource).toBe('USER')
  })

  it('department를 명시 null로 보내면 삭제되어 이후 GET에서 null이 반영된다', async () => {
    const patchRes = await patchProfile({ department: null })
    expect(patchRes.status).toBe(200)
    const patchBody = (await patchRes.json()) as ProfileResponseBody
    expect(patchBody.department).toBeNull()

    const getRes = await getProfile()
    const getBody = (await getRes.json()) as ProfileResponseBody
    expect(getBody.department).toBeNull()
  })

  it('department에 새 값을 보내면 갱신된다', async () => {
    const patchRes = await patchProfile({ department: '신규팀' })
    const patchBody = (await patchRes.json()) as ProfileResponseBody
    expect(patchBody.department).toBe('신규팀')
  })

  it('빈 displayName이면 400 PROFILE_VALIDATION_FAILED를 반환하고 store는 변경되지 않는다', async () => {
    const res = await patchProfile({ displayName: '' })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ErrorBody
    expect(body.code).toBe('PROFILE_VALIDATION_FAILED')

    const getRes = await getProfile()
    const getBody = (await getRes.json()) as ProfileResponseBody
    expect(getBody.displayName).toBe(ALICE_PROFILE_FIXTURE.displayName)
  })

  it('공백만 있는 displayName도 400 PROFILE_VALIDATION_FAILED를 반환한다', async () => {
    const res = await patchProfile({ displayName: '   ' })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ErrorBody
    expect(body.code).toBe('PROFILE_VALIDATION_FAILED')
  })

  it('미인증이면 401을 반환한다', async () => {
    const res = await fetch('/api/v1/users/me/profile', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName: '김맥시' }),
    })
    expect(res.status).toBe(401)
  })

  it('alice와 bob의 수정이 서로 격리된다 (actor 격리)', async () => {
    await patchProfile({ displayName: '김맥시' }, ALICE_TOKEN)

    const bobRes = await getProfile(mockAccessToken('bob'))
    const bobBody = (await bobRes.json()) as ProfileResponseBody
    expect(bobBody.displayName).toBe(BOB_PROFILE_FIXTURE.displayName)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// validateAvatarUpload — 크기/MIME 검증 순수 함수 단위 테스트.
// jsdom `File`은 `.stream()`을 구현하지 않아 실제 멀티파트 전송 시 대용량 바이트가
// 손상된다(환경 한계). 6MB 크기 초과 판정은 실제 HTTP round-trip 대신 이 함수로
// 직접 검증한다(msw-derived-behavior-shared-store 계열 환경 제약, attachments.test.ts의
// 413 테스트가 실제 바이트 대신 server.use() 스텁으로 우회하는 것과 동일한 이유).
// ─────────────────────────────────────────────────────────────────────────────

describe('validateAvatarUpload', () => {
  it('5MB(정확히 상한)는 통과한다(null)', () => {
    expect(validateAvatarUpload('image/png', 5 * 1024 * 1024)).toBeNull()
  })

  it('5MB + 1byte는 400 AVATAR_VALIDATION_FAILED를 반환한다(6MB 초과 케이스 대표)', () => {
    const result = validateAvatarUpload('image/png', 6 * 1024 * 1024)
    expect(result?.code).toBe('AVATAR_VALIDATION_FAILED')
  })

  it('비이미지 MIME이면 크기와 무관하게 AVATAR_VALIDATION_FAILED를 반환한다', () => {
    const result = validateAvatarUpload('text/plain', 10)
    expect(result?.code).toBe('AVATAR_VALIDATION_FAILED')
  })

  it('허용 MIME + 상한 이하 크기는 null(통과)을 반환한다', () => {
    expect(validateAvatarUpload('image/webp', 1024)).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/users/me/profile/avatar — 업로드
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/users/me/profile/avatar', () => {
  it('업로드 성공 시 avatarUrl을 반환하고 이후 GET에서 avatarUrl이 노출된다', async () => {
    const file = new File(['avatar-bytes'], 'avatar.png', { type: 'image/png' })
    const uploadRes = await uploadAvatar(file)
    expect(uploadRes.status).toBe(200)
    const uploadBody = (await uploadRes.json()) as { avatarUrl: string }
    expect(uploadBody.avatarUrl).toBe(`/api/v1/users/${ALICE_ID}/avatar`)

    const getRes = await getProfile()
    const getBody = (await getRes.json()) as ProfileResponseBody
    expect(getBody.avatarUrl).toBe(`/api/v1/users/${ALICE_ID}/avatar`)
  })

  it('비이미지 MIME이면 400 AVATAR_VALIDATION_FAILED를 반환한다', async () => {
    const file = new File(['not an image'], 'doc.txt', { type: 'text/plain' })
    const res = await uploadAvatar(file)
    expect(res.status).toBe(400)
    const body = (await res.json()) as ErrorBody
    expect(body.code).toBe('AVATAR_VALIDATION_FAILED')
  })

  it('미인증이면 401을 반환한다', async () => {
    const file = new File(['avatar-bytes'], 'avatar.png', { type: 'image/png' })
    const formData = new FormData()
    formData.append('file', file)
    const res = await fetch('/api/v1/users/me/profile/avatar', { method: 'POST', body: formData })
    expect(res.status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/users/me/profile/avatar — 삭제(멱등)
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/users/me/profile/avatar', () => {
  it('아바타 삭제 후 GET에서 avatarUrl이 null화된다', async () => {
    const file = new File(['avatar-bytes'], 'avatar.png', { type: 'image/png' })
    await uploadAvatar(file)

    const delRes = await deleteAvatar()
    expect(delRes.status).toBe(204)

    const getRes = await getProfile()
    const getBody = (await getRes.json()) as ProfileResponseBody
    expect(getBody.avatarUrl).toBeNull()
  })

  it('아바타가 없어도 204를 반환한다 (멱등)', async () => {
    const res = await deleteAvatar()
    expect(res.status).toBe(204)
  })

  it('미인증이면 401을 반환한다', async () => {
    const res = await fetch('/api/v1/users/me/profile/avatar', { method: 'DELETE' })
    expect(res.status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/:userId/avatar — 아바타 바이너리 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/:userId/avatar', () => {
  it('아바타가 설정된 사용자의 이미지 바이트를 image/png로 반환한다', async () => {
    const file = new File(['avatar-bytes'], 'avatar.png', { type: 'image/png' })
    await uploadAvatar(file)

    const res = await getAvatarBytes(ALICE_ID)
    expect(res.status).toBe(200)
    expect(res.headers.get('Content-Type')).toBe('image/png')
    const bytes = await res.arrayBuffer()
    expect(bytes.byteLength).toBeGreaterThan(0)
  })

  it('아바타가 없으면 404 AVATAR_NOT_FOUND를 반환한다', async () => {
    const res = await getAvatarBytes(BOB_ID)
    expect(res.status).toBe(404)
    const body = (await res.json()) as ErrorBody
    expect(body.code).toBe('AVATAR_NOT_FOUND')
  })

  it('동료(bob)의 아바타도 alice 토큰으로 조회 가능하다 (동료 아바타 표시)', async () => {
    await uploadAvatar(new File(['b'], 'b.png', { type: 'image/png' }), mockAccessToken('bob'))
    const res = await getAvatarBytes(BOB_ID, ALICE_TOKEN)
    expect(res.status).toBe(200)
  })

  it('미인증이면 401을 반환한다', async () => {
    const res = await fetch(`/api/v1/users/${ALICE_ID}/avatar`)
    expect(res.status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/users/me/profile/display-name/resync — LDAP 값으로 재설정 (FR-PR-04)
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/users/me/profile/display-name/resync', () => {
  it('displayName을 직접 편집(source=USER)한 뒤 resync하면 source가 LDAP로 되돌아간다', async () => {
    await patchProfile({ displayName: '김맥시' })
    const beforeResync = await getProfile()
    const beforeBody = (await beforeResync.json()) as ProfileResponseBody
    expect(beforeBody.displayNameSource).toBe('USER')

    const resyncRes = await resyncDisplayName()
    expect(resyncRes.status).toBe(200)
    const resyncBody = (await resyncRes.json()) as ProfileResponseBody
    expect(resyncBody.displayNameSource).toBe('LDAP')

    const afterResync = await getProfile()
    const afterBody = (await afterResync.json()) as ProfileResponseBody
    expect(afterBody.displayNameSource).toBe('LDAP')
    // 표시 이름 값 자체는 이 호출로 바뀌지 않는다 — 다음 로그인의 지연 동기화 몫(ADR D4)
    expect(afterBody.displayName).toBe('김맥시')
  })

  it('ldapLinked=false(bob)이면 409 DISPLAY_NAME_NOT_LDAP_LINKED를 반환한다', async () => {
    const res = await resyncDisplayName(mockAccessToken('bob'))
    expect(res.status).toBe(409)
    const body = (await res.json()) as ErrorBody
    expect(body.code).toBe('DISPLAY_NAME_NOT_LDAP_LINKED')
  })

  it('미인증이면 401을 반환한다', async () => {
    const res = await fetch('/api/v1/users/me/profile/display-name/resync', { method: 'POST' })
    expect(res.status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// stateful 통합 시나리오 — 조회↔수정 반영 전체 플로우
// ─────────────────────────────────────────────────────────────────────────────

describe('stateful 통합 시나리오', () => {
  it('PATCH → 업로드 → GET → 삭제 → GET 순서로 상태가 정확히 변한다', async () => {
    await patchProfile({ displayName: '김맥시', timezone: 'UTC', department: '신규팀' })

    const file = new File(['avatar-bytes'], 'avatar.png', { type: 'image/png' })
    await uploadAvatar(file)

    const afterUpload = await getProfile()
    const afterUploadBody = (await afterUpload.json()) as ProfileResponseBody
    expect(afterUploadBody.displayName).toBe('김맥시')
    expect(afterUploadBody.timezone).toBe('UTC')
    expect(afterUploadBody.department).toBe('신규팀')
    expect(afterUploadBody.avatarUrl).toBe(`/api/v1/users/${ALICE_ID}/avatar`)

    await deleteAvatar()

    const final = await getProfile()
    const finalBody = (await final.json()) as ProfileResponseBody
    expect(finalBody.avatarUrl).toBeNull()
    // 아바타 삭제는 다른 필드에 영향 없음
    expect(finalBody.displayName).toBe('김맥시')
  })
})
