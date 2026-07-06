// 사용자 상태 메시지 MSW 핸들러 stateful 동작 단위 테스트 (FR-PR-02 Task 6)
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { statusHandlers, resetStatusStore, seedStatusRecord } from './status-handlers'
import { mockAccessToken } from './auth-fixtures'
import { ALICE_STATUS_FIXTURE } from './status-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — 전역 공유 server(@/test/server)에 매 테스트 server.use()로 등록한다
// (profile-handlers.test.ts 선례 — 개별 setupServer 인스턴스 이중 디스패치 회귀 방지).
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  resetStatusStore()
  server.use(...statusHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 / 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_TOKEN = mockAccessToken('alice')
const ALICE_ID = ALICE_STATUS_FIXTURE.userId

interface StatusResponseBody {
  emoji: string | null
  text: string | null
  expiresAt: string | null
}

interface ErrorBody {
  code: string
  message: string
}

function authHeaders(token: string, extra: HeadersInit = {}): HeadersInit {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra }
}

function getStatus(token: string = ALICE_TOKEN): Promise<Response> {
  return fetch('/api/v1/users/me/status', { headers: authHeaders(token) })
}

function patchStatus(body: Record<string, unknown>, token: string = ALICE_TOKEN): Promise<Response> {
  return fetch('/api/v1/users/me/status', {
    method: 'PATCH',
    headers: authHeaders(token),
    body: JSON.stringify(body),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/status
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/status', () => {
  it('시드값(미설정)은 all-null을 반환한다(EC1 — row 없어도 강제 생성 안 함)', async () => {
    const res = await getStatus()
    expect(res.status).toBe(200)
    const body = (await res.json()) as StatusResponseBody
    expect(body).toEqual({ emoji: null, text: null, expiresAt: null })
  })

  it('미인증(Authorization 헤더 없음)이면 401을 반환한다', async () => {
    const res = await fetch('/api/v1/users/me/status')
    expect(res.status).toBe(401)
  })

  it('bob으로 조회해도 미설정(all-null)이다(actor 격리 기본값)', async () => {
    const res = await getStatus(mockAccessToken('bob'))
    const body = (await res.json()) as StatusResponseBody
    expect(body).toEqual({ emoji: null, text: null, expiresAt: null })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/users/me/status — 원자적 교체(replace)
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/users/me/status', () => {
  it('이모지+텍스트+만료 설정 후 GET에서 그대로 반영된다(stateful 오버라이드 영속, S2)', async () => {
    const patchRes = await patchStatus({
      emoji: '🌴',
      text: '휴가 중',
      expiresAt: '2099-01-01T00:00:00Z',
    })
    expect(patchRes.status).toBe(200)
    const patchBody = (await patchRes.json()) as StatusResponseBody
    expect(patchBody.emoji).toBe('🌴')
    expect(patchBody.text).toBe('휴가 중')
    expect(patchBody.expiresAt).toBe('2099-01-01T00:00:00Z')

    const getRes = await getStatus()
    const getBody = (await getRes.json()) as StatusResponseBody
    expect(getBody.emoji).toBe('🌴')
    expect(getBody.text).toBe('휴가 중')
    expect(getBody.expiresAt).toBe('2099-01-01T00:00:00Z')
  })

  it('이모지만 설정하면 텍스트는 null이다(S3)', async () => {
    const res = await patchStatus({ emoji: '🌴' })
    const body = (await res.json()) as StatusResponseBody
    expect(body.emoji).toBe('🌴')
    expect(body.text).toBeNull()
  })

  it('텍스트만 설정하면 이모지는 null이다(S4)', async () => {
    const res = await patchStatus({ text: '회의 중' })
    const body = (await res.json()) as StatusResponseBody
    expect(body.emoji).toBeNull()
    expect(body.text).toBe('회의 중')
  })

  it('재설정하면 이전 값이 잔존하지 않는다(replace 시맨틱, EC6)', async () => {
    await patchStatus({ emoji: '🌴', text: '휴가 중', expiresAt: '2099-01-01T00:00:00Z' })
    const res = await patchStatus({ text: '회의 중' })
    const body = (await res.json()) as StatusResponseBody
    expect(body.emoji).toBeNull()
    expect(body.text).toBe('회의 중')
    expect(body.expiresAt).toBeNull()
  })

  it('둘 다 빈값이면 해제되어 이후 GET도 all-null이다(S5, EC7)', async () => {
    await patchStatus({ emoji: '🌴', text: '휴가 중' })
    const patchRes = await patchStatus({ emoji: '', text: '' })
    const patchBody = (await patchRes.json()) as StatusResponseBody
    expect(patchBody).toEqual({ emoji: null, text: null, expiresAt: null })

    const getRes = await getStatus()
    const getBody = (await getRes.json()) as StatusResponseBody
    expect(getBody).toEqual({ emoji: null, text: null, expiresAt: null })
  })

  it('공백만 있는 emoji/text도 정규화되어 해제된다(EC3)', async () => {
    const res = await patchStatus({ emoji: '   ', text: '   ' })
    const body = (await res.json()) as StatusResponseBody
    expect(body).toEqual({ emoji: null, text: null, expiresAt: null })
  })

  it('빈 body({})는 멱등 해제다(EC2)', async () => {
    const res = await patchStatus({})
    const body = (await res.json()) as StatusResponseBody
    expect(body).toEqual({ emoji: null, text: null, expiresAt: null })
  })

  it('text가 100자 초과면 400 STATUS_VALIDATION_FAILED를 반환하고 store는 변경되지 않는다(EC5)', async () => {
    const res = await patchStatus({ text: 'x'.repeat(101) })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ErrorBody
    expect(body.code).toBe('STATUS_VALIDATION_FAILED')

    const getRes = await getStatus()
    const getBody = (await getRes.json()) as StatusResponseBody
    expect(getBody).toEqual({ emoji: null, text: null, expiresAt: null })
  })

  it('emoji가 32자 초과면 400 STATUS_VALIDATION_FAILED를 반환한다(NFR3)', async () => {
    const res = await patchStatus({ emoji: 'x'.repeat(33) })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ErrorBody
    expect(body.code).toBe('STATUS_VALIDATION_FAILED')
  })

  it('expiresAt이 과거 시각이면 400 STATUS_VALIDATION_FAILED를 반환한다(EC4)', async () => {
    const res = await patchStatus({ emoji: '🌴', expiresAt: '2020-01-01T00:00:00Z' })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ErrorBody
    expect(body.code).toBe('STATUS_VALIDATION_FAILED')
  })

  it('미인증이면 401을 반환한다', async () => {
    const res = await fetch('/api/v1/users/me/status', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ emoji: '🌴' }),
    })
    expect(res.status).toBe(401)
  })

  it('alice와 bob의 상태가 서로 격리된다(actor 격리)', async () => {
    await patchStatus({ emoji: '🌴', text: '휴가 중' }, ALICE_TOKEN)

    const bobRes = await getStatus(mockAccessToken('bob'))
    const bobBody = (await bobRes.json()) as StatusResponseBody
    expect(bobBody).toEqual({ emoji: null, text: null, expiresAt: null })

    const aliceRes = await getStatus(ALICE_TOKEN)
    const aliceBody = (await aliceRes.json()) as StatusResponseBody
    expect(aliceBody.emoji).toBe('🌴')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 만료 상태 lazy 필터 (S6) — PATCH는 과거 expiresAt을 거부하므로(EC4) 이미 만료된
// 레코드는 seedStatusRecord로 직접 주입해 재현한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/users/me/status — 만료 상태 lazy 필터(S6)', () => {
  it('expiresAt이 과거인 레코드는 GET에서 all-null로 필터된다', async () => {
    seedStatusRecord({
      userId: ALICE_ID,
      emoji: '🌴',
      text: '휴가 중',
      expiresAt: '2020-01-01T00:00:00Z',
    })

    const res = await getStatus()
    const body = (await res.json()) as StatusResponseBody
    expect(body).toEqual({ emoji: null, text: null, expiresAt: null })
  })

  it('expiresAt이 미래인 레코드는 GET에서 그대로 노출된다', async () => {
    seedStatusRecord({
      userId: ALICE_ID,
      emoji: '🌴',
      text: '휴가 중',
      expiresAt: '2099-01-01T00:00:00Z',
    })

    const res = await getStatus()
    const body = (await res.json()) as StatusResponseBody
    expect(body.emoji).toBe('🌴')
    expect(body.text).toBe('휴가 중')
  })

  it('expiresAt이 null(만료 없음)인 레코드는 계속 노출된다', async () => {
    seedStatusRecord({ userId: ALICE_ID, emoji: '🌴', text: null, expiresAt: null })

    const res = await getStatus()
    const body = (await res.json()) as StatusResponseBody
    expect(body.emoji).toBe('🌴')
    expect(body.expiresAt).toBeNull()
  })
})
