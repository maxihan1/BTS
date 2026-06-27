// 저장 필터 MSW 핸들러 — stateful CRUD + 가시성 판정 (FR-SR-03 D6 Task-7)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

/** 공유 대상 — backend ShareDto shareType enum 미러 */
type ShareType = 'PROJECT' | 'GROUP' | 'AUTHENTICATED'

/** 공유 항목 */
interface ShareEntry {
  shareType: ShareType
  targetId: string | null
}

/**
 * store 내부 저장 형태 — isOwner 파생 전 raw 데이터.
 * isOwner는 조회 시 viewerId와 ownerId 비교로 도출한다.
 */
interface SavedFilterStoreItem {
  id: string
  ownerId: string
  name: string
  aqlQuery: string
  projectKey: string
  createdAt: string | null
  updatedAt: string | null
  version: number
  shares: ReadonlyArray<ShareEntry>
}

/**
 * 멤버십 시드 항목 — 가시성 판정에 쓰이는 사용자별 프로젝트/그룹 소속.
 * PROJECT 또는 GROUP 공유 필터의 접근 여부를 시뮬레이션한다.
 */
interface MembershipEntry {
  projectKeys: ReadonlySet<string>
  groupIds: ReadonlySet<string>
}

// ─────────────────────────────────────────────────────────────────────────────
// 저장소 — userId(ownerId) → filters
// ─────────────────────────────────────────────────────────────────────────────

/** 필터 store — userId별 소유 필터 배열 */
let savedFilterStore: Map<string, SavedFilterStoreItem[]> = new Map()

/** 멤버십 store — userId별 프로젝트/그룹 소속 */
let membershipStore: Map<string, MembershipEntry> = new Map()

// ─────────────────────────────────────────────────────────────────────────────
// 외부 시드/리셋 API — 테스트 beforeEach / afterEach 용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필터·멤버십 store를 초기 상태로 리셋한다.
 * 각 테스트 afterEach에서 호출해 state leak을 방지한다.
 */
export function resetSavedFilterStore(): void {
  savedFilterStore = new Map()
  membershipStore = new Map()
}

/**
 * 시드 입력 타입 — isOwner 파생 전 raw 형태.
 * Vitest 단위 테스트 및 E2E 사전 시드에서 사용한다.
 */
export interface SavedFilterSeedItem {
  id: string
  ownerId: string
  name: string
  aqlQuery: string
  projectKey: string
  createdAt: string | null
  updatedAt: string | null
  version: number
  shares: ReadonlyArray<{ shareType: string; targetId: string | null }>
}

/**
 * 특정 userId의 소유 필터를 시드한다.
 * 동일 userId 재호출 시 덮어씀.
 *
 * @param userId 필터를 소유할 사용자 UUID
 * @param items 초기 필터 배열
 */
export function seedSavedFilters(userId: string, items: SavedFilterSeedItem[]): void {
  const storeItems: SavedFilterStoreItem[] = items.map((item) => ({
    id: item.id,
    ownerId: item.ownerId,
    name: item.name,
    aqlQuery: item.aqlQuery,
    projectKey: item.projectKey,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
    version: item.version,
    shares: item.shares.flatMap((s): ShareEntry[] => {
      const shareType = s.shareType
      if (
        shareType === 'PROJECT' ||
        shareType === 'GROUP' ||
        shareType === 'AUTHENTICATED'
      ) {
        return [{ shareType, targetId: s.targetId }]
      }
      return []
    }),
  }))
  savedFilterStore.set(userId, storeItems)
}

/**
 * 사용자 멤버십을 시드한다 — PROJECT/GROUP 공유 필터 가시성 판정에 사용.
 *
 * @param userId 사용자 UUID
 * @param membership 소속 projectKeys·groupIds
 */
