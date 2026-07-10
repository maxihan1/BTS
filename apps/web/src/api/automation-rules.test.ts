// 자동화 룰 API 클라이언트 단위 테스트 — MSW(automationRuleHandlers) 통해 실 fetch로 CRUD·XSRF·에러코드 검증
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import {
  fetchAutomationRules,
  fetchAutomationRule,
  createAutomationRule,
  patchAutomationRule,
  deleteAutomationRule,
  extractAutomationRuleErrorCode,
} from './automation-rules'
import { ApiError } from './client'
import { automationRuleHandlers } from '@/mocks/automation-rule-handlers'
import {
  DEFAULT_AUTOMATION_PROJECT_KEY,
  DEFAULT_AUTOMATION_RULES,
  resetAutomationRuleStore,
  seedAutomationRules,
} from '@/mocks/automation-rule-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — automation-rule-handlers.test.ts와 동형(전역 handlers.ts 미등록, 로컬 서버)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...automationRuleHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})
afterEach(() => {
  server.resetHandlers()
  resetAutomationRuleStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})
afterAll(() => server.close())

const PROJECT_KEY = DEFAULT_AUTOMATION_PROJECT_KEY
const NONEXISTENT_ID = 'a1000000-0000-4000-8000-000000000099'

/** DEFAULT_AUTOMATION_RULES[0] — noUncheckedIndexedAccess 가드 헬퍼 */
function firstSeedRule() {
  const rule = DEFAULT_AUTOMATION_RULES[0]
  if (rule === undefined) {
    throw new Error('fixture DEFAULT_AUTOMATION_RULES[0]이 비어있음')
  }
  return rule
}

