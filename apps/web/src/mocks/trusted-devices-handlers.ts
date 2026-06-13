// FR-MF-05 신뢰 디바이스 MSW 핸들러 — stateful store + GET/DELETE + cross-handler 플래그
import { http, HttpResponse } from 'msw'
import type { TrustedDevice } from '@/api/trusted-devices'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture 데이터 — 기기 2개 (label 있는 것 + null, lastUsedAt 있는 것 + null)
// UUID 값은 RFC4122 v4 형식: 3번째 그룹 4xxx, 4번째 그룹 [89ab]xxx
// (zod-v4-uuid-fixture-strictness, session-handlers 선례)
// TrustedDevice 타입은 api/trusted-devices.ts의 z.infer 타입을 import해 drift 원천 차단
// (frontend-zod-backend-dto-contract-gap, fixture 옵션 B)
// ─────────────────────────────────────────────────────────────────────────────

const DEVICE_A_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const DEVICE_B_ID = 'bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb'

const fixtureDevices: readonly TrustedDevice[] = [
  {
    id: DEVICE_A_ID,
    label: 'Chrome on macOS',
    lastUsedAt: '2026-06-10T09:00:00Z',
    createdAt: '2026-06-01T09:00:00Z',
    expiresAt: '2026-07-01T09:00:00Z',
  },
  {
    id: DEVICE_B_ID,
    label: null,
    lastUsedAt: null,
    createdAt: '2026-06-05T14:00:00Z',
    expiresAt: '2026-07-05T14:00:00Z',
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// Stateful store — 삭제된 id 집합
// 각 E2E 테스트 시작 시 X-MSW-Reset-TrustedDevices: true 헤더 또는
// resetTrustedDevicesStore() 호출로 초기화한다.
// ─────────────────────────────────────────────────────────────────────────────

const revokedIds = new Set<string>()

/**
 * cross-handler 연동용 플래그.
 * POST /api/v1/auth/mfa/verify 에서 trust_device=true 이면 true 로 설정된다.
 * auth-handlers.ts 의 login 핸들러가 이 값을 읽어 mfa_required 를 건너뛴다.
 * resetTrustedDevicesStore() 호출 시 false 로 초기화된다.
 */
export let trustedThisBrowser = false

/**
 * 신뢰 디바이스 store 및 cross-handler 플래그를 초기 상태로 리셋한다.
 * 테스트 afterEach 또는 E2E 시드 전 호출해 테스트 간 격리를 보장한다.
 */
export function resetTrustedDevicesStore(): void {
  revokedIds.clear()
  trustedThisBrowser = false
}

/**
 * trustedThisBrowser 플래그를 true 로 설정한다.
 * mfa-handlers.ts 의 verify 핸들러에서 trust_device=true 일 때 호출된다.
 */
export function markTrustedThisBrowser(): void {
  trustedThisBrowser = true
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/auth/mfa/trusted-devices
// 응답: { devices: [...] } — revokedIds 에 없는 기기만 반환.
// X-MSW-Reset-TrustedDevices: true 헤더 포함 시 상태 초기화 후 전체 목록 반환.
// ─────────────────────────────────────────────────────────────────────────────

const listDevicesHandler = http.get('/api/v1/auth/mfa/trusted-devices', ({ request }) => {
  if (request.headers.get('X-MSW-Reset-TrustedDevices') === 'true') {
    revokedIds.clear()
    trustedThisBrowser = false
  }

  const activeDevices = fixtureDevices.filter((d) => !revokedIds.has(d.id))
  return HttpResponse.json({ devices: activeDevices })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/mfa/trusted-devices/:id  (단건 취소)
// 존재하지 않는 id → 404 { error: "not_found" }
// 성공 → 204 + store 에서 제거
// ─────────────────────────────────────────────────────────────────────────────

const revokeDeviceHandler = http.delete(
  '/api/v1/auth/mfa/trusted-devices/:id',
  ({ params }) => {
    const id = params['id'] as string
    const allIds = fixtureDevices.map((d) => d.id)

    if (!allIds.includes(id) || revokedIds.has(id)) {
      return HttpResponse.json({ error: 'not_found' }, { status: 404 })
    }

    revokedIds.add(id)
    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/auth/mfa/trusted-devices  (전체 취소)
// 성공 → 204 + store 를 모든 id 로 채움 (이후 GET 에서 빈 배열 반환)
// ─────────────────────────────────────────────────────────────────────────────

const revokeAllDevicesHandler = http.delete('/api/v1/auth/mfa/trusted-devices', () => {
  for (const d of fixtureDevices) {
    revokedIds.add(d.id)
  }
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 배열 export
// 순서 주의: 단건(:id) 핸들러를 전체(경로 없음) 보다 먼저 등록해야 MSW 가 올바르게 매칭한다.
// ─────────────────────────────────────────────────────────────────────────────

export const trustedDevicesHandlers = [
  listDevicesHandler,
  revokeDeviceHandler,
  revokeAllDevicesHandler,
]
