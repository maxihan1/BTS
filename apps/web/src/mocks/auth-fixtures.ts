// identity-access BC 테스트 사용자 fixture — MSW handler 및 테스트에서 공유
import type { WhoamiResponse } from '@/api/schemas'

/** alice fixture — backend DB seed 사용자와 일치 (userId는 UUID v4 고정값) */
export const aliceUser: WhoamiResponse = {
  username: 'alice',
  email: 'alice@bts.local',
  authMethod: 'jwt',
  userId: '00000000-0000-0000-0000-000000000001',
}

/** bob fixture — 추가 fixture 사용자 */
export const bobUser: WhoamiResponse = {
  username: 'bob',
  email: 'bob@bts.local',
  authMethod: 'jwt',
  userId: '00000000-0000-0000-0000-000000000002',
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

/** mock access token 생성 — username 기반으로 E2E에서 추적 가능 */
export function mockAccessToken(username: string): string {
  return `mock-access-token-${username}`
}
