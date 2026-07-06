// 사용자 프로필 BC 테스트 fixture — profile-handlers.ts store 초기 시드
import { aliceUser, bobUser } from './auth-fixtures'

/**
 * 프로필 store 레코드 — 백엔드 `ProfileResponse` 계약(userId/username/email/displayName/
 * timezone/department) + 아바타는 저장값(`avatarObjectKey`)만 담고, 다운로드 URL 파생은
 * profile-handlers.ts 책임(백엔드 UserProfileController.toResponse 미러).
 */
export interface ProfileFixture {
  userId: string
  username: string
  email: string | null
  displayName: string
  /** 아바타 저장 키 — null이면 미설정(avatarUrl도 null로 파생). */
  avatarObjectKey: string | null
  timezone: string
  department: string | null
}

/** alice 프로필 fixture — auth-fixtures.aliceUser와 동일 userId/username/email로 정합. */
export const ALICE_PROFILE_FIXTURE: ProfileFixture = {
  userId: aliceUser.userId,
  username: aliceUser.username,
  email: aliceUser.email,
  displayName: '김앨리스',
  avatarObjectKey: null,
  timezone: 'Asia/Seoul',
  department: '플랫폼팀',
}

/** bob 프로필 fixture — 아바타/부서 미설정 상태(EC1 lazy 케이스) 대표. */
export const BOB_PROFILE_FIXTURE: ProfileFixture = {
  userId: bobUser.userId,
  username: bobUser.username,
  email: bobUser.email,
  displayName: 'bob',
  avatarObjectKey: null,
  timezone: 'UTC',
  department: null,
}

/** 프로필 store 초기 시드 배열 — profile-handlers.ts가 이 값으로 store를 채운다. */
export const PROFILE_FIXTURES: readonly ProfileFixture[] = [
  ALICE_PROFILE_FIXTURE,
  BOB_PROFILE_FIXTURE,
]
