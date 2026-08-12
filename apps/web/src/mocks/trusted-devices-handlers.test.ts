// FR-MF-05 신뢰 디바이스 MSW 핸들러 단위 테스트 — fixture drift 원천 차단 + cross-handler 연동 검증
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { trustedDevicesHandlers, resetTrustedDevicesStore } from './trusted-devices-handlers'
import { mfaHandlers } from './mfa-handlers'
import { authHandlers } from './auth-handlers'
import { trustedDeviceSchema } from '@/api/trusted-devices'
import { mfaStrings } from '@/i18n/ko'
import { MFA_E2E_ENABLED_KEY, resetMfaStore } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 서버 — trusted-devices + mfa (verify trust_device) + auth (login mfa_required 분기)
// cross-handler 연동 테스트에 세 핸들러 집합 모두 필요
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...trustedDevicesHandlers, ...mfaHandlers, ...authHandlers)
})
afterEach(() => {
  resetTrustedDevicesStore()
  resetMfaStore()
  localStorage.removeItem(MFA_E2E_ENABLED_KEY)
})

// ─────────────────────────────────────────────────────────────────────────────
// 내부 응답 타입 (테스트 전용)
// ─────────────────────────────────────────────────────────────────────────────

interface TokenResponse {
  access_token: string
  token_type: string
  expires_in: number
}

interface MfaRequiredResponse {
  mfa_required: boolean
  mfa_challenge_token: string
  expires_in: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function getDevices(): Promise<Response> {
  return fetch('/api/v1/auth/mfa/trusted-devices')
}

function postVerifyWithTrustDevice(trustDevice: boolean): Promise<Response> {
  return fetch('/api/v1/auth/mfa/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mfa_challenge_token: 'mock-mfa-challenge-token',
      code: '123456',
      trust_device: trustDevice,
    }),
  })
}

function postLogin(): Promise<Response> {
  return fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider: 'local', username: 'alice', password: 'password' }),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 수정 1 — fixture drift 원천 차단
// GET 응답의 각 device가 trustedDeviceSchema로 parse 성공해야 한다.
// expiresAt 필드 누락 시 ZodError → 테스트 실패(RED).
// ─────────────────────────────────────────────────────────────────────────────

