// FR-AT-05 D6/D7 자동화 룰 실행 이력 MSW stateful 핸들러 단위 테스트 — 목록 필터/정렬 + 단건 404 + replay 복제·409 계약 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import {
  actionOutcomeSchema,
  ruleExecutionDetailSchema,
  ruleExecutionSummarySchema,
} from '@/api/automation-executions.types'
import { automationExecutionHandlers } from './automation-execution-handlers'
import {
  DEFAULT_RULE_EXECUTIONS,
  resetAutomationExecutionStore,
  SCENARIO_KEY,
  SEED_EXECUTION_IDS,
  SEEDED_EXECUTION_RULE_IDS,
  seedAutomationExecutions,
} from './automation-execution-fixtures'
import { DEFAULT_AUTOMATION_PROJECT_KEY } from './automation-rule-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...automationExecutionHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetAutomationExecutionStore()
  for (const key of Object.values(SCENARIO_KEY)) {
    localStorage.removeItem(key)
  }
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — fetch 래퍼 (drift 차단을 위해 리터럴 URL 산재 대신 helper로 생성)
// ─────────────────────────────────────────────────────────────────────────────

const executionsUrl = (
  ruleId: string,
  query: Record<string, string> = {},
  projectKey: string = DEFAULT_AUTOMATION_PROJECT_KEY,
): string => {
  const params = new URLSearchParams(query)
  const qs = params.toString()
  return `/api/v1/projects/${projectKey}/automation/rules/${ruleId}/executions${qs ? `?${qs}` : ''}`
}

async function listExecutions(
  ruleId: string,
  query?: Record<string, string>,
  projectKey?: string,
): Promise<{ status: number; body: unknown }> {
  const res = await fetch(executionsUrl(ruleId, query, projectKey))
  return { status: res.status, body: (await res.json()) as unknown }
}

async function getExecution(id: string): Promise<{ status: number; body: unknown }> {
  const res = await fetch(`/api/v1/automation/executions/${id}`)
  return { status: res.status, body: (await res.json()) as unknown }
}

async function replayExecution(id: string): Promise<{ status: number; body: unknown }> {
  const res = await fetch(`/api/v1/automation/executions/${id}/replay`, { method: 'POST' })
  return { status: res.status, body: (await res.json()) as unknown }
}

/**
 * 단건 trace/replay 응답의 wire 형태(자세한 근거는 automation-execution-fixtures.ts 상단 KDoc "알려진 스키마
 * drift" 참고) — 실제 backend `RuleExecutionDetailResponse`는 `actionCount`/`successCount`를 포함하지 않으므로
 * (`ruleExecutionDetailSchema`가 `.extend()`로 잘못 요구) 여기서는 그 스키마로 parse하지 않고 직접 필드를
 * 검증한다.
 */
