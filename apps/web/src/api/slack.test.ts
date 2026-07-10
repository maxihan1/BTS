// Slack 연결 상태 조회 + authorize URL 발급 + 본인 계정 연결/해제 API client 단위 테스트 — MSW + Zod 파싱 검증 (FR-SL-01 D6, FR-SL-02 D6)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  SlackInstallationSchema,
  SlackInstallUrlSchema,
  SlackConnectionSchema,
  getSlackInstallation,
  getSlackInstallUrl,
  getMyConnection,
  connectSlack,
  disconnectSlack,
} from './slack'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — SlackInstallation / SlackInstallUrl (백엔드 view-layer 응답 계약 1:1 — 스펙 FR1/FR2)
// ─────────────────────────────────────────────────────────────────────────────
const SLACK_INSTALLATION_FIXTURE_CONNECTED = {
  connected: true,
  teamId: 'T1',
  teamName: 'Acme',
  botUserId: 'U-BOT-1',
  installedAt: '2026-07-08T00:00:00Z',
  updatedAt: '2026-07-08T01:00:00Z',
  installerName: '홍길동',
}

const SLACK_INSTALLATION_FIXTURE_DISCONNECTED = {
  connected: false,
  teamId: null,
  teamName: null,
  botUserId: null,
  installedAt: null,
  updatedAt: null,
  installerName: null,
}

const SLACK_INSTALL_URL_FIXTURE = {
  url: 'https://slack.com/oauth/v2/authorize?client_id=xxx',
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — SlackConnection (백엔드 view-layer 응답 계약 1:1 — 스펙 FR3, GET/POST/DELETE 공통 DTO)
// ─────────────────────────────────────────────────────────────────────────────
const SLACK_CONNECTION_FIXTURE_CONNECTED = {
  connected: true,
  workspaceName: 'Acme Corp',
  linkedAt: '2026-07-10T00:00:00Z',
}

const SLACK_CONNECTION_FIXTURE_DISCONNECTED = {
  connected: false,
  workspaceName: null,
  linkedAt: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-S. SlackInstallationSchema / SlackInstallUrlSchema — Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('SlackInstallationSchema', () => {
  it('T-SL-S-1: 연결됨 응답(teamId/teamName/botUserId/installedAt/updatedAt/installerName 값 존재)을 파싱한다', () => {
    const result = SlackInstallationSchema.parse(SLACK_INSTALLATION_FIXTURE_CONNECTED)
    expect(result.connected).toBe(true)
    expect(result.teamId).toBe('T1')
    expect(result.teamName).toBe('Acme')
    expect(result.botUserId).toBe('U-BOT-1')
    expect(result.installedAt).toBe('2026-07-08T00:00:00Z')
    expect(result.updatedAt).toBe('2026-07-08T01:00:00Z')
    expect(result.installerName).toBe('홍길동')
  })

  it('T-SL-S-2: 미연결 응답(teamId/teamName/botUserId/installedAt/updatedAt/installerName 모두 null)을 파싱한다', () => {
    const result = SlackInstallationSchema.parse(SLACK_INSTALLATION_FIXTURE_DISCONNECTED)
    expect(result.connected).toBe(false)
    expect(result.teamId).toBeNull()
    expect(result.teamName).toBeNull()
    expect(result.botUserId).toBeNull()
    expect(result.installedAt).toBeNull()
    expect(result.updatedAt).toBeNull()
    expect(result.installerName).toBeNull()
  })

  it('T-SL-S-2b: 연결됨이지만 installerName 미해석 시 null을 허용한다', () => {
    const result = SlackInstallationSchema.parse({
      ...SLACK_INSTALLATION_FIXTURE_CONNECTED,
      installerName: null,
    })
    expect(result.installerName).toBeNull()
  })

  it('T-SL-S-3: connected 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { connected: _connected, ...without } = SLACK_INSTALLATION_FIXTURE_CONNECTED
    expect(() => SlackInstallationSchema.parse(without)).toThrow()
  })

  it('T-SL-S-4: teamId 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { teamId: _teamId, ...without } = SLACK_INSTALLATION_FIXTURE_CONNECTED
    expect(() => SlackInstallationSchema.parse(without)).toThrow()
  })

  it('T-SL-S-5: teamName 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { teamName: _teamName, ...without } = SLACK_INSTALLATION_FIXTURE_CONNECTED
    expect(() => SlackInstallationSchema.parse(without)).toThrow()
  })

  it('T-SL-S-6: installedAt 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { installedAt: _installedAt, ...without } = SLACK_INSTALLATION_FIXTURE_CONNECTED
    expect(() => SlackInstallationSchema.parse(without)).toThrow()
  })
})

