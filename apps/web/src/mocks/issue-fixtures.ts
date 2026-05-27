// issue-tracking BC 이슈 목록 MSW fixture 데이터
import type { IssueResponse, IssuePage } from '@/api/issues'

/** 이슈 단건 fixture — ATLAS-1 */
export const issueAtlas1Fixture: IssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '첫 번째 이슈 — 로그인 페이지 구현',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  version: 0,
  createdAt: '2026-01-01T09:00:00Z',
  updatedAt: null,
}

/** 이슈 단건 fixture — ATLAS-2 */
export const issueAtlas2Fixture: IssueResponse = {
  key: 'ATLAS-2',
  id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f',
  projectKey: 'ATLAS',
  summary: '두 번째 이슈 — 이슈 목록 페이지 UI 구현. 긴 요약 텍스트: 모바일 반응형 + 페이지네이션 + 빈 상태 안내 + 접근성 WCAG AA 준수.',
  currentStateKey: 'in_progress',
  reporterId: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a',
  version: 1,
  createdAt: '2026-01-02T10:00:00Z',
  updatedAt: '2026-01-03T11:00:00Z',
}

/** 이슈 단건 fixture — ATLAS-3 */
export const issueAtlas3Fixture: IssueResponse = {
  key: 'ATLAS-3',
  id: 'e5f6a7b8-c9d0-4e1f-ab2a-4c5d6e7f8a9b',
  projectKey: 'ATLAS',
  summary: '세 번째 이슈 — 이슈 상세 페이지 구현',
  currentStateKey: 'done',
  reporterId: 'f6a7b8c9-d0e1-4f2a-bc3b-5d6e7f8a9b0c',
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
