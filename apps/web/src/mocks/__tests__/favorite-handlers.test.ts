// 즐겨찾기 MSW 핸들러 stateful 동작 단위 테스트 (FR-UX-02 D6)
import { server } from '@/test/server'
import { afterEach, describe, expect, it } from 'vitest'
import {
  favoriteHandlers,
  resetFavoriteStore,
  seedFavorites,
} from '../favorite-handlers'
import { mockAccessToken } from '../auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 전용 MSW 서버 (handlers.ts 공유 서버와 독립)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...favoriteHandlers)
})
afterEach(() => {
  resetFavoriteStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_TOKEN = mockAccessToken('alice')
const BOB_TOKEN = mockAccessToken('bob')

function authHeaders(token: string): HeadersInit {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/** MSW Node 환경에서 상대경로 fetch — absolute URL은 핸들러 매칭에서 누락됨 */
function favUrl(suffix = ''): string {
  return `/api/v1/favorites${suffix}`
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/favorites
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/favorites', () => {
  it('새 즐겨찾기를 추가하면 201을 반환한다 (신규=status 201로 식별)', async () => {
    const res = await fetch(favUrl(), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ targetType: 'ISSUE', targetId: 'ATLAS-1' }),
    })

    expect(res.status).toBe(201)
    const body = (await res.json()) as { data: { id: string; targetType: string; targetId: string; createdAt: string } }
    expect(body.data.targetType).toBe('ISSUE')
    expect(body.data.targetId).toBe('ATLAS-1')
    // 백엔드 FavoriteResponse는 {id, targetType, targetId, createdAt} 4필드 — created 필드 없음
    expect('created' in body.data).toBe(false)
    expect(body.data.id).toBeTruthy()
    expect(body.data.createdAt).toBeTruthy()
  })

  it('같은 targetType+targetId를 중복 POST하면 200(멱등) — created 필드 없음', async () => {
    // 첫 번째 POST
    await fetch(favUrl(), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ targetType: 'ISSUE', targetId: 'ATLAS-1' }),
    })

    // 두 번째 POST (중복) — 200은 멱등을 의미 (created 필드로 구분하지 않음)
    const res = await fetch(favUrl(), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ targetType: 'ISSUE', targetId: 'ATLAS-1' }),
    })

    expect(res.status).toBe(200)
    const body = (await res.json()) as { data: { id: string; targetType: string } }
    // 백엔드 FavoriteResponse는 {id, targetType, targetId, createdAt} 4필드 — created 필드 없음
    expect('created' in body.data).toBe(false)
    expect(body.data.id).toBeTruthy()
  })

  it('GET 목록에 추가된 항목이 반영된다', async () => {
    await fetch(favUrl(), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ targetType: 'ISSUE', targetId: 'ATLAS-2' }),
    })

    const res = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { data: { items: Array<{ targetId: string }> } }
    expect(body.data.items.some((i) => i.targetId === 'ATLAS-2')).toBe(true)
  })

  it('빈 targetId이면 400 NOTIF_FAV_INVALID를 반환한다', async () => {
    const res = await fetch(favUrl(), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ targetType: 'ISSUE', targetId: '' }),
    })

    expect(res.status).toBe(400)
    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_FAV_INVALID')
  })

  it('알 수 없는 targetType이면 400 NOTIF_FAV_INVALID를 반환한다', async () => {
    const res = await fetch(favUrl(), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ targetType: 'UNKNOWN_TYPE', targetId: 'ATLAS-1' }),
    })

    expect(res.status).toBe(400)
    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_FAV_INVALID')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/favorites
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/favorites', () => {
  it('빈 store에서 빈 items 배열을 반환한다', async () => {
    const res = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })

    expect(res.status).toBe(200)
    const body = (await res.json()) as { data: { items: unknown[] } }
    expect(body.data.items).toEqual([])
  })

  it('seedFavorites로 심은 항목을 반환한다', async () => {
    seedFavorites('00000000-0000-4000-8000-000000000001', [
      { id: 'fav-1', targetType: 'ISSUE', targetId: 'ATLAS-10', createdAt: '2025-01-01T00:00:00Z' },
    ])

    const res = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { data: { items: Array<{ targetId: string }> } }
    expect(body.data.items).toHaveLength(1)
    expect(body.data.items[0]?.targetId).toBe('ATLAS-10')
  })

  it('created_at DESC 정렬 — 최근 추가가 먼저 온다', async () => {
    seedFavorites('00000000-0000-4000-8000-000000000001', [
      { id: 'fav-old', targetType: 'ISSUE', targetId: 'ATLAS-OLD', createdAt: '2025-01-01T00:00:00Z' },
      { id: 'fav-new', targetType: 'ISSUE', targetId: 'ATLAS-NEW', createdAt: '2025-06-01T00:00:00Z' },
    ])

    const res = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { data: { items: Array<{ targetId: string }> } }
    expect(body.data.items[0]?.targetId).toBe('ATLAS-NEW')
    expect(body.data.items[1]?.targetId).toBe('ATLAS-OLD')
  })

  it('?targetType=ISSUE 필터 — DASHBOARD 항목을 제외한다', async () => {
    seedFavorites('00000000-0000-4000-8000-000000000001', [
      { id: 'fav-i', targetType: 'ISSUE', targetId: 'ATLAS-1', createdAt: '2025-01-01T00:00:00Z' },
      { id: 'fav-d', targetType: 'DASHBOARD', targetId: 'dash-1', createdAt: '2025-01-01T00:00:00Z' },
    ])

    const res = await fetch(favUrl('?targetType=ISSUE'), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { data: { items: Array<{ targetType: string }> } }
    expect(body.data.items.every((i) => i.targetType === 'ISSUE')).toBe(true)
    expect(body.data.items).toHaveLength(1)
  })

  it('다른 userId(Bob)의 즐겨찾기는 Alice GET에 누출되지 않는다 (actor 격리)', async () => {
    // Bob 즐겨찾기 직접 시드
    seedFavorites('00000000-0000-4000-8000-000000000002', [
      { id: 'bob-fav', targetType: 'PROJECT', targetId: 'BOB-PROJ', createdAt: '2025-01-01T00:00:00Z' },
    ])

    // Alice로 GET
    const res = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as { data: { items: Array<{ targetId: string }> } }

    // Bob의 항목이 Alice 목록에 없어야 한다
    expect(body.data.items.some((i) => i.targetId === 'BOB-PROJ')).toBe(false)
    // Alice 목록은 비어 있어야 한다 (Alice는 아무것도 안 추가했으므로)
    expect(body.data.items).toHaveLength(0)
  })

  it('Alice와 Bob이 동시에 독립된 즐겨찾기를 가진다', async () => {
    seedFavorites('00000000-0000-4000-8000-000000000001', [
      { id: 'alice-fav', targetType: 'ISSUE', targetId: 'ALICE-ISSUE', createdAt: '2025-01-01T00:00:00Z' },
    ])
    seedFavorites('00000000-0000-4000-8000-000000000002', [
      { id: 'bob-fav', targetType: 'ISSUE', targetId: 'BOB-ISSUE', createdAt: '2025-01-01T00:00:00Z' },
    ])

    const aliceRes = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const bobRes = await fetch(favUrl(), {
      headers: authHeaders(BOB_TOKEN),
    })

    const aliceBody = (await aliceRes.json()) as { data: { items: Array<{ targetId: string }> } }
    const bobBody = (await bobRes.json()) as { data: { items: Array<{ targetId: string }> } }

    expect(aliceBody.data.items[0]?.targetId).toBe('ALICE-ISSUE')
    expect(bobBody.data.items[0]?.targetId).toBe('BOB-ISSUE')
    // 교차 오염 없음
    expect(aliceBody.data.items.some((i) => i.targetId === 'BOB-ISSUE')).toBe(false)
    expect(bobBody.data.items.some((i) => i.targetId === 'ALICE-ISSUE')).toBe(false)
  })

  it('무효 targetType ?targetType=UNKNOWN 이면 400 NOTIF_FAV_INVALID를 반환한다', async () => {
    const res = await fetch(favUrl('?targetType=UNKNOWN_TYPE'), {
      headers: authHeaders(ALICE_TOKEN),
    })

    expect(res.status).toBe(400)
    const body = (await res.json()) as { errorCode: string }
    expect(body.errorCode).toBe('NOTIF_FAV_INVALID')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/favorites?targetType=&targetId=
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/favorites?targetType=&targetId=', () => {
  it('쿼리 파라미터로 DELETE 후 GET에서 해당 항목이 사라진다', async () => {
    // POST로 추가
    await fetch(favUrl(), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ targetType: 'FILTER', targetId: 'filter-99' }),
    })

    // DELETE — 쿼리 파라미터로 (백엔드 계약)
    const delRes = await fetch(favUrl('?targetType=FILTER&targetId=filter-99'), {
      method: 'DELETE',
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(delRes.status).toBe(204)

    // GET으로 사라짐 확인
    const getRes = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const getBody = (await getRes.json()) as { data: { items: Array<{ targetId: string }> } }
    expect(getBody.data.items.some((i) => i.targetId === 'filter-99')).toBe(false)
  })

  it('없는 대상 DELETE는 204를 반환한다 (멱등)', async () => {
    const res = await fetch(favUrl('?targetType=ISSUE&targetId=ATLAS-999'), {
      method: 'DELETE',
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(204)
  })

  it('다른 actor(Bob) 항목은 Alice DELETE로 제거되지 않는다 (actor 격리)', async () => {
    // Bob 즐겨찾기 시드
    seedFavorites('00000000-0000-4000-8000-000000000002', [
      { id: 'bob-fav-1', targetType: 'ISSUE', targetId: 'BOB-ISSUE', createdAt: '2025-01-01T00:00:00Z' },
    ])

    // Alice가 동일 targetType/targetId로 DELETE 시도
    const delRes = await fetch(favUrl('?targetType=ISSUE&targetId=BOB-ISSUE'), {
      method: 'DELETE',
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(delRes.status).toBe(204)

    // Bob의 항목은 여전히 존재해야 한다
    const bobGetRes = await fetch(favUrl(), {
      headers: authHeaders(BOB_TOKEN),
    })
    const bobBody = (await bobGetRes.json()) as { data: { items: Array<{ targetId: string }> } }
    expect(bobBody.data.items.some((i) => i.targetId === 'BOB-ISSUE')).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 전체 플로우 — stateful 통합 시나리오
// ─────────────────────────────────────────────────────────────────────────────

describe('stateful 통합 시나리오', () => {
  it('POST → GET → DELETE → GET 순서로 상태가 정확히 변한다', async () => {
    // 1. 초기 상태 — 비어 있음
    const init = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const initBody = (await init.json()) as { data: { items: unknown[] } }
    expect(initBody.data.items).toHaveLength(0)

    // 2. POST로 추가
    const post = await fetch(favUrl(), {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ targetType: 'DASHBOARD', targetId: 'dash-42' }),
    })
    expect(post.status).toBe(201)

    // 3. GET — 항목이 있음
    const after = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const afterBody = (await after.json()) as { data: { items: Array<{ id: string }> } }
    expect(afterBody.data.items).toHaveLength(1)

    // 4. DELETE — 쿼리 파라미터로 (백엔드 계약)
    await fetch(favUrl('?targetType=DASHBOARD&targetId=dash-42'), {
      method: 'DELETE',
      headers: authHeaders(ALICE_TOKEN),
    })

    // 5. GET — 다시 비어 있음
    const final = await fetch(favUrl(), {
      headers: authHeaders(ALICE_TOKEN),
    })
    const finalBody = (await final.json()) as { data: { items: unknown[] } }
    expect(finalBody.data.items).toHaveLength(0)
  })
})
