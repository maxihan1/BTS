// issue-tracking BC MSW mock handlers — GET 목록/단건 + POST 생성 + PATCH 수정 + PATCH /assignee + DELETE 삭제
// 소프트 삭제 stateful: deletedKeys 와 createdIssues 로 모듈-스코프 상태 유지 (E2E 검증 gap-H + E2E-1 happy path).
// FR-PM-07: restrictedFields/noneditableFields 시나리오 시드 — field-permission store 파생.
// FR-MN-01 D7: description PATCH 시 @멘션 추출 → Inbox 파생 (msw-derived-behavior-shared-store-e2e 교훈)
import { http, HttpResponse } from 'msw'
import { setIssueEstimate, getIssueEstimate } from './worklog-handlers'
import { getStoredComponentsByIds } from './component-handlers'
import { getStoredProjectLead } from './project-lead-handlers'
import { getFieldPermissionsForProject } from './field-permission-handlers'
import { AUTH_USERS } from './auth-fixtures'
import { appendToInbox } from './inbox-handlers'
import { appendCreatedIssueToBacklog, BACKLOG_EPIC_FIXTURES } from './backlog-fixtures'
import type { BacklogEpicFixture } from './backlog-fixtures'
import type { InboxItem } from '@/api/inbox'
import {
  issuePageFixture,
  issueAtlas1Fixture,
  issueAtlas2Fixture,
  issueAtlas3Fixture,
  issueAtlas4Fixture,
  issueAtlas5Fixture,
  issueAtlasNoWorkflowFixture,
  issueAtlasEpic1Fixture,
  issueAtlasChild1Fixture,
  issueAtlasForEpicFixture,
  issueAtlasMentionFixture,
  issueAtlasPaginationExtraFixtures,
} from './issue-fixtures'
import { allIssueTypeFixtures } from './issue-type-fixtures'
import { softwareDefaultFixture } from './workflow-fixtures'
import { userListFixture } from './user-fixtures'
import { ISSUE_SORT_FIELDS } from '@/api/issues'
import type { IssueResponse, IssuePage, IssueSortField } from '@/api/issues'

/**
 * 이슈 생성 성공 응답 픽스처.
 * typeId=3 → task (issue-type-fixtures id=3: key='task', name='작업').
 */
export const createdIssueFixture = {
  key: 'ATLAS-42',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '새 이슈 제목',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  /** FR-VR-03 — 영향 버전 기본값. */
  affectsVersionIds: [] as string[],
  /** FR-VR-03 — 수정 버전 기본값. */
  fixVersionIds: [] as string[],
  version: 0,
  createdAt: '2026-05-27T00:00:00Z',
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
  securityLevelId: null,
  /** FR-IS-10 — 커스텀 필드 기본값. 생성 시 customFields 미전달이면 백엔드 기본값 {} 에코. */
  customFields: {} as Record<string, unknown>,
  /** FR-PM-07 — 생성 이슈는 열람 마스킹 없음(기본 빈 배열). */
  restrictedFields: [],
  /** FR-PM-07 — 생성 이슈는 편집 제한 없음(기본 빈 배열). */
  noneditableFields: [],
}

const issueFixtureMap: Record<string, IssueResponse> = {
  'ATLAS-1': issueAtlas1Fixture,
  'ATLAS-2': issueAtlas2Fixture,
  'ATLAS-3': issueAtlas3Fixture,
  'ATLAS-4': issueAtlas4Fixture,
  'ATLAS-5': issueAtlas5Fixture,
  'ATLAS-NOWF': issueAtlasNoWorkflowFixture,
  // FR-EP-01 E2E용 에픽/자식 이슈 fixture
  'ATLAS-EPIC-1': issueAtlasEpic1Fixture,
  'ATLAS-CHILD-1': issueAtlasChild1Fixture,
  'ATLAS-FOR-EPIC': issueAtlasForEpicFixture,
  // FR-MN-01 D7 E2E용 멘션 강조 검증 fixture
  'ATLAS-MENTION': issueAtlasMentionFixture,
}

/**
 * 백로그 에픽 픽스처 하나를 이슈 상세 응답으로 만든다 (FR-UX-13 F16).
 *
 * `useBacklogEpics` 는 에픽 이름을 **이슈 상세 조회 하나로만** 얻는다(에픽 목록 API 가 없다).
 * 그래서 백로그 카드에 `epicKey` 를 심어도 이 응답이 없으면 패널이 키를 그대로 보여 준다 —
 * 화면은 안 깨지는데 「사람이 읽는 이름」만 조용히 사라지는 형태다.
 *
 * @param epic 이름의 출처인 백로그 에픽 픽스처
 * @param index 픽스처 배열에서의 순번 — UUID 꼬리에 실어 키마다 다른 id 를 만든다
 */
