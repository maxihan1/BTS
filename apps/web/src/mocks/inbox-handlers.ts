// 개인 알림 보관함(Inbox) MSW 핸들러 — stateful CRUD (FR-UX-03 D6)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'
import { defaultInboxFixtures } from './inbox-fixtures'
import type { InboxItem } from '@/api/inbox'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 페이지 크기 — 백엔드 InboxController 기본값과 일치 */
const DEFAULT_PAGE_SIZE = 20

/** mock access token prefix — auth-fixtures.mockAccessToken과 동일 형식 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 — userId → InboxItem[] Map
// ─────────────────────────────────────────────────────────────────────────────

/** inbox 저장소 — userId별 격리 */
let inboxStore: Map<string, InboxItem[]> = new Map()

// 모듈 로드 시 alice의 기본 시드를 자동 주입 (learnings: 신규 store 모듈로드 자동시드 필수)
inboxStore.set('00000000-0000-4000-8000-000000000001', [...defaultInboxFixtures])

// ─────────────────────────────────────────────────────────────────────────────
// 저장소 관리 exports
// ─────────────────────────────────────────────────────────────────────────────

/**
 * inbox 저장소를 완전히 초기화한다.
 * 각 테스트 afterEach에서 호출해 테스트 간 state leak을 방지한다.
 */
export function resetInboxStore(): void {
  inboxStore = new Map()
}

/**
 * 특정 userId의 inbox 항목을 시드한다.
 * E2E 및 단위 테스트 beforeEach에서 초기 데이터를 주입할 때 사용한다.
 *
 * @param userId inbox를 소유할 사용자 UUID
 * @param items 초기 inbox 항목 배열
 */
