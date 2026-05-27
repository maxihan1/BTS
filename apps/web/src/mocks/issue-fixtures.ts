// issue-tracking BC 이슈 목록 MSW fixture 데이터
import type { IssueResponse, IssuePage } from '@/api/issues'

/** 이슈 단건 fixture — ATLAS-1 */
export const issueAtlas1Fixture: IssueResponse = {
  key: 'ATLAS-1',
  id: '11111111-1111-1111-1111-111111111111',
  projectKey: 'ATLAS',
  summary: '첫 번째 이슈 — 로그인 페이지 구현',
  currentStateKey: 'open',
  reporterId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  version: 0,
  createdAt: '2026-01-01T09:00:00Z',
  updatedAt: null,
}

/** 이슈 단건 fixture — ATLAS-2 */
export const issueAtlas2Fixture: IssueResponse = {
  key: 'ATLAS-2',
  id: '22222222-2222-2222-2222-222222222222',
  projectKey: 'ATLAS',
  summary: '두 번째 이슈 — 이슈 목록 페이지 UI 구현. 긴 요약 텍스트: 모바일 반응형 + 페이지네이션 + 빈 상태 안내 + 접근성 WCAG AA 준수.',
  currentStateKey: 'in_progress',
  reporterId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  version: 1,
  createdAt: '2026-01-02T10:00:00Z',
  updatedAt: '2026-01-03T11:00:00Z',
}

/** 이슈 단건 fixture — ATLAS-3 */
export const issueAtlas3Fixture: IssueResponse = {
  key: 'ATLAS-3',
  id: '33333333-3333-3333-3333-333333333333',
  projectKey: 'ATLAS',
  summary: '세 번째 이슈 — 이슈 상세 페이지 구현',
  currentStateKey: 'done',
  reporterId: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
  version: 2,
  createdAt: '2026-01-03T08:00:00Z',
  updatedAt: '2026-01-04T12:00:00Z',
}

/** Spring Page 형태의 이슈 목록 fixture — 3건, 1 페이지 */
export const issuePageFixture: IssuePage = {
  content: [issueAtlas1Fixture, issueAtlas2Fixture, issueAtlas3Fixture],
  totalElements: 3,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
}

/** 빈 이슈 목록 fixture */
export const emptyIssuePageFixture: IssuePage = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: true,
}

/** 2 페이지짜리 이슈 목록 — 첫 번째 페이지 */
export const issuePageFirstFixture: IssuePage = {
  content: [issueAtlas1Fixture, issueAtlas2Fixture],
  totalElements: 3,
  totalPages: 2,
  size: 2,
  number: 0,
  first: true,
  last: false,
  empty: false,
}

/** 2 페이지짜리 이슈 목록 — 두 번째 (마지막) 페이지 */
export const issuePageLastFixture: IssuePage = {
  content: [issueAtlas3Fixture],
  totalElements: 3,
  totalPages: 2,
  size: 2,
  number: 1,
  first: false,
  last: true,
  empty: false,
}
