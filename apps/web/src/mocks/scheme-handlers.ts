// 워크플로우 스킴 MSW 핸들러 + 시뮬레이션 errorCode
import { http, HttpResponse } from 'msw'
import {
  allSchemeFixtures,
  assignmentFixtures,
  makeScheme,
  makeMapping,
} from './scheme-fixtures'
import type { SchemeDetailResponse, SchemeSummaryResponse } from './scheme-fixtures'

/** 스킴 배열의 요약 필드만 추출 (mappings 제외) */
const toSummary = (scheme: SchemeDetailResponse): SchemeSummaryResponse => ({
  schemeKey: scheme.schemeKey,
  name: scheme.name,
  description: scheme.description,
  isStandard: scheme.isStandard,
  usedByProjectsCount: scheme.usedByProjectsCount,
  mappingsCount: scheme.mappingsCount,
})

/** backend 원본 어휘(key/isDefault)의 할당 가능 스킴 응답 형태 — assignableSchemeResponseSchema 파싱 전 mock 응답 shape */
interface AssignableSchemeBackendShape {
  id: number
  key: string
  name: string
  description: string
  isDefault: boolean
}

/** 스킴 fixture를 backend 원본 어휘(key/isDefault)로 변환하는 helper */
const toAssignableBackendShape = (scheme: SchemeDetailResponse, index: number): AssignableSchemeBackendShape => ({
  id: index + 1,
  key: scheme.schemeKey,
  name: scheme.name,
  description: scheme.description,
  isDefault: scheme.isStandard,
})

/** 스킴 키로 fixture를 찾는 helper */
const findScheme = (schemeKey: string): SchemeDetailResponse | undefined =>
  allSchemeFixtures.find((s) => s.schemeKey === schemeKey)

/** 표준 스킴인지 확인하는 helper */
const isStandard = (schemeKey: string): boolean => {
  const scheme = findScheme(schemeKey)
  return scheme?.isStandard === true
}

/** 스킴이 프로젝트에서 사용 중인지 확인하는 helper */
const isInUse = (schemeKey: string): boolean => {
  const scheme = findScheme(schemeKey)
  return (scheme?.usedByProjectsCount ?? 0) > 0
}

/** 매핑 ID 기준 fixture 내 매핑 존재 여부 확인 helper */
const findMappingById = (schemeKey: string, mappingId: number) => {
  const scheme = findScheme(schemeKey)
  return scheme?.mappings.find((m) => m.id === mappingId)
}

/** 이슈 타입 키 기준 중복 매핑 존재 여부 확인 helper */
const hasDuplicateMapping = (schemeKey: string, issueTypeKey: string | null): boolean => {
  const scheme = findScheme(schemeKey)
  if (scheme === undefined) return false
  return scheme.mappings.some((m) => m.issueTypeKey === issueTypeKey)
}

/** 다음 매핑 ID 생성 helper (fixture 내 최대 ID + 1) */
const nextMappingId = (): number => {
  const allIds = allSchemeFixtures.flatMap((s) => s.mappings.map((m) => m.id))
  return Math.max(0, ...allIds) + 1
}

/**
 * project-workflow BC 워크플로우 스킴 MSW 핸들러 목록.
 *
 * 10 endpoint 지원:
 * - GET    /api/v1/workflow-schemes              — 스킴 목록
 * - GET    /api/v1/workflow-schemes/:schemeKey   — 스킴 단건 (mappings 포함)
 * - POST   /api/v1/workflow-schemes              — 스킴 생성
 * - PUT    /api/v1/workflow-schemes/:schemeKey   — 스킴 수정
 * - DELETE /api/v1/workflow-schemes/:schemeKey   — 스킴 삭제
 * - POST   /api/v1/workflow-schemes/:schemeKey/mappings                — 매핑 추가
 * - DELETE /api/v1/workflow-schemes/:schemeKey/mappings/:mappingId     — 매핑 삭제
 * - GET    /api/v1/projects/:projectKey/workflow-scheme               — 할당 조회
 * - PUT    /api/v1/projects/:projectKey/workflow-scheme               — 할당 갱신
 * - GET    /api/v1/projects/:projectKey/assignable-workflow-schemes   — 할당 가능 스킴 목록 (backend key/isDefault 원형 응답)
 *
 * errorCode 시뮬레이션 규칙:
 * - 표준 스킴 DELETE                     → 409 SCHEME_STANDARD_NOT_DELETABLE
 * - usedByProjectsCount > 0 DELETE       → 409 SCHEME_IN_USE
 * - 표준 스킴 PUT (name 포함)             → 409 SCHEME_STANDARD_FIELD_LOCKED (description 만 변경은 허용)
 * - 중복 issueTypeKey 매핑 추가           → 409 MAPPING_DUPLICATE
 * - default 매핑 중복 추가               → 409 MAPPING_DEFAULT_DUPLICATE
 * - X-Mock-Forbidden 헤더               → 403 FORBIDDEN
 * - 미존재 schemeKey                     → 404 SCHEME_NOT_FOUND
 * - 미존재 mappingId                     → 404 MAPPING_NOT_FOUND
 * - 미존재 프로젝트 할당                  → 404 ASSIGNMENT_NOT_FOUND
 */