function buildBacklogEpicIssue(epic: BacklogEpicFixture, index: number): IssueResponse {
  return {
    key: epic.key,
    id: `e0000002-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
    projectKey: 'ATLAS',
    summary: epic.summary,
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
}

/**
 * 백로그 에픽의 이슈 상세 — 키 → 응답.
 *
 * ★`issueFixtureMap` 에 **넣지 않는다.** 그 맵은 이슈 목록(`GET /api/v1/issues`)의 모수이자
 *   클론 키 채번(`maxNum`)의 모수라, 거기에 2건을 더하면 목록 건수를 세는 기존 테스트가
 *   함께 흔들린다. 에픽은 백로그 카드가 아니라 **이름 해석 대상**이므로 단건 조회 전용으로
 *   분리하고, {@link resolveIssue} 체인의 맨 끝에만 붙인다.
 */
const backlogEpicIssueMap: Record<string, IssueResponse> = Object.fromEntries(
  BACKLOG_EPIC_FIXTURES.map((epic, index) => [epic.key, buildBacklogEpicIssue(epic, index)]),
)

/** E2E 시나리오용 localStorage 키 — S4 재오픈 검증 시 done+resolution 이슈로 응답 분기 */
const LS_KEY_RESOLUTION_ISSUE = '__bts_e2e_resolution_issue'

/**
 * FR-PM-07 E2E 시나리오용 localStorage 키 — 'true' 세팅 시 이슈 단건 GET에서
 * field-permission store를 기반으로 restrictedFields/noneditableFields를 파생하여 채운다.
 * Playwright addInitScript로 goto 전에 설정하면 필드 권한 적용 시나리오를 검증할 수 있다.
 */
export const LS_KEY_FIELD_PERMISSION_SCENARIO = '__bts_e2e_field_permission_scenario'

/**
 * E2E 테스트 전용 localStorage 키 — 권한없는 이슈 키 목록(쉼표 구분).
 * Playwright addInitScript 로 goto 전에 설정하면 해당 키 GET 단건 요청이 404 반환.
 * permissionDeniedKeys(인메모리 Set)가 없을 때 fallback으로 읽어 리로드 생존을 보장한다.
 */
export const LS_KEY_PERMISSION_DENIED_KEYS = '__bts_e2e_permission_denied_keys'

/**
 * FR-UX-06 Phase 5 PR18 Task 6(E2E) 전용 localStorage 키 — 'true' 세팅 시 이슈 목록 GET 응답에
 * issueAtlasPaginationExtraFixtures 20건을 추가로 포함시켜 총 24건(size=20 기준 2페이지)을 만든다.
 * "정렬 유지 페이지 이동"(S3) 검증은 실제 2페이지가 있어야 "다음" 버튼을 클릭할 수 있다.
 * 미설정 시 기존 4건 응답에 전혀 영향을 주지 않는다(무회귀).
 */
export const LS_KEY_PAGINATION_EXTRA_ISSUES = '__bts_e2e_issue_pagination_extra'

/**
 * 이슈 타입 카탈로그 lookup — id 로 활성 타입 조회.
 * 존재하지 않으면 undefined 반환.
 */
function lookupIssueType(
  id: number,
): { id: number; key: string; name: string } | undefined {
  return allIssueTypeFixtures.find((t) => t.id === id)
}

// 소프트 삭제된 이슈 키 집합 — DELETE 핸들러가 add, GET 목록/단건이 필터링 (gap-H).
const deletedKeys = new Set<string>()

// E2E-1 happy path 용 — POST 로 생성된 이슈를 GET 목록/단건 에서 조회 가능하도록 stateful 유지.
const createdIssues = new Map<string, IssueResponse>()

// 이슈 현재 상태 오버라이드 — issueFixtureMap 원본 불변 유지 + 전환/수정(PATCH) 결과 반영.
// key: 이슈 키, value: 갱신된 IssueResponse (전환 또는 타입/요약 수정 후)
const issueOverrides = new Map<string, IssueResponse>()

/**
 * 권한없음 시나리오 이슈 키 집합 (E2E 단위 공용).
 * addPermissionDeniedKey(key) 로 추가된 키는 GET 단건에서 404 반환.
 * resetIssueState() / clearPermissionDeniedKeys() 로 초기화.
 */
const permissionDeniedKeys = new Set<string>()

/**
 * 권한없는 이슈 키를 시나리오 집합에 추가 — 이후 GET 단건 요청이 404를 반환한다.
 * E2E(Playwright addInitScript + localStorage 플래그 → 핸들러에서 읽음)와
 * 단위 테스트(직접 호출) 양쪽에서 동일한 시나리오 경로를 검증한다.
 */
export function addPermissionDeniedKey(key: string): void {
  permissionDeniedKeys.add(key)
}

/** 권한없음 시나리오 집합 초기화 — 각 테스트 afterEach 에서 호출. */
export function clearPermissionDeniedKeys(): void {
  permissionDeniedKeys.clear()
}

/** E2E / 단위 테스트 격리용 — 모듈-스코프 state 초기화. 각 test setup 에서 호출. */
export function resetIssueState(): void {
  deletedKeys.clear()
  createdIssues.clear()
  issueOverrides.clear()
  permissionDeniedKeys.clear()
}

/**
 * FR-SR-01 B2 — 이슈가 query param 필터 조건을 모두 만족하는지 판단한다.
 *
 * 필드 내(status 복수, assignee 복수, label 복수, component 복수)는 OR,
 * 필드 간(status vs assignee vs label vs component)은 AND.
 *
 * assignee=unassigned → assigneeId가 null인 이슈만 통과.
 * 빈 param(getAll 빈 배열) → 해당 필드 조건 없음(전체 통과).
 *
 * @param issue 평가할 이슈 응답
 * @param params URLSearchParams — request URL에서 파싱한 파라미터
 */
function matchesIssueFilter(issue: IssueResponse, params: URLSearchParams): boolean {
  const statusKeys = params.getAll('status')
  if (statusKeys.length > 0) {
    if (!statusKeys.includes(issue.currentStateKey)) return false
  }

  const assignees = params.getAll('assignee')
  if (assignees.length > 0) {
    const passes = assignees.some((a) => {
      if (a === 'unassigned') return issue.assigneeId === null
      return issue.assigneeId === a
    })
    if (!passes) return false
  }

  const labels = params.getAll('label')
  if (labels.length > 0) {
    const passes = labels.some((l) => issue.labels.includes(l))
    if (!passes) return false
  }

  const components = params.getAll('component')
  if (components.length > 0) {
    const passes = components.some((c) => issue.componentIds.includes(c))
    if (!passes) return false
  }

  return true
}

/**
 * FR-UX-06 Phase 5 PR18 Task 6(E2E) — `sort` 쿼리 파라미터(`<field>,<dir>`)를 파싱해
 * 이슈 배열을 정렬한다. 프론트(api/issues.ts ISSUE_SORT_FIELDS)·백엔드 whitelist와
 * 동일한 5개 필드(key·summary·priority·createdAt·updatedAt)만 허용한다.
 * 값이 없거나 형식/필드가 유효하지 않으면 원본 순서를 그대로 유지한다
 * (백엔드 listWithType의 EC1 fallback과 동일한 관대 처리 — matchesIssueFilter와 같은 기조).
 *
 * @param content 정렬 대상 배열 (원본을 변경하지 않고 새 배열 반환)
 * @param sortParam URL의 sort 파라미터 원문. null이면 정렬하지 않는다.
 */
function applySortParam(content: IssueResponse[], sortParam: string | null): IssueResponse[] {
  if (sortParam === null) return content
  const [field, dir] = sortParam.split(',')
  if (field === undefined || dir === undefined) return content
  if (!(ISSUE_SORT_FIELDS as readonly string[]).includes(field)) return content
  if (dir !== 'asc' && dir !== 'desc') return content

  const sortField = field as IssueSortField
  const sorted = [...content].sort((a, b) => {
    const av = a[sortField]
    const bv = b[sortField]
    if (av === null && bv === null) return 0
    if (av === null) return 1
    if (bv === null) return -1
    if (av < bv) return -1
    if (av > bv) return 1
    return 0
  })
  return dir === 'desc' ? sorted.reverse() : sorted
}

/**
 * FR-UX-06 Phase 5 PR18 Task 6(E2E) — `page`/`size` 쿼리 파라미터 기준으로 정렬된 content를
 * 슬라이스하고 Spring Page 메타(totalPages/first/last)를 계산한다.
 *
 * 기본 4건 시나리오는 size=20 > totalElements라 항상 1페이지로 응답돼 기존 e2e/단위
 * 테스트에 영향이 없다(무회귀). LS_KEY_PAGINATION_EXTRA_ISSUES 플래그로 24건이 되면
 * 실제 2페이지가 생겨 "정렬 유지 페이지 이동"(S3)을 검증할 수 있다.
 *
 * @param content 정렬까지 끝난 필터링 결과 전체 (슬라이스 전)
 * @param params page/size를 읽을 URLSearchParams
 */
function paginateSortedContent(content: IssueResponse[], params: URLSearchParams): IssuePage {
  const page = Number(params.get('page') ?? '0')
  const size = Number(params.get('size') ?? '20')
  const totalElements = content.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  const sliced = content.slice(page * size, page * size + size)
  return {
    content: sliced,
    totalElements,
    totalPages,
    size,
    number: page,
    first: page === 0,
    last: page >= totalPages - 1,
    empty: totalElements === 0,
  }
}

/**
 * 필터·정렬·페이지네이션 파라미터를 적용한 이슈 목록 페이지를 생성한다.
 *
 * 조회 우선순위: issueOverrides → issuePageFixture (단건 GET과 동일 순서).
 * PATCH 후 목록 재조회 시 최신 상태(assignee/labels 등)를 필터에 올바르게 반영한다.
 * createdIssues(POST 생성 이슈)도 포함한다. LS_KEY_PAGINATION_EXTRA_ISSUES 플래그가
 * 설정되면 issueAtlasPaginationExtraFixtures 20건도 포함한다(Task 6, S3).
 *
 * @param params 필터/정렬/페이지 URLSearchParams (없으면 전체 1페이지 반환)
 */
function buildFilteredPage(params?: URLSearchParams): IssuePage {
  const sp = params ?? new URLSearchParams()
  // 오버라이드 우선 적용 — PATCH 후 변경된 assignee/labels/componentIds가 필터에 반영됨
  const fixtureContent = issuePageFixture.content
    .filter((i) => !deletedKeys.has(i.key))
    .map((i) => issueOverrides.get(i.key) ?? i)
    .filter((i) => matchesIssueFilter(i, sp))
  const includePaginationExtra = globalThis.localStorage?.getItem(LS_KEY_PAGINATION_EXTRA_ISSUES) === 'true'
  const extraContent = includePaginationExtra
    ? issueAtlasPaginationExtraFixtures.filter((i) => matchesIssueFilter(i, sp))
    : []
  const createdContent = Array.from(createdIssues.values())
    .filter((i) => !deletedKeys.has(i.key))
    .filter((i) => matchesIssueFilter(i, sp))
  const content = applySortParam(
    [...fixtureContent, ...extraContent, ...createdContent],
    sp.get('sort'),
  )
  return paginateSortedContent(content, sp)
}

/**
 * FR-UX-07 S7 전용 sentinel 프로젝트 키 — 이 키로 조회하면 BROWSE 권한 없음(403)을 흉내낸다.
 * `createIssueHandler`의 `'INVALID'`(PROJECT_NOT_FOUND) sentinel과 동형 관례.
 */
const ACCESS_DENIED_PROJECT_KEY = 'NOPERM'

/**
 * GET /api/v1/issues — 이슈 목록 페이징 조회.
 * FR-SR-01 B2: query param(status/assignee/label/component)을 읽어 필터링 후 반환.
 * FR-UX-06 Phase 5 PR18 Task 6: sort(`<field>,<dir>`) 정렬 + page/size 실제 페이지네이션 적용.
 * 소프트 삭제된 이슈는 응답에서 제외 (gap-H).
 * FR-UX-07 S7: projectKey가 {@link ACCESS_DENIED_PROJECT_KEY}('NOPERM')이면 403을 반환해
 * "명시 지정이 실패하면 조용히 대체하지 않고 에러를 표시한다"를 재현한다.
 */
const listIssuesHandler = http.get('/api/v1/issues', ({ request }) => {
  const params = new URL(request.url).searchParams
  if (params.get('projectKey') === ACCESS_DENIED_PROJECT_KEY) {
    return HttpResponse.json(
      { errorCode: 'ACCESS_DENIED', message: '이 작업을 수행할 권한이 없습니다.' },
      { status: 403 },
    )
  }
  return HttpResponse.json(buildFilteredPage(params))
})

/**
 * GET /api/v1/issues/:key — 이슈 단건 조회 (`{ data: IssueResponse }` 래퍼).
 * 소프트 삭제된 키는 404 (production backend 의 `deleted_at IS NOT NULL` 필터링 시뮬).
 * POST 로 생성된 이슈도 조회 가능.
 */
const getIssueHandler = http.get('/api/v1/issues/:key', ({ params }) => {
  const key = params['key'] as string
  if (deletedKeys.has(key)) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  // 시나리오 S-PM05: 권한없는 이슈 → 미존재 동일 UX (backend 403/404 동일 처리 계약).
  // 단위 테스트: addPermissionDeniedKey(key) 직접 호출로 트리거.
  // E2E: addInitScript 로 LS_KEY_PERMISSION_DENIED_KEYS localStorage 에 쉼표 구분 키 목록 설정.
  //      인메모리 Set(permissionDeniedKeys) 또는 localStorage 어느 쪽이든 매칭되면 404 반환.
  const lsDeniedRaw = globalThis.localStorage?.getItem(LS_KEY_PERMISSION_DENIED_KEYS) ?? ''
  const lsDeniedKeys = lsDeniedRaw ? lsDeniedRaw.split(',').map((k) => k.trim()) : []
  if (permissionDeniedKeys.has(key) || lsDeniedKeys.includes(key)) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  // 조회 체인은 resolveIssue 하나만 쓴다 — 같은 순서를 여기 한 벌 더 적으면 출처가 늘 때
  // 둘이 갈라진다 (백로그 에픽 상세가 정확히 그 사례였다).
  let found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }

  // S4 E2E 플래그: done+resolution(Fixed) 이슈로 응답 분기 (오버라이드가 없는 경우만)
  // 오버라이드가 있으면(전환 후 refetch) 플래그 무시 — stateful 결과 우선
  if (
    key === 'ATLAS-5' &&
    !issueOverrides.has(key) &&
    globalThis.localStorage?.getItem(LS_KEY_RESOLUTION_ISSUE) === 'done-with-resolution'
  ) {
    found = {
      ...found,
      currentStateKey: 'done',
      resolution: { id: '00000000-0000-4000-8000-000000000001', key: 'fixed', name: 'Fixed' },
      version: found.version,
    }
  }

  // 단건 GET — descriptionHtml 을 description 기반으로 채워 반환 (목록 API는 null 그대로)
  // FR-PM-07: field-permission store 파생 시나리오 활성화 시 restrictedFields/noneditableFields 채움
  const fieldPermissionScenario =
    globalThis.localStorage?.getItem(LS_KEY_FIELD_PERMISSION_SCENARIO) === 'true'

  let resolvedRestrictedFields = found.restrictedFields
  let resolvedNoneditableFields = found.noneditableFields
  if (fieldPermissionScenario) {
    const permissions = getFieldPermissionsForProject(found.projectKey)
    resolvedRestrictedFields = permissions
      .filter((fp) => fp.accessLevel === 'VIEW')
      .map((fp) => fp.fieldKey)
    resolvedNoneditableFields = permissions
      .filter((fp) => fp.accessLevel === 'EDIT')
      .map((fp) => fp.fieldKey)
  }

  // estimateStore에 설정된 값이 있으면 issueOverrides보다 우선 사용.
  // worklog POST 자동차감 후 remainingEstimateSeconds가 estimateStore에 업데이트되면
  // GET 이슈 refetch 시 이 값이 IssueEstimatePanel에 반영된다.
  const liveEstimate = getIssueEstimate(key)
  const withHtml: IssueResponse = {
    ...found,
    descriptionHtml: renderDescriptionHtml(found.description),
    restrictedFields: resolvedRestrictedFields,
    noneditableFields: resolvedNoneditableFields,
    originalEstimateSeconds:
      liveEstimate.originalEstimateSeconds !== null
        ? liveEstimate.originalEstimateSeconds
        : found.originalEstimateSeconds,
    remainingEstimateSeconds:
      liveEstimate.remainingEstimateSeconds !== null
        ? liveEstimate.remainingEstimateSeconds
        : found.remainingEstimateSeconds,
  }
  return HttpResponse.json({ data: withHtml })
})

/**
 * POST /api/v1/issues — 이슈 생성 핸들러.
 * projectKey 가 'INVALID' 이면 PROJECT_NOT_FOUND(404) 반환.
 * 그 외는 201 + { data: 요청 값이 반영된 IssueResponse } 반환.
 *
 * FR-CM-03: 요청 componentIds를 응답에 에코.
 * 백엔드 default-assignee resolve 규칙(미할당일 때만):
 *   componentStore(component-handlers.ts 단일 출처)에 정보가 없으면 assigneeId=null 유지.
 *   X-MSW-Seed-Components 헤더로 시드된 컴포넌트 중 리드 보유 컴포넌트를
 *   name 오름차순 → id 오름차순(tiebreak) 정렬 후 첫 번째의 leadUserId가 assigneeId로 에코된다.
 *
 * FR-UX-09 F2: `typeId`·`description`·`priority`·`labels` 를 요청에서 읽어 응답에 반영한다.
 *   **fixture 스프레드로 두면 요청과 무관하게 같은 값이 돌아와, 프론트가 아무것도 안 보내도
 *   상위 테스트가 통과하는 가짜 그린이 된다** (learnings 2026-06-25 동형).
 *
 * FR-UX-09 F2: `assigneeId` 는 백엔드 `JsonNullable` 3-state 라 **키 존재**로 분기한다
 *   (ADR 2026-07-31 D-2). 키가 없을 때만 위 default-assignee resolve 를 돌린다 —
 *   명시 `null` 은 「자동 배정을 끄고 미할당 확정」이므로 resolve 를 건너뛰어야 한다.
 */
const createIssueHandler = http.post('/api/v1/issues', async ({ request }) => {
  const body = await request.clone().json() as {
    projectKey?: string
    summary?: string
    componentIds?: string[]
    securityLevelId?: string | null
    customFields?: Record<string, unknown>
    typeId?: number
    description?: string
    assigneeId?: string | null
    priority?: number
    labels?: string[]
  }
  if (body.projectKey === 'INVALID') {
    return HttpResponse.json(
      { errorCode: 'PROJECT_NOT_FOUND', message: '프로젝트를 찾을 수 없습니다' },
      { status: 404 },
    )
  }
  // FR-UX-09 F2 — 미존재 사용자 시뮬레이션. 백엔드는 422 ASSIGNEE_NOT_FOUND 로 거부한다.
  if (body.assigneeId === MOCK_ASSIGNEE_NOT_FOUND) {
    return HttpResponse.json(
      { errorCode: 'ASSIGNEE_NOT_FOUND', message: '지정한 담당자를 찾을 수 없습니다' },
      { status: 422 },
    )
  }

  const componentIds = body.componentIds ?? []
  // FR-UX-09 F2 — 3-state 판별. `!== undefined` 가 아니라 키 존재로 본다.
  const hasAssigneeKey = Object.prototype.hasOwnProperty.call(body, 'assigneeId')

  // default-assignee resolve — componentStore(component-handlers.ts 단일 출처)에서 읽는다.
  // X-MSW-Seed-Components 헤더로 브라우저 시드된 컴포넌트에서 리드를 조회한다.
  // 정렬 기준: name 오름차순, name 동률이면 id 오름차순 (백엔드 DefaultAssigneeResolver 동일).
  let resolvedAssigneeId: string | null = createdIssueFixture.assigneeId
  if (hasAssigneeKey) {
    // 클라이언트가 담당자 의사를 명시했다 — 자동 배정을 돌리지 않는다.
    resolvedAssigneeId = body.assigneeId ?? null
  } else if (resolvedAssigneeId === null && componentIds.length > 0) {
    // 우선순위 1: 컴포넌트 리드 (name 오름차순 → id 오름차순 tiebreak)
    const firstLead = getStoredComponentsByIds(componentIds)
      .filter((c) => c.leadUserId !== null)
      .sort((a, b) => {
        const nameCmp = a.name.localeCompare(b.name)
        return nameCmp !== 0 ? nameCmp : a.id.localeCompare(b.id)
      })[0]
    if (firstLead !== undefined) {
      resolvedAssigneeId = firstLead.leadUserId
    } else {
      // 우선순위 2: 프로젝트 리드 폴백 — 컴포넌트 리드가 없는 경우
      // body.projectKey 원본 키로 조회 (store 키와 일치: 'ATLAS')
      resolvedAssigneeId = getStoredProjectLead(body.projectKey ?? '')
    }
  }

  // securityLevelId: body에 명시된 경우(null 포함) 반영, 미전달이면 fixture 기본값(null) 유지
  const resolvedSecurityLevelId = body.securityLevelId !== undefined
    ? body.securityLevelId
    : createdIssueFixture.securityLevelId

  // FR-IS-10 — customFields: body에 명시된 경우 반영, 미전달이면 fixture 기본값({}) 유지
  const resolvedCustomFields = body.customFields !== undefined
    ? body.customFields
    : createdIssueFixture.customFields

  // FR-UX-14 F14 — typeKey 를 typeId 에서 파생시킨다.
  // 이전에는 typeId 만 요청값을 반영하고 typeKey 는 fixture 고정값('task')으로 남아 둘이
  // 어긋나 있었다. 보드/백로그 카드가 typeKey 로 유형 아이콘을 그리기 시작하면서 그 잠복
  // 불일치가 화면에 드러나므로 여기서 한 값에서 파생시킨다.
  // typeKey 만 파생시키고 typeName 을 두면 반쪽 봉합이다 — typeId=1(버그) 요청이
  // `typeKey: 'bug'` + `typeName: '작업'` 이라는 자기모순 응답을 낸다. 한 조회에서 둘 다 뽑는다.
  const resolvedCreateTypeId = body.typeId ?? createdIssueFixture.typeId
  const resolvedCreateType = lookupIssueType(resolvedCreateTypeId)
  const resolvedCreateTypeKey = resolvedCreateType?.key ?? createdIssueFixture.typeKey
  const resolvedCreateTypeName = resolvedCreateType?.name ?? createdIssueFixture.typeName

  const created: IssueResponse = {
    ...createdIssueFixture,
    projectKey: body.projectKey ?? 'ATLAS',
    summary: body.summary ?? '',
    componentIds,
    assigneeId: resolvedAssigneeId,
    securityLevelId: resolvedSecurityLevelId,
    customFields: resolvedCustomFields,
    // FR-UX-09 F2 — 요청 값을 반영한다. fixture 고정값을 돌려주면 상위 테스트가 공허해진다.
    typeId: resolvedCreateTypeId,
    typeKey: resolvedCreateTypeKey,
    typeName: resolvedCreateTypeName,
    description: body.description ?? createdIssueFixture.description,
    priority: body.priority ?? DEFAULT_ISSUE_PRIORITY,
    labels: body.labels ?? [],
  }
  // E2E-1 happy path 용 — POST 직후 GET 으로 조회 가능하도록 stateful 보관.
  createdIssues.set(created.key, created)

  // FR-UX-09 F3 FR-13 — 백로그 목과 같은 출처를 보게 한다.
  // 이게 없으면 「만들었더니 백로그 칸에 나타난다」가 구현이 옳아도 실패하고,
  // 그 실패를 피해 단언을 「호출됐다」로 약화하면 언제나 통과하는 가짜 그린이 된다.
  appendCreatedIssueToBacklog(created.projectKey, {
    key: created.key,
    summary: created.summary,
    currentStateKey: created.currentStateKey,
    assigneeId: created.assigneeId,
    priority: created.priority,
    version: created.version,
    // 생성 폼에 에픽 축이 없다 — 새 이슈는 항상 에픽 미지정이다. 픽스처에 에픽이 2종
    // 생긴 뒤에도 이 값은 null 이 맞다(누락이 아니다). 새로 만든 카드는 「에픽 없음」쪽에 선다.
    epicKey: null,
    // FR-UX-14 F14 — 카드 밀도 3필드. 생성 응답과 같은 출처를 쓴다.
    typeKey: created.typeKey,
    labels: created.labels,
    // 생성 폼에 추정 축이 없다 — 새 이슈는 항상 미추정이다 (누락이 아니라 값이 없는 것).
    originalEstimateSeconds: null,
  })

  return HttpResponse.json({ data: created }, { status: 201 })
})

/** E2E-5 회귀 가드 트리거 — summary 값이 이 문자열이면 409 VERSION_CONFLICT 응답. */
export const MOCK_CONFLICT_TRIGGER = '__TRIGGER_409__'

/**
 * FR-UX-09 F2 — 미존재 담당자 트리거. `assigneeId` 가 이 값이면 422 `ASSIGNEE_NOT_FOUND` 응답.
 * 백엔드 `IssueApplicationService` 가 존재하지 않는 사용자에 대해 내는 응답과 같은 형태다.
 */
export const MOCK_ASSIGNEE_NOT_FOUND = '__ASSIGNEE_NOT_FOUND__'

/** 백엔드 도메인 기본 우선순위 (Medium). 생성 요청에 priority 가 없을 때 서버가 적용하는 값. */
const DEFAULT_ISSUE_PRIORITY = 3

/** 전환 워크플로우 미설정 트리거 — toStatusKey 값이 이 문자열이면 422 응답. */
export const MOCK_NO_WORKFLOW_TRIGGER = '__TRIGGER_422_NO_WORKFLOW__'

/**
 * PATCH /api/v1/issues/:key — 이슈 수정 핸들러.
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) typeId 검증 실패 → 404
 *   (3) VERSION_CONFLICT → 409 (두 가지 트리거):
 *       (a) summary === MOCK_CONFLICT_TRIGGER (E2E-5 회귀 가드, 기존 동작 유지)
 *       (b) expectedVersion !== fixture 현재 version (OCC 시맨틱 — typeId 변경 409 재현용)
 *   (4) 성공 → 200 + { data: 수정된 IssueResponse(version+1) }
 *
 * body.expectedVersion 은 낙관적 잠금(OCC) 필드 — updateIssue API 함수 전송 형태와 일치.
 */
const updateIssueHandler = http.patch('/api/v1/issues/:key', async ({ params, request }) => {
  const key = params['key'] as string
  // 오버라이드 → 생성된 이슈 → 정적 fixture 순서 (전환 핸들러와 동일). 반복 수정/전환 후 수정 시 최신 version 기준.
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  const body = await request.clone().json() as {
    summary?: string
    typeId?: number
    expectedVersion?: number
    description?: string | null
    priority?: number | null
    labels?: string[] | null
    environment?: string | null
    impact?: number | null
    securityLevelId?: string | null
    /**
     * FR-IS-10 — 커스텀 필드 키 단위 병합 패치.
     * undefined(미전달) → 기존값 유지.
     * {}(빈 객체) → 전체 초기화.
     * { key: value } → 키 단위 병합 (value가 null이면 해당 키 삭제).
     */
    customFields?: Record<string, unknown> | null
    /**
     * FR-PL-01 — 일정 3필드 (JsonNullable 3-state).
     * undefined(미전달) → 기존값 유지.
     * null → DB NULL 클리어.
     * "yyyy-MM-dd" → 해당 날짜로 설정.
     */
    startDate?: string | null
    dueDate?: string | null
    targetDate?: string | null
    /**
     * FR-TT-01 — 추정 2필드 (JsonNullable 3-state, applyDatePatch와 동일).
     * undefined(미전달) → 기존값 유지.
     * null → DB NULL 클리어.
     * number → 해당 초 값으로 설정.
     */
    originalEstimateSeconds?: number | null
    remainingEstimateSeconds?: number | null
  }

  // (2) typeId 검증 — 카탈로그에 없는 id 는 404
  let resolvedTypeId = found.typeId
  let resolvedTypeKey = found.typeKey
  let resolvedTypeName = found.typeName
  if (body.typeId !== undefined) {
    const matched = lookupIssueType(body.typeId)
    if (matched === undefined) {
      return HttpResponse.json(
        { message: `이슈 타입을 찾을 수 없습니다: ${body.typeId}` },
        { status: 404 },
      )
    }
    resolvedTypeId = matched.id
    resolvedTypeKey = matched.key
    resolvedTypeName = matched.name
  }

  // (3) VERSION_CONFLICT — 두 가지 트리거:
  //   (a) E2E-5 회귀 가드: summary === MOCK_CONFLICT_TRIGGER (기존 동작 유지)
  //   (b) OCC 시맨틱: expectedVersion 이 fixture 현재 version 과 불일치
  const isConflictTrigger = body.summary === MOCK_CONFLICT_TRIGGER
  const isVersionMismatch =
    body.expectedVersion !== undefined && body.expectedVersion !== found.version
  if (isConflictTrigger || isVersionMismatch) {
    return HttpResponse.json(
      { errorCode: 'VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
      { status: 409 },
    )
  }

  // (4) 성공 — 5필드 merge-patch 적용
  const resolvedDescription = applyNullableStringPatch(found.description, body.description)
  const resolvedPriority = body.priority ?? found.priority
  const resolvedLabels = body.labels !== undefined && body.labels !== null
    ? body.labels
    : found.labels
  const resolvedEnvironment = applyNullableStringPatch(found.environment, body.environment)
  const resolvedImpact = body.impact !== undefined ? (body.impact ?? found.impact) : found.impact

  // securityLevelId: undefined(미전달) → 기존값 유지, null → 해제, UUID → 지정
  const resolvedSecurityLevelId = body.securityLevelId !== undefined
    ? body.securityLevelId
    : found.securityLevelId

  // FR-IS-10 — customFields 키 단위 병합 (msw-derived-behavior-shared-store-e2e 교훈 적용).
  // undefined(미전달) → 기존값 유지.
  // {}(빈 객체) → 전체 초기화.
  // { key: value } → 키 단위 병합, value null → 해당 키 삭제.
  const resolvedCustomFields = mergeCustomFields(
    found.customFields as Record<string, unknown>,
    body.customFields,
  )

  // FR-PL-01 — 일정 3필드 3-state 적용.
  // undefined(미전달) → 기존값 유지, null → 클리어, "yyyy-MM-dd" → 설정.
  const resolvedStartDate = applyDatePatch(found.startDate ?? null, body.startDate)
  const resolvedDueDate = applyDatePatch(found.dueDate ?? null, body.dueDate)
  const resolvedTargetDate = applyDatePatch(found.targetDate ?? null, body.targetDate)

  // FR-TT-01 — 추정 2필드 3-state 적용 (worklog-handlers estimateStore 동기화).
  // applyDatePatch와 동일한 3-state 의미론: undefined=유지, null=클리어, number=설정.
  const currentEstimate = getIssueEstimate(key)
  const resolvedOriginalEstimateSeconds =
    body.originalEstimateSeconds !== undefined
      ? body.originalEstimateSeconds
      : (found.originalEstimateSeconds ?? currentEstimate.originalEstimateSeconds)
  const resolvedRemainingEstimateSeconds =
    body.remainingEstimateSeconds !== undefined
      ? body.remainingEstimateSeconds
      : (found.remainingEstimateSeconds ?? currentEstimate.remainingEstimateSeconds)

  // estimateStore에 동기화 — worklog summary 조회 시 일관된 값 반환
  if (body.originalEstimateSeconds !== undefined || body.remainingEstimateSeconds !== undefined) {
    setIssueEstimate(key, body.originalEstimateSeconds, body.remainingEstimateSeconds)
  }

  // timeSpentSeconds는 worklog store SUM에서 파생 — IssueResponse 고정값 대신 0 기본값 유지
  // (이슈 단건 GET에서 getIssueEstimate로 조회한 값을 보강하는 방식은 issue-handlers 범위 밖이므로
  //  추정 필드만 issueOverrides에 저장하고, WorklogSection이 GET /worklogs의 summary를 단일 출처로 사용)

  const updated: IssueResponse = {
    ...found,
    summary: body.summary ?? found.summary,
    typeId: resolvedTypeId,
    typeKey: resolvedTypeKey,
    typeName: resolvedTypeName,
    description: resolvedDescription,
    descriptionHtml: null, // PATCH 응답은 목록과 동일 — 단건 GET 에서만 채워짐
    priority: resolvedPriority,
    priorityName: priorityName(resolvedPriority),
    labels: resolvedLabels,
    environment: resolvedEnvironment,
    impact: resolvedImpact,
    impactName: impactNameOf(resolvedImpact),
    securityLevelId: resolvedSecurityLevelId,
    customFields: resolvedCustomFields,
    startDate: resolvedStartDate,
    dueDate: resolvedDueDate,
    targetDate: resolvedTargetDate,
    // FR-TT-01 — 추정 2필드: PATCH 응답에 반영 (IssueEstimatePanel이 issue 단건 refetch로 draft 재동기화)
    originalEstimateSeconds: resolvedOriginalEstimateSeconds,
    remainingEstimateSeconds: resolvedRemainingEstimateSeconds,
    version: found.version + 1,
    updatedAt: new Date().toISOString(),
  }
  // 수정 결과를 오버라이드에 보관 — invalidateQueries 후 GET 단건이 최신 타입/요약/5필드 반영 (refetch 롤백 방지).
  issueOverrides.set(key, updated)
  // POST 로 생성된 이슈는 목록(buildFilteredPage) 일관성을 위해 createdIssues 도 갱신.
  if (createdIssues.has(key)) {
    createdIssues.set(key, updated)
  }

  // FR-MN-01 D7 — description 변경 시 @멘션 추출 → 언급된 사용자 Inbox 파생
  // msw-derived-behavior-shared-store-e2e 교훈: 파생 알림은 공유 inboxStore 경유
  if ('description' in body && typeof body.description === 'string') {
    const mentionedUsernames = extractMentionedUsernames(body.description)
    const actorUserId = resolveActorUserIdFromRequest(request)

    for (const username of mentionedUsernames) {
      const user = AUTH_USERS[username]
      if (user === undefined) continue
      // 자기 자신 멘션 제외 (alice가 @alice 입력 시 본인 알림 불필요)
      if (user.userId === actorUserId) continue

      const inboxItem: InboxItem = {
        id: crypto.randomUUID(),
        eventType: 'ISSUE_MENTIONED',
        issueKey: key,
        // 백엔드 NotificationWorker.kt buildTitleBody 형식 미러 (NotificationWorker.kt:357)
        title: `${key} 에서 멘션되었습니다`,
        body: null,
        // actorUserId는 Zod uuid() 검증 필수 — resolveActorUserIdFromRequest 반환값 사용
        actorUserId: actorUserId,
        readAt: null,
        archivedAt: null,
        createdAt: new Date().toISOString(),
      }
      appendToInbox(user.userId, inboxItem)
    }
  }

  return HttpResponse.json({ data: updated })
})

/**
 * PATCH /api/v1/issues/:key/components — 컴포넌트 변경 핸들러 (FR-CM-02).
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) expectedVersion 불일치 → 409 VERSION_CONFLICT
 *   (3) componentIds 중 UUID 형식이 아닌 항목 존재 → 422 COMPONENT_NOT_FOUND
 *   (4) 성공 → 200 + { data: 수정된 IssueResponse(version+1) }
 *       stateful: issueOverrides에 변경 사항 영속 (invalidate refetch 후 롤백 방지)
 */
const changeComponentsHandler = http.patch('/api/v1/issues/:key/components', async ({ params, request }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  const body = await request.clone().json() as {
    componentIds: string[]
    expectedVersion?: number
  }

  // (2) VERSION_CONFLICT — expectedVersion 불일치
  if (body.expectedVersion !== undefined && body.expectedVersion !== found.version) {
    return HttpResponse.json(
      { errorCode: 'VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
      { status: 409 },
    )
  }

  // (3) COMPONENT_NOT_FOUND — UUID 형식이 아닌 componentId 존재
  const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  const invalidId = body.componentIds.find((id) => !uuidPattern.test(id))
  if (invalidId !== undefined) {
    return HttpResponse.json(
      { errorCode: 'COMPONENT_NOT_FOUND', message: `컴포넌트를 찾을 수 없습니다: ${invalidId}` },
      { status: 422 },
    )
  }

  // (4) 성공 — componentIds 영속 + version+1, stateful 보관 (교훈: msw-mutation-stateful-refetch)
  const updated: IssueResponse = {
    ...found,
    componentIds: body.componentIds,
    descriptionHtml: null, // PATCH 응답은 목록과 동일 — 단건 GET에서만 채워짐
    version: found.version + 1,
    updatedAt: new Date().toISOString(),
  }
  issueOverrides.set(key, updated)
  if (createdIssues.has(key)) {
    createdIssues.set(key, updated)
  }
  return HttpResponse.json({ data: updated })
})

/**
 * PATCH /api/v1/issues/:key/affects-versions — 영향 버전 변경 핸들러 (FR-VR-03).
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) expectedVersion 불일치 → 409 ISSUE_VERSION_CONFLICT
 *   (3) versionIds 중 UUID 형식이 아닌 항목 존재 → 422 ISSUE_LINKED_VERSION_NOT_FOUND
 *   (4) 성공 → 200 + { data: 수정된 IssueResponse(version+1) }
 *       stateful: issueOverrides에 변경 사항 영속 (invalidate refetch 후 롤백 방지)
 */
const changeAffectsVersionsHandler = http.patch('/api/v1/issues/:key/affects-versions', async ({ params, request }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  const body = await request.clone().json() as {
    versionIds: string[]
    expectedVersion?: number
  }

  // (2) VERSION_CONFLICT — expectedVersion 불일치
  if (body.expectedVersion !== undefined && body.expectedVersion !== found.version) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
      { status: 409 },
    )
  }

  // (3) ISSUE_LINKED_VERSION_NOT_FOUND — UUID 형식이 아닌 versionId 존재
  const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  const invalidId = body.versionIds.find((id) => !uuidPattern.test(id))
  if (invalidId !== undefined) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_LINKED_VERSION_NOT_FOUND', message: `버전을 찾을 수 없습니다: ${invalidId}` },
      { status: 422 },
    )
  }

  // (4) 성공 — affectsVersionIds 영속 + version+1, stateful 보관
  const updated: IssueResponse = {
    ...found,
    affectsVersionIds: body.versionIds,
    descriptionHtml: null, // PATCH 응답은 목록과 동일 — 단건 GET에서만 채워짐
    version: found.version + 1,
    updatedAt: new Date().toISOString(),
  }
  issueOverrides.set(key, updated)
  if (createdIssues.has(key)) {
    createdIssues.set(key, updated)
  }
  return HttpResponse.json({ data: updated })
})

/**
 * PATCH /api/v1/issues/:key/fix-versions — 수정 버전 변경 핸들러 (FR-VR-03).
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) expectedVersion 불일치 → 409 ISSUE_VERSION_CONFLICT
 *   (3) versionIds 중 UUID 형식이 아닌 항목 존재 → 422 ISSUE_LINKED_VERSION_NOT_FOUND
 *   (4) 성공 → 200 + { data: 수정된 IssueResponse(version+1) }
 *       stateful: issueOverrides에 변경 사항 영속 (invalidate refetch 후 롤백 방지)
 */
const changeFixVersionsHandler = http.patch('/api/v1/issues/:key/fix-versions', async ({ params, request }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  const body = await request.clone().json() as {
    versionIds: string[]
    expectedVersion?: number
  }

  // (2) VERSION_CONFLICT — expectedVersion 불일치
  if (body.expectedVersion !== undefined && body.expectedVersion !== found.version) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
      { status: 409 },
    )
  }

  // (3) ISSUE_LINKED_VERSION_NOT_FOUND — UUID 형식이 아닌 versionId 존재
  const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  const invalidId = body.versionIds.find((id) => !uuidPattern.test(id))
  if (invalidId !== undefined) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_LINKED_VERSION_NOT_FOUND', message: `버전을 찾을 수 없습니다: ${invalidId}` },
      { status: 422 },
    )
  }

  // (4) 성공 — fixVersionIds 영속 + version+1, stateful 보관
  const updated: IssueResponse = {
    ...found,
    fixVersionIds: body.versionIds,
    descriptionHtml: null, // PATCH 응답은 목록과 동일 — 단건 GET에서만 채워짐
    version: found.version + 1,
    updatedAt: new Date().toISOString(),
  }
  issueOverrides.set(key, updated)
  if (createdIssues.has(key)) {
    createdIssues.set(key, updated)
  }
  return HttpResponse.json({ data: updated })
})

/**
 * PATCH /api/v1/issues/:key/assignee — 담당자 변경 핸들러 (FR-IS-03).
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) expectedVersion 불일치 → 409 VERSION_CONFLICT
 *   (3) assigneeId가 실재하지 않는 사용자 → 422 ASSIGNEE_NOT_FOUND
 *   (4) 성공 → 200 + { data: 수정된 IssueResponse(assigneeId+version+1) }
 *       stateful: issueOverrides에 assigneeId 영속 (invalidate refetch 후 롤백 방지 — 교훈 2)
 */
const changeAssigneeHandler = http.patch('/api/v1/issues/:key/assignee', async ({ params, request }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  const body = await request.clone().json() as {
    assigneeId: string | null
    expectedVersion?: number
  }

  // (2) VERSION_CONFLICT — expectedVersion 불일치
  if (body.expectedVersion !== undefined && body.expectedVersion !== found.version) {
    return HttpResponse.json(
      { errorCode: 'VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
      { status: 409 },
    )
  }

  // (3) ASSIGNEE_NOT_FOUND — assigneeId가 null이 아니고 userListFixture에 없음
  if (body.assigneeId !== null) {
    const userExists = userListFixture.some((u) => u.id === body.assigneeId)
    if (!userExists) {
      return HttpResponse.json(
        { errorCode: 'ASSIGNEE_NOT_FOUND', message: '담당자를 찾을 수 없습니다.' },
        { status: 422 },
      )
    }
  }

  // (4) 성공 — assigneeId 영속 (stateful, 교훈 2)
  const updated: IssueResponse = {
    ...found,
    assigneeId: body.assigneeId,
    descriptionHtml: null, // PATCH 응답은 목록과 동일 — 단건 GET에서만 채워짐
    version: found.version + 1,
    updatedAt: new Date().toISOString(),
  }
  issueOverrides.set(key, updated)
  if (createdIssues.has(key)) {
    createdIssues.set(key, updated)
  }
  return HttpResponse.json({ data: updated })
})

/**
 * DELETE /api/v1/issues/:key — 이슈 삭제 핸들러.
 * 소프트 삭제: deletedKeys add + 204 No Content. 이후 GET 목록/단건 모두 제외 (gap-H).
 */
const deleteIssueHandler = http.delete('/api/v1/issues/:key', ({ params }) => {
  const key = params['key'] as string
  deletedKeys.add(key)
  return new HttpResponse(null, { status: 204 })
})

/**
 * 이슈 키로 현재 상태 조회 helper —
 * issueOverrides → createdIssues → issueFixtureMap → {@link backlogEpicIssueMap} 순서.
 * 소프트 삭제된 키는 undefined 반환.
 *
 * ★조회 체인은 **이 함수 하나뿐**이다. 단건 GET 이 같은 체인을 손으로 한 벌 더 적고 있으면
 *   출처가 늘 때마다 둘 중 하나만 고쳐져 서로를 검사하지 못한다
 *   (`two-lists-never-check-each-other`).
 */
function resolveIssue(key: string): IssueResponse | undefined {
  if (deletedKeys.has(key)) return undefined
  return (
    issueOverrides.get(key) ??
    createdIssues.get(key) ??
    issueFixtureMap[key] ??
    backlogEpicIssueMap[key] ??
    ambiguousIssueMap[key]
  )
}

/** getIssueFieldOverride가 반환하는 필드 오버라이드 값 (FR-UX-06 PR21b Task 6). */
export interface IssueFieldOverride {
  /** 담당자 사용자 UUID. 미배정이면 null. */
  assigneeId: string | null
  /** 우선순위 (1=Highest ~ 5=Lowest). */
  priority: number
  /** 소속 에픽 키. 미소속이면 null. */
  epicKey: string | null
}

/**
 * 담당자·우선순위·에픽 필드의 최신 오버라이드 값을 읽기 전용으로 노출한다 (FR-UX-06 PR21b Task 6).
 *
 * changeAssignee/updateIssue/connectEpicChild/disconnectEpicChild 핸들러는 해당 필드가
 * 실제로 변경될 때만 issueOverrides에 기록한다. board-handlers.ts의 board GET 핸들러가 이
 * 접근자로 최신값을 읽어 카드 응답에 오버레이한다(PR21 resolveLiveRank의 필드 버전) — 그래야
 * 스윔레인 간 드래그로 필드를 바꾼 뒤 board를 재조회해도 옛 시드값으로 되돌아가지 않는다
 * (msw-mutation-stateful-refetch 회귀 회피).
 *
 * issueOverrides에 아직 기록이 없으면(해당 이슈의 필드변경 이력 없음) undefined를 반환한다 —
 * 호출부(board-handlers.ts)가 board 자체 시드값을 그대로 쓰게 해, 필드변경이 일어난 적 없는
 * 기존 26개+ 보드 fixture/E2E 스펙에는 무회귀다.
 *
 * @param key 조회할 이슈 키
 */
export function getIssueFieldOverride(key: string): IssueFieldOverride | undefined {
  const override = issueOverrides.get(key)
  if (override === undefined) return undefined
  return {
    assigneeId: override.assigneeId,
    priority: override.priority,
    epicKey: override.epic?.key ?? null,
  }
}

/** priority 숫자 → 표시 이름 변환 (1=Highest ~ 5=Lowest, backend 기본값 3=Medium). */
function priorityName(priority: number): string {
  const map: Record<number, string> = {
    1: 'Highest',
    2: 'High',
    3: 'Medium',
    4: 'Low',
    5: 'Lowest',
  }
  return map[priority] ?? 'Medium'
}

/** impact 숫자 → 표시 이름 변환 (1=High, 2=Medium, 3=Low). null 허용. */
function impactNameOf(impact: number | null): string | null {
  if (impact === null) return null
  const map: Record<number, string> = { 1: 'High', 2: 'Medium', 3: 'Low' }
  return map[impact] ?? null
}

/**
 * nullable 문자열 필드의 merge-patch 3-state 처리.
 * - undefined(미전달) → 기존값 유지
 * - "" → null (DB NULL 클리어)
 * - 값 → 그대로 설정
 *
 * description, environment 두 필드가 동일 규칙이므로 공유.
 */
function applyNullableStringPatch(
  current: string | null,
  incoming: string | null | undefined,
): string | null {
  if (incoming === undefined) return current
  if (incoming === '') return null
  return incoming
}

/**
 * FR-IS-10 — customFields 키 단위 병합 헬퍼.
 *
 * 병합 규칙 (backend customFields PATCH 계약과 동일).
 * - incoming undefined(미전달) → current 그대로 반환.
 * - incoming {}(빈 객체) → {} 로 전체 초기화.
 * - incoming { key: null } → 해당 키 삭제.
 * - incoming { key: value } → 해당 키 갱신, 나머지 키 보존.
 *
 * @param current 현재 저장된 customFields
 * @param incoming PATCH body의 customFields (undefined/null 허용)
 * @returns 병합 결과
 */
function mergeCustomFields(
  current: Record<string, unknown>,
  incoming: Record<string, unknown> | null | undefined,
): Record<string, unknown> {
  // null/undefined: 무변경 (백엔드 IssueApplicationService.mergeCustomFieldsAndValidate — customFields=null → return null)
  if (incoming === undefined || incoming === null) return current
  // 빈 객체: 병합할 키 0개 → 기존 유지(무변경). 백엔드에 "전체 제거" 기능 없음.
  // 키 단위 병합: value null → 삭제, 값 → 갱신
  const merged = { ...current }
  for (const [key, value] of Object.entries(incoming)) {
    if (value === null) {
      delete merged[key]
    } else {
      merged[key] = value
    }
  }
  return merged
}

/**
 * FR-PL-01 — 날짜 필드 3-state 패치 헬퍼.
 *
 * 3-state 규칙 (JsonNullable<LocalDate> 백엔드 계약 미러).
 * - incoming undefined(미전달) → current 그대로 반환 (무변경).
 * - incoming null → null 반환 (클리어).
 * - incoming "yyyy-MM-dd" 문자열 → 해당 값 반환 (설정).
 *
 * @param current 현재 저장된 날짜 값 (null 허용)
 * @param incoming PATCH body의 날짜 값 (undefined/null/"yyyy-MM-dd" 허용)
 * @returns 패치 결과
 */
function applyDatePatch(
  current: string | null,
  incoming: string | null | undefined,
): string | null {
  if (incoming === undefined) return current
  return incoming
}

/**
 * description 텍스트에서 @멘션 사용자명을 추출한다.
 *
 * 제외 규칙.
 *   - 코드스팬(`...`) 내부의 @ (예: `@code`)
 *   - 이메일 형식의 @ — 앞에 단어문자(\w)가 있는 경우 (예: user@example.com)
 *   - 겹침 @ — 앞에 @가 있는 경우 (예: @@bob)
 *
 * 사용자명 패턴. [A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])? — 영숫자 시작·끝
 * (백엔드 MentionParser.MENTION_PATTERN 일치 — 후행 마침표 등 구두점 제외)
 *
 * @param text 원본 description 텍스트
 * @returns 중복 제거된 @뒤 사용자명 배열 (@ 기호 제외)
 */
function extractMentionedUsernames(text: string): string[] {
  // 코드스팬(`...`)을 제거해 내부 @ 를 보호
  const withoutCode = text.replace(/`[^`]*`/g, '')
  // 이메일/겹침/선행구두점 @ 제외: 앞에 [A-Za-z0-9._@-] 가 없는 @ 만 매칭 (백엔드 MentionParser 동일)
  const matches = withoutCode.matchAll(/(?<![A-Za-z0-9._@-])@([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)/g)
  return [...new Set([...matches].map((m) => m[1] as string))]
}

/** mock access token 접두사 — auth-fixtures.mockAccessToken과 동일 형식 */
const MENTION_MOCK_TOKEN_PREFIX = 'mock-access-token-'

/**
 * Authorization Bearer 헤더에서 PATCH 요청자(actor)의 userId를 도출한다.
 * 멘션 파생 시 자기 자신 멘션 제외와 actorUserId 기록에 사용한다.
 */
function resolveActorUserIdFromRequest(request: Request): string | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null
  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MENTION_MOCK_TOKEN_PREFIX)) return null
  const username = token.slice(MENTION_MOCK_TOKEN_PREFIX.length)
  return AUTH_USERS[username]?.userId ?? null
}