export function seedInbox(userId: string, items: InboxItem[]): void {
  inboxStore.set(userId, [...items])
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Authorization Bearer 헤더에서 현재 사용자 userId를 도출한다.
 *
 * 토큰 형식. `mock-access-token-<username>` (auth-handlers.ts whoami 핸들러와 동일).
 * AUTH_USERS에서 username → userId를 조회한다.
 * 토큰 미존재 / 미인식 / 사용자 미존재 시 null을 반환한다.
 *
 * @param request MSW Request 객체
 * @returns userId 문자열 또는 null
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
 * inbox 항목에 탭 필터를 적용한다.
 * 백엔드 InboxTab enum 정의와 정확히 일치한다.
 *
 * - ALL: archived_at IS NULL (보관되지 않은 전체, 읽음 무관)
 * - UNREAD: read_at IS NULL AND archived_at IS NULL
 * - ARCHIVED: archived_at IS NOT NULL
 *
 * @param items 전체 항목 배열
 * @param tab 탭 필터 값 (미지정 시 'ALL')
 * @returns 필터된 항목 배열
 */
function applyTabFilter(items: InboxItem[], tab: string): InboxItem[] {
  if (tab === 'ARCHIVED') {
    return items.filter((i) => i.archivedAt !== null)
  }
  if (tab === 'UNREAD') {
    return items.filter((i) => i.readAt === null && i.archivedAt === null)
  }
  // ALL (기본): 미보관 전체
  return items.filter((i) => i.archivedAt === null)
}

/**
 * inbox 항목에 검색 필터를 적용한다.
 * 탭 필터 이후에 적용한다.
 *
 * @param items 탭 필터 후 항목 배열
 * @param params 검색 파라미터 객체
 * @returns 필터된 항목 배열
 */
function applySearchFilters(
  items: InboxItem[],
  params: {
    q: string | null
    senderId: string | null
    issueKey: string | null
    from: string | null
    to: string | null
  },
): InboxItem[] {
  let result = items

  if (params.q !== null && params.q !== '') {
    const lower = params.q.toLowerCase()
    result = result.filter((i) => i.title.toLowerCase().includes(lower))
  }

  if (params.senderId !== null) {
    result = result.filter((i) => i.actorUserId === params.senderId)
  }

  if (params.issueKey !== null) {
    result = result.filter((i) => i.issueKey === params.issueKey)
  }

  if (params.from !== null) {
    result = result.filter((i) => i.createdAt >= params.from!)
  }

  if (params.to !== null) {
    result = result.filter((i) => i.createdAt <= params.to!)
  }

  return result
}

/**
 * 항목 배열을 createdAt DESC 정렬하여 Spring Page 응답 형식으로 변환한다.
 *
 * @param items 필터된 항목 배열
 * @param page 0-based 페이지 번호
 * @param size 페이지 크기
 * @returns Spring Page 형식 응답
 */
function toSpringPage(
  items: InboxItem[],
  page: number,
  size: number,
): {
  content: InboxItem[]
  totalElements: number
  totalPages: number
  number: number
  size: number
} {
  // createdAt DESC 정렬
  const sorted = [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt))

  const totalElements = sorted.length
  const totalPages = size > 0 ? Math.ceil(totalElements / size) : 0
  const start = page * size
  const content = sorted.slice(start, start + size)

  return {
    content,
    totalElements,
    totalPages,
    number: page,
    size,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/users/me/inbox — inbox 목록 조회 (탭/검색/페이지 필터 지원).
 *
 * 쿼리 파라미터.
 *   tab: ALL(기본) | UNREAD | ARCHIVED
 *   q: title 부분일치
 *   senderId: actorUserId UUID 일치
 *   issueKey: issueKey 일치
 *   from/to: createdAt 범위 (ISO-8601)
 *   page: 0-based (기본 0)
 *   size: 기본 20
 *
 * 응답. 200 Spring Page<InboxItem> (content/totalElements/totalPages/number/size)
 * 미인증. 401
 */
const getInboxHandler = http.get('/api/v1/users/me/inbox', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) {
    return new HttpResponse(null, { status: 401 })
  }

  const url = new URL(request.url)
  const tab = url.searchParams.get('tab') ?? 'ALL'
  const q = url.searchParams.get('q')
  const senderId = url.searchParams.get('senderId')
  const issueKey = url.searchParams.get('issueKey')
  const from = url.searchParams.get('from')
  const to = url.searchParams.get('to')
  const page = Math.max(0, parseInt(url.searchParams.get('page') ?? '0', 10) || 0)
  const size = Math.min(
    100,
    Math.max(1, parseInt(url.searchParams.get('size') ?? String(DEFAULT_PAGE_SIZE), 10) || DEFAULT_PAGE_SIZE),
  )

  const userItems = inboxStore.get(userId) ?? []
  const tabFiltered = applyTabFilter(userItems, tab)
  const searched = applySearchFilters(tabFiltered, { q, senderId, issueKey, from, to })
  const pageResult = toSpringPage(searched, page, size)

  return HttpResponse.json(pageResult, { status: 200 })
})

/**
 * GET /api/v1/users/me/inbox/unread-count — 미읽음 카운트 조회.
 *
 * 미읽음 기준. readAt IS NULL AND archivedAt IS NULL
 *
 * 응답. 200 { data: { count: number } }
 * 미인증. 401
 */
const getUnreadCountHandler = http.get('/api/v1/users/me/inbox/unread-count', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) {
    return new HttpResponse(null, { status: 401 })
  }

  const userItems = inboxStore.get(userId) ?? []
  const count = userItems.filter((i) => i.readAt === null && i.archivedAt === null).length

  return HttpResponse.json({ data: { count } }, { status: 200 })
})

/**
 * PATCH /api/v1/users/me/inbox/{id}/read — 읽음 상태 변경.
 *
 * read=true.  readAt 설정 (COALESCE 모사 — 기존 값이 있으면 보존)
 * read=false. readAt null로 초기화
 *
 * 응답. 204 (본문 없음)
 * 미존재. 404
 * 미인증. 401
 */
const patchReadHandler = http.patch('/api/v1/users/me/inbox/:id/read', async ({ request, params }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) {
    return new HttpResponse(null, { status: 401 })
  }

  const id = params['id']
  if (typeof id !== 'string') {
    return new HttpResponse(null, { status: 400 })
  }

  const userItems = inboxStore.get(userId) ?? []
  const itemIndex = userItems.findIndex((i) => i.id === id)

  if (itemIndex === -1) {
    return new HttpResponse(null, { status: 404 })
  }

  let body: { read?: unknown }
  try {
    body = (await request.json()) as { read?: unknown }
  } catch {
    return new HttpResponse(null, { status: 400 })
  }

  const read = body.read

  const updated = [...userItems]
  const item = updated[itemIndex]
  if (item === undefined) {
    return new HttpResponse(null, { status: 404 })
  }

  if (read === true) {
    // COALESCE 모사 — 이미 readAt이 있으면 기존 시각 보존
    updated[itemIndex] = {
      ...item,
      readAt: item.readAt !== null ? item.readAt : new Date().toISOString(),
    }
  } else {
    updated[itemIndex] = { ...item, readAt: null }
  }

  inboxStore.set(userId, updated)
  return new HttpResponse(null, { status: 204 })
})

