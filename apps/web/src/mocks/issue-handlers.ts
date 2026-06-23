// issue-tracking BC MSW mock handlers — GET 목록/단건 + POST 생성 + PATCH 수정 + PATCH /assignee + DELETE 삭제
// 소프트 삭제 stateful: deletedKeys 와 createdIssues 로 모듈-스코프 상태 유지 (E2E 검증 gap-H + E2E-1 happy path).
// FR-PM-07: restrictedFields/noneditableFields 시나리오 시드 — field-permission store 파생.
import { http, HttpResponse } from 'msw'
import { setIssueEstimate, getIssueEstimate } from './worklog-handlers'
import { getStoredComponentsByIds } from './component-handlers'
import { getStoredProjectLead } from './project-lead-handlers'
import { getFieldPermissionsForProject } from './field-permission-handlers'
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
}

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
 * 그 외는 201 + { data: createdIssueFixture } 반환.
 *
 * FR-CM-03: 요청 componentIds를 응답에 에코.
 * 백엔드 default-assignee resolve 규칙(미할당일 때만):
 *   componentStore(component-handlers.ts 단일 출처)에 정보가 없으면 assigneeId=null 유지.
 *   X-MSW-Seed-Components 헤더로 시드된 컴포넌트 중 리드 보유 컴포넌트를
 *   name 오름차순 → id 오름차순(tiebreak) 정렬 후 첫 번째의 leadUserId가 assigneeId로 에코된다.
 */
const createIssueHandler = http.post('/api/v1/issues', async ({ request }) => {
  const body = await request.clone().json() as {
    projectKey?: string
    summary?: string
    componentIds?: string[]
    securityLevelId?: string | null
    customFields?: Record<string, unknown>
  }
  if (body.projectKey === 'INVALID') {
    return HttpResponse.json(
      { errorCode: 'PROJECT_NOT_FOUND', message: '프로젝트를 찾을 수 없습니다' },
      { status: 404 },
    )
  }

  const componentIds = body.componentIds ?? []

  // default-assignee resolve — componentStore(component-handlers.ts 단일 출처)에서 읽는다.
  // X-MSW-Seed-Components 헤더로 브라우저 시드된 컴포넌트에서 리드를 조회한다.
  // 정렬 기준: name 오름차순, name 동률이면 id 오름차순 (백엔드 DefaultAssigneeResolver 동일).
  let resolvedAssigneeId: string | null = createdIssueFixture.assigneeId
  if (resolvedAssigneeId === null && componentIds.length > 0) {
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

  const created: IssueResponse = {
    ...createdIssueFixture,
    projectKey: body.projectKey ?? 'ATLAS',
    summary: body.summary ?? '',
    componentIds,
    assigneeId: resolvedAssigneeId,
    securityLevelId: resolvedSecurityLevelId,
    customFields: resolvedCustomFields,
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