/**
 * 코드스팬 이외의 텍스트에서 @멘션을 <span class="mention"> 으로 강조한다.
 * renderDescriptionHtml의 코드스팬 밖 세그먼트에 단독 적용한다.
 *
 * MSW 테스트더블이므로 백엔드 sanitization(XSS 이스케이프 등)을 의도적으로 미러하지 않음.
 * 실 백엔드 MarkdownRenderer는 CommonMark 파서 기반으로 이스케이프를 처리한다.
 */
function applyMentionHighlight(text: string): string {
  return text.replace(
    /(?<![A-Za-z0-9._@-])@([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)/g,
    '<span class="mention">@$1</span>',
  )
}

/**
 * description 값에서 descriptionHtml 생성 — 단건 GET 모킹용.
 *
 * 백엔드 MarkdownRenderer가 생성하는 HTML 형식을 MSW에서 재현한다.
 * - @멘션 → <span class="mention">@username</span> (FR-MN-01 D6/D7)
 * - 코드스팬(`...`) → <code>...</code>
 * - 이메일(@앞 단어문자) / 겹침(@@ 등) → 강조 없이 그대로 출력
 * - 전체 → <p>...</p> 래핑
 *
 * 처리 순서 (단일 패스 — 제어문자 플레이스홀더 사용 금지).
 *   코드스팬(`...`)과 일반 텍스트를 교대로 처리.
 *   일반 텍스트 세그먼트에만 @멘션 강조 적용.
 *   코드스팬 세그먼트는 <code>...</code> 변환 후 그대로 출력.
 */
function renderDescriptionHtml(description: string | null): string | null {
  if (description === null) return null

  let result = ''
  let lastIndex = 0
  const codeSpanRegex = /`([^`]*)`/g
  let codeMatch: RegExpExecArray | null

  // 코드스팬(`...`)과 일반 텍스트를 교대로 처리 — 단일 패스
  while ((codeMatch = codeSpanRegex.exec(description)) !== null) {
    // 코드스팬 이전 일반 텍스트 → @멘션 강조 적용
    result += applyMentionHighlight(description.slice(lastIndex, codeMatch.index))
    // 코드스팬 → <code>내용</code>
    result += '<code>' + (codeMatch[1] ?? '') + '</code>'
    lastIndex = codeSpanRegex.lastIndex
  }
  // 마지막 코드스팬 이후 나머지 텍스트 → @멘션 강조 적용
  result += applyMentionHighlight(description.slice(lastIndex))

  return '<p>' + result + '</p>'
}

/**
 * 가용 전환 응답 1건 — backend `TransitionItem` 7필드와 1:1.
 *
 * 워크플로우 픽스처의 `id` 가 여기서 `transitionId` 로 바뀐다. 실제 응답에 없는 `id` 를
 * 흘려보내면 프론트가 한 번도 없는 필드를 있다고 믿게 되므로 이름을 맞춰 옮긴다.
 */
interface MockTransitionItem {
  key: string
  name: string
  fromStateKey: string
  toStateKey: string
  toCategory: string | null
  transitionId: string
  kind: string
}

/**
 * 현재 이슈 상태 기준 가용전환 반환 helper.
 * softwareDefaultFixture 가 단일 출처 — 전환 직접 정의 금지.
 * toCategory를 toStateKey → states category lookup으로 enrichment.
 *
 * @param currentStateKey 이슈의 현재 상태 키
 * @returns 그 상태에서 출발하는 전환 목록 (backend TransitionItem 형태)
 */
function getAvailableTransitions(currentStateKey: string): MockTransitionItem[] {
  const stateMap = new Map(softwareDefaultFixture.states.map((s) => [s.key, s.category]))
  return softwareDefaultFixture.transitions
    .filter((t) => t.fromStateKey === currentStateKey)
    .map((t) => ({
      key: t.key,
      name: t.name,
      fromStateKey: currentStateKey,
      toStateKey: t.toStateKey,
      toCategory: stateMap.get(t.toStateKey) ?? null,
      transitionId: t.id,
      kind: t.kind,
    }))
}

/**
 * 모호 전환(409 `AMBIGUOUS_TRANSITION`) 검증 전용 이슈 키.
 *
 * ★`issueFixtureMap` 에 **넣지 않는다.** 그 맵은 이슈 목록의 모수이자 클론 키 채번의
 *   모수라 거기에 1건을 더하면 목록 건수를 세는 기존 테스트가 함께 흔들린다.
 *   `backlogEpicIssueMap` 과 같은 처방으로 {@link resolveIssue} 체인 끝에만 붙인다.
 */
export const MOCK_AMBIGUOUS_ISSUE_KEY = 'ATLAS-AMBIG'

/** 모호 전환 후보 중 픽스처에 없는 쪽의 이름 — E2E 가 이 버튼을 눌러 후보를 지목한다. */
export const MOCK_AMBIGUOUS_TRANSITION_NAME = '긴급 착수'

/**
 * 위 전환의 1급 식별자.
 * `workflow-fixtures.ts` 의 `fixtureTransitionId(워크플로우번호, 전환번호)` 와 같은 RFC4122 v4
 * 형식이되 워크플로우 번호 `00` 대역을 써서 픽스처 전환 id 와 겹치지 않는다.
 */
const MOCK_AMBIGUOUS_TRANSITION_ID = '00000000-0000-4000-8000-000000000099'

/**
 * 모호 전환 이슈 — open 에서 in_progress 로 가는 전환이 **2건**이라 도착 상태만으로는 못 가른다.
 * ATLAS-1 을 그대로 복제하고 키·id·요약만 갈아 끼운다 (상세 화면 렌더 경로를 그대로 태우기 위함).
 */
const ambiguousIssueFixture: IssueResponse = {
  ...issueAtlas1Fixture,
  key: MOCK_AMBIGUOUS_ISSUE_KEY,
  id: 'd4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f70',
  summary: '같은 도착 상태로 가는 전환이 둘인 이슈 (409 후보 선택 검증용)',
}

/** 모호 전환 이슈 단건 조회용 맵 — resolveIssue 체인 맨 끝. */
const ambiguousIssueMap: Record<string, IssueResponse> = {
  [MOCK_AMBIGUOUS_ISSUE_KEY]: ambiguousIssueFixture,
}

/**
 * 이슈 키 기준 가용전환 — 모호 전환 이슈면 같은 도착 상태 전환을 하나 더 얹는다.
 *
 * backend 는 `UNIQUE(workflow_id, from, to)` 해제로 같은 상태쌍에 이름만 다른 전환을
 * 여럿 둘 수 있다(ADR 2026-08-18). 목이 그 상태를 한 번도 만들지 않으면 409 경로가
 * 프론트에서 영원히 도달 불가가 되어 후보 선택 UI 가 죽은 코드로 남는다.
 *
 * @param key 이슈 키
 * @param currentStateKey 이슈의 현재 상태 키
 * @returns 그 이슈에서 지금 이동 가능한 전환 목록
 */
function getAvailableTransitionsForIssue(
  key: string,
  currentStateKey: string,
): MockTransitionItem[] {
  const base = getAvailableTransitions(currentStateKey)
  if (key !== MOCK_AMBIGUOUS_ISSUE_KEY || currentStateKey !== 'open') return base
  return [
    ...base,
    {
      key: 'open__in_progress',
      name: MOCK_AMBIGUOUS_TRANSITION_NAME,
      fromStateKey: 'open',
      toStateKey: 'in_progress',
      toCategory: 'IN_PROGRESS',
      transitionId: MOCK_AMBIGUOUS_TRANSITION_ID,
      kind: 'NORMAL',
    },
  ]
}

/**
 * GET /api/v1/issues/:key/transitions — 현재 상태 기준 가용전환 목록 반환.
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) 워크플로우 미설정 이슈(ATLAS-NOWF) → 422 (E2E 미설정 UI 검증용)
 *   (3) 성공 → 200 + { data: { transitions } }
 * 응답: { data: { transitions: [{ key, name, fromStateKey, toStateKey }] } }
 */
const getTransitionsHandler = http.get('/api/v1/issues/:key/transitions', ({ params }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  // (2) 워크플로우 미설정 이슈 → 422
  if (key === issueAtlasNoWorkflowFixture.key) {
    return HttpResponse.json(
      { errorCode: 'workflow_not_configured', message: '이슈에 워크플로우가 설정되지 않았습니다.' },
      { status: 422 },
    )
  }
  const transitions = getAvailableTransitionsForIssue(key, found.currentStateKey)
  return HttpResponse.json({ data: { transitions } })
})

/**
 * POST /api/v1/issues/:key/transition — 이슈 상태 전환 핸들러.
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) MOCK_NO_WORKFLOW_TRIGGER → 422 (워크플로우 미설정 시뮬)
 *   (3-a) expectedVersion 불일치 → 409 VERSION_CONFLICT (OCC 버전충돌)
 *   (3-b) MOCK_CONFLICT_TRIGGER → 409 TRANSITION_NOT_ALLOWED (전환거부)
 *   (3-c) transitionId 없이 도착 상태만 왔는데 후보가 2개 이상 → 409 AMBIGUOUS_TRANSITION
 *   (4) 성공 → 200 + currentStateKey=toStatusKey + version+1, stateful 보관
 */
const transitionHandler = http.post('/api/v1/issues/:key/transition', async ({ params, request }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }

  const body = await request.clone().json() as {
    toStatusKey?: string
    expectedVersion?: number
    resolutionId?: string
    transitionId?: string
  }
  const toStatusKey = body.toStatusKey ?? ''

  // (2) 워크플로우 미설정 트리거 → 422
  if (toStatusKey === MOCK_NO_WORKFLOW_TRIGGER) {
    return HttpResponse.json(
      { errorCode: 'workflow_not_configured', message: '이슈에 워크플로우가 설정되지 않았습니다.' },
      { status: 422 },
    )
  }

  // (3-a) OCC 버전 충돌 → 409 VERSION_CONFLICT
  const isVersionMismatch =
    body.expectedVersion !== undefined && body.expectedVersion !== found.version
  if (isVersionMismatch) {
    return HttpResponse.json(
      { errorCode: 'VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
      { status: 409 },
    )
  }

  // (3-b) 전환거부 트리거 → 409 TRANSITION_NOT_ALLOWED
  if (toStatusKey === MOCK_CONFLICT_TRIGGER) {
    return HttpResponse.json(
      { errorCode: 'TRANSITION_NOT_ALLOWED', message: '허용되지 않는 전환입니다.' },
      { status: 409 },
    )
  }

  // (3-c) 모호 전환 → 409 AMBIGUOUS_TRANSITION + 후보 전량 (ADR 2026-08-18 §D3).
  //   transitionId 가 오면 그것으로 실행하고, 없이 toStatusKey 만 오면 후보가 정확히 1개일
  //   때만 실행한다. 조용히 첫 후보를 고르지 않는다 — 그러면 틀린 전환이 실행된다.
  //   backend AmbiguousTransitionExceptionHandler 의 응답 골격과 같은 모양이다.
  const candidates = getAvailableTransitionsForIssue(key, found.currentStateKey)
    .filter((t) => t.toStateKey === toStatusKey)
  if (body.transitionId === undefined && candidates.length > 1) {
    return HttpResponse.json(
      {
        error: {
          code: 'AMBIGUOUS_TRANSITION',
          message: `이동할 수 있는 전환이 ${candidates.length}개입니다. 어느 전환인지 골라 주세요.`,
        },
        candidates: candidates.map((t) => ({ transitionId: t.transitionId, name: t.name })),
      },
      { status: 409 },
    )
  }

  // (4) 성공 — currentStateKey 갱신 + version+1 + resolutionId stateful 보관
  // resolutionId가 있으면 resolution 객체를 찾아 채움 (invalidateQueries refetch 롤백 방지).
  const stateMap = new Map(softwareDefaultFixture.states.map((s) => [s.key, s.category]))
  const toCategory = stateMap.get(toStatusKey) ?? null
  // DONE 전환 시 resolution 영속, 비DONE 전환 시 resolution clear
  let updatedResolution: IssueResponse['resolution'] = found.resolution
  if (toCategory === 'DONE' && body.resolutionId) {
    // resolution-handlers.ts의 표준 5종 seed UUID → name 매핑
    const resolutionSeedMap: Record<string, { id: string; key: string; name: string }> = {
      '00000000-0000-4000-8000-000000000001': { id: '00000000-0000-4000-8000-000000000001', key: 'fixed', name: 'Fixed' },
      '00000000-0000-4000-8000-000000000002': { id: '00000000-0000-4000-8000-000000000002', key: 'wontfix', name: "Won't Fix" },
      '00000000-0000-4000-8000-000000000003': { id: '00000000-0000-4000-8000-000000000003', key: 'duplicate', name: 'Duplicate' },
      '00000000-0000-4000-8000-000000000004': { id: '00000000-0000-4000-8000-000000000004', key: 'cannotreproduce', name: 'Cannot Reproduce' },
      '00000000-0000-4000-8000-000000000005': { id: '00000000-0000-4000-8000-000000000005', key: 'done', name: 'Done' },
    }
    updatedResolution = resolutionSeedMap[body.resolutionId] ?? null
  } else if (toCategory !== 'DONE') {
    // 비DONE 전환 시 resolution clear
    updatedResolution = null
  }

  const updated: IssueResponse = {
    ...found,
    currentStateKey: toStatusKey,
    resolution: updatedResolution,
    version: found.version + 1,
    updatedAt: new Date().toISOString(),
  }
  issueOverrides.set(key, updated)
  return HttpResponse.json({ data: updated })
})

/**
 * 전환 항목의 toStateKey 기준으로 두 전환 배열의 교집합을 반환한다.
 * 첫 번째 배열 항목 기준을 유지한다 (fromStateKey·name·key 등은 첫 이슈 기준 보존).
 * backend BulkAvailableTransitionsService.intersect 시맨틱과 동일.
 */
function intersectByToStateKey(
  base: MockTransitionItem[],
  other: MockTransitionItem[],
): MockTransitionItem[] {
  const otherToKeys = new Set(other.map((t) => t.toStateKey))
  return base.filter((t) => otherToKeys.has(t.toStateKey))
}

/**
 * GET /api/v1/issues/:key/pdf — 이슈 PDF 다운로드 핸들러 (FR-IS-08).
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) 성공 → 200 + application/pdf 바이너리 (최소 PDF 헤더 포함)
 */
const downloadIssuePdfHandler = http.get('/api/v1/issues/:key/pdf', ({ params }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  // %PDF-1.4 로 시작하는 최소 PDF 바이트
  const pdfBytes = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34])
  return new HttpResponse(pdfBytes, {
    headers: {
      'Content-Type': 'application/pdf',
      'Content-Disposition': `attachment; filename="${key}.pdf"`,
    },
  })
})

/**
 * POST /api/v1/issues/bulk-transitions/available — 일괄 가용 전환 교집합 조회 핸들러.
 * 각 이슈의 가용전환을 구한 뒤 toStateKey 기준으로 교집합 계산.
 * 분기 순서 (backend 일치):
 *   (1) 미존재·소프트삭제·ATLAS-NOWF 이슈 → unresolvedIssueKeys에 추가
 *   (2) 성공분의 가용전환 교집합 계산
 *   (3) 성공 → 200 + { data: { transitions, unresolvedIssueKeys } }
 * 응답: { data: { transitions: [...], unresolvedIssueKeys: [...] } }
 */
const bulkAvailableTransitionsHandler = http.post(
  '/api/v1/issues/bulk-transitions/available',
  async ({ request }) => {
    const body = await request.clone().json() as { issueKeys?: string[] }
    const issueKeys = body.issueKeys ?? []

    const unresolvedIssueKeys: string[] = []
    const transitionSets: MockTransitionItem[][] = []

    for (const key of issueKeys) {
      const found = resolveIssue(key)
      if (found === undefined) {
        unresolvedIssueKeys.push(key)
        continue
      }
      // 워크플로우 미설정 이슈 (ATLAS-NOWF) → unresolved
      if (key === issueAtlasNoWorkflowFixture.key) {
        unresolvedIssueKeys.push(key)
        continue
      }
      transitionSets.push(getAvailableTransitions(found.currentStateKey))
    }

    // 성공 분이 없으면 빈 교집합
    let transitions: MockTransitionItem[] = []
    if (transitionSets.length > 0) {
      const [first, ...rest] = transitionSets
      transitions = rest.reduce(
        (acc, cur) => intersectByToStateKey(acc, cur),
        first ?? [],
      )
    }

    return HttpResponse.json({ data: { transitions, unresolvedIssueKeys } })
  },
)

/**
 * POST /api/v1/issues/:key/clone — 이슈 클론 핸들러 (FR-IS-06).
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404 ISSUE_NOT_FOUND
 *   (2) 성공 → 201 + { data: 클론된 IssueResponse }
 *       stateful: createdIssues에 영속해야 navigate 후 GET /issues/{newKey} 가 동작.
 *
 * 클론 키 생성: 원본 키의 숫자 부분을 최대값+100으로 증가 (fixture 범위 밖 고유 키 보장).
 */
const cloneIssueHandler = http.post('/api/v1/issues/:key/clone', async ({ params, request }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_NOT_FOUND', message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }

  const body = await request.clone().json() as {
    includeAssignee?: boolean
    summaryOverride?: string
  }

  // 클론본 키 생성 — 프로젝트 키 접두사 + 기존 최대 번호 + 1
  const projectPrefix = found.projectKey
  const existingKeys = [
    ...Object.keys(issueFixtureMap),
    ...Array.from(createdIssues.keys()),
  ]
  const maxNum = existingKeys
    .filter((k) => k.startsWith(`${projectPrefix}-`))
    .map((k) => parseInt(k.slice(projectPrefix.length + 1), 10))
    .filter((n) => !isNaN(n))
    .reduce((max, n) => Math.max(max, n), 0)
  const newKey = `${projectPrefix}-${maxNum + 1}`

  const includeAssignee = body.includeAssignee !== false
  const newSummary = body.summaryOverride?.trim() !== '' && body.summaryOverride !== undefined
    ? body.summaryOverride
    : found.summary

  const cloned: IssueResponse = {
    ...found,
    key: newKey,
    // 클론본은 초기 상태(open)로 시작 — version 0, 날짜 초기화
    currentStateKey: 'open',
    assigneeId: includeAssignee ? found.assigneeId : null,
    summary: newSummary,
    version: 0,
    createdAt: new Date().toISOString(),
    updatedAt: null,
    // resolution은 클론 시 초기화 (DONE 상태에서 복제해도 새 이슈는 open)
    resolution: undefined,
    descriptionHtml: null,
  }

  // stateful 영속 — GET /api/v1/issues/{newKey} 가 즉시 동작하도록
  createdIssues.set(newKey, cloned)

  return HttpResponse.json({ data: cloned }, { status: 201 })
})

/**
 * PATCH /api/v1/issues/:key/parent — 부모 이슈 설정/해제 핸들러 (FR-LK-01).
 *
 * issueOverrides에 parent 필드를 직접 변이하여 영속한다.
 * 이렇게 해야 이슈 쿼리 invalidate/refetch 후에도 parent 상태가 유지된다.
 * (이게 안 되면 가짜그린 — 새로고침 시 parent 사라짐)
 *
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404 ISSUE_NOT_FOUND
 *   (2) parentKey === key → 422 PARENT_SELF_REFERENCE
 *   (3) 성공 → 200 { data: { key, parent?: { key, summary } } }
 *       parentKey가 null이면 parent 키 자체 생략 (@JsonInclude(NON_NULL) 미러)
 */
const setParentHandler = http.patch('/api/v1/issues/:key/parent', async ({ params, request }) => {
  const key = params['key'] as string
  const found = resolveIssue(key)
  if (found === undefined) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_NOT_FOUND', message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }

  const body = (await request.clone().json()) as { parentKey?: string | null }
  const parentKey = body.parentKey ?? null

  // 자기 자신을 부모로 설정 방지
  if (parentKey !== null && parentKey === key) {
    return HttpResponse.json(
      { errorCode: 'PARENT_SELF_REFERENCE', message: '자기 자신을 부모로 설정할 수 없습니다' },
      { status: 422 },
    )
  }

  // issueOverrides에 parent 변이 영속 (invalidate refetch 후 롤백 방지 — 설계 주석 참조)
  let parentRef: { key: string; summary: string } | undefined = undefined
  if (parentKey !== null) {
    // 부모 이슈를 조회해 summary를 채운다. 없으면 기본 summary 사용.
    const parentIssue = resolveIssue(parentKey)
    parentRef = {
      key: parentKey,
      summary: parentIssue?.summary ?? `${parentKey} 이슈`,
    }
  }

  const updated: IssueResponse = {
    ...found,
    parent: parentRef,
  }
  issueOverrides.set(key, updated)
  if (createdIssues.has(key)) {
    createdIssues.set(key, updated)
  }

  // 응답 구성 — parentKey가 null이면 parent 키 자체 생략 (@JsonInclude(NON_NULL) 미러)
  if (parentRef !== undefined) {
    return HttpResponse.json({ data: { key, parent: parentRef } })
  }
  return HttpResponse.json({ data: { key } })
})

// ─────────────────────────────────────────────────────────────────────────────
// 에픽 자식 이슈 stateful store — FR-EP-01
// key: 에픽 이슈 키, value: 자식 이슈 키 Set
// ─────────────────────────────────────────────────────────────────────────────

const epicChildrenStore = new Map<string, Set<string>>()

/** 에픽 자식 이슈 store 초기화 — resetIssueState 내에서 호출하도록 한다. */
function resetEpicChildrenStore(): void {
  epicChildrenStore.clear()
}

/**
 * 에픽 자식 이슈 목록 조회 핸들러 (FR-EP-01).
 * GET /api/v1/issues/:epicKey/epic-children → 200 + `{ data: { children: [...] } }`
 * epicChildrenStore에서 현재 연결 상태를 파생해 응답한다.
 */
const getEpicChildrenHandler = http.get(
  '/api/v1/issues/:epicKey/epic-children',
  ({ params }) => {
    const epicKey = params['epicKey'] as string
    const found = resolveIssue(epicKey)
    if (found === undefined) {
      return HttpResponse.json(
        { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: `에픽을 찾을 수 없습니다: ${epicKey}` },
        { status: 404 },
      )
    }

    const childKeys = epicChildrenStore.get(epicKey) ?? new Set<string>()
    const children = Array.from(childKeys)
      .map((childKey) => {
        const child = resolveIssue(childKey)
        if (child === undefined) return undefined
        return {
          key: child.key,
          summary: child.summary,
          typeKey: child.typeKey ?? null,
          currentStateKey: child.currentStateKey,
        }
      })
      .filter((c): c is NonNullable<typeof c> => c !== undefined)

    return HttpResponse.json({ data: { children } })
  },
)

/**
 * 에픽 자식 이슈 연결 핸들러 (FR-EP-01).
 * POST /api/v1/issues/:epicKey/epic-children body { childKey: string } → 201
 * epicChildrenStore에 연결 상태를 영속한다 (stateful).
 */
const connectEpicChildHandler = http.post(
  '/api/v1/issues/:epicKey/epic-children',
  async ({ params, request }) => {
    const epicKey = params['epicKey'] as string
    const epicFound = resolveIssue(epicKey)
    if (epicFound === undefined) {
      return HttpResponse.json(
        { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: `에픽을 찾을 수 없습니다: ${epicKey}` },
        { status: 404 },
      )
    }

    const body = await request.clone().json() as { childKey?: string }
    const childKey = body.childKey ?? ''

    const childFound = resolveIssue(childKey)
    if (childFound === undefined) {
      return HttpResponse.json(
        { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: `자식 이슈를 찾을 수 없습니다: ${childKey}` },
        { status: 404 },
      )
    }

    // 자기 자신 연결 방지
    if (childKey === epicKey) {
      return HttpResponse.json(
        { errorCode: 'ISSUE_EPIC_CHILD_SELF_REFERENCE', message: '자기 자신을 자식으로 연결할 수 없습니다' },
        { status: 422 },
      )
    }

    const childKeys = epicChildrenStore.get(epicKey) ?? new Set<string>()
    // 이미 연결된 자식인 경우 409
    if (childKeys.has(childKey)) {
      return HttpResponse.json(
        { errorCode: 'ISSUE_EPIC_CHILD_ALREADY_LINKED', message: '이미 연결된 이슈입니다' },
        { status: 409 },
      )
    }

    childKeys.add(childKey)
    epicChildrenStore.set(epicKey, childKeys)

    // 자식 이슈의 epic 필드를 issueOverrides에 반영한다 (단건 GET refetch 시 반영).
    const updatedChild: IssueResponse = {
      ...childFound,
      epic: { key: epicFound.key, summary: epicFound.summary },
    }
    issueOverrides.set(childKey, updatedChild)

    const responseData = {
      key: childFound.key,
      summary: childFound.summary,
      typeKey: childFound.typeKey ?? null,
      currentStateKey: childFound.currentStateKey,
    }
    return HttpResponse.json({ data: responseData }, { status: 201 })
  },
)

/**
 * 에픽 진행률 조회 핸들러 (FR-EP-02).
 * GET /api/v1/epics/:key/progress → 200 + `{ data: EpicProgressResponse }`
 * epicChildrenStore + issueOverrides/issueFixtureMap에서 자식 이슈 상태를 읽어
 * softwareDefaultFixture 카테고리 기준으로 진행률을 파생 집계한다.
 * 자식 연결/해제(connectEpicChildHandler/disconnectEpicChildHandler) 후 즉시 반영.
 */
const getEpicProgressHandler = http.get('/api/v1/epics/:key/progress', ({ params }) => {
  const epicKey = params['key'] as string
  const found = resolveIssue(epicKey)
  if (found === undefined) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: `에픽을 찾을 수 없습니다: ${epicKey}` },
      { status: 404 },
    )
  }

  const childKeys = epicChildrenStore.get(epicKey) ?? new Set<string>()
  const stateMap = new Map(softwareDefaultFixture.states.map((s) => [s.key, s.category]))

  let todoCount = 0
  let inProgressCount = 0
  let doneCount = 0

  for (const childKey of childKeys) {
    const child = resolveIssue(childKey)
    if (child === undefined) continue
    const category = stateMap.get(child.currentStateKey)
    if (category === 'TODO') {
      todoCount += 1
    } else if (category === 'IN_PROGRESS') {
      inProgressCount += 1
    } else if (category === 'DONE') {
      doneCount += 1
    }
  }

  const total = todoCount + inProgressCount + doneCount
  const donePercentage = total === 0 ? 0 : Math.round((doneCount / total) * 100)

  return HttpResponse.json({
    data: {
      total,
      done: doneCount,
      donePercentage,
      byCategory: {
        todo: todoCount,
        inProgress: inProgressCount,
        done: doneCount,
      },
    },
  })
})

/**
 * 에픽 자식 이슈 연결 해제 핸들러 (FR-EP-01).
 * DELETE /api/v1/issues/:epicKey/epic-children/:childKey → 204
 * epicChildrenStore에서 연결을 제거하고 자식 이슈의 epic 필드를 클리어한다.
 */
const disconnectEpicChildHandler = http.delete(
  '/api/v1/issues/:epicKey/epic-children/:childKey',
  ({ params }) => {
    const epicKey = params['epicKey'] as string
    const childKey = params['childKey'] as string

    const epicFound = resolveIssue(epicKey)
    if (epicFound === undefined) {
      return HttpResponse.json(
        { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: `에픽을 찾을 수 없습니다: ${epicKey}` },
        { status: 404 },
      )
    }

    const childFound = resolveIssue(childKey)
    if (childFound === undefined) {
      return HttpResponse.json(
        { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: `자식 이슈를 찾을 수 없습니다: ${childKey}` },
        { status: 404 },
      )
    }

    const childKeys = epicChildrenStore.get(epicKey)
    if (childKeys !== undefined) {
      childKeys.delete(childKey)
    }

    // 자식 이슈의 epic 필드를 issueOverrides에서 클리어한다.
    const updatedChild: IssueResponse = {
      ...childFound,
      epic: undefined,
    }
    issueOverrides.set(childKey, updatedChild)

    return new HttpResponse(null, { status: 204 })
  },
)

/** E2E / 단위 테스트 격리용 — epic store 포함 전체 state 초기화. */
export function resetIssueStateWithEpic(): void {
  resetIssueState()
  resetEpicChildrenStore()
}

export const issueHandlers = [
  listIssuesHandler,
  getIssueHandler,
  createIssueHandler,
  updateIssueHandler,
  changeComponentsHandler,
  changeAffectsVersionsHandler,
  changeFixVersionsHandler,
  changeAssigneeHandler,
  deleteIssueHandler,
  getTransitionsHandler,
  transitionHandler,
  bulkAvailableTransitionsHandler,
  downloadIssuePdfHandler,
  cloneIssueHandler,
  setParentHandler,
  getEpicChildrenHandler,
  connectEpicChildHandler,
  disconnectEpicChildHandler,
  getEpicProgressHandler,
]
