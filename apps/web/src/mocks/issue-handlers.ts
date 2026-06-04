// issue-tracking BC MSW mock handlers — GET 목록/단건 + POST 생성 + PATCH 수정 + PATCH /assignee + DELETE 삭제
// 소프트 삭제 stateful: deletedKeys 와 createdIssues 로 모듈-스코프 상태 유지 (E2E 검증 gap-H + E2E-1 happy path).
import { http, HttpResponse } from 'msw'
import {
  issuePageFixture,
  issueAtlas1Fixture,
  issueAtlas2Fixture,
  issueAtlas3Fixture,
  issueAtlas4Fixture,
  issueAtlas5Fixture,
  issueAtlasNoWorkflowFixture,
} from './issue-fixtures'
import { allIssueTypeFixtures } from './issue-type-fixtures'
import { softwareDefaultFixture } from './workflow-fixtures'
import { userListFixture } from './user-fixtures'
import type { IssueResponse, IssuePage } from '@/api/issues'

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
}

const issueFixtureMap: Record<string, IssueResponse> = {
  'ATLAS-1': issueAtlas1Fixture,
  'ATLAS-2': issueAtlas2Fixture,
  'ATLAS-3': issueAtlas3Fixture,
  'ATLAS-4': issueAtlas4Fixture,
  'ATLAS-5': issueAtlas5Fixture,
  'ATLAS-NOWF': issueAtlasNoWorkflowFixture,
}

/** E2E 시나리오용 localStorage 키 — S4 재오픈 검증 시 done+resolution 이슈로 응답 분기 */
const LS_KEY_RESOLUTION_ISSUE = '__bts_e2e_resolution_issue'

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

// 이슈 현재 상태 오버라이드 — issueFixtureMap 원본 불변 유지 + 전이/수정(PATCH) 결과 반영.
// key: 이슈 키, value: 갱신된 IssueResponse (전이 또는 타입/요약 수정 후)
const issueOverrides = new Map<string, IssueResponse>()

/** E2E / 단위 테스트 격리용 — 모듈-스코프 state 초기화. 각 test setup 에서 호출. */
export function resetIssueState(): void {
  deletedKeys.clear()
  createdIssues.clear()
  issueOverrides.clear()
}

function buildFilteredPage(): IssuePage {
  const fixtureContent = issuePageFixture.content.filter((i) => !deletedKeys.has(i.key))
  const createdContent = Array.from(createdIssues.values()).filter((i) => !deletedKeys.has(i.key))
  const content = [...fixtureContent, ...createdContent]
  return {
    ...issuePageFixture,
    content,
    totalElements: content.length,
    empty: content.length === 0,
  }
}

/** GET /api/v1/issues — 이슈 목록 페이징 조회. 소프트 삭제된 이슈는 응답에서 제외 (gap-H). */
const listIssuesHandler = http.get('/api/v1/issues', () => {
  return HttpResponse.json(buildFilteredPage())
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
  // 상태 오버라이드 → 생성된 이슈 → 정적 fixture 순으로 조회
  let found = issueOverrides.get(key) ?? createdIssues.get(key) ?? issueFixtureMap[key]
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }

  // S4 E2E 플래그: done+resolution(Fixed) 이슈로 응답 분기 (오버라이드가 없는 경우만)
  // 오버라이드가 있으면(전이 후 refetch) 플래그 무시 — stateful 결과 우선
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
  const withHtml: IssueResponse = {
    ...found,
    descriptionHtml: renderDescriptionHtml(found.description),
  }
  return HttpResponse.json({ data: withHtml })
})

/**
 * POST /api/v1/issues — 이슈 생성 핸들러.
 * projectKey 가 'INVALID' 이면 PROJECT_NOT_FOUND(404) 반환.
 * 그 외는 201 + { data: createdIssueFixture } 반환.
 */
const createIssueHandler = http.post('/api/v1/issues', async ({ request }) => {
  const body = await request.clone().json() as { projectKey?: string; summary?: string }
  if (body.projectKey === 'INVALID') {
    return HttpResponse.json(
      { errorCode: 'PROJECT_NOT_FOUND', message: '프로젝트를 찾을 수 없습니다' },
      { status: 404 },
    )
  }
  const created: IssueResponse = {
    ...createdIssueFixture,
    projectKey: body.projectKey ?? 'ATLAS',
    summary: body.summary ?? '',
  }
  // E2E-1 happy path 용 — POST 직후 GET 으로 조회 가능하도록 stateful 보관.
  createdIssues.set(created.key, created)
  return HttpResponse.json({ data: created }, { status: 201 })
})

/** E2E-5 회귀 가드 트리거 — summary 값이 이 문자열이면 409 VERSION_CONFLICT 응답. */
export const MOCK_CONFLICT_TRIGGER = '__TRIGGER_409__'

/** 전이 워크플로우 미설정 트리거 — toStatusKey 값이 이 문자열이면 422 응답. */
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
  // 오버라이드 → 생성된 이슈 → 정적 fixture 순서 (전이 핸들러와 동일). 반복 수정/전이 후 수정 시 최신 version 기준.
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
    version: found.version + 1,
    updatedAt: new Date().toISOString(),
  }
  // 수정 결과를 오버라이드에 보관 — invalidateQueries 후 GET 단건이 최신 타입/요약/5필드 반영 (refetch 롤백 방지).
  issueOverrides.set(key, updated)
  // POST 로 생성된 이슈는 목록(buildFilteredPage) 일관성을 위해 createdIssues 도 갱신.
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
 * 이슈 키로 현재 상태 조회 helper — issueOverrides → createdIssues → issueFixtureMap 순서.
 * 소프트 삭제된 키는 undefined 반환.
 */
