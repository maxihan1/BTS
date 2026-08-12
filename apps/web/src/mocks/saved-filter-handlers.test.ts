// 저장 필터 MSW 핸들러 동작 검증 테스트 (FR-SR-03 Task-7)
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  savedFilterHandlers,
  resetSavedFilterStore,
  seedSavedFilters,
  seedMembership,
  type SavedFilterSeedItem,
} from './saved-filter-handlers'
import { aliceUser, bobUser } from './auth-fixtures'
import { SAVED_FILTER_ERROR_CODES } from '@/api/saved-filters'
import { server } from '@/test/server'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정
//
// 전역 서버를 쓴다 — 로컬 `setupServer` 를 함께 띄우면 인스턴스 2개가 동시에 listen 해
// **같은 요청이 두 번 디스패치**된다(실측. resolver 2회 · `request:start` 2회 · 고유
// requestId 는 1). 상태를 누적하는 핸들러가 조용히 중복되는 것이 그 증상이다.
// 생명주기(listen · resetHandlers · close)는 `src/test/setup.ts` 가 전담한다.
//
// ★등록은 반드시 `beforeEach` 다. 전역 `setup.ts` 가 매 테스트 뒤 `server.resetHandlers()`
// 를 부르므로 `beforeAll` 에 두면 **첫 테스트 뒤 조용히 사라진다.**
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...savedFilterHandlers)
})

