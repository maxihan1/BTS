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
  ChannelMappingSchema,
  listChannelMappings,
  createChannelMapping,
  updateChannelMapping,
  deleteChannelMapping,
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

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — ChannelMapping (백엔드 ChannelMappingResponse 응답 계약 1:1 — FR-SL-06 D6,
// SlackChannelMappingController/SlackChannelMappingResponses.kt 확인 완료. team_id 비노출)
// ─────────────────────────────────────────────────────────────────────────────
const CHANNEL_MAPPING_FIXTURE = {
  id: 'a1000000-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  channelId: 'C0123456789',
  channelName: '#general',
  eventTypes: ['issue.assigned', 'issue.created'],
  createdAt: '2026-07-13T00:00:00Z',
  updatedAt: '2026-07-13T00:00:00Z',
}

const CHANNEL_MAPPING_FIXTURE_NO_NAME = {
  ...CHANNEL_MAPPING_FIXTURE,
  id: 'a1000000-0000-4000-8000-000000000002',
  channelName: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-CM-S. ChannelMappingSchema — Zod 파싱 검증 (FR-SL-06 D6)
// ─────────────────────────────────────────────────────────────────────────────
describe('ChannelMappingSchema', () => {
  it('T-SL-CM-S-1: channelName 값이 존재하는 매핑을 파싱한다', () => {
    const result = ChannelMappingSchema.parse(CHANNEL_MAPPING_FIXTURE)
    expect(result.id).toBe(CHANNEL_MAPPING_FIXTURE.id)
    expect(result.projectKey).toBe('ATLAS')
    expect(result.channelId).toBe('C0123456789')
    expect(result.channelName).toBe('#general')
    expect(result.eventTypes).toEqual(['issue.assigned', 'issue.created'])
    expect(result.createdAt).toBe('2026-07-13T00:00:00Z')
    expect(result.updatedAt).toBe('2026-07-13T00:00:00Z')
  })

  it('T-SL-CM-S-2: channelName이 null인 매핑을 파싱한다', () => {
    const result = ChannelMappingSchema.parse(CHANNEL_MAPPING_FIXTURE_NO_NAME)
    expect(result.channelName).toBeNull()
  })

  it('T-SL-CM-S-3: eventTypes 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { eventTypes: _eventTypes, ...without } = CHANNEL_MAPPING_FIXTURE
    expect(() => ChannelMappingSchema.parse(without)).toThrow()
  })

  it('T-SL-CM-S-4: projectKey 필드 누락 시 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { projectKey: _projectKey, ...without } = CHANNEL_MAPPING_FIXTURE
    expect(() => ChannelMappingSchema.parse(without)).toThrow()
  })

  it('T-SL-CM-S-5: id가 UUID 형식이 아니면 throw한다', () => {
    expect(() => ChannelMappingSchema.parse({ ...CHANNEL_MAPPING_FIXTURE, id: 'not-a-uuid' })).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-CM-1. listChannelMappings — GET /api/v1/slack/channel-mappings?projectKey=
// ─────────────────────────────────────────────────────────────────────────────
describe('listChannelMappings', () => {
  it('T-SL-CM-1-1: projectKey 쿼리로 GET 호출해 매핑 배열을 파싱해 반환한다', async () => {
    let capturedMethod: string | undefined
    let capturedProjectKey: string | null = null
    server.use(
      http.get('/api/v1/slack/channel-mappings', ({ request }) => {
        capturedMethod = request.method
        capturedProjectKey = new URL(request.url).searchParams.get('projectKey')
        return HttpResponse.json([CHANNEL_MAPPING_FIXTURE, CHANNEL_MAPPING_FIXTURE_NO_NAME])
      }),
    )
    const result = await listChannelMappings('ATLAS')
    expect(capturedMethod).toBe('GET')
    expect(capturedProjectKey).toBe('ATLAS')
    expect(result).toHaveLength(2)
    expect(result[0]?.channelId).toBe('C0123456789')
    expect(result[1]?.channelName).toBeNull()
  })

  it('T-SL-CM-1-2: 빈 배열도 파싱해 반환한다', async () => {
    server.use(http.get('/api/v1/slack/channel-mappings', () => HttpResponse.json([])))
    const result = await listChannelMappings('ATLAS')
    expect(result).toEqual([])
  })

  it('T-SL-CM-1-3: projectKey를 쿼리 문자열로 URL-인코딩한다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/slack/channel-mappings', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([])
      }),
    )
    await listChannelMappings('A B')
    expect(capturedUrl).toContain('projectKey=A%20B')
  })

  it('T-SL-CM-1-4: 404 응답(권한없음/미존재) → ApiError(404, code 노출)', async () => {
    server.use(
      http.get('/api/v1/slack/channel-mappings', () =>
        HttpResponse.json(
          { code: 'SLACK_CHANNEL_MAPPING_NOT_FOUND', message: '채널 매핑을 찾을 수 없습니다.' },
          { status: 404 },
        ),
      ),
    )
    await expect(listChannelMappings('ATLAS')).rejects.toBeInstanceOf(ApiError)
    await expect(listChannelMappings('ATLAS')).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-CM-2. createChannelMapping — POST /api/v1/slack/channel-mappings
// ─────────────────────────────────────────────────────────────────────────────
describe('createChannelMapping', () => {
  it('T-SL-CM-2-1: POST 바디를 그대로 전송해 201 응답을 파싱해 반환한다', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    server.use(
      http.post('/api/v1/slack/channel-mappings', async ({ request }) => {
        capturedMethod = request.method
        capturedBody = await request.json()
        return HttpResponse.json(CHANNEL_MAPPING_FIXTURE, { status: 201 })
      }),
    )
    const result = await createChannelMapping({
      projectKey: 'ATLAS',
      channelId: 'C0123456789',
      channelName: '#general',
      eventTypes: ['issue.assigned', 'issue.created'],
    })
    expect(capturedMethod).toBe('POST')
    expect(capturedBody).toEqual({
      projectKey: 'ATLAS',
      channelId: 'C0123456789',
      channelName: '#general',
      eventTypes: ['issue.assigned', 'issue.created'],
    })
    expect(result.id).toBe(CHANNEL_MAPPING_FIXTURE.id)
  })

  it('T-SL-CM-2-2: 중복 매핑(409 SLACK_CHANNEL_MAPPING_CONFLICT) → ApiError(409, code 노출)', async () => {
    server.use(
      http.post('/api/v1/slack/channel-mappings', () =>
        HttpResponse.json(
          { code: 'SLACK_CHANNEL_MAPPING_CONFLICT', message: '이미 동일한 채널 매핑이 존재합니다.' },
          { status: 409 },
        ),
      ),
    )
    try {
      await createChannelMapping({ projectKey: 'ATLAS', channelId: 'C1', eventTypes: ['issue.created'] })
      expect.fail('should have thrown ApiError')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      const apiError = error as ApiError
      expect(apiError.status).toBe(409)
      const body = apiError.body as { code?: string }
      expect(body.code).toBe('SLACK_CHANNEL_MAPPING_CONFLICT')
    }
  })

  it('T-SL-CM-2-3: 워크스페이스 미설치(409 WORKSPACE_NOT_INSTALLED) → ApiError(409) throw', async () => {
    server.use(
      http.post('/api/v1/slack/channel-mappings', () =>
        HttpResponse.json(
          { code: 'WORKSPACE_NOT_INSTALLED', message: 'Slack 워크스페이스가 설치되어 있지 않습니다.' },
          { status: 409 },
        ),
      ),
    )
    await expect(
      createChannelMapping({ projectKey: 'ATLAS', channelId: 'C1', eventTypes: ['issue.created'] }),
    ).rejects.toMatchObject({ status: 409 })
  })

  it('T-SL-CM-2-4: 빈 eventTypes(400) → ApiError(400) throw', async () => {
    server.use(
      http.post('/api/v1/slack/channel-mappings', () =>
        HttpResponse.json(
          { code: 'SLACK_CHANNEL_MAPPING_INVALID', message: '이벤트 유형 값이 올바르지 않습니다.' },
          { status: 400 },
        ),
      ),
    )
    await expect(
      createChannelMapping({ projectKey: 'ATLAS', channelId: 'C1', eventTypes: [] }),
    ).rejects.toMatchObject({ status: 400 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-CM-3. updateChannelMapping — PATCH /api/v1/slack/channel-mappings/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('updateChannelMapping', () => {
  it('T-SL-CM-3-1: id 경로 + PATCH 바디를 전송해 200 응답을 파싱해 반환한다', async () => {
    let capturedMethod: string | undefined
    let capturedBody: unknown
    let capturedId: string | readonly string[] | undefined
    const updated = { ...CHANNEL_MAPPING_FIXTURE, eventTypes: ['issue.commented'] }
    server.use(
      http.patch('/api/v1/slack/channel-mappings/:id', async ({ request, params }) => {
        capturedMethod = request.method
        capturedBody = await request.json()
        capturedId = params['id']
        return HttpResponse.json(updated)
      }),
    )
    const result = await updateChannelMapping(CHANNEL_MAPPING_FIXTURE.id, { eventTypes: ['issue.commented'] })
    expect(capturedMethod).toBe('PATCH')
    expect(capturedId).toBe(CHANNEL_MAPPING_FIXTURE.id)
    expect(capturedBody).toEqual({ eventTypes: ['issue.commented'] })
    expect(result.eventTypes).toEqual(['issue.commented'])
  })

  it('T-SL-CM-3-2: 미존재 id(404) → ApiError(404, code 노출)', async () => {
    server.use(
      http.patch('/api/v1/slack/channel-mappings/:id', () =>
        HttpResponse.json(
          { code: 'SLACK_CHANNEL_MAPPING_NOT_FOUND', message: '채널 매핑을 찾을 수 없습니다.' },
          { status: 404 },
        ),
      ),
    )
    try {
      await updateChannelMapping(CHANNEL_MAPPING_FIXTURE.id, { channelId: 'C2' })
      expect.fail('should have thrown ApiError')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      const apiError = error as ApiError
      expect(apiError.status).toBe(404)
      const body = apiError.body as { code?: string }
      expect(body.code).toBe('SLACK_CHANNEL_MAPPING_NOT_FOUND')
    }
  })

  it('T-SL-CM-3-3: OCC 충돌 없이 채널 매핑 중복(409) → ApiError(409) throw', async () => {
    server.use(
      http.patch('/api/v1/slack/channel-mappings/:id', () =>
        HttpResponse.json(
          { code: 'SLACK_CHANNEL_MAPPING_CONFLICT', message: '이미 동일한 채널 매핑이 존재합니다.' },
          { status: 409 },
        ),
      ),
    )
    await expect(
      updateChannelMapping(CHANNEL_MAPPING_FIXTURE.id, { channelId: 'C2' }),
    ).rejects.toMatchObject({ status: 409 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-CM-4. deleteChannelMapping — DELETE /api/v1/slack/channel-mappings/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('deleteChannelMapping', () => {
  it('T-SL-CM-4-1: id 경로로 DELETE 호출해 204(본문 없음)를 처리한다', async () => {
    let capturedMethod: string | undefined
    let capturedId: string | readonly string[] | undefined
    server.use(
      http.delete('/api/v1/slack/channel-mappings/:id', ({ request, params }) => {
        capturedMethod = request.method
        capturedId = params['id']
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await expect(deleteChannelMapping(CHANNEL_MAPPING_FIXTURE.id)).resolves.toBeUndefined()
    expect(capturedMethod).toBe('DELETE')
    expect(capturedId).toBe(CHANNEL_MAPPING_FIXTURE.id)
  })

  it('T-SL-CM-4-2: 미존재 id(404) → ApiError(404, code 노출)', async () => {
    server.use(
      http.delete('/api/v1/slack/channel-mappings/:id', () =>
        HttpResponse.json(
          { code: 'SLACK_CHANNEL_MAPPING_NOT_FOUND', message: '채널 매핑을 찾을 수 없습니다.' },
          { status: 404 },
        ),
      ),
    )
    await expect(deleteChannelMapping(CHANNEL_MAPPING_FIXTURE.id)).rejects.toBeInstanceOf(ApiError)
    await expect(deleteChannelMapping(CHANNEL_MAPPING_FIXTURE.id)).rejects.toMatchObject({ status: 404 })
  })
})
