// 대시보드 MSW 핸들러 단위 테스트 — stateful CRUD + 권한·OCC 검증 (FR-DB-01 D6)

import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { dashboardHandlers } from './dashboard-handlers'
import {
  ALICE_OWNER_ID,
  BOB_OTHER_ID,
  DEFAULT_DASHBOARD,
  OTHER_DASHBOARD,
  SHARE_DEMO_DASHBOARD,
  dashboardStore,
  resetDashboardStore,
  resetShareTokenStore,
  seedDashboard,
  shareTokenStore,
} from './dashboard-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...dashboardHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

/**
 * 각 테스트 전에 store를 리셋하고 기본 픽스처 2개를 시드한다.
 * - DEFAULT_DASHBOARD: alice 소유 (ALICE_OWNER_ID)
 * - OTHER_DASHBOARD: bob 소유 (BOB_OTHER_ID) — 비소유자 읽기 전용 검증용
 */
beforeEach(() => {
  resetDashboardStore()
  seedDashboard(DEFAULT_DASHBOARD)
  seedDashboard(OTHER_DASHBOARD)
})

afterEach(() => server.resetHandlers())

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 인증된 사용자 ID를 X-Actor-Id 헤더로 전달한다.
 * MSW 핸들러는 이 헤더를 읽어 actor를 식별한다.
 */
