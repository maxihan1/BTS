// issue-tracking BC MSW mock handlers — GET 목록/단건 + POST 생성 + PATCH 수정 + DELETE 삭제
// 소프트 삭제 stateful: deletedKeys 와 createdIssues 로 모듈-스코프 상태 유지 (E2E 검증 gap-H + E2E-1 happy path).
import { http, HttpResponse } from 'msw'
import {
  issuePageFixture,
  issueAtlas1Fixture,
  issueAtlas2Fixture,
  issueAtlas3Fixture,
} from './issue-fixtures'
import { allIssueTypeFixtures } from './issue-type-fixtures'
import type { IssueResponse, IssuePage } from '@/api/issues'

/** 이슈 생성 성공 응답 픽스처 */
export const createdIssueFixture = {
  key: 'ATLAS-42',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '새 이슈 제목',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  version: 0,
  createdAt: '2026-05-27T00:00:00Z',
  updatedAt: null,
  typeId: 2,
  typeKey: 'task',
  typeName: '작업',
}

const issueFixtureMap: Record<string, IssueResponse> = {
  'ATLAS-1': issueAtlas1Fixture,
  'ATLAS-2': issueAtlas2Fixture,
  'ATLAS-3': issueAtlas3Fixture,
}

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

/** E2E / 단위 테스트 격리용 — 모듈-스코프 state 초기화. 각 test setup 에서 호출. */
export function resetIssueState(): void {
  deletedKeys.clear()
  createdIssues.clear()
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
  const found = createdIssues.get(key) ?? issueFixtureMap[key]
  if (found === undefined) {
    return HttpResponse.json(
      { message: `이슈를 찾을 수 없습니다: ${key}` },
      { status: 404 },
    )
  }
  return HttpResponse.json({ data: found })
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

/**
 * PATCH /api/v1/issues/:key — 이슈 수정 핸들러.
 * 분기 순서 (backend 일치):
 *   (1) 이슈 not-found → 404
 *   (2) typeId 검증 실패 → 404
 *   (3) VERSION_CONFLICT 트리거 → 409
 *   (4) 성공 → 200 + { data: 수정된 IssueResponse(version+1) }
 *
 * body.expectedVersion 은 낙관적 잠금(OCC) 필드 — updateIssue API 함수 전송 형태와 일치.
 */
const updateIssueHandler = http.patch('/api/v1/issues/:key', async ({ params, request }) => {
  const key = params['key'] as string
  const found = createdIssues.get(key) ?? issueFixtureMap[key]
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

  // (3) VERSION_CONFLICT 회귀 가드 — E2E-5 트리거
  if (body.summary === MOCK_CONFLICT_TRIGGER) {
    return HttpResponse.json(
      { errorCode: 'VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
      { status: 409 },
    )
  }

  // (4) 성공
  const updated: IssueResponse = {
    ...found,
    summary: body.summary ?? found.summary,
    typeId: resolvedTypeId,
    typeKey: resolvedTypeKey,
    typeName: resolvedTypeName,
    version: found.version + 1,
    updatedAt: new Date().toISOString(),
  }
  // POST 로 생성된 이슈가 수정되면 stateful 보관도 갱신.
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

export const issueHandlers = [
  listIssuesHandler,
  getIssueHandler,
  createIssueHandler,
  updateIssueHandler,
  deleteIssueHandler,
]
