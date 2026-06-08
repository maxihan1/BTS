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
  assigneeId: null,
  componentIds: [],
  version: 0,
  createdAt: '2026-01-01T09:00:00Z',
  updatedAt: null,
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
}

/** 이슈 단건 fixture — ATLAS-2 (typeId=2 → story, issue-type-fixtures id=2: key='story') */
export const issueAtlas2Fixture: IssueResponse = {
  key: 'ATLAS-2',
  id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f',
  projectKey: 'ATLAS',
  summary: '두 번째 이슈 — 이슈 목록 페이지 UI 구현. 긴 요약 텍스트: 모바일 반응형 + 페이지네이션 + 빈 상태 안내 + 접근성 WCAG AA 준수.',
  currentStateKey: 'in_progress',
  reporterId: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a',
  assigneeId: null,
  componentIds: [],
  version: 1,
  createdAt: '2026-01-02T10:00:00Z',
  updatedAt: '2026-01-03T11:00:00Z',
  typeId: 2,
  typeKey: 'story',
  typeName: '스토리',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
}

/** 이슈 단건 fixture — ATLAS-3 (typeId=3 → task, issue-type-fixtures id=3: key='task') */
export const issueAtlas3Fixture: IssueResponse = {
  key: 'ATLAS-3',
  id: 'e5f6a7b8-c9d0-4e1f-ab2a-4c5d6e7f8a9b',
  projectKey: 'ATLAS',
  summary: '세 번째 이슈 — 이슈 상세 페이지 구현',
  currentStateKey: 'done',
  reporterId: 'f6a7b8c9-d0e1-4f2a-bc3b-5d6e7f8a9b0c',
  assigneeId: null,
  componentIds: [],
  version: 2,
  createdAt: '2026-01-03T08:00:00Z',
  updatedAt: '2026-01-04T12:00:00Z',
  typeId: 3,
  typeKey: 'task',
  typeName: '작업',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
}

/** 이슈 단건 fixture — ATLAS-5 (FR-IS-07 E2E용, in_review 상태 → Approve → done 전이 검증) */
export const issueAtlas5Fixture: IssueResponse = {
  key: 'ATLAS-5',
  id: 'a5b6c7d8-e9f0-4a1b-8c2d-3e4f5a6b7c8d',
  projectKey: 'ATLAS',
  summary: '다섯 번째 이슈 — 리뷰 중 (FR-IS-07 종료 결의안 E2E 검증용)',
  currentStateKey: 'in_review',
  reporterId: 'b6c7d8e9-f0a1-4b2c-9d3e-4f5a6b7c8d9e',
  assigneeId: null,
  componentIds: [],
  version: 1,
  createdAt: '2026-01-05T10:00:00Z',
  updatedAt: '2026-01-06T08:00:00Z',
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
}

/** Spring Page 형태의 이슈 목록 fixture — 4건(ATLAS-5 포함), 1 페이지 */
export const issuePageFixture: IssuePage = {
  content: [issueAtlas1Fixture, issueAtlas2Fixture, issueAtlas3Fixture, issueAtlas5Fixture],
  totalElements: 4,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
}

/** 이슈 단건 fixture — ATLAS-4 (S6 검증용 closed 상태) */
export const issueAtlas4Fixture: IssueResponse = {
  key: 'ATLAS-4',
  id: 'f7a8b9c0-d1e2-4f3a-bc4b-6e7f8a9b0c1d',
  projectKey: 'ATLAS',
  summary: '네 번째 이슈 — 종료된 이슈 (closed 상태 전이 검증용)',
  currentStateKey: 'closed',
  reporterId: 'a8b9c0d1-e2f3-4a4b-8d5c-7f8a9b0c1d2e',
  assigneeId: null,
  componentIds: [],
  version: 3,
  createdAt: '2026-01-04T07:00:00Z',
  updatedAt: '2026-01-05T13:00:00Z',
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
}

/** 이슈 단건 fixture — ATLAS-NOWF (워크플로우 미설정 이슈, 422 E2E 검증용) */
export const issueAtlasNoWorkflowFixture: IssueResponse = {
  key: 'ATLAS-NOWF',
  id: 'b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e',
  projectKey: 'ATLAS',
  summary: '워크플로우가 설정되지 않은 이슈 (422 미설정 UI 검증용)',
  currentStateKey: 'open',
  reporterId: 'c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f',
  assigneeId: null,
  componentIds: [],
  version: 0,
  createdAt: '2026-01-06T09:00:00Z',
  updatedAt: null,
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
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
