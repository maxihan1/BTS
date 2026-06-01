// identity-access BC 사용자 목록 MSW fixture 데이터
import type { UserSummary } from '@/api/users'

/** 사용자 fixture — alice (displayName 있음) */
export const userAliceFixture: UserSummary = {
  id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f',
  username: 'alice',
  displayName: '김앨리스',
  email: null,
}

/** 사용자 fixture — bob (displayName 없음 — username 폴백 검증용) */
export const userBobFixture: UserSummary = {
  id: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a',
  username: 'bob',
  displayName: null,
  email: null,
}

/** 사용자 목록 fixture — 2건 */
export const userListFixture: UserSummary[] = [userAliceFixture, userBobFixture]
