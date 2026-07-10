// FR-AT-01 D6 자동화 룰 MSW stateful 핸들러 단위 테스트 — CRUD 반영 + OCC 409 + WEBHOOK 토큰 계약 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import {
  automationRuleResponseSchema,
  createAutomationRuleResponseSchema,
} from '@/api/automation-rules.types'
import type { AutomationRule, TriggerType } from '@/api/automation-rules.types'
import { automationRuleHandlers } from './automation-rule-handlers'
import {
  DEFAULT_AUTOMATION_PROJECT_KEY,
  DEFAULT_AUTOMATION_RULES,
  resetAutomationRuleStore,
  SCENARIO_KEY,
  seedAutomationRules,
} from './automation-rule-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...automationRuleHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetAutomationRuleStore()
  for (const key of Object.values(SCENARIO_KEY)) {
    localStorage.removeItem(key)
  }
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — fetch 래퍼 (drift 차단을 위해 리터럴 요청 바디 산재 대신 helper로 생성)
// ─────────────────────────────────────────────────────────────────────────────

const rulesUrl = (projectKey: string = DEFAULT_AUTOMATION_PROJECT_KEY): string =>
  `/api/v1/projects/${projectKey}/automation/rules`

const ruleUrl = (id: string, projectKey: string = DEFAULT_AUTOMATION_PROJECT_KEY): string =>
  `${rulesUrl(projectKey)}/${id}`

interface CreateRuleBody {
  name: string
  triggerType: TriggerType
  triggerConfig: string
}

/** POST 생성 요청 바디를 트리거 타입 기본값으로 채워 만든다(drift 차단 helper). */
function buildCreateBody(overrides: Partial<CreateRuleBody> = {}): CreateRuleBody {
  return {
    name: '새 자동화 룰',
    triggerType: 'ISSUE_CREATED',
    triggerConfig: '{}',
    ...overrides,
  }
}

async function listRules(projectKey?: string): Promise<{ status: number; body: unknown }> {
  const res = await fetch(rulesUrl(projectKey))
  return { status: res.status, body: (await res.json()) as unknown }
}

async function createRule(
  overrides: Partial<CreateRuleBody> = {},
  projectKey?: string,
): Promise<{ status: number; body: unknown }> {
  const res = await fetch(rulesUrl(projectKey), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(buildCreateBody(overrides)),
  })
  return { status: res.status, body: (await res.json()) as unknown }
}

async function getRule(id: string, projectKey?: string): Promise<{ status: number; body: unknown }> {
  const res = await fetch(ruleUrl(id, projectKey))
  return { status: res.status, body: (await res.json()) as unknown }
}