function resolveIssue(key: string): IssueResponse | undefined {
  if (deletedKeys.has(key)) return undefined
  return issueOverrides.get(key) ?? createdIssues.get(key) ?? issueFixtureMap[key]
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
 * description 값에서 간단한 descriptionHtml 생성 — 단건 GET 모킹용.
 * 실제 Markdown 렌더링 대신 텍스트를 <p> 로 래핑.
 */
function renderDescriptionHtml(description: string | null): string | null {
  if (description === null) return null
  return `<p>${description}</p>`
}

/**
 * 현재 이슈 상태 기준 가용전이 반환 helper.
 * softwareDefaultFixture 가 단일 출처 — 전이 직접 정의 금지.
 * toCategory를 toStateKey → states category lookup으로 enrichment.
 */
function getAvailableTransitions(
  currentStateKey: string,
): (typeof softwareDefaultFixture.transitions[0] & { toCategory: string | null })[] {
  const stateMap = new Map(softwareDefaultFixture.states.map((s) => [s.key, s.category]))
  return softwareDefaultFixture.transitions
    .filter((t) => t.fromStateKey === currentStateKey)
    .map((t) => ({
      ...t,
      toCategory: stateMap.get(t.toStateKey) ?? null,
    }))
}

/**
 * GET /api/v1/issues/:key/transitions — 현재 상태 기준 가용전이 목록 반환.
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
  const transitions = getAvailableTransitions(found.currentStateKey)
  return HttpResponse.json({ data: { transitions } })
})

/**
 * POST /api/v1/issues/:key/transition — 이슈 상태 전이 핸들러.
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) MOCK_NO_WORKFLOW_TRIGGER → 422 (워크플로우 미설정 시뮬)
 *   (3-a) expectedVersion 불일치 → 409 VERSION_CONFLICT (OCC 버전충돌)
 *   (3-b) MOCK_CONFLICT_TRIGGER → 409 TRANSITION_NOT_ALLOWED (전이거부)
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

  // (3-b) 전이거부 트리거 → 409 TRANSITION_NOT_ALLOWED
  if (toStatusKey === MOCK_CONFLICT_TRIGGER) {
    return HttpResponse.json(
      { errorCode: 'TRANSITION_NOT_ALLOWED', message: '허용되지 않는 전이입니다.' },
      { status: 409 },
    )
  }

  // (4) 성공 — currentStateKey 갱신 + version+1 + resolutionId stateful 보관
  // resolutionId가 있으면 resolution 객체를 찾아 채움 (invalidateQueries refetch 롤백 방지).
  const stateMap = new Map(softwareDefaultFixture.states.map((s) => [s.key, s.category]))
  const toCategory = stateMap.get(toStatusKey) ?? null
  // DONE 전이 시 resolution 영속, 비DONE 전이 시 resolution clear
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
    // 비DONE 전이 시 resolution clear
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
 * 전이 항목의 toStateKey 기준으로 두 전이 배열의 교집합을 반환한다.
 * 첫 번째 배열 항목 기준을 유지한다 (fromStateKey·name·key 등은 첫 이슈 기준 보존).
 * backend BulkAvailableTransitionsService.intersect 시맨틱과 동일.
 */
function intersectByToStateKey(
  base: typeof softwareDefaultFixture.transitions,
  other: typeof softwareDefaultFixture.transitions,
): typeof softwareDefaultFixture.transitions {
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
 * POST /api/v1/issues/bulk-transitions/available — 일괄 가용 전이 교집합 조회 핸들러.
 * 각 이슈의 가용전이를 구한 뒤 toStateKey 기준으로 교집합 계산.
 * 분기 순서 (backend 일치):
 *   (1) 미존재·소프트삭제·ATLAS-NOWF 이슈 → unresolvedIssueKeys에 추가
 *   (2) 성공분의 가용전이 교집합 계산
 *   (3) 성공 → 200 + { data: { transitions, unresolvedIssueKeys } }
 * 응답: { data: { transitions: [...], unresolvedIssueKeys: [...] } }
 */
const bulkAvailableTransitionsHandler = http.post(
  '/api/v1/issues/bulk-transitions/available',
  async ({ request }) => {
    const body = await request.clone().json() as { issueKeys?: string[] }
    const issueKeys = body.issueKeys ?? []

    const unresolvedIssueKeys: string[] = []
    const transitionSets: (typeof softwareDefaultFixture.transitions)[] = []

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
    let transitions: typeof softwareDefaultFixture.transitions = []
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

export const issueHandlers = [
  listIssuesHandler,
  getIssueHandler,
  createIssueHandler,
  updateIssueHandler,
  changeAssigneeHandler,
  deleteIssueHandler,
  getTransitionsHandler,
  transitionHandler,
  bulkAvailableTransitionsHandler,
  downloadIssuePdfHandler,
]
