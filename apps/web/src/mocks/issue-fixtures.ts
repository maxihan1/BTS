// issue-tracking BC 이슈 목록 MSW fixture 데이터
import type { IssueResponse, IssuePage } from '@/api/issues'

// ─────────────────────────────────────────────────────────────────────────────
// FR-SR-01 D6 — 필터 분별 시드용 상수 (B3 vacuous 차단)
//
// issuePageFixture 4건의 분포:
//   ATLAS-1  open      assigneeId=null         labels=[]           componentIds=[]
//   ATLAS-2  in_progress assigneeId=BOB_ID     labels=['bug']      componentIds=[]
//   ATLAS-3  done      assigneeId=ALICE_ID     labels=['frontend'] componentIds=[COMP_A_ID]
//   ATLAS-5  in_review assigneeId=null         labels=[]           componentIds=[]
//
// ★ 주의: userAliceFixture.id == issueAtlas2Fixture.id (UUID 충돌).
//   assigneeId에는 userAliceFixture.id를 그대로 사용하되,
//   이슈 id 필드(issueAtlas2Fixture.id)와 혼동하지 않도록 주석으로 명시.
//   BOB_ID = userBobFixture.id (충돌 없음).
//   COMP_A_ID = 보드 컴포넌트A UUID (board-fixtures.ts와 동기화).
// ─────────────────────────────────────────────────────────────────────────────

/** userBobFixture.id 와 동기화 — assignee 분별 시드 */
export const ISSUE_FILTER_BOB_ID = 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a'

/**
 * userAliceFixture.id 와 동기화 — assignee 분별 시드.
 * ★ 이 값은 issueAtlas2Fixture.id 와 같은 UUID이므로 이슈 id 용도로 사용 금지.
 */
export const ISSUE_FILTER_ALICE_ID = 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f'

/** 컴포넌트A UUID — board-fixtures.ts COMP_A 상수와 동기화 */
export const ISSUE_FILTER_COMP_A_ID = '40000000-0000-4000-8000-000000000001'

/** 이슈 단건 fixture — ATLAS-1 (status=open, 미배정, 라벨 없음, 컴포넌트 없음) */
export const issueAtlas1Fixture: IssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '첫 번째 이슈 — 로그인 페이지 구현',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  // FR-SR-01 B3 분별 시드: 미배정(null)
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
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
  // FR-SR-01 B3 분별 시드: 라벨 없음
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
}

/**
 * 이슈 단건 fixture — ATLAS-2 (status=in_progress, bob 담당, bug 라벨, 컴포넌트 없음).
 * typeId=2 → story (issue-type-fixtures id=2: key='story').
 * ★ id 필드 값이 userAliceFixture.id와 동일 UUID — 이슈 id이므로 혼용 금지.
 */
export const issueAtlas2Fixture: IssueResponse = {
  key: 'ATLAS-2',
  id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f',
  projectKey: 'ATLAS',
  summary: '두 번째 이슈 — 이슈 목록 페이지 UI 구현. 긴 요약 텍스트: 모바일 반응형 + 페이지네이션 + 빈 상태 안내 + 접근성 WCAG AA 준수.',
  currentStateKey: 'in_progress',
  reporterId: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a',
  // FR-SR-01 B3 분별 시드: bob 담당
  assigneeId: ISSUE_FILTER_BOB_ID,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
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
  // FR-SR-01 B3 분별 시드: bug 라벨
  labels: ['bug'],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
}

/**
 * 이슈 단건 fixture — ATLAS-3 (status=done, alice 담당, frontend 라벨, 컴포넌트A 소속).
 * typeId=3 → task (issue-type-fixtures id=3: key='task').
 */
export const issueAtlas3Fixture: IssueResponse = {
  key: 'ATLAS-3',
  id: 'e5f6a7b8-c9d0-4e1f-ab2a-4c5d6e7f8a9b',
  projectKey: 'ATLAS',
  summary: '세 번째 이슈 — 이슈 상세 페이지 구현',
  currentStateKey: 'done',
  reporterId: 'f6a7b8c9-d0e1-4f2a-bc3b-5d6e7f8a9b0c',
  // FR-SR-01 B3 분별 시드: alice 담당 (ISSUE_FILTER_ALICE_ID = userAliceFixture.id)
  assigneeId: ISSUE_FILTER_ALICE_ID,
  // FR-SR-01 B3 분별 시드: 컴포넌트A 소속
  componentIds: [ISSUE_FILTER_COMP_A_ID],
  affectsVersionIds: [],
  fixVersionIds: [],
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
  // FR-SR-01 B3 분별 시드: frontend 라벨
  labels: ['frontend'],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
}

/**
 * 이슈 단건 fixture — ATLAS-5 (status=in_review, 미배정, 라벨 없음, 컴포넌트 없음).
 * FR-IS-07 E2E용 — in_review 상태 → Approve → done 전이 검증.
 */
