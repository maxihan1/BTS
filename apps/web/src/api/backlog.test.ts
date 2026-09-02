// 백로그 및 스프린트 API 클라이언트 단위 테스트 — Zod 스키마 계약 + fetch 함수 검증 (FR-BL-01/02 D6/D7)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { backlogHandlers, LS_KEY_SPRINT_DELETE_FAIL } from '@/mocks/backlog-handlers'
import {
  resetBacklogStore,
  seedBacklog,
  DEFAULT_BACKLOG,
} from '@/mocks/backlog-fixtures'
import {
  resetBoardStore,
  createBoardInStore,
  setBoardActiveSprint,
  boardStore,
} from '@/mocks/board-fixtures'
import {
  backlogIssueSchema,
  sprintMetaSchema,
  backlogViewSchema,
  issueRankResultSchema,
  fetchBacklog,
  rerankIssue,
  assignToSprint,
  unassignFromSprint,
  createSprint,
  startSprint,
  completeSprint,
  updateSprint,
  deleteSprint,
} from './backlog'
import type { SprintMeta } from './backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — Zod v4 RFC4122 UUID 형식 필수
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const ISSUE_KEY_1 = 'ATLAS-1'
const ISSUE_KEY_2 = 'ATLAS-2'
const SPRINT_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
const ASSIGNEE_UUID = 'd4e5f6a7-b8c9-4123-8def-a12345678903'
/** 백로그를 스코프할 보드 UUID (FR-BD-04) */
const BOARD_ID = 'b0b1b2b3-c4d5-4e6f-8a9b-0c1d2e3f4a5b'

