// 이슈 워처 BC MSW 핸들러 — stateful CRUD (GET/POST/DELETE) (FR-WT-01 D6)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 — issueKey → userId Set
// ─────────────────────────────────────────────────────────────────────────────

/** 워처 저장소 — issueKey별 userId Set */
let watcherStore: Map<string, Set<string>> = new Map()

/** 현재 인증 사용자 userId — 테스트에서 setCurrentWatcherUserId()로 시드 */
let currentUserId: string | null = null

// ─────────────────────────────────────────────────────────────────────────────
// 저장소 관리 exports
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워처 저장소와 현재 userId를 초기화한다 — 각 테스트 afterEach에서 호출.
 */
export function resetIssueWatcherStore(): void {
  watcherStore = new Map()
  currentUserId = null
}

/**
 * 특정 이슈의 워처 userId Set을 시드한다.
 *
 * @param issueKey 이슈 키 (예: "ATLAS-1")
 * @param userIds 초기 워처 userId 배열
 */
export function seedIssueWatchers(issueKey: string, userIds: string[]): void {
  watcherStore.set(issueKey, new Set(userIds))
}

/**
 * GET 핸들러가 isWatching 계산에 사용할 현재 userId를 설정한다.
 * 단위 테스트에서 auth store를 직접 읽을 수 없으므로 핸들러 레벨에서 시드.
 *
 * @param userId 현재 인증 사용자 UUID
 */
export function setCurrentWatcherUserId(userId: string | null): void {
  currentUserId = userId
}

// ─────────────────────────────────────────────────────────────────────────────
// 요청 토큰 → 현재 사용자 도출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** mock access token prefix — auth-fixtures.mockAccessToken 과 동일 형식 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

/**
 * Authorization Bearer 헤더에서 현재 사용자 userId 를 도출한다.
 *
 * 토큰 형식: `mock-access-token-<username>` (auth-handlers.ts whoami 핸들러와 동일 방식).
 * username 은 AUTH_USERS 에서 조회해 userId 를 반환한다.
 * 토큰 미존재 / 미인식 / 사용자 미존재 시 null 을 반환한다.
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

// ─────────────────────────────────────────────────────────────────────────────
// displayName 생성 헬퍼 — userId 기반 결정적 생성
// ─────────────────────────────────────────────────────────────────────────────

/** 알려진 userId → displayName 매핑 (픽스처 시드, RFC4122 v4 형식) */
const KNOWN_DISPLAY_NAMES: Readonly<Record<string, string>> = {
  '00000000-0000-4000-8000-000000000001': 'User alice',
  '00000000-0000-4000-8000-000000000002': 'User bob',
}

/**
 * userId를 displayName으로 변환한다.
 * KNOWN_DISPLAY_NAMES에 없으면 `User {id 앞 8자}` 형식을 사용한다.
 *
 * @param userId 사용자 id
 * @returns 화면에 표시할 이름
 */
function resolveDisplayName(userId: string): string {
  return KNOWN_DISPLAY_NAMES[userId] ?? `User ${userId.slice(0, 8)}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/issues/:key/watchers — 워처 목록 조회.
 * 성공 → 200 { data: { watchers, count, isWatching } }
 *
 * 현재 사용자 판정 우선순위.
 * (a) setCurrentWatcherUserId() 로 명시 설정된 값 (vitest override)
 * (b) 없으면 Authorization Bearer 토큰에서 도출 (브라우저 E2E)
 */
const getWatchersHandler = http.get('/api/v1/issues/:key/watchers', ({ params, request }) => {
  const key = params['key'] as string
  const watchers = watcherStore.get(key) ?? new Set<string>()
  const watcherList = Array.from(watchers).map((userId) => ({
    userId,
    displayName: resolveDisplayName(userId),
  }))

  // vitest 명시 override 우선, 없으면 토큰 파생
  const effectiveUserId = currentUserId ?? resolveUserIdFromRequest(request)
  const isWatching = effectiveUserId !== null && watchers.has(effectiveUserId)

  return HttpResponse.json({
    data: {
      watchers: watcherList,
      count: watcherList.length,
      isWatching,
    },
  })
})

/**
 * POST /api/v1/issues/:key/watchers — 워처 추가 (멱등).
 * body.userId 있으면 해당 사용자를, 없으면 self(현재 사용자)를 추가한다.
 * 성공 → 201 No Content
 *
 * 현재 사용자 판정 우선순위.
 * (a) setCurrentWatcherUserId() 로 명시 설정된 값 (vitest override)
 * (b) 없으면 Authorization Bearer 토큰에서 도출 (브라우저 E2E)
 */
const addWatcherHandler = http.post('/api/v1/issues/:key/watchers', async ({ params, request }) => {
  const key = params['key'] as string

  // vitest 명시 override 우선, 없으면 토큰 파생
  const effectiveUserId = currentUserId ?? resolveUserIdFromRequest(request)

  let targetUserId: string | null = null
  try {
    const body = (await request.json()) as { userId?: string }
    targetUserId = body.userId ?? effectiveUserId
  } catch {
    // body 없음 → self 추가
    targetUserId = effectiveUserId
  }

  if (targetUserId === null) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_ACCESS_DENIED', message: '인증 필요' },
      { status: 401 },
    )
  }

  const watchers = watcherStore.get(key) ?? new Set<string>()
  watchers.add(targetUserId)
  watcherStore.set(key, watchers)

  return new HttpResponse(null, { status: 201 })
})

/**
 * DELETE /api/v1/issues/:key/watchers/:userId — 워처 제거 (멱등).
 * 성공 → 204 No Content
 */
const removeWatcherHandler = http.delete(
  '/api/v1/issues/:key/watchers/:userId',
  ({ params }) => {
    const key = params['key'] as string
    const userId = params['userId'] as string

    const watchers = watcherStore.get(key) ?? new Set<string>()
    watchers.delete(userId)
    watcherStore.set(key, watchers)

    return new HttpResponse(null, { status: 204 })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 워처 BC MSW 핸들러 배열 */
export const issueWatcherHandlers = [
  getWatchersHandler,
  addWatcherHandler,
  removeWatcherHandler,
]