export function seedMembership(
  userId: string,
  membership: { projectKeys: string[]; groupIds: string[] },
): void {
  membershipStore.set(userId, {
    projectKeys: new Set(membership.projectKeys),
    groupIds: new Set(membership.groupIds),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** mock access token prefix — auth-fixtures.mockAccessToken 형식과 동일 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

/**
 * Authorization Bearer 헤더에서 userId를 도출한다.
 * 토큰 미존재 / 미인식 / 사용자 미존재 시 null 반환.
 */
function resolveUserIdFromRequest(request: Request): string | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null

  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MOCK_TOKEN_PREFIX)) return null

  const username = token.slice(MOCK_TOKEN_PREFIX.length)
  const user = AUTH_USERS[username]
  return user?.userId ?? null
}

/**
 * 주어진 share가 viewerId에게 매칭되는지 판정한다.
 * AUTHENTICATED → 항상 매칭.
 * PROJECT / GROUP → membershipStore에서 소속 확인.
 */
function matchesShare(share: ShareEntry, viewerId: string): boolean {
  if (share.shareType === 'AUTHENTICATED') return true

  const membership = membershipStore.get(viewerId)
  if (share.shareType === 'PROJECT') {
    return membership?.projectKeys.has(share.targetId ?? '') ?? false
  }
  if (share.shareType === 'GROUP') {
    return membership?.groupIds.has(share.targetId ?? '') ?? false
  }
  return false
}

/**
 * 필터가 viewerId에게 가시적인지 판정한다.
 * 소유자이거나 하나 이상의 share가 매칭되면 가시.
 */
function isVisibleToUser(filter: SavedFilterStoreItem, viewerId: string): boolean {
  if (filter.ownerId === viewerId) return true
  return filter.shares.some((share) => matchesShare(share, viewerId))
}

/**
 * viewerId 기준으로 보이는 shares만 추출한다 (C2 — 매칭 share만 노출).
 * 소유자에게는 전체 shares가 반환된다.
 */
function visibleShares(filter: SavedFilterStoreItem, viewerId: string): ReadonlyArray<ShareEntry> {
  if (filter.ownerId === viewerId) return filter.shares
  return filter.shares.filter((share) => matchesShare(share, viewerId))
}

/**
 * store 아이템을 HTTP 응답 객체로 변환한다.
 * isOwner와 shares는 viewerId 기준으로 도출한다.
 */
function toResponseObject(
  filter: SavedFilterStoreItem,
  viewerId: string,
): Record<string, unknown> {
  const isOwner = filter.ownerId === viewerId
  return {
    id: filter.id,
    ownerId: filter.ownerId,
    name: filter.name,
    aqlQuery: filter.aqlQuery,
    projectKey: filter.projectKey,
    createdAt: filter.createdAt,
    updatedAt: filter.updatedAt,
    version: filter.version,
    isOwner,
    shares: [...visibleShares(filter, viewerId)].map((s) => ({
      shareType: s.shareType,
      targetId: s.targetId,
    })),
  }
}

/**
 * ProblemDetail 형태 에러 응답을 생성한다 — 백엔드 ProblemDetail 형식과 동형.
 */
function problemDetail(
  status: number,
  title: string,
  detail: string,
  errorCode: string,
) {
  return HttpResponse.json(
    {
      type: 'about:blank',
      title,
      status,
      detail,
      errorCode,
      timestamp: new Date().toISOString(),
    },
    { status },
  )
}

/**
 * store 전체에서 특정 id의 필터를 탐색한다.
 * 소유자 userId와 함께 반환해 후속 store 갱신에 사용한다.
 */