const backlogIssueFixture = {
  key: ISSUE_KEY_1,
  summary: '백로그 이슈 1',
  currentStateKey: 'todo',
  assigneeId: ASSIGNEE_UUID,
  priority: 2,
  rank: 'abc|00000z',
  version: 1,
  epicKey: null,
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

const backlogIssueNoAssignee = {
  key: ISSUE_KEY_2,
  summary: '백로그 이슈 2',
  currentStateKey: 'in_progress',
  assigneeId: null,
  priority: 3,
  rank: null,
  version: 2,
  epicKey: 'ATLAS-10',
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

const sprintMetaFixture = {
  sprintId: SPRINT_ID,
  name: '스프린트 1',
  goal: '목표 달성',
  status: 'PLANNED',
  startDate: '2026-07-01',
  endDate: '2026-07-14',
  version: 1,
}

const sprintMetaNullDates = {
  sprintId: SPRINT_ID,
  name: '날짜 없는 스프린트',
  goal: null,
  status: 'ACTIVE',
  startDate: null,
  endDate: null,
  version: 3,
}

const backlogViewFixture = {
  backlog: [backlogIssueFixture],
  sprints: [
    {
      sprint: sprintMetaFixture,
      issues: [backlogIssueNoAssignee],
    },
  ],
  truncated: false,
}

const issueRankResultFixture = {
  key: ISSUE_KEY_1,
  rank: 'abc|00001z',
  version: 2,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-1. backlogIssueSchema — Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogIssueSchema — 유효 픽스처 파싱', () => {
  it('T-BL-1a: 모든 필드가 있는 이슈를 파싱한다', () => {
    const result = backlogIssueSchema.safeParse(backlogIssueFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.key).toBe(ISSUE_KEY_1)
    expect(result.data.assigneeId).toBe(ASSIGNEE_UUID)
    expect(result.data.rank).toBe('abc|00000z')
    expect(result.data.epicKey).toBeNull()
  })

  it('T-BL-1b: assigneeId=null, rank=null, epicKey=있는 이슈를 파싱한다', () => {
    const result = backlogIssueSchema.safeParse(backlogIssueNoAssignee)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.assigneeId).toBeNull()
    expect(result.data.rank).toBeNull()
    expect(result.data.epicKey).toBe('ATLAS-10')
  })

  it('T-BL-1c: summary 누락 시 파싱을 거부한다', () => {
    const invalid: Record<string, unknown> = { ...backlogIssueFixture }
    delete invalid['summary']
    expect(backlogIssueSchema.safeParse(invalid).success).toBe(false)
  })

  it('T-BL-1d: priority가 소수이면 파싱을 거부한다', () => {
    expect(backlogIssueSchema.safeParse({ ...backlogIssueFixture, priority: 1.5 }).success).toBe(false)
  })

  it('T-BL-1e: @JsonInclude(NON_NULL) 방어 — assigneeId 필드 자체가 없어도 null 취급된다', () => {
    const withoutAssigneeId: Record<string, unknown> = { ...backlogIssueFixture }
    delete withoutAssigneeId['assigneeId']
    const result = backlogIssueSchema.safeParse(withoutAssigneeId)
    // nullish()는 undefined → null로 처리
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.assigneeId).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-1f. backlogIssueSchema — typeKey/labels/originalEstimateSeconds 필수 계약 (FR-UX-14 F14 Task 1)
//
// B2(#346)부터 백엔드가 이 3필드를 항상 전송한다. `.default()`/`.optional()`/`.nullish()` 로
// 조용히 때우면 백엔드가 필드를 빠뜨리는 결함이 파싱 단계에서 잡히지 않는다 — Maxi 확정(2026-08-07).
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogIssueSchema — typeKey/labels/originalEstimateSeconds 필수 계약 (FR-UX-14 F14)', () => {
  const validCard = backlogIssueFixture

  it('typeKey 가 없으면 파싱이 실패한다', () => {
    const withoutType: Record<string, unknown> = { ...validCard }
    delete withoutType['typeKey']
    expect(() => backlogIssueSchema.parse(withoutType)).toThrow()
  })

  it('labels 가 없으면 파싱이 실패한다 (기본값으로 때우지 않는다)', () => {
    const withoutLabels: Record<string, unknown> = { ...validCard }
    delete withoutLabels['labels']
    expect(() => backlogIssueSchema.parse(withoutLabels)).toThrow()
  })

  it('originalEstimateSeconds 는 null 을 허용하되 키 자체는 필수다', () => {
    expect(
      backlogIssueSchema.parse({ ...validCard, originalEstimateSeconds: null }).originalEstimateSeconds,
    ).toBeNull()
    const withoutEstimate: Record<string, unknown> = { ...validCard }
    delete withoutEstimate['originalEstimateSeconds']
    expect(() => backlogIssueSchema.parse(withoutEstimate)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-2. sprintMetaSchema — Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('sprintMetaSchema — 유효 픽스처 파싱', () => {
  it('T-BL-2a: 날짜·goal이 있는 스프린트를 파싱한다', () => {
    const result = sprintMetaSchema.safeParse(sprintMetaFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.sprintId).toBe(SPRINT_ID)
    expect(result.data.status).toBe('PLANNED')
    expect(result.data.startDate).toBe('2026-07-01')
    expect(result.data.goal).toBe('목표 달성')
  })

  it('T-BL-2b: goal=null, startDate=null, endDate=null 스프린트를 파싱한다', () => {
    const result = sprintMetaSchema.safeParse(sprintMetaNullDates)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.goal).toBeNull()
    expect(result.data.startDate).toBeNull()
    expect(result.data.endDate).toBeNull()
  })

  it('T-BL-2c: @JsonInclude(NON_NULL) 방어 — goal 필드 자체가 없어도 null 취급된다', () => {
    const withoutGoal: Record<string, unknown> = { ...sprintMetaFixture }
    delete withoutGoal['goal']
    const result = sprintMetaSchema.safeParse(withoutGoal)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.goal).toBeNull()
  })

  it('T-BL-2d: sprintId 누락 시 파싱을 거부한다', () => {
    const invalid: Record<string, unknown> = { ...sprintMetaFixture }
    delete invalid['sprintId']
    expect(sprintMetaSchema.safeParse(invalid).success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-3. backlogViewSchema — 전체 뷰 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('sprintMetaSchema — boardId 필수 계약 (FR-BD-04 PR ⑤)', () => {
  // 스프린트는 프로젝트가 아니라 **보드**에 매달린다(ADR §D2). 이 필드가 없으면 클라이언트가
  // 소속 보드를 알 방법이 없어 관측이 요청 바디로 밀린다 — 서버가 boardId 를 흘려도 화면이 멀쩡하다.
  it('T-BL-2e: boardId 가 있으면 파싱하고 그대로 노출한다', () => {
    const result = sprintMetaSchema.safeParse(sprintMetaFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.boardId).toBe(BOARD_ID)
  })

  it('T-BL-2f: boardId 가 없으면 파싱을 거부한다', () => {
    const withoutBoardId: Record<string, unknown> = { ...sprintMetaFixture }
    delete withoutBoardId['boardId']
    expect(sprintMetaSchema.safeParse(withoutBoardId).success).toBe(false)
  })
})

describe('backlogViewSchema — 전체 뷰 파싱', () => {
  it('T-BL-3a: backlog 목록 + sprints 배열 + truncated를 파싱한다', () => {
    const result = backlogViewSchema.safeParse(backlogViewFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.backlog).toHaveLength(1)
    expect(result.data.sprints).toHaveLength(1)
    expect(result.data.sprints[0]?.sprint.sprintId).toBe(SPRINT_ID)
    expect(result.data.sprints[0]?.issues[0]?.key).toBe(ISSUE_KEY_2)
    expect(result.data.truncated).toBe(false)
  })

  it('T-BL-3b: backlog이 비어 있고 sprints도 비어 있는 뷰를 파싱한다', () => {
    const empty = { backlog: [], sprints: [], truncated: false }
    const result = backlogViewSchema.safeParse(empty)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.backlog).toHaveLength(0)
    expect(result.data.sprints).toHaveLength(0)
  })

  it('T-BL-3c: truncated=true를 파싱한다', () => {
    const truncatedView = { ...backlogViewFixture, truncated: true }
    const result = backlogViewSchema.safeParse(truncatedView)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.truncated).toBe(true)
  })

  it('T-BL-3d: truncated 필드 누락 시 파싱을 거부한다', () => {
    const invalid: Record<string, unknown> = { ...backlogViewFixture }
    delete invalid['truncated']
    expect(backlogViewSchema.safeParse(invalid).success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-4. issueRankResultSchema — 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('issueRankResultSchema — 파싱', () => {
  it('T-BL-4a: key·rank·version을 파싱한다', () => {
    const result = issueRankResultSchema.safeParse(issueRankResultFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.key).toBe(ISSUE_KEY_1)
    expect(result.data.rank).toBe('abc|00001z')
    expect(result.data.version).toBe(2)
  })

  it('T-BL-4b: rank=null을 파싱한다', () => {
    const result = issueRankResultSchema.safeParse({ ...issueRankResultFixture, rank: null })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.rank).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-5. fetchBacklog — GET /api/v1/projects/{projectKey}/backlog
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchBacklog — GET /api/v1/projects/{projectKey}/backlog', () => {
  it('T-BL-5a: projectKey 경로로 GET 호출하고 BacklogView를 반환한다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/projects/:projectKey/backlog', ({ request, params }) => {
        capturedUrl = request.url
        expect(params['projectKey']).toBe(PROJECT_KEY)
        return HttpResponse.json({ data: backlogViewFixture })
      }),
    )
    const result = await fetchBacklog(PROJECT_KEY, undefined)
    expect(result.backlog).toHaveLength(1)
    expect(result.backlog[0]?.key).toBe(ISSUE_KEY_1)
    expect(result.sprints).toHaveLength(1)
    expect(result.truncated).toBe(false)
    expect(capturedUrl).toContain(`/api/v1/projects/${PROJECT_KEY}/backlog`)
  })

  it('T-BL-5b: 빈 백로그 + 빈 sprints를 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/backlog', () => {
        return HttpResponse.json({ data: { backlog: [], sprints: [], truncated: false } })
      }),
    )
    const result = await fetchBacklog(PROJECT_KEY, undefined)
    expect(result.backlog).toHaveLength(0)
    expect(result.sprints).toHaveLength(0)
  })

  it('T-BL-5c: projectKey에 특수문자가 있으면 encodeURIComponent 처리된다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/projects/:projectKey/backlog', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: { backlog: [], sprints: [], truncated: false } })
      }),
    )
    await fetchBacklog('MY PROJECT', undefined)
    expect(capturedUrl).toContain('MY%20PROJECT')
  })

  it('T-BL-5d: boardId를 주면 ?board= 쿼리 파라미터로 실어 보낸다 (FR-BD-04)', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/projects/:projectKey/backlog', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: { backlog: [], sprints: [], truncated: false } })
      }),
    )

    await fetchBacklog(PROJECT_KEY, BOARD_ID)

    // 문자열 포함이 아니라 **파라미터로** 잰다 — 경로 어딘가에 UUID가 섞여도 통과하면 안 된다
    const params = new URL(capturedUrl ?? '', 'http://localhost').searchParams
    expect(params.get('board')).toBe(BOARD_ID)
  })

  it('T-BL-5e: boardId가 undefined면 board 파라미터를 아예 붙이지 않는다 (기본 보드 폴백)', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/projects/:projectKey/backlog', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: { backlog: [], sprints: [], truncated: false } })
      }),
    )

    await fetchBacklog(PROJECT_KEY, undefined)

    // ★ 빈 `?board=` 를 붙이면 서버가 「잘못된 보드」로 읽어 404다 (E7). 폴백은 **키 부재**로만 성립한다
    const params = new URL(capturedUrl ?? '', 'http://localhost').searchParams
    expect(params.has('board')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-6. rerankIssue — PATCH /api/v1/issues/{key}/rank
// ─────────────────────────────────────────────────────────────────────────────

describe('rerankIssue — PATCH /api/v1/issues/{key}/rank', () => {
  it('T-BL-6a: issueKey 경로 + body {previousIssueKey, nextIssueKey}를 전송하고 IssueRankResult를 반환한다', async () => {
    let capturedMethod: string | null = null
    let capturedBody: unknown = null

    server.use(
      http.patch('/api/v1/issues/:key/rank', async ({ request, params }) => {
        capturedMethod = request.method
        capturedBody = await request.json()
        expect(params['key']).toBe(ISSUE_KEY_1)
        return HttpResponse.json({ data: issueRankResultFixture })
      }),
    )

    const result = await rerankIssue(ISSUE_KEY_1, {
      previousIssueKey: 'ATLAS-0',
      nextIssueKey: 'ATLAS-2',
    })
    expect(capturedMethod).toBe('PATCH')
    expect((capturedBody as Record<string, unknown>)['previousIssueKey']).toBe('ATLAS-0')
    expect((capturedBody as Record<string, unknown>)['nextIssueKey']).toBe('ATLAS-2')
    expect(result.key).toBe(ISSUE_KEY_1)
    expect(result.rank).toBe('abc|00001z')
  })

  it('T-BL-6b: previousIssueKey·nextIssueKey가 undefined이면 body에 포함되지 않는다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.patch('/api/v1/issues/:key/rank', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: issueRankResultFixture })
      }),
    )
    await rerankIssue(ISSUE_KEY_1, {})
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'previousIssueKey')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'nextIssueKey')).toBe(false)
  })

  it('T-BL-6c: previousIssueKey만 있으면 previousIssueKey만 body에 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.patch('/api/v1/issues/:key/rank', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: issueRankResultFixture })
      }),
    )
    await rerankIssue(ISSUE_KEY_1, { previousIssueKey: 'ATLAS-0' })
    expect((capturedBody as Record<string, unknown>)['previousIssueKey']).toBe('ATLAS-0')
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'nextIssueKey')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-7. assignToSprint — POST /api/v1/sprints/{id}/issues (201, void)
// ─────────────────────────────────────────────────────────────────────────────