afterEach(() => {
  resetSavedFilterStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처 상수
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_ID = aliceUser.userId
const BOB_ID = bobUser.userId

const ALICE_TOKEN = 'Bearer mock-access-token-alice'
const BOB_TOKEN = 'Bearer mock-access-token-bob'

const FILTER_1_ID = 'f0000000-0000-4000-8000-000000000001'
const FILTER_2_ID = 'f0000000-0000-4000-8000-000000000002'

/** 인증 헤더 생성 헬퍼 */
function authHeaders(token: string): Record<string, string> {
  return { Authorization: token, 'Content-Type': 'application/json' }
}

/** alice 소유 기본 필터 */
const ALICE_PRIVATE_FILTER: SavedFilterSeedItem = {
  id: FILTER_1_ID,
  ownerId: ALICE_ID,
  name: 'Alice Private Filter',
  aqlQuery: 'status = OPEN',
  projectKey: 'PRJ',
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: null,
  version: 0,
  shares: [],
}

/** alice 소유 AUTHENTICATED 공유 필터 */
const ALICE_SHARED_FILTER: SavedFilterSeedItem = {
  id: FILTER_2_ID,
  ownerId: ALICE_ID,
  name: 'Alice Shared Filter',
  aqlQuery: 'priority = HIGH',
  projectKey: 'PRJ',
  createdAt: '2026-01-02T00:00:00.000Z',
  updatedAt: null,
  version: 0,
  shares: [{ shareType: 'AUTHENTICATED', targetId: null }],
}

// ─────────────────────────────────────────────────────────────────────────────
// 응답 타입 (테스트 내부 편의용 — Zod z.infer와 동형)
// ─────────────────────────────────────────────────────────────────────────────

interface ShareEntry {
  shareType: 'PROJECT' | 'GROUP' | 'AUTHENTICATED'
  targetId: string | null
}

interface SavedFilterDto {
  id: string
  ownerId: string
  name: string
  aqlQuery: string
  projectKey: string
  createdAt: string | null
  updatedAt: string | null
  version: number
  isOwner: boolean
  shares: ShareEntry[]
}

interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  errorCode: string
  timestamp: string
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/filters — 소유 필터 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/filters', () => {
  it('미인증 요청 → 401', async () => {
    const res = await fetch('/api/v1/filters')
    expect(res.status).toBe(401)
  })

  it('소유 필터 없으면 빈 배열 반환', async () => {
    const res = await fetch('/api/v1/filters', { headers: authHeaders(ALICE_TOKEN) })
    expect(res.status).toBe(200)
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toEqual([])
  })

  it('소유 필터 목록 반환 — isOwner=true, 전체 shares 포함', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER, ALICE_SHARED_FILTER])

    const res = await fetch('/api/v1/filters', { headers: authHeaders(ALICE_TOKEN) })
    expect(res.status).toBe(200)
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toHaveLength(2)
    for (const item of body) {
      expect(item.isOwner).toBe(true)
      expect(item.ownerId).toBe(ALICE_ID)
    }
  })

  it('다른 사용자(bob) 필터는 alice 목록에 포함되지 않음', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])
    seedSavedFilters(BOB_ID, [
      { ...ALICE_PRIVATE_FILTER, id: FILTER_2_ID, ownerId: BOB_ID, name: 'Bob Filter' },
    ])

    const res = await fetch('/api/v1/filters', { headers: authHeaders(ALICE_TOKEN) })
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toHaveLength(1)
    expect(body[0]?.name).toBe('Alice Private Filter')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/filters/shared — 비소유 가시 필터 목록
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/filters/shared', () => {
  it('미인증 요청 → 401', async () => {
    const res = await fetch('/api/v1/filters/shared?page=0&size=20')
    expect(res.status).toBe(401)
  })

  it('공유 필터 없으면 빈 배열 반환', async () => {
    const res = await fetch('/api/v1/filters/shared?page=0&size=20', {
      headers: authHeaders(BOB_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toEqual([])
  })

  it('AUTHENTICATED 공유 필터 → bob도 열람 가능, isOwner=false', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_SHARED_FILTER])

    const res = await fetch('/api/v1/filters/shared?page=0&size=20', {
      headers: authHeaders(BOB_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toHaveLength(1)
    expect(body[0]?.isOwner).toBe(false)
    expect(body[0]?.name).toBe('Alice Shared Filter')
  })

  it('소유 필터는 shared 목록에 포함되지 않음 — alice가 자신 shared 필터 조회 시 제외', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_SHARED_FILTER])

    const res = await fetch('/api/v1/filters/shared?page=0&size=20', {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toHaveLength(0)
  })

  it('PROJECT 공유 — 멤버 bob은 가시, 비멤버 alice는 불가시', async () => {
    const projectSharedFilter: SavedFilterSeedItem = {
      id: FILTER_1_ID,
      ownerId: ALICE_ID,
      name: 'Project Filter',
      aqlQuery: 'type = BUG',
      projectKey: 'PRJ',
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: null,
      version: 0,
      shares: [{ shareType: 'PROJECT', targetId: 'PROJ-B' }],
    }
    seedSavedFilters(ALICE_ID, [projectSharedFilter])
    seedMembership(BOB_ID, { projectKeys: ['PROJ-B'], groupIds: [] })
    seedMembership(ALICE_ID, { projectKeys: [], groupIds: [] })

    const bobRes = await fetch('/api/v1/filters/shared?page=0&size=20', {
      headers: authHeaders(BOB_TOKEN),
    })
    const bobBody = (await bobRes.json()) as SavedFilterDto[]
    expect(bobBody).toHaveLength(1)

    const aliceRes = await fetch('/api/v1/filters/shared?page=0&size=20', {
      headers: authHeaders(ALICE_TOKEN),
    })
    const aliceBody = (await aliceRes.json()) as SavedFilterDto[]
    // alice는 소유자이므로 shared 목록엔 안 나옴
    expect(aliceBody).toHaveLength(0)
  })

  it('C2: 비소유 응답에 매칭된 share만 노출 — 다른 share는 포함 안 됨', async () => {
    const multiShareFilter: SavedFilterSeedItem = {
      id: FILTER_1_ID,
      ownerId: ALICE_ID,
      name: 'Multi Share Filter',
      aqlQuery: 'status = OPEN',
      projectKey: 'PRJ',
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: null,
      version: 0,
      shares: [
        { shareType: 'AUTHENTICATED', targetId: null },
        { shareType: 'PROJECT', targetId: 'PROJ-X' },
      ],
    }
    seedSavedFilters(ALICE_ID, [multiShareFilter])
    seedMembership(BOB_ID, { projectKeys: [], groupIds: [] })

    const res = await fetch('/api/v1/filters/shared?page=0&size=20', {
      headers: authHeaders(BOB_TOKEN),
    })
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toHaveLength(1)
    // AUTHENTICATED만 매칭 — PROJECT+PROJ-X은 bob 멤버십 없어 제외
    expect(body[0]?.shares).toHaveLength(1)
    expect(body[0]?.shares[0]?.shareType).toBe('AUTHENTICATED')
  })

  it('GROUP 공유 — 그룹 멤버 bob은 가시', async () => {
    const groupSharedFilter: SavedFilterSeedItem = {
      id: FILTER_1_ID,
      ownerId: ALICE_ID,
      name: 'Group Filter',
      aqlQuery: 'assignee = me()',
      projectKey: 'PRJ',
      createdAt: '2026-01-01T00:00:00.000Z',
      updatedAt: null,
      version: 0,
      shares: [{ shareType: 'GROUP', targetId: 'group-reviewers' }],
    }
    seedSavedFilters(ALICE_ID, [groupSharedFilter])
    seedMembership(BOB_ID, { projectKeys: [], groupIds: ['group-reviewers'] })

    const res = await fetch('/api/v1/filters/shared?page=0&size=20', {
      headers: authHeaders(BOB_TOKEN),
    })
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/filters/:id — 단건 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/filters/:id', () => {
  it('미인증 요청 → 401', async () => {
    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`)
    expect(res.status).toBe(401)
  })

  it('소유자 조회 → 200, isOwner=true, 전체 shares', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_SHARED_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_2_ID}`, {
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as SavedFilterDto
    expect(body.isOwner).toBe(true)
    expect(body.name).toBe('Alice Shared Filter')
    expect(body.shares).toHaveLength(1)
  })

  it('비소유 가시 필터 → 200, isOwner=false, 매칭 share만', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_SHARED_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_2_ID}`, {
      headers: authHeaders(BOB_TOKEN),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as SavedFilterDto
    expect(body.isOwner).toBe(false)
    expect(body.shares).toHaveLength(1)
  })

  it('존재하지 않는 필터 → 404', async () => {
    const res = await fetch('/api/v1/filters/00000000-0000-4000-8000-000000000099', {
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.NOT_FOUND)
  })

  it('비가시 비소유 필터 → 404 (존재 은닉)', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      headers: authHeaders(BOB_TOKEN),
    })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/filters — 필터 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/filters', () => {
  it('미인증 요청 → 401', async () => {
    const res = await fetch('/api/v1/filters', { method: 'POST', body: JSON.stringify({}) })
    expect(res.status).toBe(401)
  })

  it('정상 생성 → 201, isOwner=true, version=0', async () => {
    const res = await fetch('/api/v1/filters', {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({
        name: 'New Filter',
        aqlQuery: 'status = OPEN',
        projectKey: 'PRJ',
        shares: [],
      }),
    })
    expect(res.status).toBe(201)
    const body = (await res.json()) as SavedFilterDto
    expect(body.name).toBe('New Filter')
    expect(body.isOwner).toBe(true)
    expect(body.version).toBe(0)
    expect(body.ownerId).toBe(ALICE_ID)
  })

  it('생성 후 GET 목록에 반영 (stateful)', async () => {
    await fetch('/api/v1/filters', {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ name: 'Stateful Filter', aqlQuery: 'type = BUG', projectKey: 'PRJ' }),
    })

    const res = await fetch('/api/v1/filters', { headers: authHeaders(ALICE_TOKEN) })
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toHaveLength(1)
    expect(body[0]?.name).toBe('Stateful Filter')
  })

  it('name 빈 값 → 400 SEARCH_VALIDATION_FAILED', async () => {
    const res = await fetch('/api/v1/filters', {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ name: '', aqlQuery: 'status = OPEN', projectKey: 'PRJ' }),
    })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.VALIDATION_FAILED)
  })

  it('name 공백 → 400 SEARCH_VALIDATION_FAILED', async () => {
    const res = await fetch('/api/v1/filters', {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ name: '   ', aqlQuery: 'status = OPEN', projectKey: 'PRJ' }),
    })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.VALIDATION_FAILED)
  })

  it('이름 중복 → 409 SEARCH_FILTER_NAME_CONFLICT', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch('/api/v1/filters', {
      method: 'POST',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({
        name: ALICE_PRIVATE_FILTER.name,
        aqlQuery: 'type = BUG',
        projectKey: 'PRJ',
      }),
    })
    expect(res.status).toBe(409)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.NAME_CONFLICT)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PUT /api/v1/filters/:id — 필터 수정
// ─────────────────────────────────────────────────────────────────────────────

describe('PUT /api/v1/filters/:id', () => {
  it('미인증 요청 → 401', async () => {
    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'PUT',
      body: JSON.stringify({}),
    })
    expect(res.status).toBe(401)
  })

  it('정상 수정 → 200, version+1, shares replace-all', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'PUT',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({
        name: 'Updated Filter',
        aqlQuery: 'status = CLOSED',
        version: 0,
        shares: [{ shareType: 'AUTHENTICATED', targetId: null }],
      }),
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as SavedFilterDto
    expect(body.name).toBe('Updated Filter')
    expect(body.aqlQuery).toBe('status = CLOSED')
    expect(body.version).toBe(1)
    expect(body.shares).toHaveLength(1)
    expect(body.shares[0]?.shareType).toBe('AUTHENTICATED')
  })

  it('수정 후 GET 재조회 시 변경 반영 (stateful)', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'PUT',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ name: 'Mutated Name', aqlQuery: 'type = TASK', version: 0 }),
    })

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      headers: authHeaders(ALICE_TOKEN),
    })
    const body = (await res.json()) as SavedFilterDto
    expect(body.name).toBe('Mutated Name')
    expect(body.version).toBe(1)
  })

  it('B2: name blank → 400 SEARCH_VALIDATION_FAILED', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'PUT',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ name: ' ', aqlQuery: 'status = OPEN', version: 0 }),
    })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.VALIDATION_FAILED)
  })

  it('B2: aqlQuery blank → 400 SEARCH_VALIDATION_FAILED', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'PUT',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ name: 'Valid Name', aqlQuery: '', version: 0 }),
    })
    expect(res.status).toBe(400)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.VALIDATION_FAILED)
  })

  it('OCC 버전 불일치 → 409 SEARCH_FILTER_CONFLICT', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'PUT',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({ name: 'Updated', aqlQuery: 'status = OPEN', version: 99 }),
    })
    expect(res.status).toBe(409)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.CONFLICT)
  })

  it('이름 중복(다른 필터) → 409 SEARCH_FILTER_NAME_CONFLICT', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER, ALICE_SHARED_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'PUT',
      headers: authHeaders(ALICE_TOKEN),
      body: JSON.stringify({
        name: ALICE_SHARED_FILTER.name,
        aqlQuery: 'status = OPEN',
        version: 0,
      }),
    })
    expect(res.status).toBe(409)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.NAME_CONFLICT)
  })

  it('비소유 필터 수정 → 404 (존재 은닉)', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'PUT',
      headers: authHeaders(BOB_TOKEN),
      body: JSON.stringify({ name: 'Hijack', aqlQuery: 'status = OPEN', version: 0 }),
    })
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/filters/:id — 필터 삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/filters/:id', () => {
  it('미인증 요청 → 401', async () => {
    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, { method: 'DELETE' })
    expect(res.status).toBe(401)
  })

  it('정상 삭제 → 204', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'DELETE',
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(204)
  })

  it('삭제 후 GET 목록에서 제거 (stateful)', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER, ALICE_SHARED_FILTER])

    await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'DELETE',
      headers: authHeaders(ALICE_TOKEN),
    })

    const res = await fetch('/api/v1/filters', { headers: authHeaders(ALICE_TOKEN) })
    const body = (await res.json()) as SavedFilterDto[]
    expect(body).toHaveLength(1)
    expect(body[0]?.id).toBe(FILTER_2_ID)
  })

  it('존재하지 않는 필터 → 404', async () => {
    const res = await fetch('/api/v1/filters/00000000-0000-4000-8000-000000000099', {
      method: 'DELETE',
      headers: authHeaders(ALICE_TOKEN),
    })
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe(SAVED_FILTER_ERROR_CODES.NOT_FOUND)
  })

  it('비소유 필터 삭제 → 404 (존재 은닉)', async () => {
    seedSavedFilters(ALICE_ID, [ALICE_PRIVATE_FILTER])

    const res = await fetch(`/api/v1/filters/${FILTER_1_ID}`, {
      method: 'DELETE',
      headers: authHeaders(BOB_TOKEN),
    })
    expect(res.status).toBe(404)
  })
})
