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

/**
 * 사용자 fixture — carol (ATLAS 비멤버 — 멤버 추가 typeahead 검색 대상).
 * email: null (PII — 실데이터 금지).
 */
export const userCarolFixture: UserSummary = {
  id: '961fb10c-6317-47c8-b377-d8fc5594db82',
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