describe('SlackInstallUrlSchema', () => {
  it('T-SL-S-7: url 필드를 파싱한다', () => {
    const result = SlackInstallUrlSchema.parse(SLACK_INSTALL_URL_FIXTURE)
    expect(result.url).toBe('https://slack.com/oauth/v2/authorize?client_id=xxx')
  })

  it('T-SL-S-8: url 필드 누락 시 throw한다', () => {
    expect(() => SlackInstallUrlSchema.parse({})).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-1. getSlackInstallation — GET /api/v1/slack/installation
// ─────────────────────────────────────────────────────────────────────────────
describe('getSlackInstallation', () => {
  it('T-SL-1-1: /api/v1/slack/installation 을 호출해 연결됨 응답을 파싱해 반환한다', async () => {
    server.use(
      http.get('/api/v1/slack/installation', () => HttpResponse.json(SLACK_INSTALLATION_FIXTURE_CONNECTED)),
    )
    const result = await getSlackInstallation()
    expect(result.connected).toBe(true)
    expect(result.teamId).toBe('T1')
    expect(result.teamName).toBe('Acme')
    expect(result.installedAt).toBe('2026-07-08T00:00:00Z')
  })

  it('T-SL-1-2: 미연결 응답도 파싱해 반환한다', async () => {
    server.use(
      http.get('/api/v1/slack/installation', () => HttpResponse.json(SLACK_INSTALLATION_FIXTURE_DISCONNECTED)),
    )
    const result = await getSlackInstallation()
    expect(result.connected).toBe(false)
    expect(result.teamId).toBeNull()
    expect(result.teamName).toBeNull()
    expect(result.installedAt).toBeNull()
  })

  it('T-SL-1-3: 401 응답 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/slack/installation', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(getSlackInstallation()).rejects.toBeInstanceOf(ApiError)
    await expect(getSlackInstallation()).rejects.toMatchObject({ status: 401 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-2. getSlackInstallUrl — GET /api/v1/slack/install-url
// ─────────────────────────────────────────────────────────────────────────────
describe('getSlackInstallUrl', () => {
  it('T-SL-2-1: /api/v1/slack/install-url 을 호출해 url을 파싱해 반환한다', async () => {
    server.use(http.get('/api/v1/slack/install-url', () => HttpResponse.json(SLACK_INSTALL_URL_FIXTURE)))
    const result = await getSlackInstallUrl()
    expect(result.url).toBe('https://slack.com/oauth/v2/authorize?client_id=xxx')
  })

  it('T-SL-2-2: 401 응답 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/slack/install-url', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(getSlackInstallUrl()).rejects.toBeInstanceOf(ApiError)
    await expect(getSlackInstallUrl()).rejects.toMatchObject({ status: 401 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-C. SlackConnectionSchema — Zod 파싱 검증 (FR-SL-02 D6 스펙 FR3, GET/POST/DELETE 공통 DTO)
// ─────────────────────────────────────────────────────────────────────────────
describe('SlackConnectionSchema', () => {
  it('T-SL-C-1: 연결됨 응답(workspaceName/linkedAt 값 존재)을 파싱한다', () => {
    const result = SlackConnectionSchema.parse(SLACK_CONNECTION_FIXTURE_CONNECTED)
    expect(result.connected).toBe(true)
    expect(result.workspaceName).toBe('Acme Corp')
    expect(result.linkedAt).toBe('2026-07-10T00:00:00Z')
  })

  it('T-SL-C-2: 미연결 응답(workspaceName/linkedAt 모두 null)을 파싱한다', () => {
    const result = SlackConnectionSchema.parse(SLACK_CONNECTION_FIXTURE_DISCONNECTED)
    expect(result.connected).toBe(false)
    expect(result.workspaceName).toBeNull()
    expect(result.linkedAt).toBeNull()
  })

  it('T-SL-C-3: connected 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { connected: _connected, ...without } = SLACK_CONNECTION_FIXTURE_CONNECTED
    expect(() => SlackConnectionSchema.parse(without)).toThrow()
  })

  it('T-SL-C-4: workspaceName 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { workspaceName: _workspaceName, ...without } = SLACK_CONNECTION_FIXTURE_CONNECTED
    expect(() => SlackConnectionSchema.parse(without)).toThrow()
  })

  it('T-SL-C-5: linkedAt 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { linkedAt: _linkedAt, ...without } = SLACK_CONNECTION_FIXTURE_CONNECTED
    expect(() => SlackConnectionSchema.parse(without)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-3. getMyConnection — GET /api/v1/slack/me/connection
// ─────────────────────────────────────────────────────────────────────────────
describe('getMyConnection', () => {
  it('T-SL-3-1: /api/v1/slack/me/connection 을 GET으로 호출해 연결됨 응답을 파싱해 반환한다', async () => {
    let capturedMethod: string | undefined
    server.use(
      http.get('/api/v1/slack/me/connection', ({ request }) => {
        capturedMethod = request.method
        return HttpResponse.json(SLACK_CONNECTION_FIXTURE_CONNECTED)
      }),
    )
    const result = await getMyConnection()
    expect(capturedMethod).toBe('GET')
    expect(result.connected).toBe(true)
    expect(result.workspaceName).toBe('Acme Corp')
    expect(result.linkedAt).toBe('2026-07-10T00:00:00Z')
  })

  it('T-SL-3-2: 미연결 응답도 파싱해 반환한다', async () => {
    server.use(
      http.get('/api/v1/slack/me/connection', () => HttpResponse.json(SLACK_CONNECTION_FIXTURE_DISCONNECTED)),
    )
    const result = await getMyConnection()
    expect(result.connected).toBe(false)
    expect(result.workspaceName).toBeNull()
    expect(result.linkedAt).toBeNull()
  })

  it('T-SL-3-3: 401 응답(미인증/PAT) → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/slack/me/connection', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(getMyConnection()).rejects.toBeInstanceOf(ApiError)
    await expect(getMyConnection()).rejects.toMatchObject({ status: 401 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-4. connectSlack — POST /api/v1/slack/me/connection (스펙 FR2, 오류 매핑)
// ─────────────────────────────────────────────────────────────────────────────
describe('connectSlack', () => {
  it('T-SL-4-1: /api/v1/slack/me/connection 을 POST(본문 없음)로 호출해 연결됨 응답을 파싱해 반환한다', async () => {
    let capturedMethod: string | undefined
    server.use(
      http.post('/api/v1/slack/me/connection', ({ request }) => {
        capturedMethod = request.method
        return HttpResponse.json(SLACK_CONNECTION_FIXTURE_CONNECTED)
      }),
    )
    const result = await connectSlack()
    expect(capturedMethod).toBe('POST')
    expect(result.connected).toBe(true)
    expect(result.workspaceName).toBe('Acme Corp')
  })

  it('T-SL-4-2: 이메일 없음(422 EMAIL_UNAVAILABLE) → ApiError(422) throw', async () => {
    server.use(
      http.post('/api/v1/slack/me/connection', () =>
        HttpResponse.json({ code: 'EMAIL_UNAVAILABLE' }, { status: 422 }),
      ),
    )
    await expect(connectSlack()).rejects.toBeInstanceOf(ApiError)
    await expect(connectSlack()).rejects.toMatchObject({ status: 422 })
  })

  it('T-SL-4-3: 워크스페이스 미설치(409 WORKSPACE_NOT_INSTALLED) → ApiError(409) throw', async () => {
    server.use(
      http.post('/api/v1/slack/me/connection', () =>
        HttpResponse.json({ code: 'WORKSPACE_NOT_INSTALLED' }, { status: 409 }),
      ),
    )
    await expect(connectSlack()).rejects.toBeInstanceOf(ApiError)
    await expect(connectSlack()).rejects.toMatchObject({ status: 409 })
  })

  it('T-SL-4-4: 봇 스코프 부족(409 SLACK_SCOPE_MISSING) → ApiError(409) throw', async () => {
    server.use(
      http.post('/api/v1/slack/me/connection', () =>
        HttpResponse.json({ code: 'SLACK_SCOPE_MISSING' }, { status: 409 }),
      ),
    )
    await expect(connectSlack()).rejects.toBeInstanceOf(ApiError)
    await expect(connectSlack()).rejects.toMatchObject({ status: 409 })
  })

  it('T-SL-4-5: Slack에서 이메일 미발견(404 SLACK_USER_NOT_FOUND) → ApiError(404) throw', async () => {
    server.use(
      http.post('/api/v1/slack/me/connection', () =>
        HttpResponse.json({ code: 'SLACK_USER_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(connectSlack()).rejects.toBeInstanceOf(ApiError)
    await expect(connectSlack()).rejects.toMatchObject({ status: 404 })
  })

  it('T-SL-4-6: Slack 일시 오류(503 SLACK_TEMPORARILY_UNAVAILABLE) → ApiError(503) throw', async () => {
    server.use(
      http.post('/api/v1/slack/me/connection', () =>
        HttpResponse.json({ code: 'SLACK_TEMPORARILY_UNAVAILABLE' }, { status: 503 }),
      ),
    )
    await expect(connectSlack()).rejects.toBeInstanceOf(ApiError)
    await expect(connectSlack()).rejects.toMatchObject({ status: 503 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-5. disconnectSlack — DELETE /api/v1/slack/me/connection (스펙 FR4, 멱등)
// ─────────────────────────────────────────────────────────────────────────────
describe('disconnectSlack', () => {
  it('T-SL-5-1: /api/v1/slack/me/connection 을 DELETE로 호출해 미연결 응답을 파싱해 반환한다', async () => {
    let capturedMethod: string | undefined
    server.use(
      http.delete('/api/v1/slack/me/connection', ({ request }) => {
        capturedMethod = request.method
        return HttpResponse.json(SLACK_CONNECTION_FIXTURE_DISCONNECTED)
      }),
    )
    const result = await disconnectSlack()
    expect(capturedMethod).toBe('DELETE')
    expect(result.connected).toBe(false)
    expect(result.workspaceName).toBeNull()
    expect(result.linkedAt).toBeNull()
  })

  it('T-SL-5-2: 401 응답(미인증/PAT) → ApiError(401) throw', async () => {
    server.use(
      http.delete('/api/v1/slack/me/connection', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(disconnectSlack()).rejects.toBeInstanceOf(ApiError)
    await expect(disconnectSlack()).rejects.toMatchObject({ status: 401 })
  })
})