async function patchRule(
  id: string,
  body: Record<string, unknown>,
  projectKey?: string,
): Promise<{ status: number; body: unknown }> {
  const res = await fetch(ruleUrl(id, projectKey), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return { status: res.status, body: (await res.json()) as unknown }
}

async function deleteRule(id: string, projectKey?: string): Promise<{ status: number }> {
  const res = await fetch(ruleUrl(id, projectKey), { method: 'DELETE' })
  return { status: res.status }
}

// ─────────────────────────────────────────────────────────────────────────────
// 계약 drift 가드 — 시드 fixture가 실제 Zod 응답 스키마를 통과하는지 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('DEFAULT_AUTOMATION_RULES 픽스처 계약 검증', () => {
  it('시드 룰 각각이 automationRuleResponseSchema를 통과한다', () => {
    for (const rule of DEFAULT_AUTOMATION_RULES) {
      expect(() => automationRuleResponseSchema.parse(rule)).not.toThrow()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) GET 목록 — 초기 빈 상태 + 시드 반영
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /automation/rules (목록)', () => {
  it('시드 없이 시작하면 빈 배열을 bare(봉투 없음)로 반환한다', async () => {
    const { status, body } = await listRules()
    expect(status).toBe(200)
    expect(Array.isArray(body)).toBe(true)
    expect(body).toHaveLength(0)
  })

  it('seedAutomationRules로 시드하면 목록에 반영된다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const { body } = await listRules()
    const rules = body as AutomationRule[]
    expect(rules).toHaveLength(DEFAULT_AUTOMATION_RULES.length)
    for (const rule of rules) {
      expect(() => automationRuleResponseSchema.parse(rule)).not.toThrow()
    }
  })

  it('다른 프로젝트의 룰은 목록에 섞이지 않는다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const { body } = await listRules('OTHER-PROJECT')
    expect(body).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) POST 생성 → 201 + 후속 GET 목록 증가 + WEBHOOK 토큰 동봉
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /automation/rules → 201, 후속 GET 목록 증가', () => {
  it('ISSUE_CREATED 룰 생성 시 201을 반환하고 목록이 1개 증가한다', async () => {
    const before = await listRules()
    const beforeCount = (before.body as unknown[]).length

    const { status, body } = await createRule({ name: '이슈 생성 알림', triggerType: 'ISSUE_CREATED' })
    expect(status).toBe(201)

    const parsed = createAutomationRuleResponseSchema.parse(body)
    expect(parsed.rule.name).toBe('이슈 생성 알림')
    expect(parsed.rule.triggerType).toBe('ISSUE_CREATED')
    expect(parsed.rule.enabled).toBe(true)
    expect(parsed.rule.version).toBe(1)
    expect(parsed.webhookToken).toBeNull()

    const after = await listRules()
    const afterRules = after.body as AutomationRule[]
    expect(afterRules).toHaveLength(beforeCount + 1)
    expect(afterRules.map((r) => r.id)).toContain(parsed.rule.id)
  })

  it('WEBHOOK 트리거 생성 시 webhookToken 원문이 동봉되고 hasWebhookToken=true다', async () => {
    const { status, body } = await createRule({ name: '웹훅 룰', triggerType: 'WEBHOOK' })
    expect(status).toBe(201)

    const parsed = createAutomationRuleResponseSchema.parse(body)
    expect(parsed.webhookToken).toBeTruthy()
    expect(typeof parsed.webhookToken).toBe('string')
    expect(parsed.rule.hasWebhookToken).toBe(true)

    // 후속 단건 GET 응답에는 원문 토큰 필드 자체가 없다(스키마에 필드 없음 — 1회성 노출 보장)
    const single = await getRule(parsed.rule.id)
    const singleParsed = automationRuleResponseSchema.parse(single.body)
    expect(singleParsed.hasWebhookToken).toBe(true)
    expect(Object.keys(single.body as object)).not.toContain('webhookToken')
  })

  it('SCHEDULED 트리거 생성 시 nextFireAt이 채워진다', async () => {
    const { body } = await createRule({
      name: '매일 스캔',
      triggerType: 'SCHEDULED',
      triggerConfig: '{"cron":"0 0 9 * * *"}',
    })
    const parsed = createAutomationRuleResponseSchema.parse(body)
    expect(parsed.rule.nextFireAt).not.toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) PATCH → 200 + 필드 변경 + version 증가 + 후속 GET 반영
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /automation/rules/:id → 필드 변경 + version 증가', () => {
  it('name·enabled를 수정하면 200을 반환하고 version이 1 증가한다', async () => {
    const created = await createRule({ name: '원래 이름' })
    const rule = createAutomationRuleResponseSchema.parse(created.body).rule

    const { status, body } = await patchRule(rule.id, {
      version: rule.version,
      name: '변경된 이름',
      enabled: false,
    })
    expect(status).toBe(200)

    const updated = automationRuleResponseSchema.parse(body)
    expect(updated.name).toBe('변경된 이름')
    expect(updated.enabled).toBe(false)
    expect(updated.version).toBe(rule.version + 1)

    // 후속 GET에 즉시 반영 (msw-mutation-stateful-refetch)
    const after = await getRule(rule.id)
    const afterParsed = automationRuleResponseSchema.parse(after.body)
    expect(afterParsed.name).toBe('변경된 이름')
    expect(afterParsed.enabled).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) PATCH version 불일치 → 409 AUTOMATION_RULE_VERSION_CONFLICT
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /automation/rules/:id — OCC 버전 충돌 → 409', () => {
  it('body.version이 현재 저장된 version과 다르면 409 ProblemDetail을 반환한다', async () => {
    const created = await createRule()
    const rule = createAutomationRuleResponseSchema.parse(created.body).rule

    const staleVersion = rule.version + 99
    const { status, body } = await patchRule(rule.id, { version: staleVersion, name: '충돌 시도' })

    expect(status).toBe(409)
    const problem = body as { errorCode: string; status: number }
    expect(problem.errorCode).toBe('AUTOMATION_RULE_VERSION_CONFLICT')
    expect(problem.status).toBe(409)

    // 충돌 후 store는 변경되지 않아야 한다
    const after = await getRule(rule.id)
    const afterParsed = automationRuleResponseSchema.parse(after.body)
    expect(afterParsed.name).not.toBe('충돌 시도')
    expect(afterParsed.version).toBe(rule.version)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) DELETE → 204 + 후속 GET 목록 감소
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /automation/rules/:id → 204, 후속 GET 목록 감소', () => {
  it('삭제 후 204를 반환하고 목록에서 제거된다', async () => {
    const first = await createRule({ name: '삭제 대상' })
    const firstRule = createAutomationRuleResponseSchema.parse(first.body).rule
    await createRule({ name: '남는 룰' })

    const before = await listRules()
    const beforeCount = (before.body as unknown[]).length

    const { status } = await deleteRule(firstRule.id)
    expect(status).toBe(204)

    const after = await listRules()
    const afterRules = after.body as AutomationRule[]
    expect(afterRules).toHaveLength(beforeCount - 1)
    expect(afterRules.map((r) => r.id)).not.toContain(firstRule.id)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (f) SCENARIO_KEY.EMPTY_LIST 플래그 — 시드 존재해도 빈 목록 강제
// ─────────────────────────────────────────────────────────────────────────────

describe('SCENARIO_KEY.EMPTY_LIST localStorage 플래그', () => {
  it('플래그가 true면 시드된 룰이 있어도 빈 배열을 반환한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    localStorage.setItem(SCENARIO_KEY.EMPTY_LIST, 'true')

    const { body } = await listRules()
    expect(body).toEqual([])
  })
})
