// issue-tracking BC MSW mock handlers — GET 목록/단건 + POST /api/v1/issues (생성)
import { http, HttpResponse } from 'msw'
import {
  issuePageFixture,
  issueAtlas1Fixture,
  issueAtlas2Fixture,
  issueAtlas3Fixture,
} from './issue-fixtures'

/** 이슈 생성 성공 응답 픽스처 */
export const createdIssueFixture = {
  key: 'ATLAS-42',
  id: '00000000-0000-0000-0000-000000000042',
  projectKey: 'ATLAS',
  summary: '새 이슈 제목',
  currentStateKey: 'OPEN',
  reporterId: '00000000-0000-0000-0000-000000000001',
  version: 0,
  createdAt: '2026-05-27T00:00:00Z',
  updatedAt: null,
}

const issueFixtureMap: Record<string, typeof issueAtlas1Fixture> = {
  'ATLAS-1': issueAtlas1Fixture,
  'ATLAS-2': issueAtlas2Fixture,
  'ATLAS-3': issueAtlas3Fixture,
}

/** GET /api/v1/issues — 이슈 목록 페이징 조회 (Spring Page 구조, 래퍼 없음) */
const listIssuesHandler = http.get('/api/v1/issues', () => {
  return HttpResponse.json(issuePageFixture)
})

/** GET /api/v1/issues/:key — 이슈 단건 조회 (`{ data: IssueResponse }` 래퍼) */
const getIssueHandler = http.get('/api/v1/issues/:key', ({ params }) => {
  const key = params['key'] as string
  const found = issueFixtureMap[key]
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
  const body = await request.json() as { projectKey?: string; summary?: string }
  if (body.projectKey === 'INVALID') {
    return HttpResponse.json(
      { errorCode: 'PROJECT_NOT_FOUND', message: '프로젝트를 찾을 수 없습니다' },
      { status: 404 },
    )
  }
  return HttpResponse.json(
    { data: { ...createdIssueFixture, projectKey: body.projectKey ?? 'ATLAS', summary: body.summary ?? '' } },
    { status: 201 },
  )
})

export const issueHandlers = [listIssuesHandler, getIssueHandler, createIssueHandler]