// ─────────────────────────────────────────────────────────────────────────────
// fetchAutomationRules
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchAutomationRules', () => {
  it('빈 스토어에서 빈 배열을 반환한다', async () => {
    const result = await fetchAutomationRules(PROJECT_KEY)
    expect(result).toEqual([])
  })

  it('시드된 룰 목록을 AutomationRule[]로 반환한다(경로/메서드/Zod parse)', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const result = await fetchAutomationRules(PROJECT_KEY)
    expect(result).toHaveLength(DEFAULT_AUTOMATION_RULES.length)
    expect(result[0]?.name).toBe(firstSeedRule().name)
    expect(result[0]?.triggerType).toBe(firstSeedRule().triggerType)
  })

  it('필수 필드가 누락된 응답은 Zod parse 실패로 reject된다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules', () =>
        HttpResponse.json([{ id: 'broken', name: 'no-other-fields' }]),
      ),
    )
    await expect(fetchAutomationRules(PROJECT_KEY)).rejects.toThrow()
  })

  it('403 응답 시 ApiError(AUTOMATION_ACCESS_DENIED)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_ACCESS_DENIED' }, { status: 403 }),
      ),
    )
    await expect(fetchAutomationRules(PROJECT_KEY)).rejects.toBeInstanceOf(ApiError)
    try {
      await fetchAutomationRules(PROJECT_KEY)
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(extractAutomationRuleErrorCode(error)).toBe('AUTOMATION_ACCESS_DENIED')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchAutomationRule
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchAutomationRule', () => {
  it('단건 조회 시 AutomationRule을 반환한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = firstSeedRule()
    const result = await fetchAutomationRule(PROJECT_KEY, target.id)
    expect(result.id).toBe(target.id)
    expect(result.hasWebhookToken).toBe(target.hasWebhookToken)
  })

  it('미존재 id는 404 ApiError(AUTOMATION_RULE_NOT_FOUND)를 throw한다', async () => {
    try {
      await fetchAutomationRule(PROJECT_KEY, NONEXISTENT_ID)
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).status).toBe(404)
      expect(extractAutomationRuleErrorCode(error)).toBe('AUTOMATION_RULE_NOT_FOUND')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createAutomationRule
// ─────────────────────────────────────────────────────────────────────────────

describe('createAutomationRule', () => {
  it('생성 시 CreateAutomationRuleResponse를 반환하고 webhookToken은 null이다', async () => {
    const result = await createAutomationRule(PROJECT_KEY, {
      name: '새 룰',
      triggerType: 'ISSUE_CREATED',
      triggerConfig: '{}',
    })
    expect(result.rule.name).toBe('새 룰')
    expect(result.rule.version).toBe(1)
    expect(result.webhookToken).toBeNull()
  })

  it('WEBHOOK 트리거 생성 시 webhookToken 원문이 동봉된다', async () => {
    const result = await createAutomationRule(PROJECT_KEY, {
      name: '웹훅 룰',
      triggerType: 'WEBHOOK',
      triggerConfig: '{}',
    })
    expect(typeof result.webhookToken).toBe('string')
    expect(result.rule.hasWebhookToken).toBe(true)
  })

  it('POST 요청에 X-XSRF-TOKEN 헤더를 부착한다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(
          { rule: { ...firstSeedRule(), id: 'a1000000-0000-4000-8000-000000000050' }, webhookToken: null },
          { status: 201 },
        )
      }),
    )
    await createAutomationRule(PROJECT_KEY, {
      name: 'xsrf 확인용',
      triggerType: 'ISSUE_CREATED',
      triggerConfig: '{}',
    })
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('400 응답 시 ApiError(AUTOMATION_RULE_INVALID)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/rules', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_RULE_INVALID' }, { status: 400 }),
      ),
    )
    try {
      await createAutomationRule(PROJECT_KEY, { name: '', triggerType: 'SCHEDULED', triggerConfig: '{}' })
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect(extractAutomationRuleErrorCode(error)).toBe('AUTOMATION_RULE_INVALID')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// patchAutomationRule
// ─────────────────────────────────────────────────────────────────────────────

describe('patchAutomationRule', () => {
  it('필드 변경 후 AutomationRule을 반환하고 version이 증가한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = firstSeedRule()
    const result = await patchAutomationRule(PROJECT_KEY, target.id, {
      version: target.version,
      enabled: false,
    })
    expect(result.enabled).toBe(false)
    expect(result.version).toBe(target.version + 1)
  })

  it('PATCH 요청에 X-XSRF-TOKEN 헤더를 부착한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = firstSeedRule()
    let capturedXsrf: string | null = null
    server.use(
      http.patch('/api/v1/projects/:projectKey/automation/rules/:id', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json({ ...target, version: target.version + 1 })
      }),
    )
    await patchAutomationRule(PROJECT_KEY, target.id, { version: target.version })
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('버전 불일치 시 409 ApiError(AUTOMATION_RULE_VERSION_CONFLICT)를 throw한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = firstSeedRule()
    try {
      await patchAutomationRule(PROJECT_KEY, target.id, { version: target.version + 99 })
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).status).toBe(409)
      expect(extractAutomationRuleErrorCode(error)).toBe('AUTOMATION_RULE_VERSION_CONFLICT')
    }
  })

  it('미존재 id 수정 시 404 ApiError(AUTOMATION_RULE_NOT_FOUND)를 throw한다', async () => {
    try {
      await patchAutomationRule(PROJECT_KEY, NONEXISTENT_ID, { version: 1 })
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect(extractAutomationRuleErrorCode(error)).toBe('AUTOMATION_RULE_NOT_FOUND')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deleteAutomationRule
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteAutomationRule', () => {
  it('삭제 성공 시 void를 반환한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = firstSeedRule()
    await expect(deleteAutomationRule(PROJECT_KEY, target.id)).resolves.toBeUndefined()
  })

  it('DELETE 요청에 X-XSRF-TOKEN 헤더를 부착한다', async () => {
    seedAutomationRules(DEFAULT_AUTOMATION_RULES)
    const target = firstSeedRule()
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/projects/:projectKey/automation/rules/:id', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await deleteAutomationRule(PROJECT_KEY, target.id)
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('미존재 id 삭제 시 404 ApiError(AUTOMATION_RULE_NOT_FOUND)를 throw한다', async () => {
    try {
      await deleteAutomationRule(PROJECT_KEY, NONEXISTENT_ID)
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).status).toBe(404)
      expect(extractAutomationRuleErrorCode(error)).toBe('AUTOMATION_RULE_NOT_FOUND')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// extractAutomationRuleErrorCode
// ─────────────────────────────────────────────────────────────────────────────

describe('extractAutomationRuleErrorCode', () => {
  it('ApiError body의 errorCode를 string으로 반환한다', () => {
    const err = new ApiError(409, { errorCode: 'AUTOMATION_RULE_VERSION_CONFLICT' })
    expect(extractAutomationRuleErrorCode(err)).toBe('AUTOMATION_RULE_VERSION_CONFLICT')
  })

  it('ApiError이지만 errorCode가 없으면 null을 반환한다', () => {
    const err = new ApiError(500, { detail: 'Internal Server Error' })
    expect(extractAutomationRuleErrorCode(err)).toBeNull()
  })

  it('ApiError가 아니면 null을 반환한다', () => {
    expect(extractAutomationRuleErrorCode(new Error('network error'))).toBeNull()
    expect(extractAutomationRuleErrorCode('string error')).toBeNull()
    expect(extractAutomationRuleErrorCode(null)).toBeNull()
  })
})
