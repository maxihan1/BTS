// identity-access BC 테스트 사용자 fixture — MSW handler 및 테스트에서 공유
import type { WhoamiResponse } from '@/api/schemas'

// ─────────────────────────────────────────────────────────────────────────────
// MFA E2E 시나리오 토글 — localStorage 키 + 인메모리 store
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키.
 * 이 키가 'true'이면 login 핸들러가 정식 토큰 대신 MFA 챌린지 응답을 반환한다.
 *
 * Playwright addInitScript 로 goto 전에 플래그를 설정하면
 * 첫 login fetch 시점부터 적용된다 (msw-derived-behavior-shared-store-e2e).
 */
export const MFA_E2E_ENABLED_KEY = '__bts_e2e_mfa_enabled'

/**
 * MFA 고정 유효 코드 — MSW mock 전용.
 * 이 코드만 enable/disable/verify에서 성공으로 처리한다.
 */
export const MFA_VALID_CODE = '123456'

/**
 * 백업코드 고정 유효 코드 — MSW mock 전용.
 * verify method:"backup_code" 요청에서 이 코드만 성공으로 처리한다.
 */
export const MFA_VALID_BACKUP_CODE = 'aaaaa-bbbbb'

// ─────────────────────────────────────────────────────────────────────────────
// MFA 인메모리 store — mfa-handlers.ts 와 공유하는 단일 진실 출처
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MFA store 상태 — 단일 사용자 기준 (mock은 다중 사용자 격리 불필요)
 */
export interface MfaStore {
  /** TOTP 활성화 여부 */
  enabled: boolean
  /** setup 호출 후 pending 상태 여부 (enable 성공 전까지 true) */
  hasPendingSetup: boolean
  /** 백업코드가 한 번이라도 생성된 여부 */
  backupCodesGenerated: boolean
  /** 소진되지 않은 백업코드 잔여 개수 */
  backupCodesRemaining: number
}

/** MFA 인메모리 store — 초기값 비활성 */
export const mfaStore: MfaStore = {
  enabled: false,
  hasPendingSetup: false,
  backupCodesGenerated: false,
  backupCodesRemaining: 0,
}

/**
 * MFA store를 초기 상태(비활성, pending 없음, 백업코드 없음)로 리셋한다.
 * 테스트 afterEach 또는 E2E 시드 전 호출해 테스트 간 격리를 보장한다.
 */
export function resetMfaStore(): void {
  mfaStore.enabled = false
  mfaStore.hasPendingSetup = false
  mfaStore.backupCodesGenerated = false
  mfaStore.backupCodesRemaining = 0
}

/** alice fixture — backend DB seed 사용자와 일치 (userId는 UUID v4 고정값) */
export const aliceUser: WhoamiResponse = {
  username: 'alice',
  email: 'alice@bts.local',
  authMethod: 'jwt',
  userId: '00000000-0000-0000-0000-000000000001',
  mustChangePassword: false,
  isSystemAdmin: false,
}

/** bob fixture — 추가 fixture 사용자 */
export const bobUser: WhoamiResponse = {
  username: 'bob',
  email: 'bob@bts.local',
  authMethod: 'jwt',
  userId: '00000000-0000-0000-0000-000000000002',
  mustChangePassword: false,
  isSystemAdmin: false,
}

/** username → fixture 사용자 맵 */
export const AUTH_USERS: Readonly<Record<string, WhoamiResponse>> = {
  alice: aliceUser,
  bob: bobUser,
}

/** username → 유효 비밀번호 맵 (mock 전용, 실제 비밀번호 아님) */
export const VALID_PASSWORDS: Readonly<Record<string, string>> = {
  alice: 'password',
  bob: 'password',
}

/** username → 유효 비밀번호 맵 (LDAP provider, mock 전용 — backend `LdapAuthFlowIntegrationTest` (uid=alice/bob, Test1234!) 매칭) */
export const LDAP_VALID_PASSWORDS: Readonly<Record<string, string>> = {
  alice: 'Test1234!',
  bob: 'Test1234!',
}

/** mock access token 생성 — username 기반으로 E2E에서 추적 가능 */
export function mockAccessToken(username: string): string {
  return `mock-access-token-${username}`
}

/**
 * WhoamiResponse 테스트 픽스처 팩토리.
 * aliceUser 기본값에 override를 머지하여 반환한다.
 * 새 필드 추가 시 aliceUser만 갱신하면 하위 호출 전체가 동기화된다.
 */
export function makeWhoami(overrides: Partial<WhoamiResponse> = {}): WhoamiResponse {
  return { ...aliceUser, ...overrides }
}
