// 자동화 실행 이력 API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 실 fetch 통해 조회/재실행/쿼리조립/에러코드 검증 (FR-AT-05 D6/D7)
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import {
  fetchRuleExecutions,
  fetchRuleExecution,
  replayRuleExecution,
  extractAutomationExecutionErrorCode,
} from './automation-executions'
import { ApiError } from './client'
import type { RuleExecutionDetail, RuleExecutionSummary } from './automation-executions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — automation-executions.types.test.ts와 동형 UUID(v4 형식, Zod v4 uuid() 검증 통과)
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const RULE_ID = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e'
const EXECUTION_ID = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d'
const NONEXISTENT_ID = 'c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f'

const summaryFixture: RuleExecutionSummary = {
  id: EXECUTION_ID,
  ruleId: RULE_ID,
  triggerType: 'ISSUE_CREATED',
  issueKey: 'ATLAS-1',
  status: 'SUCCESS',
  actionCount: 2,
  successCount: 2,
  startedAt: '2026-07-10T10:00:00Z',
  finishedAt: '2026-07-10T10:00:01Z',
  replayedFrom: null,
}

// RuleExecutionDetail은 backend RuleExecutionDetailResponse와 정합해 actionCount/successCount를
// 갖지 않는다(그 두 필드는 summary 전용 집계값이다) — summaryFixture를 spread하지 않고 detail 필드만
// 직접 구성한다.
const detailFixture: RuleExecutionDetail = {
  id: EXECUTION_ID,
  ruleId: RULE_ID,
  projectKey: PROJECT_KEY,
  triggerType: 'ISSUE_CREATED',
  triggerEvent: { issueKey: 'ATLAS-1', type: 'ISSUE_CREATED' },
  issueKey: 'ATLAS-1',
  status: 'SUCCESS',
  outcomes: [{ position: 0, actionType: 'SET_FIELD', success: true, error: null }],
  replayedFrom: null,
  startedAt: '2026-07-10T10:00:00Z',
  finishedAt: '2026-07-10T10:00:01Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 — automation-rules.test.ts 선례를 미러하되, 이 task의 허용 파일이 2개(.ts/.test.ts)뿐이라
// 별도 mocks 핸들러 파일을 신설하지 않고 이 테스트 파일 안에서 직접 인라인 정의한다.
// ─────────────────────────────────────────────────────────────────────────────

let capturedListUrl: string | null = null
let capturedReplayXsrf: string | null = null

const server = setupServer(
  http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', ({ request, params }) => {
    capturedListUrl = request.url
    if (params['ruleId'] === NONEXISTENT_ID) {
      return HttpResponse.json({ errorCode: 'AUTOMATION_RULE_UNAVAILABLE' }, { status: 409 })
    }
    return HttpResponse.json([summaryFixture])
  }),
  http.get('/api/v1/automation/executions/:id', ({ params }) => {
    if (params['id'] === NONEXISTENT_ID) {
      return HttpResponse.json({ errorCode: 'AUTOMATION_EXECUTION_NOT_FOUND' }, { status: 404 })
    }
    return HttpResponse.json(detailFixture)
  }),
  http.post('/api/v1/automation/executions/:id/replay', ({ request, params }) => {
    capturedReplayXsrf = request.headers.get('X-XSRF-TOKEN')
    if (params['id'] === NONEXISTENT_ID) {
      return HttpResponse.json({ errorCode: 'AUTOMATION_EXECUTION_NOT_FOUND' }, { status: 404 })
    }
    return HttpResponse.json({ ...detailFixture, replayedFrom: EXECUTION_ID })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
  capturedListUrl = null
  capturedReplayXsrf = null
})
afterEach(() => {
  server.resetHandlers()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// fetchRuleExecutions
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchRuleExecutions', () => {
  it('bare 배열 응답을 RuleExecutionSummary[]로 반환한다(Zod parse)', async () => {
    const result = await fetchRuleExecutions(PROJECT_KEY, RULE_ID)
    expect(result).toHaveLength(1)
    expect(result[0]?.id).toBe(EXECUTION_ID)
    expect(result[0]?.status).toBe('SUCCESS')
  })

  it('opts 없이 호출 시 query string이 비어있다', async () => {
    await fetchRuleExecutions(PROJECT_KEY, RULE_ID)
    const url = new URL(capturedListUrl ?? '')
    expect(url.search).toBe('')
  })

  it('issueKey/limit/before 모두 지정 시 쿼리스트링에 모두 포함된다', async () => {
    await fetchRuleExecutions(PROJECT_KEY, RULE_ID, {
      issueKey: 'ATLAS-1',
      limit: 10,
      before: '2026-07-10T00:00:00Z',
    })
    const url = new URL(capturedListUrl ?? '')
    expect(url.searchParams.get('issueKey')).toBe('ATLAS-1')
    expect(url.searchParams.get('limit')).toBe('10')
    expect(url.searchParams.get('before')).toBe('2026-07-10T00:00:00Z')
  })

  it('일부 옵션만 지정 시 undefined 필드는 쿼리스트링에서 생략된다', async () => {
    await fetchRuleExecutions(PROJECT_KEY, RULE_ID, { limit: 5 })
    const url = new URL(capturedListUrl ?? '')
    expect(url.searchParams.get('limit')).toBe('5')
    expect(url.searchParams.has('issueKey')).toBe(false)
    expect(url.searchParams.has('before')).toBe(false)
  })

  it('룰 비활성/미존재 등 409 응답 시 ApiError(AUTOMATION_RULE_UNAVAILABLE)를 throw한다', async () => {
    try {
      await fetchRuleExecutions(PROJECT_KEY, NONEXISTENT_ID)
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).status).toBe(409)
      expect(extractAutomationExecutionErrorCode(error)).toBe('AUTOMATION_RULE_UNAVAILABLE')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchRuleExecution
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchRuleExecution', () => {
  it('실행 이력 단건을 RuleExecutionDetail로 반환한다(outcomes/triggerEvent 포함)', async () => {
    const result = await fetchRuleExecution(EXECUTION_ID)
    expect(result.id).toBe(EXECUTION_ID)
    expect(result.projectKey).toBe(PROJECT_KEY)
    expect(result.outcomes).toHaveLength(1)
  })

  it('미존재 id는 404 ApiError(AUTOMATION_EXECUTION_NOT_FOUND)를 throw한다', async () => {
    try {
      await fetchRuleExecution(NONEXISTENT_ID)
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).status).toBe(404)
      expect(extractAutomationExecutionErrorCode(error)).toBe('AUTOMATION_EXECUTION_NOT_FOUND')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// replayRuleExecution
// ─────────────────────────────────────────────────────────────────────────────

describe('replayRuleExecution', () => {
  it('재실행 성공 시 RuleExecutionDetail을 반환하고 replayedFrom이 채워진다', async () => {
    const result = await replayRuleExecution(EXECUTION_ID)
    expect(result.replayedFrom).toBe(EXECUTION_ID)
  })

  it('POST 요청에 X-XSRF-TOKEN 헤더를 부착한다', async () => {
    await replayRuleExecution(EXECUTION_ID)
    expect(capturedReplayXsrf).toBe('test-csrf-token')
  })

  it('미존재 id 재실행 시 404 ApiError(AUTOMATION_EXECUTION_NOT_FOUND)를 throw한다', async () => {
    try {
      await replayRuleExecution(NONEXISTENT_ID)
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).status).toBe(404)
      expect(extractAutomationExecutionErrorCode(error)).toBe('AUTOMATION_EXECUTION_NOT_FOUND')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// extractAutomationExecutionErrorCode
// ─────────────────────────────────────────────────────────────────────────────

describe('extractAutomationExecutionErrorCode', () => {
  it('ApiError body의 errorCode를 string으로 반환한다', () => {
    const err = new ApiError(409, { errorCode: 'AUTOMATION_RULE_UNAVAILABLE' })
    expect(extractAutomationExecutionErrorCode(err)).toBe('AUTOMATION_RULE_UNAVAILABLE')
  })

  it('ApiError이지만 errorCode가 없으면 null을 반환한다', () => {
    const err = new ApiError(500, { detail: 'Internal Server Error' })
    expect(extractAutomationExecutionErrorCode(err)).toBeNull()
  })

  it('ApiError가 아니면 null을 반환한다', () => {
    expect(extractAutomationExecutionErrorCode(new Error('network error'))).toBeNull()
    expect(extractAutomationExecutionErrorCode('string error')).toBeNull()
    expect(extractAutomationExecutionErrorCode(null)).toBeNull()
  })
})
