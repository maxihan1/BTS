// 즐겨찾기 BC MSW 핸들러 — stateful CRUD (POST/GET/DELETE) (FR-UX-02 D6)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

/** 즐겨찾기 대상 타입 열거 — 백엔드 FavoriteTargetType enum과 1:1 */
const VALID_TARGET_TYPES = ['ISSUE', 'DASHBOARD', 'PROJECT', 'FILTER'] as const
type FavoriteTargetType = (typeof VALID_TARGET_TYPES)[number]

/** 즐겨찾기 단건 — store 내부 + GET 응답 공유 */
interface Favorite {
  id: string
  targetType: FavoriteTargetType
  targetId: string
  createdAt: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 — userId → Favorite[] Map
// ─────────────────────────────────────────────────────────────────────────────

/** 즐겨찾기 저장소 — userId별 격리 (actor 격리 보장) */
let favoriteStore: Map<string, Favorite[]> = new Map()

// ─────────────────────────────────────────────────────────────────────────────
// 저장소 관리 exports
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 저장소를 초기화한다 — 각 테스트 afterEach에서 호출.
 * 테스트 간 state leak을 방지한다.
 */
export function resetFavoriteStore(): void {
  favoriteStore = new Map()
}

/**
 * 특정 userId의 즐겨찾기 목록을 시드한다.
 * E2E 및 단위 테스트 beforeEach에서 초기 데이터를 주입할 때 사용한다.
 *
 * @param userId 즐겨찾기를 소유할 사용자 UUID
 * @param items 초기 즐겨찾기 항목 배열
 */
export function seedFavorites(userId: string, items: Favorite[]): void {
  favoriteStore.set(userId, [...items])
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** mock access token prefix — auth-fixtures.mockAccessToken 과 동일 형식 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

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
 * targetType + targetId 조합으로 store 조회 키를 생성한다.
 *
 * @param targetType 즐겨찾기 대상 타입
 * @param targetId 즐겨찾기 대상 ID
 * @returns 복합 키 문자열
 */
function makeFavoriteKey(targetType: string, targetId: string): string {
  return `${targetType}::${targetId}`
}

/**
 * 주어진 값이 유효한 FavoriteTargetType인지 검사한다.
 *
 * @param value 검사할 값
 * @returns 유효 여부
 */
function isValidTargetType(value: string): value is FavoriteTargetType {
  return (VALID_TARGET_TYPES as readonly string[]).includes(value)
}

/** 즐겨찾기 ID 생성 — 결정적이지 않은 UUID-like 문자열 */
function generateFavoriteId(): string {
  return `fav-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/favorites — 즐겨찾기 추가.
 *
 * 새 항목. 201 { data: { id, targetType, targetId, createdAt, created: true } }
 * 이미 존재(멱등). 200 { data: { ...기존, created: false } }
 * 입력 오류. 400 { errorCode: 'NOTIF_FAV_INVALID', message }
 */
const addFavoriteHandler = http.post('/api/v1/favorites', async ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) {
    return HttpResponse.json(
      { errorCode: 'NOTIF_FAV_INVALID', message: '인증 필요' },
      { status: 401 },
    )
  }

  let body: { targetType?: unknown; targetId?: unknown }
  try {
    body = (await request.json()) as { targetType?: unknown; targetId?: unknown }
  } catch {
    return HttpResponse.json(
      { errorCode: 'NOTIF_FAV_INVALID', message: '잘못된 요청 본문' },
      { status: 400 },
    )
  }

  const targetType = body.targetType
  const targetId = body.targetId

  // 형식 검증 — targetType 열거 + targetId 비어 있지 않음
  if (typeof targetType !== 'string' || !isValidTargetType(targetType)) {
    return HttpResponse.json(
      { errorCode: 'NOTIF_FAV_INVALID', message: '유효하지 않은 targetType' },
      { status: 400 },
    )
  }
  if (typeof targetId !== 'string' || targetId.trim().length === 0) {
    return HttpResponse.json(
      { errorCode: 'NOTIF_FAV_INVALID', message: 'targetId는 비어 있을 수 없습니다' },
      { status: 400 },
    )
  }

  const userFavorites = favoriteStore.get(userId) ?? []
  const key = makeFavoriteKey(targetType, targetId)

  // 멱등 체크 — 이미 존재하면 created=false 200
  const existing = userFavorites.find(
    (f) => makeFavoriteKey(f.targetType, f.targetId) === key,
  )
  if (existing !== undefined) {
    return HttpResponse.json({ data: { ...existing, created: false } }, { status: 200 })
  }

  // 신규 추가
  const newFavorite: Favorite = {
    id: generateFavoriteId(),
    targetType,
    targetId,
    createdAt: new Date().toISOString(),
  }
  favoriteStore.set(userId, [...userFavorites, newFavorite])

  return HttpResponse.json({ data: { ...newFavorite, created: true } }, { status: 201 })
})

/**
 * GET /api/v1/favorites — 즐겨찾기 목록 조회.
 *
 * 성공. 200 { data: { items: Favorite[] } }
 * 쿼리 파라미터 `?targetType=` 으로 필터링 가능.
 * 정렬. createdAt DESC (최근 추가가 먼저).
 */
const getFavoritesHandler = http.get('/api/v1/favorites', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) {
    return HttpResponse.json(
      { errorCode: 'NOTIF_FAV_INVALID', message: '인증 필요' },
      { status: 401 },
    )
  }

  const url = new URL(request.url)
  const targetTypeFilter = url.searchParams.get('targetType')

  let items = [...(favoriteStore.get(userId) ?? [])]

  // targetType 필터 적용
  if (targetTypeFilter !== null) {
    items = items.filter((f) => f.targetType === targetTypeFilter)
  }

  // createdAt DESC 정렬
  items.sort((a, b) => b.createdAt.localeCompare(a.createdAt))

  return HttpResponse.json({ data: { items } }, { status: 200 })
})

/**
 * DELETE /api/v1/favorites/:id — 즐겨찾기 삭제 (멱등).
 *
 * 성공. 204 No Content
 * 없는 id. 204 No Content (멱등)
 */
const deleteFavoriteHandler = http.delete('/api/v1/favorites/:id', ({ params, request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) {
    return new HttpResponse(null, { status: 401 })
  }

  const favId = params['id'] as string
  const userFavorites = favoriteStore.get(userId) ?? []
  favoriteStore.set(
    userId,
    userFavorites.filter((f) => f.id !== favId),
  )

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 즐겨찾기 BC MSW 핸들러 배열 */
export const favoriteHandlers = [
  addFavoriteHandler,
  getFavoritesHandler,
  deleteFavoriteHandler,
]
