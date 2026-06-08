// identity-access BC 테스트 사용자 fixture — MSW handler 및 테스트에서 공유
import type { WhoamiResponse } from '@/api/schemas'

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
