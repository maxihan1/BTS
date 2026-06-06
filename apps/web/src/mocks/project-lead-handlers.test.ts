// 프로젝트 리드 MSW 핸들러 단위 테스트 — stateful GET/PATCH + 에러 분기 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { projectLeadHandlers, resetProjectLeadStore, seedProjectLead } from './project-lead-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 셋업 — 핸들러만 격리 등록
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...projectLeadHandlers)

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
})
afterAll(() => {
  server.close()
})
afterEach(() => {
  server.resetHandlers()
  resetProjectLeadStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function getLead(projectIdOrKey: string): Promise<Response> {
  return fetch(`/api/v1/projects/${projectIdOrKey}/lead`)
}

async function patchLead(
  projectIdOrKey: string,
  leadUserId: string | null,
): Promise<Response> {
  return fetch(`/api/v1/projects/${projectIdOrKey}/lead`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ leadUserId }),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectIdOrKey/lead
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/projects/:projectIdOrKey/lead', () => {
  it('시드된 프로젝트의 현재 leadUserId를 반환한다', async () => {
    const projectId = '00000000-0000-4000-8000-000000000001'
    const leadUserId = '00000000-0000-4000-8000-000000000002'
    seedProjectLead(projectId, leadUserId)

    const res = await getLead(projectId)

    expect(res.status).toBe(200)
    const body = (await res.json()) as { data: { projectId: string; leadUserId: string | null } }
    expect(body.data.projectId).toBe(projectId)
    expect(body.data.leadUserId).toBe(leadUserId)
  })

  it('leadUserId가 null인 프로젝트도 반환한다', async () => {
    const projectId = '00000000-0000-4000-8000-000000000003'
    seedProjectLead(projectId, null)

    const res = await getLead(projectId)

    expect(res.status).toBe(200)
    const body = (await res.json()) as { data: { projectId: string; leadUserId: string | null } }
    expect(body.data.projectId).toBe(projectId)
    expect(body.data.leadUserId).toBeNull()
  })

  it('store에 없는 프로젝트 키 → 404 PROJECT_NOT_FOUND', async () => {
    const res = await getLead('UNKNOWN-PROJECT')

    expect(res.status).toBe(404)
    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('PROJECT_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/projects/:projectIdOrKey/lead
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/projects/:projectIdOrKey/lead', () => {
  it('leadUserId를 갱신하고, 이후 GET이 새 값을 반환한다 (stateful 검증)', async () => {
    const projectId = '00000000-0000-4000-8000-000000000004'
    const oldLead = '00000000-0000-4000-8000-000000000005'
    const newLead = '00000000-0000-4000-8000-000000000006'
    seedProjectLead(projectId, oldLead)

    const patchRes = await patchLead(projectId, newLead)
    expect(patchRes.status).toBe(200)
    const patchBody = (await patchRes.json()) as {
      data: { projectId: string; leadUserId: string | null }
    }
    expect(patchBody.data.leadUserId).toBe(newLead)

    // refetch — store가 영속됐는지 검증
    const getRes = await getLead(projectId)
    expect(getRes.status).toBe(200)
    const getBody = (await getRes.json()) as {
      data: { projectId: string; leadUserId: string | null }
    }
    expect(getBody.data.leadUserId).toBe(newLead)
  })

  it('leadUserId를 null로 갱신(리드 해제)하고 GET이 null을 반환한다', async () => {
    const projectId = '00000000-0000-4000-8000-000000000007'
    seedProjectLead(projectId, '00000000-0000-4000-8000-000000000008')

    const patchRes = await patchLead(projectId, null)
    expect(patchRes.status).toBe(200)

    const getRes = await getLead(projectId)
    const getBody = (await getRes.json()) as {
      data: { projectId: string; leadUserId: string | null }
    }
    expect(getBody.data.leadUserId).toBeNull()
  })

  it('store에 없는 프로젝트 → 404 PROJECT_NOT_FOUND', async () => {
    const res = await patchLead('GHOST-PROJECT', '00000000-0000-4000-8000-000000000009')

    expect(res.status).toBe(404)
    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('PROJECT_NOT_FOUND')
  })

  it('localStorage 422 플래그 세팅 시 PROJECT_LEAD_NOT_FOUND 반환', async () => {
    const projectId = '00000000-0000-4000-8000-000000000010'
    seedProjectLead(projectId, null)

    // jsdom 환경에서 localStorage에 플래그 삽입
    globalThis.localStorage.setItem('msw-project-lead-422', 'true')

    const res = await patchLead(projectId, '00000000-0000-4000-8000-000000000011')
    expect(res.status).toBe(422)
    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('PROJECT_LEAD_NOT_FOUND')

    globalThis.localStorage.removeItem('msw-project-lead-422')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// X-MSW-Seed-ProjectLead 헤더 시드
// ─────────────────────────────────────────────────────────────────────────────

describe('X-MSW-Seed-ProjectLead 헤더 시드', () => {
  it('GET 요청에 헤더가 있으면 store를 초기화하고 시드 값을 반환한다', async () => {
    const projectId = '00000000-0000-4000-8000-000000000012'
    const leadUserId = '00000000-0000-4000-8000-000000000013'

    const seedPayload = encodeURIComponent(JSON.stringify({ projectId, leadUserId }))
    const res = await fetch(`/api/v1/projects/${projectId}/lead`, {
      headers: { 'X-MSW-Seed-ProjectLead': seedPayload },
    })

    expect(res.status).toBe(200)
    const body = (await res.json()) as { data: { projectId: string; leadUserId: string | null } }
    expect(body.data.leadUserId).toBe(leadUserId)

    // 이후 일반 GET도 시드 값 유지
    const getRes = await getLead(projectId)
    const getBody = (await getRes.json()) as {
      data: { projectId: string; leadUserId: string | null }
    }
    expect(getBody.data.leadUserId).toBe(leadUserId)
  })
})