describe('assignToSprint — POST /api/v1/sprints/{id}/issues', () => {
  it('T-BL-7a: sprintId 경로 + body {issueKey}를 전송하고 void를 반환한다 (201)', async () => {
    let capturedMethod: string | null = null
    let capturedBody: unknown = null

    server.use(
      http.post('/api/v1/sprints/:sprintId/issues', async ({ request, params }) => {
        capturedMethod = request.method
        capturedBody = await request.json()
        expect(params['sprintId']).toBe(SPRINT_ID)
        return new HttpResponse(null, { status: 201 })
      }),
    )

    const result = await assignToSprint(SPRINT_ID, ISSUE_KEY_1)
    expect(capturedMethod).toBe('POST')
    expect((capturedBody as Record<string, unknown>)['issueKey']).toBe(ISSUE_KEY_1)
    expect(result).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-8. unassignFromSprint — DELETE /api/v1/sprints/{id}/issues/{issueKey} (204)
// ─────────────────────────────────────────────────────────────────────────────

describe('unassignFromSprint — DELETE /api/v1/sprints/{id}/issues/{issueKey}', () => {
  it('T-BL-8a: sprintId·issueKey 경로로 DELETE 호출하고 void를 반환한다 (204)', async () => {
    let capturedMethod: string | null = null
    let capturedPath: string | null = null

    server.use(
      http.delete('/api/v1/sprints/:sprintId/issues/:issueKey', ({ request, params }) => {
        capturedMethod = request.method
        capturedPath = request.url
        expect(params['sprintId']).toBe(SPRINT_ID)
        expect(params['issueKey']).toBe(ISSUE_KEY_1)
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const result = await unassignFromSprint(SPRINT_ID, ISSUE_KEY_1)
    expect(capturedMethod).toBe('DELETE')
    expect(capturedPath).toContain(`/api/v1/sprints/${SPRINT_ID}/issues/${ISSUE_KEY_1}`)
    expect(result).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-9. createSprint — POST /api/v1/sprints
// ─────────────────────────────────────────────────────────────────────────────

describe('createSprint — POST /api/v1/sprints', () => {
  it('T-BL-9a: projectKey·name·goal·startDate·endDate를 body로 POST하고 SprintMeta를 반환한다', async () => {
    let capturedBody: unknown = null

    server.use(
      http.post('/api/v1/sprints', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: sprintMetaFixture }, { status: 201 })
      }),
    )

    const result = await createSprint({
      projectKey: PROJECT_KEY,
      name: '스프린트 1',
      goal: '목표 달성',
      startDate: '2026-07-01',
      endDate: '2026-07-14',
    })
    expect((capturedBody as Record<string, unknown>)['projectKey']).toBe(PROJECT_KEY)
    expect((capturedBody as Record<string, unknown>)['name']).toBe('스프린트 1')
    expect((capturedBody as Record<string, unknown>)['goal']).toBe('목표 달성')
    expect(result.sprintId).toBe(SPRINT_ID)
    expect(result.status).toBe('PLANNED')
  })

  it('T-BL-9b: goal·startDate·endDate가 없으면 body에서 제외된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/sprints', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: sprintMetaNullDates }, { status: 201 })
      }),
    )

    await createSprint({ projectKey: PROJECT_KEY, name: '최소 스프린트' })
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'goal')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'startDate')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'endDate')).toBe(false)
    // 미지정이면 백엔드가 첫 스크럼 보드로 폴백한다(하위 호환) — 빈 값을 보내면 그 폴백이 깨진다
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'boardId')).toBe(false)
  })

  it('T-BL-9c: boardId를 주면 body에 실려 그 보드에 스프린트가 붙는다 (FR-BD-04 · 부채 E-6)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/sprints', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: sprintMetaFixture }, { status: 201 })
      }),
    )

    await createSprint({ projectKey: PROJECT_KEY, name: '보드 지정 스프린트', boardId: BOARD_ID })

    expect((capturedBody as Record<string, unknown>)['boardId']).toBe(BOARD_ID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-10. startSprint — POST /api/v1/sprints/{id}/start
// ─────────────────────────────────────────────────────────────────────────────

describe('startSprint — POST /api/v1/sprints/{id}/start', () => {
  it('T-BL-10a: sprintId 경로로 POST 호출하고 ACTIVE SprintMeta를 반환한다', async () => {
    const activeSprint = { ...sprintMetaFixture, status: 'ACTIVE' }

    server.use(
      http.post('/api/v1/sprints/:sprintId/start', ({ params }) => {
        expect(params['sprintId']).toBe(SPRINT_ID)
        return HttpResponse.json({ data: activeSprint })
      }),
    )

    const result = await startSprint(SPRINT_ID)
    expect(result.sprintId).toBe(SPRINT_ID)
    expect(result.status).toBe('ACTIVE')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-11. completeSprint — POST /api/v1/sprints/{id}/complete
// ─────────────────────────────────────────────────────────────────────────────

describe('completeSprint — POST /api/v1/sprints/{id}/complete', () => {
  it('T-BL-11a: sprintId 경로로 POST 호출하고 COMPLETED SprintMeta를 반환한다', async () => {
    const completedSprint = { ...sprintMetaFixture, status: 'COMPLETED' }

    server.use(
      http.post('/api/v1/sprints/:sprintId/complete', ({ params }) => {
        expect(params['sprintId']).toBe(SPRINT_ID)
        return HttpResponse.json({ data: completedSprint })
      }),
    )

    const result = await completeSprint(SPRINT_ID)
    expect(result.sprintId).toBe(SPRINT_ID)
    expect(result.status).toBe('COMPLETED')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-12. updateSprint — PATCH /api/v1/sprints/{id} (FR-UX-13 F15 FR-4)
//
// 3-state partial 이 이 함수의 존재 이유다. 「미전송 = 무변경」과 「명시 null = 값 삭제」가
// 서로 다른 뜻이므로, 바뀌지 않은 필드를 습관적으로 실어 보내면 남의 수정을 덮어쓴다.
// ─────────────────────────────────────────────────────────────────────────────

describe('updateSprint — PATCH /api/v1/sprints/{id}', () => {
  it('T-BL-12a: 바뀐 필드 + version 만 body 에 담아 PATCH 로 보낸다', async () => {
    let capturedBody: unknown = null
    let capturedMethod = ''

    server.use(
      http.patch('/api/v1/sprints/:sprintId', async ({ request, params }) => {
        expect(params['sprintId']).toBe(SPRINT_ID)
        capturedMethod = request.method
        capturedBody = await request.json()
        return HttpResponse.json({ data: { ...sprintMetaFixture, endDate: '2026-07-20', version: 2 } })
      }),
    )

    const result = await updateSprint(SPRINT_ID, { endDate: '2026-07-20', version: 1 })

    expect(capturedMethod).toBe('PATCH')
    const body = capturedBody as Record<string, unknown>
    expect(body['endDate']).toBe('2026-07-20')
    expect(body['version']).toBe(1)
    // 바뀌지 않은 필드는 키 자체가 없어야 한다 — 실어 보내면 남의 수정을 덮어쓴다.
    expect(Object.prototype.hasOwnProperty.call(body, 'name')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(body, 'goal')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(body, 'startDate')).toBe(false)
    expect(result.version).toBe(2)
    expect(result.endDate).toBe('2026-07-20')
  })

  it('T-BL-12b: 명시 null 은 body 에 그대로 실린다 (값 삭제 — 미전송과 다른 뜻)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.patch('/api/v1/sprints/:sprintId', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: { ...sprintMetaFixture, goal: null, version: 2 } })
      }),
    )

    await updateSprint(SPRINT_ID, { goal: null, version: 1 })

    const body = capturedBody as Record<string, unknown>
    expect(Object.prototype.hasOwnProperty.call(body, 'goal')).toBe(true)
    expect(body['goal']).toBeNull()
  })

  it('T-BL-12c: 비-2xx 응답은 status 를 담은 ApiError 로 throw 된다 (409 분기 판별용)', async () => {
    server.use(
      http.patch('/api/v1/sprints/:sprintId', () =>
        HttpResponse.json({ errorCode: 'SPRINT_VERSION_CONFLICT' }, { status: 409 }),
      ),
    )

    await expect(updateSprint(SPRINT_ID, { goal: '새 목표', version: 1 })).rejects.toMatchObject({
      status: 409,
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-13. deleteSprint — DELETE /api/v1/sprints/{id} (FR-3)
//
// 확인 창은 실패를 **창 안에** 남긴다(E-3). 그러려면 사유가 상태 코드로 갈려야 한다 —
// 404(이미 지워졌다)와 403(권한이 없다)은 사용자에게 다른 문장으로 나가야 한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteSprint — DELETE /api/v1/sprints/{id}', () => {
  it('T-BL-13a: sprintId 경로로 DELETE 를 보내고 204 는 본문 없이 끝난다', async () => {
    let capturedMethod = ''

    server.use(
      http.delete('/api/v1/sprints/:sprintId', ({ request, params }) => {
        expect(params['sprintId']).toBe(SPRINT_ID)
        capturedMethod = request.method
        return new HttpResponse(null, { status: 204 })
      }),
    )

    await expect(deleteSprint(SPRINT_ID)).resolves.toBeUndefined()
    expect(capturedMethod).toBe('DELETE')
  })

  it('T-BL-13b: signal 을 요청까지 이어준다 — 끊긴 signal 이면 요청이 서버에 닿지 않는다', async () => {
    // 상한(`lib/delete-timeout.ts`)은 이 인자로만 요청을 끊는다. 여기서 signal 을 흘려버리면
    // abort 가 아무 일도 하지 않아 반환 Promise 가 영원히 pending 으로 남는다 —
    // 확인 창이 `confirming` 에 묶인 채 닫히지 못하는 상태가 그것이다.
    let handlerCalls = 0
    server.use(
      http.delete('/api/v1/sprints/:sprintId', () => {
        handlerCalls += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const controller = new AbortController()
    controller.abort()

    await expect(deleteSprint(SPRINT_ID, controller.signal)).rejects.toThrow()
    // ★signal 이 실제로 이어졌다는 유일한 증거다 — 안 이어졌다면 요청이 나가 204 로 성공한다.
    expect(handlerCalls).toBe(0)
  })

  it('T-BL-13c: 404 는 status 를 담은 ApiError 로 throw 된다 (이미 지워진 스프린트)', async () => {
    server.use(
      http.delete('/api/v1/sprints/:sprintId', () =>
        HttpResponse.json({ errorCode: 'SPRINT_NOT_FOUND' }, { status: 404 }),
      ),
    )

    await expect(deleteSprint(SPRINT_ID)).rejects.toMatchObject({ status: 404 })
  })

  it('T-BL-13d: 403 은 status 를 담은 ApiError 로 throw 된다 (권한 미충족)', async () => {
    server.use(
      http.delete('/api/v1/sprints/:sprintId', () =>
        HttpResponse.json({ title: 'Forbidden', status: 403 }, { status: 403 }),
      ),
    )

    await expect(deleteSprint(SPRINT_ID)).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-BL-14. deleteSprint — MSW 핸들러 배선 (stateful)
//
// 핸들러를 만들고 `backlogHandlers` 배열에 **넣지 않으면** MSW 는 그 요청을 미처리로 흘리는데,
// 그 증상이 「핸들러 부재」와 완전히 같다 — 파일 안에 코드가 있는 것만으로는 아무 보증이 없다.
// 그래서 이 블록은 개별 핸들러가 아니라 **배열을 통째로** 등록해(`backlog-handlers.test.ts` 와
// 같은 관례) 배선과 store 반영을 함께 잰다.
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteSprint — MSW 핸들러 (stateful)', () => {
  /** DEFAULT_BACKLOG 의 PLANNED 스프린트 — 이슈 ATLAS-3·ATLAS-4 를 들고 있다 */
  const PLANNED_SPRINT_ID = 'a0000000-0000-4000-8000-000000000001'
  /** 어느 픽스처에도 없는 UUID — 404 경로용 */
  const MISSING_SPRINT_ID = 'a0000000-0000-4000-8000-0000000000ff'

  beforeEach(() => {
    server.use(...backlogHandlers)
    resetBoardStore()
    resetBacklogStore()
    seedBacklog(DEFAULT_BACKLOG)
  })

  afterEach(() => {
    globalThis.localStorage.removeItem(LS_KEY_SPRINT_DELETE_FAIL)
    resetBacklogStore()
    resetBoardStore()
  })

  it('T-BL-14a: 지운 스프린트는 사라지고 그 이슈는 백로그 칸으로 돌아온다', async () => {
    await deleteSprint(PLANNED_SPRINT_ID)

    const view = await fetchBacklog('ATLAS', undefined)

    expect(view.sprints.map((s) => s.sprint.sprintId)).not.toContain(PLANNED_SPRINT_ID)
    // ★이슈를 어디에도 넣지 않으면 사용자가 이슈를 잃는다. 백엔드는 스프린트만 소프트 삭제하고
    //   이슈는 어느 스프린트에도 속하지 않게 되어 **모든 보드의** 백로그 칸에 나타난다(E12).
    expect(view.backlog.map((i) => i.key)).toEqual(
      expect.arrayContaining(['ATLAS-3', 'ATLAS-4']),
    )
  })

  it('T-BL-14b: 없는 스프린트는 404 다', async () => {
    await expect(deleteSprint(MISSING_SPRINT_ID)).rejects.toMatchObject({ status: 404 })
  })

  it('T-BL-14c: 실패 토글을 켜면 500 이다 (확인 창 안 실패 경로 재현용)', async () => {
    globalThis.localStorage.setItem(LS_KEY_SPRINT_DELETE_FAIL, 'true')

    await expect(deleteSprint(PLANNED_SPRINT_ID)).rejects.toMatchObject({ status: 500 })
  })

  it('T-BL-14d: 지운 스프린트가 그 보드의 활성 스프린트였다면 마커도 함께 지운다', async () => {
    // 백엔드 `findActiveByBoard` 는 `deleted_at IS NULL` 을 걸어 지운 스프린트를 활성으로
    // 뽑지 않는다. mock 이 마커를 남기면 보드 화면이 **없는 스프린트를 계속 보여준다**.
    const { detail } = createBoardInStore('ATLAS', '스크럼 보드', 'SCRUM')
    const activeSprintId = 'a0000000-0000-4000-8000-0000000000aa'
    const activeSprint: SprintMeta = {
      sprintId: activeSprintId,
      name: '진행 중 스프린트',
      goal: null,
      status: 'ACTIVE',
      startDate: null,
      endDate: null,
      version: 1,
    }
    seedBacklog({
      projectKey: 'ATLAS',
      backlog: [],
      sprints: [{ sprint: activeSprint, issues: [], boardId: detail.boardId }],
      truncated: false,
    })
    setBoardActiveSprint(detail.boardId, {
      sprintId: activeSprintId,
      name: activeSprint.name,
      startDate: null,
      endDate: null,
    })

    await deleteSprint(activeSprintId)

    expect(boardStore.get(detail.boardId)?.activeSprint ?? null).toBeNull()
  })
})
