// 사용자 상태 메시지 BC 테스트 fixture — status-handlers.ts store 초기 시드 (FR-PR-02)
import { aliceUser, bobUser } from './auth-fixtures'

/**
 * 상태 store 레코드 — 백엔드 `StatusResponse` 계약(emoji/text/expiresAt)과 1:1.
 * row 없음(신규 사용자)은 emoji/text/expiresAt 모두 null로 표현한다(EC1 — 강제 생성 안 함,
 * profile-fixtures.ProfileFixture 선례).
 */
export interface StatusFixture {
  userId: string
  emoji: string | null
  text: string | null
  expiresAt: string | null
}

/** alice 상태 fixture — 초기 시드는 미설정(all-null, EC1 신규 사용자 대표). */
export const ALICE_STATUS_FIXTURE: StatusFixture = {
  userId: aliceUser.userId,
  emoji: null,
  text: null,
  expiresAt: null,
}

/** bob 상태 fixture — 초기 시드는 미설정(all-null). */
export const BOB_STATUS_FIXTURE: StatusFixture = {
  userId: bobUser.userId,
  emoji: null,
  text: null,
  expiresAt: null,
}

/** 상태 store 초기 시드 배열 — status-handlers.ts가 이 값으로 store를 채운다. */
export const STATUS_FIXTURES: readonly StatusFixture[] = [
  ALICE_STATUS_FIXTURE,
  BOB_STATUS_FIXTURE,
]
