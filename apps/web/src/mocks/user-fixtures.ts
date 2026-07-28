// identity-access BC 사용자 목록 MSW fixture 데이터
import type { UserSummary } from '@/api/users'
import { ALICE_USER_ID, BOB_USER_ID, CAROL_USER_ID } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// ★ id 는 `auth-fixtures.ts` 를 정본으로 삼는다 (리터럴 재타이핑 금지).
//
// 이 디렉터리는 `GET /api/v1/users?ids=` 가 돌려주는 목록이고, 그 `ids` 는 **토큰이 만들어낸
// authorId/assigneeId** 다. 즉 두 파일의 id 집합이 겹치지 않으면 조회가 0건이 되고 화면은
// 이름 대신 원시 UUID 를 그린다 — 그런데 어떤 테스트도 실패하지 않는다(전부 `useUsersByIds` 를
// mock 하므로). 그래서 값을 여기서 따로 적지 않고 정본을 import 한다.
//
// dave·eve 는 대응하는 인증 사용자가 없다(디렉터리 전용 검색 대상). 그래서 자체 UUID 를 유지한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 사용자 fixture — alice (displayName 있음) */
export const userAliceFixture: UserSummary = {
  id: ALICE_USER_ID,
  username: 'alice',
  displayName: '김앨리스',
  email: null,
}

/** 사용자 fixture — bob (displayName 없음 — username 폴백 검증용) */
export const userBobFixture: UserSummary = {
  id: BOB_USER_ID,
  username: 'bob',
  displayName: null,
  email: null,
}

/**
 * 사용자 fixture — carol (ATLAS 비멤버 — 멤버 추가 typeahead 검색 대상).
 * email: null (PII — 실데이터 금지).
 */
export const userCarolFixture: UserSummary = {
  id: CAROL_USER_ID,
  username: 'carol',
  displayName: '캐럴',
  email: null,
}

/**
 * 사용자 fixture — dave (BTS 멤버, ATLAS 비멤버).
 * email: null (PII — 실데이터 금지).
 */
export const userDaveFixture: UserSummary = {
  id: 'e745adab-f152-44cb-987e-aff965d3db4f',
  username: 'dave',
  displayName: '데이브',
  email: null,
}

/**
 * 사용자 fixture — eve (모든 프로젝트 비멤버).
 * email: null (PII — 실데이터 금지).
 */
export const userEveFixture: UserSummary = {
  id: '9cd2d4a3-c3a8-49fb-bc8b-03521f94e55c',
  username: 'eve',
  displayName: '이브',
  email: null,
}

/** 사용자 전체 목록 fixture — 5건 */
export const userListFixture: UserSummary[] = [
  userAliceFixture,
  userBobFixture,
  userCarolFixture,
  userDaveFixture,
  userEveFixture,
]