export const schemeHandlers = [
  /** GET /api/v1/workflow-schemes — 스킴 목록 */
  http.get('/api/v1/workflow-schemes', () => {
    const summaries = allSchemeFixtures.map(toSummary)
    return HttpResponse.json({ data: summaries })
  }),

  /** GET /api/v1/workflow-schemes/:schemeKey — 스킴 단건 상세 */
  http.get('/api/v1/workflow-schemes/:schemeKey', ({ params }) => {
    const schemeKey = params['schemeKey'] as string
    const scheme = findScheme(schemeKey)

    if (scheme === undefined) {
      return HttpResponse.json({ errorCode: 'SCHEME_NOT_FOUND', message: '워크플로우 스킴을 찾을 수 없습니다' }, { status: 404 })
    }

    return HttpResponse.json({ data: scheme })
  }),

  /** POST /api/v1/workflow-schemes — 스킴 생성 */
  http.post('/api/v1/workflow-schemes', async ({ request }) => {
    if (request.headers.get('X-Mock-Forbidden') === 'true') {
      return HttpResponse.json({ errorCode: 'FORBIDDEN', message: '권한이 없습니다' }, { status: 403 })
    }

    const body = await request.json() as { name?: string; description?: string }
    const newSchemeKey = `custom-scheme-${Date.now()}`
    const created = makeScheme({
      schemeKey: newSchemeKey,
      name: body.name ?? '새 스킴',
      description: body.description ?? '',
      isStandard: false,
      usedByProjectsCount: 0,
      mappingsCount: 0,
    })

    return HttpResponse.json({ data: created }, { status: 201 })
  }),

  /** PUT /api/v1/workflow-schemes/:schemeKey — 스킴 수정 */
  http.put('/api/v1/workflow-schemes/:schemeKey', async ({ params, request }) => {
    const schemeKey = params['schemeKey'] as string
    const scheme = findScheme(schemeKey)

    if (scheme === undefined) {
      return HttpResponse.json({ errorCode: 'SCHEME_NOT_FOUND', message: '워크플로우 스킴을 찾을 수 없습니다' }, { status: 404 })
    }

    const body = await request.json() as { name?: string; description?: string }
    const hasNameChange = body.name !== undefined

    if (isStandard(schemeKey) && hasNameChange) {
      return HttpResponse.json({ errorCode: 'SCHEME_STANDARD_FIELD_LOCKED', message: '표준 스킴의 이름은 변경할 수 없습니다' }, { status: 409 })
    }

    const updated = toSummary({
      ...scheme,
      name: body.name ?? scheme.name,
      description: body.description ?? scheme.description,
    })

    return HttpResponse.json({ data: updated })
  }),

  /** DELETE /api/v1/workflow-schemes/:schemeKey — 스킴 삭제 */
  http.delete('/api/v1/workflow-schemes/:schemeKey', ({ params }) => {
    const schemeKey = params['schemeKey'] as string
    const scheme = findScheme(schemeKey)

    if (scheme === undefined) {
      return HttpResponse.json({ errorCode: 'SCHEME_NOT_FOUND', message: '워크플로우 스킴을 찾을 수 없습니다' }, { status: 404 })
    }

    if (isStandard(schemeKey)) {
      return HttpResponse.json({ errorCode: 'SCHEME_STANDARD_NOT_DELETABLE', message: '표준 스킴은 삭제할 수 없습니다' }, { status: 409 })
    }

    if (isInUse(schemeKey)) {
      return HttpResponse.json({ errorCode: 'SCHEME_IN_USE', message: '스킴이 프로젝트에서 사용 중입니다' }, { status: 409 })
    }

    return new HttpResponse(null, { status: 204 })
  }),

  /** POST /api/v1/workflow-schemes/:schemeKey/mappings — 매핑 추가 */
  http.post('/api/v1/workflow-schemes/:schemeKey/mappings', async ({ params, request }) => {
    const schemeKey = params['schemeKey'] as string

    if (findScheme(schemeKey) === undefined) {
      return HttpResponse.json({ errorCode: 'SCHEME_NOT_FOUND', message: '워크플로우 스킴을 찾을 수 없습니다' }, { status: 404 })
    }

    const body = await request.json() as { issueTypeKey: string | null; workflowKey: string }
    const isDefaultMapping = body.issueTypeKey === null

    if (isDefaultMapping && hasDuplicateMapping(schemeKey, null)) {
      return HttpResponse.json({ errorCode: 'MAPPING_DEFAULT_DUPLICATE', message: '기본 매핑이 이미 존재합니다' }, { status: 409 })
    }

    if (!isDefaultMapping && hasDuplicateMapping(schemeKey, body.issueTypeKey)) {
      return HttpResponse.json({ errorCode: 'MAPPING_DUPLICATE', message: '이미 존재하는 이슈 타입 매핑입니다' }, { status: 409 })
    }

    const newMapping = makeMapping({
      id: nextMappingId(),
      issueTypeKey: body.issueTypeKey,
      issueTypeName: body.issueTypeKey,
      workflowKey: body.workflowKey,
      isDefault: isDefaultMapping,
    })

    return HttpResponse.json({ data: newMapping }, { status: 201 })
  }),

  /** DELETE /api/v1/workflow-schemes/:schemeKey/mappings/:mappingId — 매핑 삭제 */
  http.delete('/api/v1/workflow-schemes/:schemeKey/mappings/:mappingId', ({ params }) => {
    const schemeKey = params['schemeKey'] as string
    const mappingId = Number(params['mappingId'])

    if (findScheme(schemeKey) === undefined) {
      return HttpResponse.json({ errorCode: 'SCHEME_NOT_FOUND', message: '워크플로우 스킴을 찾을 수 없습니다' }, { status: 404 })
    }

    if (findMappingById(schemeKey, mappingId) === undefined) {
      return HttpResponse.json({ errorCode: 'MAPPING_NOT_FOUND', message: '매핑을 찾을 수 없습니다' }, { status: 404 })
    }

    return new HttpResponse(null, { status: 204 })
  }),

  /** GET /api/v1/projects/:projectKey/workflow-scheme — 프로젝트 스킴 할당 조회 */
  http.get('/api/v1/projects/:projectKey/workflow-scheme', ({ params }) => {
    const projectKey = params['projectKey'] as string
    const assignment = assignmentFixtures.find((a) => a.projectKey === projectKey)

    if (assignment === undefined) {
      return HttpResponse.json({ errorCode: 'ASSIGNMENT_NOT_FOUND', message: '프로젝트에 할당된 스킴이 없습니다' }, { status: 404 })
    }

    return HttpResponse.json({ data: assignment })
  }),

  /** PUT /api/v1/projects/:projectKey/workflow-scheme — 프로젝트 스킴 할당 갱신 (UPSERT) */
  http.put('/api/v1/projects/:projectKey/workflow-scheme', async ({ params, request }) => {
    const projectKey = params['projectKey'] as string
    const body = await request.json() as { schemeKey: string }

    const targetScheme = findScheme(body.schemeKey)
    if (targetScheme === undefined) {
      return HttpResponse.json({ errorCode: 'SCHEME_NOT_FOUND', message: '워크플로우 스킴을 찾을 수 없습니다' }, { status: 404 })
    }

    const assignment = {
      projectKey,
      schemeKey: body.schemeKey,
      schemeName: targetScheme.name,
    }

    return HttpResponse.json({ data: assignment })
  }),

  /**
   * GET /api/v1/projects/:projectKey/assignable-workflow-schemes — 프로젝트 할당 가능 스킴 목록.
   * backend 원본 어휘(`key`/`isDefault`)로 응답한다 — 프론트 어휘(`schemeKey`/`isStandard`) 정규화는
   * `assignableSchemeResponseSchema`의 `.transform()`이 경계에서 담당하므로 이 mock이 미리 바꾸면 안 된다.
   */
  http.get('/api/v1/projects/:projectKey/assignable-workflow-schemes', () => {
    return HttpResponse.json({ data: allSchemeFixtures.map(toAssignableBackendShape) })
  }),
]