export const issueAtlas5Fixture: IssueResponse = {
  key: 'ATLAS-5',
  id: 'a5b6c7d8-e9f0-4a1b-8c2d-3e4f5a6b7c8d',
  projectKey: 'ATLAS',
  summary: '다섯 번째 이슈 — 리뷰 중 (FR-IS-07 종료 결의안 E2E 검증용)',
  currentStateKey: 'in_review',
  reporterId: 'b6c7d8e9-f0a1-4b2c-9d3e-4f5a6b7c8d9e',
  // FR-SR-01 B3 분별 시드: 미배정(null)
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
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
  // FR-SR-01 B3 분별 시드: 라벨 없음
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
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

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-06 Phase 5 PR18 Task 6(E2E) — 정렬 유지 페이지 이동(S3) 검증용 추가 이슈 20건
//
// 기본 4건(ATLAS-1/2/3/5)만으로는 프론트 기본 페이지 크기(size=20)를 넘지 못해
// 항상 1페이지로 응답되므로, "다음 페이지" 버튼을 실제로 클릭하는 e2e를 만들 수 없다.
// issue-handlers.ts의 LS_KEY_PAGINATION_EXTRA_ISSUES 플래그가 설정된 요청에만
// 이 20건을 추가로 포함시켜 총 24건(2페이지)을 만든다. 플래그 미설정 시 기존
// 4건 응답에 전혀 영향을 주지 않는다(무회귀).
//
// 키를 'ATLAS-P01'~'ATLAS-P20'(zero-padded 2자리)로 부여 — 기본 4건의 키
// 'ATLAS-1'~'ATLAS-5'는 문자열 비교상 항상 'ATLAS-P*'보다 앞선다('5' < 'P').
// 'P' 접두 뒤 zero-padding 덕분에 문자열 정렬이 곧 의도한 순서와 일치한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 페이지네이션 검증용 추가 이슈 1건을 생성한다.
 * issueAtlas1Fixture를 기반으로 key/id/summary만 인덱스별로 교체한다.
 *
 * @param index 1~20 범위의 순번
 */
function buildPaginationExtraIssue(index: number): IssueResponse {
  const suffix = String(index).padStart(2, '0')
  return {
    ...issueAtlas1Fixture,
    key: `ATLAS-P${suffix}`,
    id: `b0000000-0000-4000-8000-0000000000${suffix}`,
    summary: `페이지네이션 검증용 이슈 ${suffix}`,
  }
}

/** ATLAS-P01~ATLAS-P20 — 기본 4건과 합쳐 총 24건(size=20 기준 2페이지)을 만드는 추가 fixture */
export const issueAtlasPaginationExtraFixtures: IssueResponse[] = Array.from(
  { length: 20 },
  (_, i) => buildPaginationExtraIssue(i + 1),
)

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
  affectsVersionIds: [],
  fixVersionIds: [],
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
  restrictedFields: [],
  noneditableFields: [],
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
  affectsVersionIds: [],
  fixVersionIds: [],
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
  restrictedFields: [],
  noneditableFields: [],
}

/**
 * 에픽 이슈 fixture — ATLAS-EPIC-1 (typeId=4, typeKey='epic')
 * E2E epic-children.spec.ts: EpicChildrenSection 자식 연결/해제 검증용.
 * issueFixtureMap에 등록되어 GET /api/v1/issues/ATLAS-EPIC-1 로 조회 가능해야 한다.
 */
export const issueAtlasEpic1Fixture: IssueResponse = {
  key: 'ATLAS-EPIC-1',
  id: 'e0000001-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  summary: '에픽 E2E 테스트 — 자식 이슈 연결/해제 검증용',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
  version: 0,
  createdAt: '2026-01-10T09:00:00Z',
  updatedAt: null,
  typeId: 4,
  typeKey: 'epic',
  typeName: '에픽',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
}

/**
 * 에픽 자식 이슈 fixture — ATLAS-CHILD-1 (typeKey='task')
 * E2E epic-children.spec.ts: EpicChildrenSection 자식 연결 대상 이슈.
 * issueFixtureMap에 등록되어야 connectEpicChildHandler가 resolveIssue로 찾을 수 있다.
 */
export const issueAtlasChild1Fixture: IssueResponse = {
  key: 'ATLAS-CHILD-1',
  id: 'c0000001-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  summary: '자식 이슈 E2E 테스트 — 에픽 연결 대상',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
  version: 0,
  createdAt: '2026-01-10T10:00:00Z',
  updatedAt: null,
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
  restrictedFields: [],
  noneditableFields: [],
}

/**
 * 소속 에픽 지정 검증용 일반 이슈 fixture — ATLAS-FOR-EPIC (typeKey='task')
 * E2E epic-children.spec.ts: EpicSection (IssueLinksPanel) 소속 에픽 지정/해제 검증.
 * 에픽 타입이 아닌 일반 이슈이므로 showEpicSection=true가 렌더됨.
 */
export const issueAtlasForEpicFixture: IssueResponse = {
  key: 'ATLAS-FOR-EPIC',
  id: 'f0000001-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  summary: '소속 에픽 지정 E2E 테스트용 일반 이슈',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
  version: 0,
  createdAt: '2026-01-10T11:00:00Z',
  updatedAt: null,
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
  restrictedFields: [],
  noneditableFields: [],
}

/**
 * 이슈 단건 fixture — ATLAS-MENTION (FR-MN-01 D7 E2E 전용, 멘션 강조 검증)
 *
 * description에 다음 3종을 포함:
 *   - @alice  → S1: .mention 강조 대상
 *   - `@code` → S2: 코드스팬 내부 @는 강조 제외
 *   - user@example.com → S2: 이메일 @ 는 강조 제외
 *
 * issueFixtureMap에 등록되어 GET /api/v1/issues/ATLAS-MENTION 로 조회 가능.
 */
export const issueAtlasMentionFixture: IssueResponse = {
  key: 'ATLAS-MENTION',
  id: 'a2b3c4d5-e6f7-4a8b-9c0d-1e2f3a4b5c6d',
  projectKey: 'ATLAS',
  summary: '멘션 강조 표시 검증용 이슈 (FR-MN-01 D7)',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
  version: 0,
  createdAt: '2026-06-27T09:00:00Z',
  updatedAt: null,
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  // S1: @alice는 강조 대상. S2: `@code`(코드스팬), user@example.com(이메일)은 강조 제외.
  description: '@alice 확인 부탁드립니다. `@code` 는 코드입니다. user@example.com 이메일도 제외됩니다.',
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
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
