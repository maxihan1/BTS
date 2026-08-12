// FR-AT-07 PR-D Git 웹훅 등록 REST 3매핑 MSW stateful 핸들러 단위 테스트
// — POST→GET 반영 + DELETE→GET 반영 + secret 400 + 권한 403 계약 검증
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import {
  createGitWebhookResponseSchema,
  gitWebhookSummarySchema,
} from '@/api/automation-git-webhooks.types'
import type { GitProvider } from '@/api/automation-git-webhooks.types'
import { gitWebhookHandlers } from './git-webhook-handlers'
import { DEFAULT_GIT_WEBHOOK_PROJECT_KEY, resetGitWebhookStore, SCENARIO_KEY } from './git-webhook-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...gitWebhookHandlers)
})
afterEach(() => {
  resetGitWebhookStore()
  for (const key of Object.values(SCENARIO_KEY)) {
    localStorage.removeItem(key)
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — fetch 래퍼 (drift 차단을 위해 리터럴 요청 바디 산재 대신 helper로 생성)
// ─────────────────────────────────────────────────────────────────────────────

const webhooksUrl = (projectKey: string = DEFAULT_GIT_WEBHOOK_PROJECT_KEY): string =>
  `/api/v1/projects/${projectKey}/automation/git-webhooks`

const webhookUrl = (id: string, projectKey: string = DEFAULT_GIT_WEBHOOK_PROJECT_KEY): string =>
  `${webhooksUrl(projectKey)}/${id}`

/** 백엔드 MIN_SECRET_LENGTH(16)를 만족하는 유효 secret — 길이 그대로 검증하는 mock 로직과 맞춘다. */
const VALID_SECRET = 'a'.repeat(32)

interface CreateWebhookBody {
  provider: GitProvider
  secret?: string
}

/** POST 등록 요청 바디를 기본값으로 채워 만든다(drift 차단 helper). */
function buildCreateBody(overrides: Partial<CreateWebhookBody> = {}): CreateWebhookBody {
  return {
    provider: 'GITHUB',
    secret: VALID_SECRET,
    ...overrides,
  }
}

async function listGitWebhooks(projectKey?: string): Promise<{ status: number; body: unknown }> {
  const res = await fetch(webhooksUrl(projectKey))
  return { status: res.status, body: (await res.json()) as unknown }
}

async function createGitWebhook(
  overrides: Partial<CreateWebhookBody> = {},
  projectKey?: string,
): Promise<{ status: number; body: unknown }> {
  const res = await fetch(webhooksUrl(projectKey), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(buildCreateBody(overrides)),
  })
  return { status: res.status, body: (await res.json()) as unknown }
}

async function deleteGitWebhook(id: string, projectKey?: string): Promise<{ status: number }> {
  const res = await fetch(webhookUrl(id, projectKey), { method: 'DELETE' })
  return { status: res.status }
}

// ─────────────────────────────────────────────────────────────────────────────
// (a) POST → 201 webhookUrl·token 동봉 + 후속 GET 목록 1건 반영 (EC19 stateful)
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /automation/git-webhooks → 201, 후속 GET 목록 반영', () => {
  it('POST → 201 이 webhookUrl·token 을 담고, 그 뒤 GET 목록에 1건이 잡힌다', async () => {
    const before = await listGitWebhooks()
    expect(before.status).toBe(200)
    expect(before.body).toEqual([])

    const { status, body } = await createGitWebhook()
    expect(status).toBe(201)

    const parsed = createGitWebhookResponseSchema.parse(body)
    expect(parsed.provider).toBe('GITHUB')
    expect(parsed.webhookUrl).toBeTruthy()
    expect(parsed.token).toBeTruthy()
    // webhookUrl은 origin 없는 절대 경로 계약(NFR4) — 스킴/호스트를 붙이지 않는다.
    expect(parsed.webhookUrl.startsWith('/api/v1/webhooks/git/')).toBe(true)

    const after = await listGitWebhooks()
    expect(after.status).toBe(200)
    const rules = after.body as unknown[]
    expect(rules).toHaveLength(1)
    for (const rule of rules) {
      const summary = gitWebhookSummarySchema.parse(rule)
      expect(summary.id).toBe(parsed.id)
      // 목록 응답에는 token·secret 관련 필드가 하나도 없다(타입 상 새어 나갈 수 없는 계약).
      expect(Object.keys(rule as object)).not.toContain('token')
      expect(Object.keys(rule as object)).not.toContain('secret')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) DELETE → 204, 후속 GET 목록에서 사라진다
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /automation/git-webhooks/:id → 204, 후속 GET 목록 반영', () => {
  it('DELETE → 204 후 GET 목록에서 사라진다', async () => {
    const created = await createGitWebhook()
    const registered = createGitWebhookResponseSchema.parse(created.body)

    const before = await listGitWebhooks()
    expect((before.body as unknown[])).toHaveLength(1)

    const { status } = await deleteGitWebhook(registered.id)
    expect(status).toBe(204)

    const after = await listGitWebhooks()
    const remaining = after.body as Array<{ id: string }>
    expect(remaining.map((w) => w.id)).not.toContain(registered.id)
    expect(remaining).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) secret 15자 이하 → 400 AUTOMATION_GIT_WEBHOOK_SECRET_INVALID + 서버 고정 detail
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /automation/git-webhooks — secret 검증 (16~4096자·비공백)', () => {
  it('secret 15자 이하면 400 AUTOMATION_GIT_WEBHOOK_SECRET_INVALID + 서버 고정 detail', async () => {
    const { status, body } = await createGitWebhook({ secret: 'a'.repeat(15) })
    expect(status).toBe(400)

    const problem = body as { errorCode: string; detail: string }
    expect(problem.errorCode).toBe('AUTOMATION_GIT_WEBHOOK_SECRET_INVALID')
    expect(problem.detail).toBe('secret 은 공백이 아닌 16자 이상 4096자 이하 문자열이어야 합니다.')

    // 실패 시 store에 반영되지 않는다 — 후속 GET 목록도 여전히 빈 배열.
    const after = await listGitWebhooks()
    expect(after.body).toEqual([])
  })

  it('secret 이 공백뿐이면 400 AUTOMATION_GIT_WEBHOOK_SECRET_INVALID 를 반환한다', async () => {
    const { status, body } = await createGitWebhook({ secret: '   ' })
    expect(status).toBe(400)
    const problem = body as { errorCode: string }
    expect(problem.errorCode).toBe('AUTOMATION_GIT_WEBHOOK_SECRET_INVALID')
  })

  it('secret 4096자를 초과하면 400 AUTOMATION_GIT_WEBHOOK_SECRET_INVALID 를 반환한다', async () => {
    const { status, body } = await createGitWebhook({ secret: 'a'.repeat(4097) })
    expect(status).toBe(400)
    const problem = body as { errorCode: string }
    expect(problem.errorCode).toBe('AUTOMATION_GIT_WEBHOOK_SECRET_INVALID')
  })

  it('secret 16자(경계값)면 201로 성공한다', async () => {
    const { status } = await createGitWebhook({ secret: 'a'.repeat(16) })
    expect(status).toBe(201)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) SCENARIO_KEY.FORBIDDEN 플래그 — GET 목록이 403 AUTOMATION_ACCESS_DENIED (FR12 error 분기 재현용)
// ─────────────────────────────────────────────────────────────────────────────

describe('SCENARIO_KEY.FORBIDDEN localStorage 플래그', () => {
  it('플래그 on 이면 GET 이 403 AUTOMATION_ACCESS_DENIED', async () => {
    localStorage.setItem(SCENARIO_KEY.FORBIDDEN, 'true')

    const { status, body } = await listGitWebhooks()
    expect(status).toBe(403)
    const problem = body as { errorCode: string; detail: string }
    expect(problem.errorCode).toBe('AUTOMATION_ACCESS_DENIED')
    expect(problem.detail).toBe('이 작업을 수행할 권한이 없습니다.')
  })

  it('플래그가 없으면 GET 은 정상 200 을 반환한다', async () => {
    const { status } = await listGitWebhooks()
    expect(status).toBe(200)
  })
})
