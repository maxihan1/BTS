// FR-PM-01 프로젝트 멤버 MSW fixture 데이터 — ATLAS/BTS 두 프로젝트 + 사용자 디렉토리
import type { ProjectMember } from '../api/project-members.types'

// ─────────────────────────────────────────────────────────────────────────────
// 사용자 디렉토리 fixture — email은 @example.com 더미 (PII 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 사용자 디렉토리 단건 타입 */
export interface UserDirectoryEntry {
  id: string
  username: string
  displayName: string
  email: string
}

/** 사용자 디렉토리 헬퍼 */
export const makeUserEntry = (overrides: Partial<UserDirectoryEntry>): UserDirectoryEntry => ({
  id: 'user-uuid-placeholder',
  username: 'user',
  displayName: '사용자',
  email: 'user@example.com',
  ...overrides,
})

// alice/bob의 id는 auth-fixtures.ts의 whoami 정규 UUID(00000000-…-001/002)와 일치시킨다.
// E2E에서 로그인한 alice(whoami userId)가 ATLAS의 PROJECT_ADMIN으로 인식돼야 액션 컨트롤이 노출된다.
/** 전체 사용자 pool — 프로젝트 멤버 추가 검색에 쓰이는 디렉토리 */
export const userDirectoryFixtures: UserDirectoryEntry[] = [
  makeUserEntry({ id: '00000000-0000-0000-0000-000000000001', username: 'alice', displayName: '앨리스', email: 'alice@example.com' }),
  makeUserEntry({ id: '00000000-0000-0000-0000-000000000002', username: 'bob', displayName: '밥', email: 'bob@example.com' }),
  makeUserEntry({ id: 'fixture-carol-uuid', username: 'carol', displayName: '캐럴', email: 'carol@example.com' }),
  makeUserEntry({ id: 'fixture-dave-uuid', username: 'dave', displayName: '데이브', email: 'dave@example.com' }),
  makeUserEntry({ id: 'fixture-eve-uuid', username: 'eve', displayName: '이브', email: 'eve@example.com' }),
]

// ─────────────────────────────────────────────────────────────────────────────
// 프로젝트 멤버 fixture — ATLAS 프로젝트
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

/**
 * ATLAS 프로젝트 초기 멤버 목록.
 * alice = PROJECT_ADMIN (유일한 admin), bob = MEMBER.
 * 테스트에서 alice-uuid 기반 alice가 "마지막 admin" 시나리오의 대상이 된다.
 */
export const atlasInitialMembers: ProjectMember[] = [
  makeMember({
    projectId: 'project-atlas-uuid',
    userId: '00000000-0000-0000-0000-000000000001',
    role: 'PROJECT_ADMIN',
    displayName: '앨리스',
    username: 'alice',
  }),
  makeMember({
    projectId: 'project-atlas-uuid',
    userId: '00000000-0000-0000-0000-000000000002',
    role: 'MEMBER',
    displayName: '밥',
    username: 'bob',
  }),
]

/**
 * BTS 프로젝트 초기 멤버 목록.
 * carol = PROJECT_ADMIN, dave = MEMBER.
 */
export const btsInitialMembers: ProjectMember[] = [
  makeMember({
    projectId: 'project-bts-uuid',
    userId: 'fixture-carol-uuid',
    role: 'PROJECT_ADMIN',
    displayName: '캐럴',
    username: 'carol',
  }),
  makeMember({
    projectId: 'project-bts-uuid',
    userId: 'fixture-dave-uuid',
    role: 'MEMBER',
    displayName: '데이브',
    username: 'dave',
  }),
]

/** 알려진 프로젝트 키 집합 */
export const KNOWN_PROJECT_KEYS = new Set(['ATLAS', 'BTS'])
