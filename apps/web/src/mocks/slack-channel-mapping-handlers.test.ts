// FR-SL-06 D6 Slack 채널 매핑 MSW stateful 핸들러 테스트 — CRUD 반영 + 중복/미존재 에러 + 전역 등록 회귀가드
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { ChannelMappingSchema } from '@/api/slack'
// handlers는 전역 등록 배열(msw-global-handler-registration-gap 회귀 방지) — 이 배열로만 서버를 띄워
// 개별 핸들러 export뿐 아니라 handlers.ts 등록 누락까지 함께 검증한다.
import { handlers } from './handlers'
import {
  resetSlackChannelMappingStore,
  seedSlackChannelMappings,
  SLACK_CHANNEL_MAPPING_WORKSPACE_NOT_INSTALLED_KEY,
} from './slack-channel-mapping-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — 전역 handlers 배열 그대로 사용(등록 회귀가드)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...handlers)
})
afterEach(() => {
  resetSlackChannelMappingStore()
  localStorage.removeItem(SLACK_CHANNEL_MAPPING_WORKSPACE_NOT_INSTALLED_KEY)
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — fetch 래퍼 (drift 차단, automation-rule-handlers.test.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────

const BASE_URL = '/api/v1/slack/channel-mappings'

interface CreateBody {
  projectKey: string
  channelId: string
  channelName?: string
  eventTypes: string[]
}

function buildCreateBody(overrides: Partial<CreateBody> = {}): CreateBody {
  return {
    projectKey: 'ATLAS',
    channelId: 'C0000000001',
    eventTypes: ['issue.created'],
    ...overrides,
  }
}

async function listMappings(projectKey = 'ATLAS'): Promise<{ status: number; body: unknown }> {
  const res = await fetch(`${BASE_URL}?projectKey=${encodeURIComponent(projectKey)}`)
  return { status: res.status, body: (await res.json()) as unknown }
}

async function createMapping(overrides: Partial<CreateBody> = {}): Promise<{ status: number; body: unknown }> {
  const res = await fetch(BASE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(buildCreateBody(overrides)),
  })
  return { status: res.status, body: (await res.json()) as unknown }
}

async function patchMapping(
  id: string,
  body: Record<string, unknown>,
): Promise<{ status: number; body: unknown }> {
  const res = await fetch(`${BASE_URL}/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return { status: res.status, body: (await res.json()) as unknown }
}

async function deleteMapping(id: string): Promise<{ status: number; body: unknown }> {
  const res = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })
  // 204는 본문이 없으므로 파싱을 건너뛴다 — 404 등 에러 응답만 JSON body를 갖는다.
  const body: unknown = res.status === 204 ? undefined : await res.json()
  return { status: res.status, body }
}

// ─────────────────────────────────────────────────────────────────────────────
// (a) GET 목록 — 초기 빈 상태(E3 빈 상태 시나리오 기본값) + 생성 반영
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /slack/channel-mappings (목록)', () => {
  it('시드 없이 시작하면 빈 배열을 반환한다', async () => {
    const { status, body } = await listMappings()
    expect(status).toBe(200)
    expect(body).toEqual([])
  })

  it('다른 프로젝트로 생성한 매핑은 목록에 섞이지 않는다', async () => {
    await createMapping({ projectKey: 'OTHER' })
    const { body } = await listMappings('ATLAS')
    expect(body).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) POST 생성 → 201 + 후속 GET 목록 반영 (msw-mutation-stateful-refetch)
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /slack/channel-mappings → 201, 후속 GET 반영', () => {
  it('생성 성공 시 201을 반환하고 목록에 즉시 반영된다', async () => {
    const { status, body } = await createMapping({
      channelId: 'C0123456789',
      channelName: '#general',
      eventTypes: ['issue.created', 'issue.assigned'],
    })
    expect(status).toBe(201)
    const parsed = ChannelMappingSchema.parse(body)
    expect(parsed.projectKey).toBe('ATLAS')
    expect(parsed.channelId).toBe('C0123456789')
    expect(parsed.channelName).toBe('#general')
    // eventTypes는 정렬되어 응답된다(백엔드 ChannelMappingResponse.from 계약)
    expect(parsed.eventTypes).toEqual(['issue.assigned', 'issue.created'])

    const after = await listMappings('ATLAS')
    const afterMappings = after.body as unknown[]
    expect(afterMappings).toHaveLength(1)
  })

  it('channelName 미지정 시 응답 channelName은 null이다', async () => {
    const { body } = await createMapping({ channelId: 'C0000000002' })
    const parsed = ChannelMappingSchema.parse(body)
    expect(parsed.channelName).toBeNull()
  })

  it('eventTypes가 빈 배열이면 400을 반환한다(EC1)', async () => {
    const { status, body } = await createMapping({ eventTypes: [] })
    expect(status).toBe(400)
    expect(body).toMatchObject({ code: 'SLACK_CHANNEL_MAPPING_INVALID' })
  })

  it('같은 (projectKey, channelId) 매핑이 이미 존재하면 409 SLACK_CHANNEL_MAPPING_CONFLICT를 반환한다', async () => {
    await createMapping({ channelId: 'C0000000003' })
    const { status, body } = await createMapping({ channelId: 'C0000000003' })
    expect(status).toBe(409)
    expect(body).toMatchObject({
      code: 'SLACK_CHANNEL_MAPPING_CONFLICT',
      message: '이미 동일한 채널 매핑이 존재합니다.',
    })
  })

  it('워크스페이스 미설치 플래그가 설정되면 409 WORKSPACE_NOT_INSTALLED를 반환한다', async () => {
    localStorage.setItem(SLACK_CHANNEL_MAPPING_WORKSPACE_NOT_INSTALLED_KEY, 'true')
    const { status, body } = await createMapping()
    expect(status).toBe(409)
    expect(body).toMatchObject({ code: 'WORKSPACE_NOT_INSTALLED' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) PATCH → 200 + 필드 변경 + 후속 GET 반영, 404/400/409 에러 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /slack/channel-mappings/:id', () => {
  it('eventTypes를 수정하면 200을 반환하고 후속 GET에 즉시 반영된다', async () => {
    const created = await createMapping({ channelId: 'C0000000004' })
    const mapping = ChannelMappingSchema.parse(created.body)

    const { status, body } = await patchMapping(mapping.id, { eventTypes: ['issue.commented'] })
    expect(status).toBe(200)
    const updated = ChannelMappingSchema.parse(body)
    expect(updated.eventTypes).toEqual(['issue.commented'])

    const after = await listMappings('ATLAS')
    const afterMappings = after.body as { id: string; eventTypes: string[] }[]
    const afterMapping = afterMappings.find((m) => m.id === mapping.id)
    expect(afterMapping?.eventTypes).toEqual(['issue.commented'])
  })

  it('필드를 지정하지 않으면 기존 값이 유지된다', async () => {
    const created = await createMapping({ channelId: 'C0000000005', channelName: '#eng' })
    const mapping = ChannelMappingSchema.parse(created.body)

    const { body } = await patchMapping(mapping.id, {})
    const updated = ChannelMappingSchema.parse(body)
    expect(updated.channelId).toBe('C0000000005')
    expect(updated.channelName).toBe('#eng')
    expect(updated.eventTypes).toEqual(mapping.eventTypes)
  })

  it('미존재 id는 404 SLACK_CHANNEL_MAPPING_NOT_FOUND를 반환한다', async () => {
    const { status, body } = await patchMapping('11111111-1111-4111-8111-111111111111', {
      channelId: 'C0000000006',
    })
    expect(status).toBe(404)
    expect(body).toMatchObject({ code: 'SLACK_CHANNEL_MAPPING_NOT_FOUND' })
  })

  it('eventTypes를 빈 배열로 수정하면 400을 반환한다', async () => {
    const created = await createMapping({ channelId: 'C0000000007' })
    const mapping = ChannelMappingSchema.parse(created.body)

    const { status, body } = await patchMapping(mapping.id, { eventTypes: [] })
    expect(status).toBe(400)
    expect(body).toMatchObject({ code: 'SLACK_CHANNEL_MAPPING_INVALID' })
  })

  it('channelId를 다른 매핑과 중복되게 수정하면 409 SLACK_CHANNEL_MAPPING_CONFLICT를 반환한다', async () => {
    await createMapping({ channelId: 'C0000000008' })
    const second = await createMapping({ channelId: 'C0000000009' })
    const secondMapping = ChannelMappingSchema.parse(second.body)

    const { status, body } = await patchMapping(secondMapping.id, { channelId: 'C0000000008' })
    expect(status).toBe(409)
    expect(body).toMatchObject({ code: 'SLACK_CHANNEL_MAPPING_CONFLICT' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) DELETE → 204 + 후속 GET 목록 감소, 404 에러 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /slack/channel-mappings/:id', () => {
  it('삭제 후 204를 반환하고 목록에서 제거된다', async () => {
    const created = await createMapping({ channelId: 'C0000000010' })
    const mapping = ChannelMappingSchema.parse(created.body)

    const { status } = await deleteMapping(mapping.id)
    expect(status).toBe(204)

    const after = await listMappings('ATLAS')
    expect(after.body).toEqual([])
  })

  it('미존재 id는 404 SLACK_CHANNEL_MAPPING_NOT_FOUND를 반환한다', async () => {
    const { status, body } = await deleteMapping('11111111-1111-4111-8111-111111111111')
    expect(status).toBe(404)
    expect(body).toMatchObject({ code: 'SLACK_CHANNEL_MAPPING_NOT_FOUND' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) seedSlackChannelMappings — 테스트/E2E 시드 헬퍼 계약
// ─────────────────────────────────────────────────────────────────────────────

describe('seedSlackChannelMappings', () => {
  it('시드한 매핑이 GET 목록에 반영된다', async () => {
    seedSlackChannelMappings([
      {
        id: '22222222-2222-4222-8222-222222222222',
        projectKey: 'ATLAS',
        channelId: 'C0000000099',
        channelName: '#seed',
        eventTypes: ['issue.created'],
        createdAt: '2026-07-13T00:00:00Z',
        updatedAt: '2026-07-13T00:00:00Z',
      },
    ])
    const { body } = await listMappings('ATLAS')
    const mappings = body as { channelId: string }[]
    expect(mappings).toHaveLength(1)
    expect(mappings[0]?.channelId).toBe('C0000000099')
  })
})
