// FR-PM-01 프로젝트 멤버 MSW fixture 데이터 — ATLAS/BTS 두 프로젝트 초기 상태
import type { ProjectMember } from '../api/project-members.types'
import { ALICE_USER_ID, BOB_USER_ID, CAROL_USER_ID } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 멤버 fixture 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 멤버 단건 fixture 헬퍼 */
export const makeMember = (overrides: Partial<ProjectMember>): ProjectMember => ({
  projectId: 'project-atlas-uuid',
  userId: 'user-uuid-placeholder',
  role: 'MEMBER',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  displayName: '사용자',
  username: 'user',
  ...overrides,
})

// ─────────────────────────────────────────────────────────────────────────────
// ATLAS 프로젝트 초기 멤버 목록
// alice = PROJECT_ADMIN (유일한 admin), bob = MEMBER.
// userId는 auth-fixtures.ts whoami UUID(00000000-…-001/002)와 동일.
// user-fixtures.ts userAliceFixture/userBobFixture와도 동일.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ATLAS 프로젝트 초기 멤버 목록.
 * alice = PROJECT_ADMIN (유일한 admin), bob = MEMBER.
 * 테스트에서 alice-uuid 기반 alice가 "마지막 admin" 시나리오의 대상이 된다.
 */
export const atlasInitialMembers: ProjectMember[] = [
  makeMember({
    projectId: 'project-atlas-uuid',
    userId: ALICE_USER_ID,
    role: 'PROJECT_ADMIN',
    displayName: '앨리스',
    username: 'alice',
  }),
  makeMember({
    projectId: 'project-atlas-uuid',
    userId: BOB_USER_ID,
    role: 'MEMBER',
    displayName: '밥',
    username: 'bob',
  }),
]

// ─────────────────────────────────────────────────────────────────────────────
// BTS 프로젝트 초기 멤버 목록
// carol = PROJECT_ADMIN, dave = MEMBER.
// userId는 user-fixtures.ts userCarolFixture/userDaveFixture UUID와 동일.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * BTS 프로젝트 초기 멤버 목록.
 * carol = PROJECT_ADMIN, dave = MEMBER.
 */
export const btsInitialMembers: ProjectMember[] = [
  makeMember({
    projectId: 'project-bts-uuid',
    // user-fixtures.ts userCarolFixture.id 와 동일
    userId: CAROL_USER_ID,
    role: 'PROJECT_ADMIN',
    displayName: '캐럴',
    username: 'carol',
  }),
  makeMember({
    projectId: 'project-bts-uuid',
    // user-fixtures.ts userDaveFixture.id 와 동일
    userId: 'e745adab-f152-44cb-987e-aff965d3db4f',
    role: 'MEMBER',
    displayName: '데이브',
    username: 'dave',
  }),
]

/** 알려진 프로젝트 키 집합 */
export const KNOWN_PROJECT_KEYS = new Set(['ATLAS', 'BTS'])
