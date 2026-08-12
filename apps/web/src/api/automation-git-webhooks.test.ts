// Git 웹훅 등록/목록/삭제 API 함수 단위 테스트 — MSW(gitWebhookHandlers) 통해 실 fetch로 secret 원문 보존·XSRF·403 검증
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createGitWebhook, deleteGitWebhook, listGitWebhooks } from './automation-git-webhooks'
import { extractAutomationRuleErrorCode } from './automation-rules'
import { ApiError } from './client'
import { gitWebhookHandlers } from '@/mocks/git-webhook-handlers'
import {
  DEFAULT_GIT_WEBHOOK_PROJECT_KEY,
  DEFAULT_GIT_WEBHOOKS,
  resetGitWebhookStore,
  seedGitWebhooks,
  SCENARIO_KEY,
} from '@/mocks/git-webhook-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — automation-rules.test.ts와 동형(전역 handlers.ts 미등록, 로컬 서버)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...gitWebhookHandlers)
})
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})
afterEach(() => {
  resetGitWebhookStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  for (const key of Object.values(SCENARIO_KEY)) {
    localStorage.removeItem(key)
  }
})

const PROJECT_KEY = DEFAULT_GIT_WEBHOOK_PROJECT_KEY
/** 백엔드 MIN_SECRET_LENGTH(16)를 만족하는 유효 secret — mock 로직과 맞춘다. */
const VALID_SECRET = 'a'.repeat(32)

/** DEFAULT_GIT_WEBHOOKS[0] — noUncheckedIndexedAccess 가드 헬퍼 */
function firstSeedWebhook() {
  const webhook = DEFAULT_GIT_WEBHOOKS[0]
  if (webhook === undefined) {
    throw new Error('fixture DEFAULT_GIT_WEBHOOKS[0]이 비어있음')
  }
  return webhook
}

// ─────────────────────────────────────────────────────────────────────────────
// listGitWebhooks
// ─────────────────────────────────────────────────────────────────────────────

describe('listGitWebhooks', () => {
  it('빈 스토어에서 빈 배열을 반환한다', async () => {
    const result = await listGitWebhooks(PROJECT_KEY)
    expect(result).toEqual([])
  })

  it('시드된 웹훅 목록을 GitWebhookSummary[]로 반환한다(경로/메서드/Zod parse)', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const result = await listGitWebhooks(PROJECT_KEY)
    expect(result).toHaveLength(DEFAULT_GIT_WEBHOOKS.length)
    expect(result[0]?.provider).toBe(firstSeedWebhook().provider)
    expect(result[0]?.id).toBe(firstSeedWebhook().id)
  })

  it('403 이면 ApiError 를 던지고 extractAutomationRuleErrorCode 가 AUTOMATION_ACCESS_DENIED 를 뽑는다', async () => {
    localStorage.setItem(SCENARIO_KEY.FORBIDDEN, 'true')
    try {
      await listGitWebhooks(PROJECT_KEY)
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).status).toBe(403)
      expect(extractAutomationRuleErrorCode(error)).toBe('AUTOMATION_ACCESS_DENIED')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createGitWebhook
// ─────────────────────────────────────────────────────────────────────────────

describe('createGitWebhook', () => {
  it('생성 시 CreateGitWebhookResponse를 반환한다(id·provider·webhookUrl·token)', async () => {
    const result = await createGitWebhook(PROJECT_KEY, { provider: 'GITHUB', secret: VALID_SECRET })
    expect(result.provider).toBe('GITHUB')
    expect(typeof result.token).toBe('string')
    expect(result.webhookUrl.startsWith('/api/v1/webhooks/git/')).toBe(true)
  })

  // ★ B2 1층 — API 함수가 secret 을 손대지 않는다(BLOCKER-0). trim 이 끼면 red.
  it('createGitWebhook 은 secret 원문을 바이트 그대로 싣는다', async () => {
    let body: unknown
    server.use(
      http.post('*/git-webhooks', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(
          {
            id: '11111111-1111-4111-8111-111111111111',
            provider: 'GITHUB',
            webhookUrl: '/api/v1/webhooks/git/tok',
            token: 'tok',
          },
          { status: 201 },
        )
      }),
    )
    await createGitWebhook(PROJECT_KEY, { provider: 'GITHUB', secret: '  abcdefghijklmnop  ' })
    expect((body as { secret: string }).secret).toBe('  abcdefghijklmnop  ')
  })

  it('createGitWebhook 은 X-XSRF-TOKEN 헤더를 싣는다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/projects/:projectKey/automation/git-webhooks', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(
          {
            id: '11111111-1111-4111-8111-111111111111',
            provider: 'GITHUB',
            webhookUrl: '/api/v1/webhooks/git/tok',
            token: 'tok',
          },
          { status: 201 },
        )
      }),
    )
    await createGitWebhook(PROJECT_KEY, { provider: 'GITHUB', secret: VALID_SECRET })
    expect(capturedXsrf).toBe('test-csrf-token')
  })

  it('secret 검증 실패(400) 시 ApiError(AUTOMATION_GIT_WEBHOOK_SECRET_INVALID)를 throw한다', async () => {
    try {
      await createGitWebhook(PROJECT_KEY, { provider: 'GITHUB', secret: 'too-short' })
      expect.fail('ApiError가 throw 되어야 한다')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).status).toBe(400)
      expect(extractAutomationRuleErrorCode(error)).toBe('AUTOMATION_GIT_WEBHOOK_SECRET_INVALID')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deleteGitWebhook
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteGitWebhook', () => {
  it('deleteGitWebhook 은 204 를 파싱 없이 통과시킨다', async () => {
    seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
    const target = firstSeedWebhook()
    await expect(deleteGitWebhook(PROJECT_KEY, target.id)).resolves.toBeUndefined()
  })

  it('DELETE 요청에 X-XSRF-TOKEN 헤더를 부착한다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/projects/:projectKey/automation/git-webhooks/:id', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await deleteGitWebhook(PROJECT_KEY, '11111111-1111-4111-8111-111111111111')
    expect(capturedXsrf).toBe('test-csrf-token')
  })
})
