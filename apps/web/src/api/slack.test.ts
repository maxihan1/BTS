// Slack 연결 상태 조회 + authorize URL 발급 API client 단위 테스트 — MSW + Zod 파싱 검증 (FR-SL-01 D6)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { SlackInstallationSchema, SlackInstallUrlSchema, getSlackInstallation, getSlackInstallUrl } from './slack'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — SlackInstallation / SlackInstallUrl (백엔드 view-layer 응답 계약 1:1 — 스펙 FR1/FR2)
// ─────────────────────────────────────────────────────────────────────────────
const SLACK_INSTALLATION_FIXTURE_CONNECTED = {
  connected: true,
  teamId: 'T1',
  teamName: 'Acme',
  installedAt: '2026-07-08T00:00:00Z',
}

const SLACK_INSTALLATION_FIXTURE_DISCONNECTED = {
  connected: false,
  teamId: null,
  teamName: null,
  installedAt: null,
}

const SLACK_INSTALL_URL_FIXTURE = {
  url: 'https://slack.com/oauth/v2/authorize?client_id=xxx',
}

// ─────────────────────────────────────────────────────────────────────────────
// T-SL-S. SlackInstallationSchema / SlackInstallUrlSchema — Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('SlackInstallationSchema', () => {
  it('T-SL-S-1: 연결됨 응답(teamId/teamName/installedAt 값 존재)을 파싱한다', () => {
    const result = SlackInstallationSchema.parse(SLACK_INSTALLATION_FIXTURE_CONNECTED)
    expect(result.connected).toBe(true)
    expect(result.teamId).toBe('T1')
    expect(result.teamName).toBe('Acme')
    expect(result.installedAt).toBe('2026-07-08T00:00:00Z')
  })

  it('T-SL-S-2: 미연결 응답(teamId/teamName/installedAt 모두 null)을 파싱한다', () => {
    const result = SlackInstallationSchema.parse(SLACK_INSTALLATION_FIXTURE_DISCONNECTED)
    expect(result.connected).toBe(false)
    expect(result.teamId).toBeNull()
    expect(result.teamName).toBeNull()
    expect(result.installedAt).toBeNull()
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