function actorHeaders(actorId: string): Record<string, string> {
  return { 'Content-Type': 'application/json', 'X-Actor-Id': actorId }
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — GET 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/dashboards', () => {
  it('S1-1: alice가 목록을 조회하면 접근 가능한 대시보드를 반환한다', async () => {
    const res = await fetch('/api/v1/dashboards', {
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const data = body.data as {
      items: unknown[]
      total: number
      limit: number
      offset: number
    }

    expect(data.items.length).toBeGreaterThanOrEqual(1)
    expect(data.total).toBeGreaterThanOrEqual(1)
    expect(typeof data.limit).toBe('number')
    expect(typeof data.offset).toBe('number')
  })

  it('S1-2: limit/offset 파라미터가 응답에 반영된다', async () => {
    const res = await fetch('/api/v1/dashboards?limit=10&offset=0', {
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const data = body.data as { limit: number; offset: number }

    expect(data.limit).toBe(10)
    expect(data.offset).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — GET 단건
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/dashboards/:id', () => {
  it('S2-1: 존재하는 대시보드를 조회하면 200과 데이터를 반환한다', async () => {
    const res = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: unknown }
    const data = body.data as {
      id: string
      ownerId: string
      name: string
      visibility: string
      version: number
    }

    expect(data.id).toBe(DEFAULT_DASHBOARD.id)
    expect(data.ownerId).toBe(ALICE_OWNER_ID)
    expect(data.name).toBe(DEFAULT_DASHBOARD.name)
    expect(typeof data.version).toBe('number')
  })

  it('S2-2: 미존재 ID 조회 시 404 NOTIF_DASHBOARD_NOT_FOUND 반환', async () => {
    const res = await fetch('/api/v1/dashboards/00000000-0000-4000-8000-000000000999', {
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(res.status).toBe(404)

    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_DASHBOARD_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — POST 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/dashboards', () => {
  it('S3-1: 올바른 요청으로 대시보드를 생성하면 201과 생성된 리소스를 반환한다', async () => {
    const res = await fetch('/api/v1/dashboards', {
      method: 'POST',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({
        name: '새 대시보드',
        visibility: 'PRIVATE',
        description: '테스트 대시보드입니다',
      }),
    })

    expect(res.status).toBe(201)

    const body = (await res.json()) as { data: unknown }
    const data = body.data as {
      id: string
      ownerId: string
      name: string
      visibility: string
      version: number
    }

    expect(data.name).toBe('새 대시보드')
    expect(data.ownerId).toBe(ALICE_OWNER_ID)
    expect(data.visibility).toBe('PRIVATE')
    expect(data.version).toBe(0)
  })

  it('S3-2: 생성된 대시보드는 이후 GET으로 조회 가능하다 (stateful)', async () => {
    const createRes = await fetch('/api/v1/dashboards', {
      method: 'POST',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: 'Stateful Test', visibility: 'PRIVATE' }),
    })

    const createBody = (await createRes.json()) as { data: { id: string } }
    const { id } = createBody.data

    const getRes = await fetch(`/api/v1/dashboards/${id}`, {
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(getRes.status).toBe(200)

    const getBody = (await getRes.json()) as { data: { name: string } }
    expect(getBody.data.name).toBe('Stateful Test')
  })

  it('S3-3: store에 대시보드가 실제로 추가된다', async () => {
    const sizeBefore = dashboardStore.size

    await fetch('/api/v1/dashboards', {
      method: 'POST',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: 'Store Test', visibility: 'ORG' }),
    })

    expect(dashboardStore.size).toBe(sizeBefore + 1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — PATCH 수정
// ─────────────────────────────────────────────────────────────────────────────

describe('PATCH /api/v1/dashboards/:id', () => {
  it('S4-1: 소유자가 올바른 version으로 PATCH하면 200과 version+1을 반환한다', async () => {
    const res = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'PATCH',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: '수정된 이름', version: DEFAULT_DASHBOARD.version }),
    })

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: { name: string; version: number } }
    expect(body.data.name).toBe('수정된 이름')
    expect(body.data.version).toBe(DEFAULT_DASHBOARD.version + 1)
  })

  it('S4-2: PATCH 후 GET으로 변경사항이 반영된다 (stateful)', async () => {
    await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'PATCH',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: '업데이트됨', version: DEFAULT_DASHBOARD.version }),
    })

    const getRes = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      headers: actorHeaders(ALICE_OWNER_ID),
    })
    const getBody = (await getRes.json()) as { data: { name: string; version: number } }

    expect(getBody.data.name).toBe('업데이트됨')
    expect(getBody.data.version).toBe(DEFAULT_DASHBOARD.version + 1)
  })

  it('S4-3: version 불일치 시 409 NOTIF_DASHBOARD_CONFLICT 반환', async () => {
    const res = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'PATCH',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: '충돌 테스트', version: DEFAULT_DASHBOARD.version + 99 }),
    })

    expect(res.status).toBe(409)

    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_DASHBOARD_CONFLICT')
  })

  it('S4-4: 비소유자가 PATCH 시 403 NOTIF_DASHBOARD_FORBIDDEN 반환', async () => {
    const res = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'PATCH',
      headers: actorHeaders(BOB_OTHER_ID),
      body: JSON.stringify({ name: '해킹 시도', version: DEFAULT_DASHBOARD.version }),
    })

    expect(res.status).toBe(403)

    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_DASHBOARD_FORBIDDEN')
  })

  it('S4-5: 미존재 ID에 PATCH 시 404 NOTIF_DASHBOARD_NOT_FOUND 반환', async () => {
    const res = await fetch('/api/v1/dashboards/00000000-0000-4000-8000-000000000999', {
      method: 'PATCH',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: '없는 대시보드', version: 0 }),
    })

    expect(res.status).toBe(404)

    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_DASHBOARD_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — DELETE 삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/dashboards/:id', () => {
  it('S5-1: 소유자가 DELETE하면 204를 반환한다', async () => {
    const res = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'DELETE',
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(res.status).toBe(204)
  })

  it('S5-2: DELETE 후 GET으로 조회하면 404가 반환된다 (소프트 삭제 — stateful)', async () => {
    await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'DELETE',
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    const getRes = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(getRes.status).toBe(404)
  })

  it('S5-3: 비소유자가 DELETE 시 403 NOTIF_DASHBOARD_FORBIDDEN 반환', async () => {
    const res = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'DELETE',
      headers: actorHeaders(BOB_OTHER_ID),
    })

    expect(res.status).toBe(403)

    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_DASHBOARD_FORBIDDEN')
  })

  it('S5-4: 미존재 ID에 DELETE 시 404 NOTIF_DASHBOARD_NOT_FOUND 반환', async () => {
    const res = await fetch('/api/v1/dashboards/00000000-0000-4000-8000-000000000999', {
      method: 'DELETE',
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(res.status).toBe(404)

    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_DASHBOARD_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — OCC 자연 충돌 시나리오
// ─────────────────────────────────────────────────────────────────────────────

describe('OCC — version 자연 충돌', () => {
  it('S6-1: 첫 번째 PATCH 성공 후 동일 version으로 두 번째 PATCH는 409가 된다', async () => {
    // 첫 번째 성공
    const firstRes = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'PATCH',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: '첫 수정', version: DEFAULT_DASHBOARD.version }),
    })
    expect(firstRes.status).toBe(200)

    // 두 번째 — 같은 version 사용 → 409
    const secondRes = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'PATCH',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: '두 번째 수정', version: DEFAULT_DASHBOARD.version }),
    })
    expect(secondRes.status).toBe(409)

    const body = (await secondRes.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_DASHBOARD_CONFLICT')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7 — 비소유 대시보드 읽기 전용 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('비소유 대시보드 — 읽기 전용', () => {
  it('S7-1: OTHER_DASHBOARD(bob 소유)는 alice도 GET으로 조회할 수 있다 (ORG 공개)', async () => {
    const res = await fetch(`/api/v1/dashboards/${OTHER_DASHBOARD.id}`, {
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: { ownerId: string } }
    expect(body.data.ownerId).toBe(BOB_OTHER_ID)
  })

  it('S7-2: alice가 bob 소유 대시보드를 PATCH하면 403이 된다', async () => {
    const res = await fetch(`/api/v1/dashboards/${OTHER_DASHBOARD.id}`, {
      method: 'PATCH',
      headers: actorHeaders(ALICE_OWNER_ID),
      body: JSON.stringify({ name: '타인 대시보드 수정 시도', version: OTHER_DASHBOARD.version }),
    })

    expect(res.status).toBe(403)
  })

  it('S7-3: alice가 bob 소유 대시보드를 DELETE하면 403이 된다', async () => {
    const res = await fetch(`/api/v1/dashboards/${OTHER_DASHBOARD.id}`, {
      method: 'DELETE',
      headers: actorHeaders(ALICE_OWNER_ID),
    })

    expect(res.status).toBe(403)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8 — X-Actor-Id 헤더 없이 호출하는 통합 회귀 테스트
//
// 실제 프론트 api(src/api/dashboards.ts)는 X-Actor-Id 헤더를 보내지 않는다.
// JWT 쿠키 인증이므로 MSW 핸들러는 헤더가 없을 때 "현재 로그인 사용자 = alice"를 가정해야 한다.
// 헤더 미존재 시 폴백이 ''이면:
//   - list에서 alice 소유 PRIVATE 대시보드가 사라짐 (ownerId === '' 거짓)
//   - PATCH/DELETE에서 alice도 항상 403 (ownerId !== '' 참)
// ─────────────────────────────────────────────────────────────────────────────

describe('S8 — X-Actor-Id 헤더 없는 통합 호출 회귀', () => {
  it('S8-1: 헤더 없는 GET 목록 호출 시 alice 소유 PRIVATE 대시보드가 포함된다', async () => {
    // 헤더를 전혀 보내지 않음 — 실제 api 클라이언트가 보내는 패턴
    const res = await fetch('/api/v1/dashboards')

    expect(res.status).toBe(200)

    const body = (await res.json()) as {
      data: { items: Array<{ id: string; ownerId: string }> }
    }
    const ids = body.data.items.map((d) => d.id)
    expect(ids).toContain(DEFAULT_DASHBOARD.id)
  })

  it('S8-2: 헤더 없는 PATCH 호출 시 alice 소유 대시보드가 200을 반환한다 (403 아님)', async () => {
    const res = await fetch(`/api/v1/dashboards/${DEFAULT_DASHBOARD.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '헤더 없이 수정', version: DEFAULT_DASHBOARD.version }),
    })

    expect(res.status).toBe(200)

    const body = (await res.json()) as { data: { name: string } }
    expect(body.data.name).toBe('헤더 없이 수정')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2 — 공유 토큰 발급/목록/취소 + 익명 공개 조회 (FR-DB-03 D6/D7 Task 2)
//
// 실제 백엔드 계약 (DashboardShareController/PublicDashboardController grep 대조).
//   - POST   /api/v1/dashboards/{id}/shares            → 201 { data: { id, token, createdAt, expiresAt? } }
//   - GET    /api/v1/dashboards/{id}/shares            → 200 { data: { items: [{ id, createdAt, expiresAt? }] } }
//   - DELETE /api/v1/dashboards/{id}/shares/{shareId}  → 204
//   - GET    /api/v1/public/dashboards/{token}          → 200 { data: { name, description, layout } } / 404
// ─────────────────────────────────────────────────────────────────────────────

describe('공유 토큰 — POST/GET/DELETE + 익명 공개 조회', () => {
  beforeEach(() => {
    resetShareTokenStore()
    seedDashboard(SHARE_DEMO_DASHBOARD)
  })

  // ── POST /api/v1/dashboards/:id/shares ──────────────────────────────────

  describe('POST /api/v1/dashboards/:id/shares', () => {
    it('T2-1: 소유자가 발급하면 201과 원문 token을 포함한 응답을 반환한다', async () => {
      const res = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })

      expect(res.status).toBe(201)

      const body = (await res.json()) as { data: unknown }
      const data = body.data as { id: string; token: string; createdAt: string }

      expect(typeof data.id).toBe('string')
      expect(typeof data.token).toBe('string')
      expect(data.token.length).toBeGreaterThan(0)
      expect(typeof data.createdAt).toBe('string')
    })

    it('T2-2: 발급된 토큰은 store에 추가된다 (stateful)', async () => {
      const sizeBefore = shareTokenStore.size

      await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })

      expect(shareTokenStore.size).toBe(sizeBefore + 1)
    })

    it('T2-3: expiresAt 생략 시 응답에 expiresAt 키가 없다 (@JsonInclude(NON_NULL) 재현)', async () => {
      const res = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })

      const body = (await res.json()) as { data: Record<string, unknown> }
      expect('expiresAt' in body.data).toBe(false)
    })

    it('T2-4: 비소유자가 발급 시도하면 403 NOTIF_DASHBOARD_FORBIDDEN을 반환한다', async () => {
      const res = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(BOB_OTHER_ID),
        body: JSON.stringify({}),
      })

      expect(res.status).toBe(403)

      const body = (await res.json()) as { errorCode: string }
      expect(body.errorCode).toBe('NOTIF_DASHBOARD_FORBIDDEN')
    })
  })

  // ── GET /api/v1/dashboards/:id/shares ───────────────────────────────────

  describe('GET /api/v1/dashboards/:id/shares', () => {
    it('T2-5: 발급된 토큰이 목록에 추가되고 token 필드를 포함하지 않는다 (EC-9 회귀가드)', async () => {
      const issueRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })
      const issueBody = (await issueRes.json()) as { data: { id: string } }

      const listRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        headers: actorHeaders(ALICE_OWNER_ID),
      })

      expect(listRes.status).toBe(200)

      const listBody = (await listRes.json()) as { data: { items: Array<Record<string, unknown>> } }
      const items = listBody.data.items

      expect(items.some((item) => item['id'] === issueBody.data.id)).toBe(true)
      for (const item of items) {
        expect('token' in item).toBe(false)
        expect('lastAccessedAt' in item).toBe(false)
      }
    })

    it('T2-6: 비소유자가 목록 조회 시도하면 403 NOTIF_DASHBOARD_FORBIDDEN을 반환한다', async () => {
      const res = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        headers: actorHeaders(BOB_OTHER_ID),
      })

      expect(res.status).toBe(403)
    })
  })

  // ── DELETE /api/v1/dashboards/:id/shares/:shareId ───────────────────────

  describe('DELETE /api/v1/dashboards/:id/shares/:shareId', () => {
    it('T2-7: 소유자가 취소하면 204를 반환하고 store에서 제거된다', async () => {
      const issueRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })
      const { id: shareId } = (await issueRes.json()) as { data: { id: string } }

      const deleteRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares/${shareId}`, {
        method: 'DELETE',
        headers: actorHeaders(ALICE_OWNER_ID),
      })

      expect(deleteRes.status).toBe(204)
      expect(shareTokenStore.has(shareId)).toBe(false)

      const listRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        headers: actorHeaders(ALICE_OWNER_ID),
      })
      const listBody = (await listRes.json()) as { data: { items: Array<{ id: string }> } }
      expect(listBody.data.items.some((item) => item.id === shareId)).toBe(false)
    })

    it('T2-8: 미존재 shareId 취소 시 404 NOTIF_DASHBOARD_SHARE_NOT_FOUND를 반환한다', async () => {
      const res = await fetch(
        `/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares/00000000-0000-4000-8000-000000000999`,
        { method: 'DELETE', headers: actorHeaders(ALICE_OWNER_ID) },
      )

      expect(res.status).toBe(404)

      const body = (await res.json()) as { errorCode: string }
      expect(body.errorCode).toBe('NOTIF_DASHBOARD_SHARE_NOT_FOUND')
    })
  })

  // ── GET /api/v1/public/dashboards/:token ────────────────────────────────

  describe('GET /api/v1/public/dashboards/:token', () => {
    it('T2-9: 유효 토큰이면 200과 정화된 layout을 반환한다 — 정적 가젯은 config를 유지한다', async () => {
      const issueRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })
      const { token } = (await issueRes.json()) as { data: { token: string } }

      const res = await fetch(`/api/v1/public/dashboards/${token}`)

      expect(res.status).toBe(200)

      const body = (await res.json()) as { data: { name: string; description: string | null; layout: string } }
      expect(body.data.name).toBe(SHARE_DEMO_DASHBOARD.name)
      expect(typeof body.data.layout).toBe('string')

      const tiles = JSON.parse(body.data.layout) as Array<Record<string, unknown>>
      const textTile = tiles.find((t) => t['gadgetType'] === 'text_widget')
      const linkTile = tiles.find((t) => t['gadgetType'] === 'link_list')

      expect(textTile).toBeDefined()
      expect(linkTile).toBeDefined()
      expect((textTile as Record<string, unknown>)['config']).toBeDefined()
      expect((linkTile as Record<string, unknown>)['config']).toBeDefined()
    })

    it('T2-10: 데이터 가젯은 config가 제거되고 requiresAuth 플레이스홀더로 치환된다', async () => {
      const issueRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })
      const { token } = (await issueRes.json()) as { data: { token: string } }

      const res = await fetch(`/api/v1/public/dashboards/${token}`)
      const body = (await res.json()) as { data: { layout: string } }

      const tiles = JSON.parse(body.data.layout) as Array<Record<string, unknown>>
      const countTile = tiles.find((t) => t['gadgetType'] === 'issue_count') as Record<string, unknown>

      expect(countTile).toBeDefined()
      expect(countTile['config']).toBeUndefined()
      expect(countTile['requiresAuth']).toBe(true)
      expect(typeof countTile['i']).toBe('string')
      expect(typeof countTile['x']).toBe('number')
      expect(typeof countTile['y']).toBe('number')
      expect(typeof countTile['w']).toBe('number')
      expect(typeof countTile['h']).toBe('number')
    })

    it('T2-11: 무효 토큰이면 404 NOTIF_DASHBOARD_NOT_FOUND를 반환한다', async () => {
      const res = await fetch('/api/v1/public/dashboards/this-token-does-not-exist')

      expect(res.status).toBe(404)

      const body = (await res.json()) as { errorCode: string }
      expect(body.errorCode).toBe('NOTIF_DASHBOARD_NOT_FOUND')
    })

    it('T2-12: 취소된 토큰으로 조회하면 404가 된다 (stateful — DELETE 후 즉시 반영)', async () => {
      const issueRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })
      const { id: shareId, token } = (await issueRes.json()) as { data: { id: string; token: string } }

      await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares/${shareId}`, {
        method: 'DELETE',
        headers: actorHeaders(ALICE_OWNER_ID),
      })

      const res = await fetch(`/api/v1/public/dashboards/${token}`)
      expect(res.status).toBe(404)
    })

    it('T2-13: description이 null이어도 응답 키는 존재한다 (PublicDashboardResponse는 NON_NULL 미적용)', async () => {
      const issueRes = await fetch(`/api/v1/dashboards/${SHARE_DEMO_DASHBOARD.id}/shares`, {
        method: 'POST',
        headers: actorHeaders(ALICE_OWNER_ID),
        body: JSON.stringify({}),
      })
      const { token } = (await issueRes.json()) as { data: { token: string } }

      const res = await fetch(`/api/v1/public/dashboards/${token}`)
      const body = (await res.json()) as { data: Record<string, unknown> }

      expect('description' in body.data).toBe(true)
      expect(body.data['description']).toBeNull()
    })
  })
})