/**
 * PATCH /api/v1/users/me/inbox/{id}/archive — 보관 상태 변경.
 *
 * archived=true.  archivedAt 설정 (COALESCE 모사 — 기존 값이 있으면 보존)
 * archived=false. archivedAt null로 초기화
 *
 * 응답. 204 (본문 없음)
 * 미존재. 404
 * 미인증. 401
 */
const patchArchiveHandler = http.patch('/api/v1/users/me/inbox/:id/archive', async ({ request, params }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) {
    return new HttpResponse(null, { status: 401 })
  }

  const id = params['id']
  if (typeof id !== 'string') {
    return new HttpResponse(null, { status: 400 })
  }

  const userItems = inboxStore.get(userId) ?? []
  const itemIndex = userItems.findIndex((i) => i.id === id)

  if (itemIndex === -1) {
    return new HttpResponse(null, { status: 404 })
  }

  let body: { archived?: unknown }
  try {
    body = (await request.json()) as { archived?: unknown }
  } catch {
    return new HttpResponse(null, { status: 400 })
  }

  const archived = body.archived

  const updated = [...userItems]
  const item = updated[itemIndex]
  if (item === undefined) {
    return new HttpResponse(null, { status: 404 })
  }

  if (archived === true) {
    // COALESCE 모사 — 이미 archivedAt이 있으면 기존 시각 보존
    updated[itemIndex] = {
      ...item,
      archivedAt: item.archivedAt !== null ? item.archivedAt : new Date().toISOString(),
    }
  } else {
    updated[itemIndex] = { ...item, archivedAt: null }
  }

  inboxStore.set(userId, updated)
  return new HttpResponse(null, { status: 204 })
})

/**
 * POST /api/v1/users/me/inbox/read-all — 일괄 읽음 처리.
 *
 * 대상. readAt IS NULL AND archivedAt IS NULL (미읽음+미보관)
 *        ids 지정 시 해당 항목 중에서만 처리.
 *
 * 응답. 200 { data: { updated: number } }
 * 미인증. 401
 */
const postReadAllHandler = http.post('/api/v1/users/me/inbox/read-all', async ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) {
    return new HttpResponse(null, { status: 401 })
  }

  let body: { ids?: unknown }
  try {
    body = (await request.json()) as { ids?: unknown }
  } catch {
    body = {}
  }

  const ids = Array.isArray(body.ids) ? (body.ids as unknown[]).filter((v): v is string => typeof v === 'string') : undefined

  const userItems = inboxStore.get(userId) ?? []
  const now = new Date().toISOString()
  let updated = 0

  const newItems = userItems.map((item) => {
    // 대상 조건: 미읽음 + 미보관
    const isTarget = item.readAt === null && item.archivedAt === null
    // ids 지정 시 해당 id만
    const isInIds = ids === undefined || ids.includes(item.id)

    if (isTarget && isInIds) {
      updated++
      return { ...item, readAt: now }
    }
    return item
  })

  inboxStore.set(userId, newItems)
  return HttpResponse.json({ data: { updated } }, { status: 200 })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inbox BC MSW 핸들러 배열.
 *
 * 등록 순서 주의.
 * - `/read-all`(POST)은 `/:id/read`(PATCH)보다 경로가 구체적이지 않으나 method가 다르므로 충돌 없음.
 * - `/unread-count`(GET)은 `/:id`(PATCH) 경로와 다르므로 충돌 없음.
 */
export const inboxHandlers = [
  getUnreadCountHandler,
  getInboxHandler,
  patchReadHandler,
  patchArchiveHandler,
  postReadAllHandler,
]