function findFilterById(id: string): { filter: SavedFilterStoreItem; ownerId: string } | null {
  for (const [ownerId, filters] of savedFilterStore) {
    const filter = filters.find((f) => f.id === id)
    if (filter !== undefined) return { filter, ownerId }
  }
  return null
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러코드 상수 — 백엔드 SavedFilterExceptionHandler errorCode 열거값과 1:1
// ─────────────────────────────────────────────────────────────────────────────

const EC_VALIDATION_FAILED = 'SEARCH_VALIDATION_FAILED'
const EC_NOT_FOUND = 'SEARCH_FILTER_NOT_FOUND'
const EC_NAME_CONFLICT = 'SEARCH_FILTER_NAME_CONFLICT'
const EC_CONFLICT = 'SEARCH_FILTER_CONFLICT'

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 — GET /api/v1/filters (소유 필터 목록)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/filters — 요청자 소유 필터 배열 반환.
 *
 * 성공. 200 SavedFilterResponse[] (bare 배열, 래퍼 없음)
 * 미인증. 401 No Content
 */
const getOwnedFiltersHandler = http.get('/api/v1/filters', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const ownedFilters = savedFilterStore.get(userId) ?? []
  return HttpResponse.json(ownedFilters.map((f) => toResponseObject(f, userId)))
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 — GET /api/v1/filters/shared (비소유 가시 필터 목록)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/filters/shared?page=&size= — 요청자가 비소유이지만 가시인 필터 배열 반환.
 *
 * C2: 응답에는 요청자에게 매칭된 share만 포함한다.
 * 성공. 200 SavedFilterResponse[] (bare 배열, 래퍼 없음)
 * 미인증. 401 No Content
 */
const getSharedFiltersHandler = http.get('/api/v1/filters/shared', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') ?? '0', 10)
  const size = parseInt(url.searchParams.get('size') ?? '20', 10)

  const visibleNonOwned: SavedFilterStoreItem[] = []
  for (const [, filters] of savedFilterStore) {
    for (const filter of filters) {
      if (filter.ownerId !== userId && isVisibleToUser(filter, userId)) {
        visibleNonOwned.push(filter)
      }
    }
  }

  const start = page * size
  const paged = visibleNonOwned.slice(start, start + size)

  return HttpResponse.json(paged.map((f) => toResponseObject(f, userId)))
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 — GET /api/v1/filters/:id (단건 조회)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/filters/:id — 가시성 판정 후 단건 반환.
 *
 * 성공. 200 SavedFilterResponse (bare)
 * 비가시 / 없음. 404 SEARCH_FILTER_NOT_FOUND (존재 은닉)
 * 미인증. 401 No Content
 */
const getFilterByIdHandler = http.get('/api/v1/filters/:id', ({ request, params }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const id = params['id']
  if (typeof id !== 'string') {
    return problemDetail(400, 'Bad Request', '잘못된 필터 ID', EC_VALIDATION_FAILED)
  }

  const found = findFilterById(id)
  if (found === null || !isVisibleToUser(found.filter, userId)) {
    return problemDetail(404, 'Not Found', '저장 필터를 찾을 수 없습니다', EC_NOT_FOUND)
  }

  return HttpResponse.json(toResponseObject(found.filter, userId))
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 — POST /api/v1/filters (필터 생성)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/filters — 필터 생성.
 *
 * 성공. 201 Created, SavedFilterResponse
 * name 빈 값. 400 SEARCH_VALIDATION_FAILED
 * 이름 중복. 409 SEARCH_FILTER_NAME_CONFLICT
 * 미인증. 401 No Content
 */
const createFilterHandler = http.post('/api/v1/filters', async ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  let raw: unknown
  try {
    raw = await request.json()
  } catch {
    return problemDetail(400, 'Bad Request', '요청 본문을 파싱할 수 없습니다', EC_VALIDATION_FAILED)
  }

  if (raw === null || typeof raw !== 'object') {
    return problemDetail(400, 'Bad Request', '요청 본문이 올바르지 않습니다', EC_VALIDATION_FAILED)
  }

  const body = raw as Record<string, unknown>
  const name = body['name']
  const aqlQuery = body['aqlQuery']
  const projectKey = body['projectKey']

  // name 빈 값 검증
  if (typeof name !== 'string' || name.trim().length === 0) {
    return problemDetail(400, 'Validation Failed', 'name은 비어 있을 수 없습니다', EC_VALIDATION_FAILED)
  }
  // aqlQuery 빈 값 검증
  if (typeof aqlQuery !== 'string' || aqlQuery.trim().length === 0) {
    return problemDetail(400, 'Validation Failed', 'aqlQuery는 비어 있을 수 없습니다', EC_VALIDATION_FAILED)
  }
  // projectKey 검증
  if (typeof projectKey !== 'string' || projectKey.trim().length === 0) {
    return problemDetail(400, 'Validation Failed', 'projectKey는 비어 있을 수 없습니다', EC_VALIDATION_FAILED)
  }

  // shares 파싱 — 미제공 시 빈 배열
  const rawShares = body['shares']
  const shares: ShareEntry[] = Array.isArray(rawShares)
    ? rawShares.flatMap((s: unknown): ShareEntry[] => {
        if (s === null || typeof s !== 'object') return []
        const shareObj = s as Record<string, unknown>
        const shareType = shareObj['shareType']
        const targetId = shareObj['targetId']
        if (
          shareType === 'PROJECT' ||
          shareType === 'GROUP' ||
          shareType === 'AUTHENTICATED'
        ) {
          return [{ shareType, targetId: typeof targetId === 'string' ? targetId : null }]
        }
        return []
      })
    : []

  // 이름 중복 검증 — 동일 소유자 내에서
  const ownedFilters = savedFilterStore.get(userId) ?? []
  if (ownedFilters.some((f) => f.name === name)) {
    return problemDetail(409, 'Conflict', '이미 존재하는 필터 이름입니다', EC_NAME_CONFLICT)
  }

  const newFilter: SavedFilterStoreItem = {
    id: crypto.randomUUID(),
    ownerId: userId,
    name,
    aqlQuery,
    projectKey,
    createdAt: new Date().toISOString(),
    updatedAt: null,
    version: 0,
    shares,
  }

  savedFilterStore.set(userId, [...ownedFilters, newFilter])

  return HttpResponse.json(toResponseObject(newFilter, userId), { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 — PUT /api/v1/filters/:id (필터 수정)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PUT /api/v1/filters/:id — 필터 수정.
 *
 * 성공. 200 OK, SavedFilterResponse (version+1, shares replace-all)
 * B2: name 또는 aqlQuery blank. 400 SEARCH_VALIDATION_FAILED
 * OCC 버전 불일치. 409 SEARCH_FILTER_CONFLICT
 * 이름 중복. 409 SEARCH_FILTER_NAME_CONFLICT
 * 비소유 / 없음. 404 SEARCH_FILTER_NOT_FOUND (존재 은닉)
 * 미인증. 401 No Content
 */
const updateFilterHandler = http.put('/api/v1/filters/:id', async ({ request, params }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const id = params['id']
  if (typeof id !== 'string') {
    return problemDetail(400, 'Bad Request', '잘못된 필터 ID', EC_VALIDATION_FAILED)
  }

  // 소유 필터에서 탐색 — 비소유이거나 없으면 404 (존재 은닉)
  const ownedFilters = savedFilterStore.get(userId) ?? []
  const existing = ownedFilters.find((f) => f.id === id)
  if (existing === undefined) {
    return problemDetail(404, 'Not Found', '저장 필터를 찾을 수 없습니다', EC_NOT_FOUND)
  }

  let raw: unknown
  try {
    raw = await request.json()
  } catch {
    return problemDetail(400, 'Bad Request', '요청 본문을 파싱할 수 없습니다', EC_VALIDATION_FAILED)
  }

  if (raw === null || typeof raw !== 'object') {
    return problemDetail(400, 'Bad Request', '요청 본문이 올바르지 않습니다', EC_VALIDATION_FAILED)
  }

  const body = raw as Record<string, unknown>
  const name = body['name']
  const aqlQuery = body['aqlQuery']
  const version = body['version']

  // B2: blank 검증
  if (typeof name !== 'string' || name.trim().length === 0) {
    return problemDetail(400, 'Validation Failed', 'name은 비어 있을 수 없습니다', EC_VALIDATION_FAILED)
  }
  if (typeof aqlQuery !== 'string' || aqlQuery.trim().length === 0) {
    return problemDetail(400, 'Validation Failed', 'aqlQuery는 비어 있을 수 없습니다', EC_VALIDATION_FAILED)
  }

  // OCC 버전 검증
  if (typeof version !== 'number' || version !== existing.version) {
    return problemDetail(409, 'Conflict', '필터가 다른 곳에서 수정되었습니다', EC_CONFLICT)
  }

  // 이름 중복 검증 — 자신 제외
  if (ownedFilters.some((f) => f.id !== id && f.name === name)) {
    return problemDetail(409, 'Conflict', '이미 존재하는 필터 이름입니다', EC_NAME_CONFLICT)
  }

  // shares replace-all — 미제공 시 기존 유지
  const rawShares = body['shares']
  const updatedShares: ReadonlyArray<ShareEntry> = Array.isArray(rawShares)
    ? rawShares.flatMap((s: unknown): ShareEntry[] => {
        if (s === null || typeof s !== 'object') return []
        const shareObj = s as Record<string, unknown>
        const shareType = shareObj['shareType']
        const targetId = shareObj['targetId']
        if (
          shareType === 'PROJECT' ||
          shareType === 'GROUP' ||
          shareType === 'AUTHENTICATED'
        ) {
          return [{ shareType, targetId: typeof targetId === 'string' ? targetId : null }]
        }
        return []
      })
    : existing.shares

  const updated: SavedFilterStoreItem = {
    ...existing,
    name,
    aqlQuery,
    updatedAt: new Date().toISOString(),
    version: existing.version + 1,
    shares: updatedShares,
  }

  savedFilterStore.set(
    userId,
    ownedFilters.map((f) => (f.id === id ? updated : f)),
  )

  return HttpResponse.json(toResponseObject(updated, userId))
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 — DELETE /api/v1/filters/:id (필터 삭제)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DELETE /api/v1/filters/:id — 소유 필터 삭제.
 *
 * 성공. 204 No Content
 * 비소유 / 없음. 404 SEARCH_FILTER_NOT_FOUND (존재 은닉)
 * 미인증. 401 No Content
 */
const deleteFilterHandler = http.delete('/api/v1/filters/:id', ({ request, params }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const id = params['id']
  if (typeof id !== 'string') {
    return problemDetail(400, 'Bad Request', '잘못된 필터 ID', EC_VALIDATION_FAILED)
  }

  const ownedFilters = savedFilterStore.get(userId) ?? []
  const existing = ownedFilters.find((f) => f.id === id)
  if (existing === undefined) {
    return problemDetail(404, 'Not Found', '저장 필터를 찾을 수 없습니다', EC_NOT_FOUND)
  }

  savedFilterStore.set(
    userId,
    ownedFilters.filter((f) => f.id !== id),
  )

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// 브라우저 E2E 시드 노출 — window.__btsSeedSavedFilters
//
// MSW v2는 핸들러를 메인 스레드(브라우저 컨텍스트)에서 실행한다.
// 따라서 window 객체가 존재하면 seedSavedFilters / seedMembership / resetSavedFilterStore를
// 글로벌로 노출해 Playwright page.evaluate() 로 직접 호출할 수 있다.
//
// 사용 예 (Playwright).
//   await page.evaluate(
//     ([uid, items]) => window.__btsSeedSavedFilters(uid, items),
//     [aliceId, seedItems]
//   )
// ─────────────────────────────────────────────────────────────────────────────

if (typeof window !== 'undefined') {
  const w = window as unknown as Record<string, unknown>
  w['__btsSeedSavedFilters'] = seedSavedFilters
  w['__btsSeedFilterMembership'] = seedMembership
  w['__btsResetSavedFilterStore'] = resetSavedFilterStore
}

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장 필터 BC MSW 핸들러 배열.
 *
 * NOTE: /filters/shared 는 /filters/:id 보다 먼저 등록해야 경로 충돌 없이 매칭된다.
 */
export const savedFilterHandlers = [
  getOwnedFiltersHandler,
  getSharedFiltersHandler,
  getFilterByIdHandler,
  createFilterHandler,
  updateFilterHandler,
  deleteFilterHandler,
]
