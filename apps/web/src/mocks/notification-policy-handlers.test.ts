// FR-NT-01 MSW 알림 정책 stateful 핸들러 단위 테스트
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import { notificationPolicyHandlers, resetNotificationPolicyStore } from './notification-policy-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...notificationPolicyHandlers)
})
afterEach(() => {
  resetNotificationPolicyStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const BASE = '/api/v1/notification-policies'

async function fetchCatalog() {
  const res = await fetch(`${BASE}/catalog`)
  return res.json() as Promise<unknown>
}

async function fetchPolicies(headers?: HeadersInit) {
  const res = await fetch(BASE, { headers })
  return res.json() as Promise<unknown>
}

async function postPolicy(body: {
  eventType: string
  recipientRole: string
  channel: string
  projectKey?: string | null
  enabled?: boolean
}) {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return { status: res.status, body: (await res.json()) as unknown }
}

async function patchPolicy(id: string, enabled: boolean) {
  const res = await fetch(`${BASE}/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
  return { status: res.status }
}

async function deletePolicy(id: string) {
  const res = await fetch(`${BASE}/${id}`, { method: 'DELETE' })
  return { status: res.status }
}

// ─────────────────────────────────────────────────────────────────────────────
// (a) GET /catalog — 9 eventTypes, 9 recipientRoles, 5 channels
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /catalog', () => {
  it('eventTypes 9종을 반환한다', async () => {
    const res = (await fetchCatalog()) as { data: { eventTypes: { value: string; publishable: boolean }[] } }
    expect(res.data.eventTypes).toHaveLength(9)
  })

  it('recipientRoles 9종을 반환한다', async () => {
    const res = (await fetchCatalog()) as { data: { recipientRoles: string[] } }
    expect(res.data.recipientRoles).toHaveLength(9)
  })

  it('channels 5종을 반환한다', async () => {
    const res = (await fetchCatalog()) as { data: { channels: string[] } }
    expect(res.data.channels).toHaveLength(5)
  })

  it('publishable 필드가 boolean이다', async () => {
    const res = (await fetchCatalog()) as { data: { eventTypes: { value: string; publishable: boolean }[] } }
    for (const et of res.data.eventTypes) {
      expect(typeof et.publishable).toBe('boolean')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) GET /notification-policies — 시드 목록 반환
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /notification-policies (목록)', () => {
  it('초기 시드 목록을 반환한다', async () => {
    const res = (await fetchPolicies()) as { data: unknown[] }
    expect(Array.isArray(res.data)).toBe(true)
    expect(res.data.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) POST → 201 + 후속 GET 반영 (stateful)
// ─────────────────────────────────────────────────────────────────────────────

describe('POST → 201, 후속 GET 반영', () => {
  it('새 정책을 생성하면 201을 반환하고 목록에 반영된다', async () => {
    // 시드에 없는 조합을 사용한다
    const { status, body } = await postPolicy({
      eventType: 'automation.failed',
      recipientRole: 'MENTIONED',
      channel: 'WEBHOOK',
    })

    expect(status).toBe(201)

    const created = (body as { data: { id: string; eventType: string } }).data
    expect(created.id).toBeTruthy()
    expect(created.eventType).toBe('automation.failed')

    // 후속 GET에서 반영
    const listRes = (await fetchPolicies()) as { data: { id: string }[] }
    const ids = listRes.data.map((p) => p.id)
    expect(ids).toContain(created.id)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) 중복 조합 POST → 409 NOTIF_POLICY_DUPLICATE
// ─────────────────────────────────────────────────────────────────────────────

describe('POST 중복 조합 → 409', () => {
  it('동일 조합(eventType+recipientRole+channel+projectKey)을 두 번 POST하면 409를 반환한다', async () => {
    // 시드에 없는 조합을 생성 후 동일 조합으로 재시도
    await postPolicy({ eventType: 'automation.failed', recipientRole: 'RULE_OWNER', channel: 'TEAMS' })
    const { status, body } = await postPolicy({
      eventType: 'automation.failed',
      recipientRole: 'RULE_OWNER',
      channel: 'TEAMS',
    })

    expect(status).toBe(409)
    const problem = body as { errorCode: string; status: number }
    expect(problem.errorCode).toBe('NOTIF_POLICY_DUPLICATE')
    expect(problem.status).toBe(409)
  })

  it('projectKey가 다르면 중복이 아니다', async () => {
    // 시드에 없는 조합으로 projectKey만 다르게 생성
    await postPolicy({ eventType: 'issue.overdue', recipientRole: 'COMPONENT_LEAD', channel: 'TEAMS', projectKey: null })
    const { status } = await postPolicy({
      eventType: 'issue.overdue',
      recipientRole: 'COMPONENT_LEAD',
      channel: 'TEAMS',
      projectKey: 'ATLAS',
    })
    expect(status).toBe(201)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) PATCH → 204 + 후속 GET enabled 반영
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH → 204 + enabled 반영', () => {
  it('enabled 토글 후 204를 반환하고 후속 GET에서 변경이 반영된다', async () => {
    // 시드에서 첫 번째 정책의 id 확인
    const listBefore = (await fetchPolicies()) as { data: { id: string; enabled: boolean }[] }
    const first = listBefore.data[0]
    if (first === undefined) throw new Error('시드 정책이 없습니다')

    const originalEnabled = first.enabled
    const { status } = await patchPolicy(first.id, !originalEnabled)
    expect(status).toBe(204)

    const listAfter = (await fetchPolicies()) as { data: { id: string; enabled: boolean }[] }
    const updated = listAfter.data.find((p) => p.id === first.id)
    expect(updated?.enabled).toBe(!originalEnabled)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (f) DELETE → 204 + 후속 GET에서 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE → 204 + 목록에서 제거', () => {
  it('삭제 후 204를 반환하고 후속 GET에서 해당 항목이 없다', async () => {
    const listBefore = (await fetchPolicies()) as { data: { id: string }[] }
    const first = listBefore.data[0]
    if (first === undefined) throw new Error('시드 정책이 없습니다')

    const { status } = await deletePolicy(first.id)
    expect(status).toBe(204)

    const listAfter = (await fetchPolicies()) as { data: { id: string }[] }
    const ids = listAfter.data.map((p) => p.id)
    expect(ids).not.toContain(first.id)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (g) X-MSW-Reset-Notification-Policies 헤더 → store 시드 복원
// ─────────────────────────────────────────────────────────────────────────────

describe('X-MSW-Reset-Notification-Policies 헤더 reset', () => {
  it('DELETE 후 reset 헤더 GET을 하면 시드 상태로 복원된다', async () => {
    // 시드 목록 크기 기억
    const initial = (await fetchPolicies()) as { data: unknown[] }
    const initialCount = initial.data.length

    // 첫 번째 항목 삭제
    const first = (initial.data[0] as { id: string })
    await deletePolicy(first.id)

    // 삭제 후 목록 크기 확인
    const afterDelete = (await fetchPolicies()) as { data: unknown[] }
    expect(afterDelete.data.length).toBe(initialCount - 1)

    // X-MSW-Reset-Notification-Policies 헤더로 시드 복원
    const afterReset = (await fetchPolicies({
      'X-MSW-Reset-Notification-Policies': 'true',
    })) as { data: unknown[] }
    expect(afterReset.data.length).toBe(initialCount)
  })
})
