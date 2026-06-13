// 신뢰 디바이스 MSW 핸들러 단위 테스트 — stateful store + cross-handler 연동(C1) 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { trustedDevicesHandlers, resetTrustedDevicesStore } from '../trusted-devices-handlers'
import { mfaHandlers } from '../mfa-handlers'
import { authHandlers } from '../auth-handlers'
import { mfaStrings } from '../../i18n/ko'
import { MFA_E2E_ENABLED_KEY } from '../auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 서버 — 신뢰 디바이스 + MFA verify + login (cross-handler 연동 검증용)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...trustedDevicesHandlers, ...mfaHandlers, ...authHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetTrustedDevicesStore()
  localStorage.removeItem(MFA_E2E_ENABLED_KEY)
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 응답 타입 (테스트 내부 편의용)
// ─────────────────────────────────────────────────────────────────────────────

interface TrustedDevice {
  id: string
  label: string | null
  lastUsedAt: string | null
  createdAt: string
}

interface TrustedDevicesListBody {
  devices: TrustedDevice[]
}

interface ErrorBody {
  error: string
}

interface TokenBody {
  access_token: string
  token_type: string
  expires_in: number
}

interface MfaRequiredBody {
  mfa_required: boolean
  mfa_challenge_token: string
  expires_in: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 공통 요청 함수
// ─────────────────────────────────────────────────────────────────────────────

function listDevices(resetHeader = false): Promise<Response> {
  return fetch('/api/v1/auth/mfa/trusted-devices', {
    headers: resetHeader ? { 'X-MSW-Reset-TrustedDevices': 'true' } : {},
  })
}

function deleteDevice(id: string): Promise<Response> {
  return fetch(`/api/v1/auth/mfa/trusted-devices/${id}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': 'test-csrf-token' },
  })
}

function deleteAllDevices(): Promise<Response> {
  return fetch('/api/v1/auth/mfa/trusted-devices', {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': 'test-csrf-token' },
  })
}

function postVerifyWithTrust(code: string, trustDevice: boolean): Promise<Response> {
  return fetch('/api/v1/auth/mfa/verify', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      mfa_challenge_token: 'mock-mfa-challenge',
      code,
      trust_device: trustDevice,
    }),
  })
}

function postLogin(username: string): Promise<Response> {
  return fetch('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider: 'local', username, password: 'password' }),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/mfa/trusted-devices
// ─────────────────────────────────────────────────────────────────────────────

describe('trustedDevicesHandlers — GET /api/v1/auth/mfa/trusted-devices', () => {
  it('초기 상태 → { devices: [...] } — fixture 2개 반환', async () => {
    const res = await listDevices()

    expect(res.status).toBe(200)
    const body = await res.json() as TrustedDevicesListBody
    expect(Array.isArray(body.devices)).toBe(true)
    expect(body.devices.length).toBeGreaterThan(0)
  })

  it('fixture id는 RFC4122 v4 형식 — 3번째 그룹 4xxx, 4번째 그룹 [89ab]xxx', async () => {
    const res = await listDevices()
    const body = await res.json() as TrustedDevicesListBody

    for (const device of body.devices) {
      expect(device.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      )
    }
  })

  it('label: null 케이스가 fixture에 포함되어 있어야 한다', async () => {
    const res = await listDevices()
    const body = await res.json() as TrustedDevicesListBody

    const hasNullLabel = body.devices.some((d) => d.label === null)
    expect(hasNullLabel).toBe(true)
  })

  it('lastUsedAt: null 케이스가 fixture에 포함되어 있어야 한다', async () => {
    const res = await listDevices()
    const body = await res.json() as TrustedDevicesListBody

    const hasNullLastUsed = body.devices.some((d) => d.lastUsedAt === null)
    expect(hasNullLastUsed).toBe(true)
  })

  it('X-MSW-Reset-TrustedDevices: true 헤더 → 상태 초기화 후 전체 fixture 반환', async () => {
    // 먼저 기기 1개 삭제
    const initialRes = await listDevices()
    const initialBody = await initialRes.json() as TrustedDevicesListBody
    const firstId = initialBody.devices[0]?.id
    if (firstId !== undefined) {
      await deleteDevice(firstId)
    }

    // 리셋 헤더로 재조회 — 전체 복원 확인
    const resetRes = await listDevices(true)
    const resetBody = await resetRes.json() as TrustedDevicesListBody
    expect(resetBody.devices.length).toBe(initialBody.devices.length)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/mfa/trusted-devices/:id
// ─────────────────────────────────────────────────────────────────────────────

describe('trustedDevicesHandlers — DELETE /api/v1/auth/mfa/trusted-devices/:id', () => {
  it('존재하는 id → 204 + 목록에서 제거', async () => {
    const listRes = await listDevices()
    const listBody = await listRes.json() as TrustedDevicesListBody
    const firstId = listBody.devices[0]?.id
    expect(firstId).toBeDefined()
    if (firstId === undefined) return

    const delRes = await deleteDevice(firstId)
    expect(delRes.status).toBe(204)

    // 삭제 후 목록에서 없어졌는지 확인
    const afterRes = await listDevices()
    const afterBody = await afterRes.json() as TrustedDevicesListBody
    expect(afterBody.devices.find((d) => d.id === firstId)).toBeUndefined()
  })

  it('존재하지 않는 id → 404 { error: "not_found" }', async () => {
    const res = await deleteDevice('00000000-0000-4000-8000-000000000000')

    expect(res.status).toBe(404)
    const body = await res.json() as ErrorBody
    expect(body.error).toBe('not_found')
  })

  it('단건 삭제 후 남은 기기는 유지', async () => {
    const listRes = await listDevices()
    const listBody = await listRes.json() as TrustedDevicesListBody
    const initialCount = listBody.devices.length
    const firstId = listBody.devices[0]?.id
    if (firstId === undefined) return

    await deleteDevice(firstId)

    const afterRes = await listDevices()
    const afterBody = await afterRes.json() as TrustedDevicesListBody
    expect(afterBody.devices.length).toBe(initialCount - 1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/mfa/trusted-devices (전체 취소)
// ─────────────────────────────────────────────────────────────────────────────

describe('trustedDevicesHandlers — DELETE /api/v1/auth/mfa/trusted-devices (전체)', () => {
  it('전체 취소 → 204 + 목록 비어 있음', async () => {
    const delRes = await deleteAllDevices()
    expect(delRes.status).toBe(204)

    const listRes = await listDevices()
    const body = await listRes.json() as TrustedDevicesListBody
    expect(body.devices).toHaveLength(0)
  })

  it('이미 비어 있을 때 전체 취소 → 204 (idempotent)', async () => {
    await deleteAllDevices()
    const res = await deleteAllDevices()
    expect(res.status).toBe(204)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// cross-handler 연동 (C1) — verify trust_device → login mfa_required 우회
// ─────────────────────────────────────────────────────────────────────────────

describe('cross-handler 연동 — trust_device → login mfa_required 우회', () => {
  it('trust_device=false → login은 여전히 mfa_required 반환', async () => {
    localStorage.setItem(MFA_E2E_ENABLED_KEY, 'true')

    // trust_device false — 신뢰 기록 없음
    await postVerifyWithTrust('123456', false)

    const loginRes = await postLogin('alice')
    const loginBody = await loginRes.json() as MfaRequiredBody
    expect(loginBody.mfa_required).toBe(true)
  })

  it('trust_device=true → 다음 login은 mfa_required 없이 access_token 직접 반환', async () => {
    localStorage.setItem(MFA_E2E_ENABLED_KEY, 'true')

    // trust_device true — 신뢰 기록
    const verifyRes = await postVerifyWithTrust('123456', true)
    expect(verifyRes.status).toBe(200)

    // 이 후 login 시 MFA 챌린지 건너뜀
    const loginRes = await postLogin('alice')
    expect(loginRes.status).toBe(200)
    const loginBody = await loginRes.json() as TokenBody
    expect(loginBody.access_token).toBeTypeOf('string')
    expect((loginBody as unknown as { mfa_required?: boolean }).mfa_required).toBeUndefined()
  })

  it('resetTrustedDevicesStore 호출 후 플래그 초기화 → login 다시 mfa_required 반환', async () => {
    localStorage.setItem(MFA_E2E_ENABLED_KEY, 'true')
    await postVerifyWithTrust('123456', true)

    // 리셋
    resetTrustedDevicesStore()

    const loginRes = await postLogin('alice')
    const loginBody = await loginRes.json() as MfaRequiredBody
    expect(loginBody.mfa_required).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// i18n 키 존재 확인 (가짜그린 방지)
// ─────────────────────────────────────────────────────────────────────────────

describe('i18n — mfaStrings 신뢰 디바이스 키 존재 확인', () => {
  it('trustedDevicesSectionTitle 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesSectionTitle).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesSectionTitle.length).toBeGreaterThan(0)
  })

  it('trustedDevicesSectionDescription 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesSectionDescription).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesSectionDescription.length).toBeGreaterThan(0)
  })

  it('trustedDevicesEmptyState 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesEmptyState).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesEmptyState.length).toBeGreaterThan(0)
  })

  it('trustedDevicesRevokeButton 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesRevokeButton).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesRevokeButton.length).toBeGreaterThan(0)
  })

  it('trustedDevicesRevokeAllButton 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesRevokeAllButton).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesRevokeAllButton.length).toBeGreaterThan(0)
  })

  it('trustedDevicesRevokeConfirm 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesRevokeConfirm).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesRevokeConfirm.length).toBeGreaterThan(0)
  })

  it('trustedDevicesLabelFallback 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesLabelFallback).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesLabelFallback.length).toBeGreaterThan(0)
  })

  it('trustedDevicesLastUsedNever 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesLastUsedNever).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesLastUsedNever.length).toBeGreaterThan(0)
  })

  it('trustedDevicesLoginCheckboxLabel 키가 존재하고 비어 있지 않다', () => {
    expect(mfaStrings.trustedDevicesLoginCheckboxLabel).toBeTypeOf('string')
    expect(mfaStrings.trustedDevicesLoginCheckboxLabel.length).toBeGreaterThan(0)
  })

  it('키 값에 콜론(:)으로 끝나는 문자열이 없다', () => {
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
    ] as const
    for (const key of trustedKeys) {
      const value = mfaStrings[key]
      expect(value.endsWith(':')).toBe(false)
    }
  })
})