describe('trustedDevicesHandlers — GET /api/v1/auth/mfa/trusted-devices', () => {
  it('응답 devices 배열의 각 항목이 trustedDeviceSchema (expiresAt 포함) parse 성공', async () => {
    const res = await getDevices()

    expect(res.status).toBe(200)
    const body = await res.json() as { devices: unknown[] }
    expect(Array.isArray(body.devices)).toBe(true)
    expect(body.devices.length).toBeGreaterThan(0)

    // 핵심 검증 — expiresAt 필드 누락 시 ZodError throw → 테스트 RED
    for (const device of body.devices) {
      const parsed = trustedDeviceSchema.safeParse(device)
      if (!parsed.success) {
        throw new Error(
          `trustedDeviceSchema parse 실패 (expiresAt 누락 가능성): ${parsed.error.message}`,
        )
      }
      expect(parsed.data.expiresAt).toBeTypeOf('string')
      expect(parsed.data.expiresAt.length).toBeGreaterThan(0)
    }
  })

  it('device A: id·label·lastUsedAt·createdAt·expiresAt 모두 존재', async () => {
    const res = await getDevices()
    const body = await res.json() as { devices: unknown[] }

    const firstDevice = body.devices[0]
    const parsed = trustedDeviceSchema.safeParse(firstDevice)
    if (!parsed.success) {
      throw new Error(`device A parse 실패: ${parsed.error.message}`)
    }

    expect(parsed.data.label).toBe('Chrome on macOS')
    expect(parsed.data.lastUsedAt).toBeTypeOf('string')
    expect(parsed.data.expiresAt).toBeTypeOf('string')
    expect(parsed.data.createdAt).toBe('2026-06-01T09:00:00Z')
    // expiresAt = createdAt + 30일
    expect(parsed.data.expiresAt).toBe('2026-07-01T09:00:00Z')
  })

  it('device B: label=null, lastUsedAt=null, expiresAt 존재', async () => {
    const res = await getDevices()
    const body = await res.json() as { devices: unknown[] }

    const secondDevice = body.devices[1]
    const parsed = trustedDeviceSchema.safeParse(secondDevice)
    if (!parsed.success) {
      throw new Error(`device B parse 실패: ${parsed.error.message}`)
    }

    expect(parsed.data.label).toBeNull()
    expect(parsed.data.lastUsedAt).toBeNull()
    expect(parsed.data.createdAt).toBe('2026-06-05T14:00:00Z')
    // expiresAt = createdAt + 30일
    expect(parsed.data.expiresAt).toBe('2026-07-05T14:00:00Z')
  })

  it('X-MSW-Reset-TrustedDevices: true 헤더 → 상태 초기화 후 전체 2건 반환', async () => {
    // 먼저 1건 삭제
    await fetch('/api/v1/auth/mfa/trusted-devices/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', {
      method: 'DELETE',
    })

    const res = await fetch('/api/v1/auth/mfa/trusted-devices', {
      headers: { 'X-MSW-Reset-TrustedDevices': 'true' },
    })
    const body = await res.json() as { devices: unknown[] }
    expect(body.devices).toHaveLength(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 수정 2 — cross-handler 단위 테스트 (C1)
// verify trust_device:true → trustedThisBrowser=true → login이 mfa_required 생략하고 토큰 발급
// ─────────────────────────────────────────────────────────────────────────────

describe('cross-handler — trust_device:true 후 login mfa_required 우회', () => {
  it('trust_device:false → MFA_E2E_ENABLED_KEY ON 상태에서 login mfa_required 반환 (기준 확인)', async () => {
    localStorage.setItem(MFA_E2E_ENABLED_KEY, 'true')

    await postVerifyWithTrustDevice(false)

    const loginRes = await postLogin()
    expect(loginRes.status).toBe(200)
    const loginBody = await loginRes.json() as MfaRequiredResponse
    expect(loginBody.mfa_required).toBe(true)
    expect(loginBody.mfa_challenge_token).toBeTypeOf('string')
  })

  it('trust_device:true → verify 후 login이 mfa_required 없이 access_token 발급', async () => {
    localStorage.setItem(MFA_E2E_ENABLED_KEY, 'true')

    // verify 성공 + trust_device:true → trustedThisBrowser=true로 설정됨
    const verifyRes = await postVerifyWithTrustDevice(true)
    expect(verifyRes.status).toBe(200)

    // 다음 login 시 trustedThisBrowser=true이므로 mfa_required 없이 토큰 직접 발급
    const loginRes = await postLogin()
    expect(loginRes.status).toBe(200)
    const loginBody = await loginRes.json() as TokenResponse
    // mfa_required가 없고 access_token이 있어야 함
    expect('mfa_required' in loginBody).toBe(false)
    expect(loginBody.access_token).toBeTypeOf('string')
    expect(loginBody.access_token.length).toBeGreaterThan(0)
    expect(loginBody.token_type).toBe('Bearer')
  })

  it('resetTrustedDevicesStore() 후 trust_device 플래그 리셋 → 다시 mfa_required 반환', async () => {
    localStorage.setItem(MFA_E2E_ENABLED_KEY, 'true')

    // 신뢰 등록
    await postVerifyWithTrustDevice(true)

    // 리셋
    resetTrustedDevicesStore()

    // 이제 다시 mfa_required
    const loginRes = await postLogin()
    const loginBody = await loginRes.json() as MfaRequiredResponse
    expect(loginBody.mfa_required).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE 핸들러 — 기본 동작 (단건/전체)
// ─────────────────────────────────────────────────────────────────────────────

describe('trustedDevicesHandlers — DELETE (단건 취소)', () => {
  it('존재하는 id 삭제 → 204 + 이후 GET에서 제외', async () => {
    const deleteRes = await fetch(
      '/api/v1/auth/mfa/trusted-devices/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      { method: 'DELETE' },
    )
    expect(deleteRes.status).toBe(204)

    const listRes = await getDevices()
    const body = await listRes.json() as { devices: Array<{ id: string }> }
    const ids = body.devices.map((d) => d.id)
    expect(ids).not.toContain('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
  })

  it('존재하지 않는 id → 404 { error: "not_found" }', async () => {
    const deleteRes = await fetch(
      '/api/v1/auth/mfa/trusted-devices/ffffffff-ffff-4fff-8fff-ffffffffffff',
      { method: 'DELETE' },
    )
    expect(deleteRes.status).toBe(404)
    const body = await deleteRes.json() as { error: string }
    expect(body.error).toBe('not_found')
  })

  it('이미 삭제된 id 재삭제 → 404', async () => {
    await fetch('/api/v1/auth/mfa/trusted-devices/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', {
      method: 'DELETE',
    })
    const res = await fetch('/api/v1/auth/mfa/trusted-devices/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', {
      method: 'DELETE',
    })
    expect(res.status).toBe(404)
  })
})

describe('trustedDevicesHandlers — DELETE 전체 취소', () => {
  it('전체 취소 → 204 + 이후 GET 빈 배열', async () => {
    const deleteRes = await fetch('/api/v1/auth/mfa/trusted-devices', { method: 'DELETE' })
    expect(deleteRes.status).toBe(204)

    const listRes = await getDevices()
    const body = await listRes.json() as { devices: unknown[] }
    expect(body.devices).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// i18n 키 존재 확인 (가짜그린 방지)
// __tests__/trusted-devices-handlers.test.ts 에서 흡수 — 중복 파일 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('i18n — mfaStrings 신뢰 디바이스 키 전수 확인', () => {
  const trustedKeys = [
    'trustedDevicesSectionTitle',
    'trustedDevicesSectionDescription',
    'trustedDevicesEmptyState',
    'trustedDevicesRevokeButton',
    'trustedDevicesRevokeAllButton',
    'trustedDevicesRevokeConfirm',
    'trustedDevicesLabelFallback',
    'trustedDevicesLastUsedNever',
    'trustedDevicesLoginCheckboxLabel',
    'trustedDevicesConfirmButton',
    'trustedDevicesCancelButton',
    'trustedDevicesRegisteredLabel',
    'trustedDevicesLastUsedLabel',
    'trustedDevicesExpiresLabel',
    'trustedDevicesLoadError',
  ] as const

  it.each(trustedKeys)('"%s" 키가 존재하고 비어 있지 않다', (key) => {
    expect(mfaStrings[key]).toBeTypeOf('string')
    expect(mfaStrings[key].length).toBeGreaterThan(0)
  })

  it('신뢰 디바이스 키 값 중 문장을 콜론(:)으로 끝내는 항목이 없다 (레이블 콜론은 허용)', () => {
    // 레이블 키(끝에 콜론이 의도적으로 붙는 것)는 제외, 나머지 문장형 키 검사
    const sentenceKeys = [
      'trustedDevicesSectionTitle',
      'trustedDevicesSectionDescription',
      'trustedDevicesEmptyState',
      'trustedDevicesRevokeButton',
      'trustedDevicesRevokeAllButton',
      'trustedDevicesRevokeConfirm',
      'trustedDevicesLabelFallback',
      'trustedDevicesLastUsedNever',
      'trustedDevicesLoginCheckboxLabel',
      'trustedDevicesConfirmButton',
      'trustedDevicesCancelButton',
      'trustedDevicesLoadError',
    ] as const
    for (const key of sentenceKeys) {
      expect(mfaStrings[key].endsWith(':')).toBe(false)
    }
  })
})