interface WireExecutionDetail {
  id: string
  ruleId: string
  projectKey: string
  triggerType: string
  triggerEvent: unknown
  issueKey: string | null
  status: string
  outcomes: { position: number; actionType: string; success: boolean; error: string | null }[]
  replayedFrom: string | null
  startedAt: string
  finishedAt: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 계약 drift 가드 — 시드 fixture가 실제 Zod 응답 스키마를 통과하는지 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('DEFAULT_RULE_EXECUTIONS 픽스처 계약 검증', () => {
  it('시드 실행 이력 각각이 ruleExecutionDetailSchema를 통과한다', () => {
    for (const execution of DEFAULT_RULE_EXECUTIONS) {
      expect(() => ruleExecutionDetailSchema.parse(execution)).not.toThrow()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) GET 목록 — 최신순 정렬 + 요약 필드만 노출
// ─────────────────────────────────────────────────────────────────────────────

describe('GET .../executions (목록) — 최신순 정렬', () => {
  it('시드된 룰의 실행 이력을 startedAt desc 순으로 반환한다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { status, body } = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled)
    expect(status).toBe(200)

    const items = body as { id: string }[]
    expect(items.map((item) => item.id)).toEqual([
      SEED_EXECUTION_IDS.scheduledSkipped,
      SEED_EXECUTION_IDS.scheduledFailed,
      SEED_EXECUTION_IDS.scheduledPartial,
      SEED_EXECUTION_IDS.scheduledSuccess,
    ])
    for (const item of items) {
      expect(() => ruleExecutionSummarySchema.parse(item)).not.toThrow()
    }
  })

  it('요약 응답에는 outcomes/triggerEvent/projectKey 키가 없다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { body } = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled)
    for (const item of body as object[]) {
      const keys = Object.keys(item)
      expect(keys).not.toContain('outcomes')
      expect(keys).not.toContain('triggerEvent')
      expect(keys).not.toContain('projectKey')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) ruleId 필터
// ─────────────────────────────────────────────────────────────────────────────

describe('GET .../executions — ruleId 필터', () => {
  it('다른 룰의 실행 이력은 섞이지 않는다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { body } = await listExecutions(SEEDED_EXECUTION_RULE_IDS.issueCreated)
    const items = body as { id: string; ruleId: string }[]
    expect(items).toHaveLength(1)
    expect(items[0]?.id).toBe(SEED_EXECUTION_IDS.issueCreatedSuccess)
    expect(items[0]?.ruleId).toBe(SEEDED_EXECUTION_RULE_IDS.issueCreated)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) 프로젝트 스코프
// ─────────────────────────────────────────────────────────────────────────────

describe('GET .../executions — 프로젝트 스코프', () => {
  it('다른 프로젝트 키로 조회하면 결과가 보이지 않는다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { body } = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled, {}, 'OTHER-PROJECT')
    expect(body).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) issueKey 필터
// ─────────────────────────────────────────────────────────────────────────────

describe('GET .../executions — issueKey 필터', () => {
  it('issueKey를 지정하면 해당 이슈 실행만 반환한다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { body } = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled, {
      issueKey: 'ATLAS-102',
    })
    const items = body as { id: string }[]
    expect(items).toHaveLength(1)
    expect(items[0]?.id).toBe(SEED_EXECUTION_IDS.scheduledPartial)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) limit
// ─────────────────────────────────────────────────────────────────────────────

describe('GET .../executions — limit', () => {
  it('limit을 지정하면 그 수만큼(최신순 상위)만 반환한다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { body } = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled, { limit: '2' })
    const items = body as { id: string }[]
    expect(items).toHaveLength(2)
    expect(items.map((item) => item.id)).toEqual([
      SEED_EXECUTION_IDS.scheduledSkipped,
      SEED_EXECUTION_IDS.scheduledFailed,
    ])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (f) before 커서(keyset)
// ─────────────────────────────────────────────────────────────────────────────

describe('GET .../executions — before 커서(keyset)', () => {
  it('before 시각보다 엄격히 이전(started_at < before) 실행만 반환한다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { body } = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled, {
      before: '2026-07-12T09:00:00Z',
    })
    const items = body as { id: string }[]
    expect(items.map((item) => item.id)).toEqual([
      SEED_EXECUTION_IDS.scheduledPartial,
      SEED_EXECUTION_IDS.scheduledSuccess,
    ])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (g) SCENARIO_KEY.EMPTY_EXECUTIONS 플래그
// ─────────────────────────────────────────────────────────────────────────────

describe('SCENARIO_KEY.EMPTY_EXECUTIONS localStorage 플래그', () => {
  it('플래그가 true면 시드된 실행 이력이 있어도 빈 배열을 반환한다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    localStorage.setItem(SCENARIO_KEY.EMPTY_EXECUTIONS, 'true')

    const { body } = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled)
    expect(body).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (h) GET 단건 trace 상세
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /automation/executions/:id (단건 trace)', () => {
  it('존재하면 outcomes/triggerEvent를 포함한 상세를 200으로 반환한다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { status, body } = await getExecution(SEED_EXECUTION_IDS.scheduledSuccess)
    expect(status).toBe(200)

    // ruleExecutionDetailSchema로 parse하지 않는다 — 상단 WireExecutionDetail KDoc 참고(스키마 drift)
    const detail = body as WireExecutionDetail
    expect(detail.id).toBe(SEED_EXECUTION_IDS.scheduledSuccess)
    expect(detail.outcomes.length).toBeGreaterThan(0)
    expect(detail.projectKey).toBe(DEFAULT_AUTOMATION_PROJECT_KEY)
    for (const outcome of detail.outcomes) {
      expect(() => actionOutcomeSchema.parse(outcome)).not.toThrow()
    }
  })

  it('상세 응답에는 actionCount/successCount 키가 없다(실제 backend DTO 계약, 스키마 drift 회귀 가드)', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const { body } = await getExecution(SEED_EXECUTION_IDS.scheduledSuccess)
    const keys = Object.keys(body as object)
    expect(keys).not.toContain('actionCount')
    expect(keys).not.toContain('successCount')
  })

  it('존재하지 않으면 404 ProblemDetail(AUTOMATION_EXECUTION_NOT_FOUND)을 반환하고 존재를 숨긴다', async () => {
    const { status, body } = await getExecution('ffffffff-0000-4000-8000-000000000000')
    expect(status).toBe(404)

    const problem = body as { errorCode: string; status: number; detail: string }
    expect(problem.errorCode).toBe('AUTOMATION_EXECUTION_NOT_FOUND')
    expect(problem.status).toBe(404)
    expect(typeof problem.detail).toBe('string')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (i) POST replay — stateful 복제 + 409
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /automation/executions/:id/replay', () => {
  it('원본을 복제해 새 실행 이력을 생성하고 후속 목록 GET에 즉시 반영한다(stateful)', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    const sourceId = SEED_EXECUTION_IDS.scheduledSuccess

    const { status, body } = await replayExecution(sourceId)
    expect(status).toBe(200)

    // ruleExecutionDetailSchema로 parse하지 않는다 — 상단 WireExecutionDetail KDoc 참고(스키마 drift)
    const replayed = body as WireExecutionDetail
    expect(replayed.id).not.toBe(sourceId)
    expect(replayed.replayedFrom).toBe(sourceId)
    expect(replayed.ruleId).toBe(SEEDED_EXECUTION_RULE_IDS.scheduled)
    expect(replayed.issueKey).toBe('ATLAS-101')
    expect(Object.keys(body as object)).not.toContain('actionCount')
    expect(Object.keys(body as object)).not.toContain('successCount')

    const after = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled)
    const items = after.body as { id: string }[]
    expect(items).toHaveLength(5)
    // 새 실행이 최신순 최상단에 위치한다 (msw-mutation-stateful-refetch)
    expect(items[0]?.id).toBe(replayed.id)
  })

  it('원본이 존재하지 않으면 404를 반환한다', async () => {
    const { status, body } = await replayExecution('ffffffff-0000-4000-8000-000000000000')
    expect(status).toBe(404)
    const problem = body as { errorCode: string }
    expect(problem.errorCode).toBe('AUTOMATION_EXECUTION_NOT_FOUND')
  })

  it('SCENARIO_KEY.RULE_UNAVAILABLE 플래그가 true면 409를 반환하고 store를 변경하지 않는다', async () => {
    seedAutomationExecutions(DEFAULT_RULE_EXECUTIONS)
    localStorage.setItem(SCENARIO_KEY.RULE_UNAVAILABLE, 'true')

    const before = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled)
    const beforeCount = (before.body as unknown[]).length

    const { status, body } = await replayExecution(SEED_EXECUTION_IDS.scheduledSuccess)
    expect(status).toBe(409)

    const problem = body as { errorCode: string; status: number }
    expect(problem.errorCode).toBe('AUTOMATION_RULE_UNAVAILABLE')
    expect(problem.status).toBe(409)

    const after = await listExecutions(SEEDED_EXECUTION_RULE_IDS.scheduled)
    expect((after.body as unknown[]).length).toBe(beforeCount)
  })
})
