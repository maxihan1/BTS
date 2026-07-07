// 부재중(Out of Office) BC MSW 핸들러 — stateful GET/PATCH/DELETE + 활성/종료 lazy 필터 (FR-PR-03 Task 6)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'
import { userListFixture } from './user-fixtures'
import { OOO_FIXTURES } from './ooo-fixtures'
import type { OooFixture } from './ooo-fixtures'
import type { OooResponse } from '@/api/schemas'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 — userId → OooFixture Map (msw-derived-behavior-shared-store 선례,
// status-handlers.ts와 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

function seedOooStoreMap(): Map<string, OooFixture> {
  const map = new Map<string, OooFixture>()
  for (const fixture of OOO_FIXTURES) {
    map.set(fixture.userId, { ...fixture })
  }
  return map
}

let oooStore: Map<string, OooFixture> = seedOooStoreMap()

/**
 * 부재중 저장소를 초기 시드 상태(`OOO_FIXTURES`)로 리셋한다 — 각 테스트 beforeEach에서 호출.
 * 테스트 간 state leak을 방지한다(status-handlers.resetStatusStore 선례).
 */
export function resetOooStore(): void {
  oooStore = seedOooStoreMap()
}

/**
 * 특정 사용자의 부재중 store 레코드를 검증 없이 직접 주입한다.
 *
 * PATCH 핸들러는 종료된(endsAt<=now) 기간을 거부하므로(EC3), 이미 종료된 부재중(S6 — lazy 필터
 * 시나리오)은 정상 API 흐름으로 재현할 수 없다. 이 헬퍼로 store에 직접 시드해 GET의 종료 필터
 * 동작만 독립적으로 테스트한다. `resetOooStore` 호출 이후에 사용해야 시드가 유지된다.
 *
 * @param fixture 주입할 부재중 레코드(종료 여부와 무관하게 그대로 저장)
 */
export function seedOooRecord(fixture: OooFixture): void {
  oooStore.set(fixture.userId, { ...fixture })
}

/**
 * 활성(현재 부재중) 부재중을 조회한다 — whoami view-layer(auth-handlers)가 oooActive/oooUntil
 * 파생에 사용할 수 있도록 노출한다(백엔드 WhoamiController가 OutOfOfficeRepository로
 * oooActive/oooUntil을 채우는 흐름 재현, FR-PR-02 getActiveStatusForUser 선례).
 *
 * @param userId 조회 대상 사용자 id
 * @returns 활성 부재중의 종료 시각(until), 없으면 null
 */
export function getActiveOooForUser(userId: string): { until: string } | null {
  const record = oooStore.get(userId)
  if (record === undefined) return null
  if (!isActiveRecord(record, Date.now())) return null
  return record.endsAt === null ? null : { until: record.endsAt }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — Authorization Bearer 토큰 파싱 (status-handlers.ts와 동일 규약)
// ─────────────────────────────────────────────────────────────────────────────

/** mock access token prefix — auth-fixtures.mockAccessToken과 동일 형식 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

/**
 * Authorization Bearer 헤더에서 현재 사용자 userId를 도출한다.
 * 토큰 형식. `mock-access-token-<username>`. 미인증/미인식 시 null.
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

/** userId → 표시 이름 조회 맵(delegateName 파생, 백엔드 LEFT JOIN users 미러). 대리자 선택기가
 *  참조하는 사용자 디렉토리(`GET /api/v1/users`, user-fixtures.ts)와 동일 출처를 사용한다. */
const DELEGATE_DIRECTORY: ReadonlyMap<string, string> = new Map(
  userListFixture.map((u) => [u.id, u.displayName ?? u.username]),
)

function resolveDelegateName(delegateUserId: string | null): string | null {
  if (delegateUserId === null) return null
  return DELEGATE_DIRECTORY.get(delegateUserId) ?? null
}

/** 백엔드 `{code, message}` 에러 봉투 생성 */
function errorBody(message: string): { code: string; message: string } {
  return { code: 'OOO_VALIDATION_FAILED', message }
}

/** all-null 부재중 응답 — EC1(row 없음)/해제/종료 필터 공통. */
function emptyOooResponse(): OooResponse {
  return { startsAt: null, endsAt: null, delegateUserId: null, delegateName: null, message: null, active: false }
}

/** 활성 여부 판정 — startsAt<=now<endsAt(백엔드 활성 판정 규칙 미러). */
function isActiveRecord(record: OooFixture, now: number): boolean {
  if (record.startsAt === null || record.endsAt === null) return false
  return new Date(record.startsAt).getTime() <= now && now < new Date(record.endsAt).getTime()
}

/** 종료됐는지(ends_at<=now) 판정 — GET 필터(G3, ends_at>now면 노출, 미래예약 포함). */
function isEnded(record: OooFixture, now: number): boolean {
  if (record.endsAt === null) return false
  return new Date(record.endsAt).getTime() <= now
}

/** OooFixture(store 레코드) → {@link OooResponse} 변환(active/delegateName 파생). */
function toOooResponse(record: OooFixture, now: number): OooResponse {
  return {
    startsAt: record.startsAt,
    endsAt: record.endsAt,
    delegateUserId: record.delegateUserId,
    delegateName: resolveDelegateName(record.delegateUserId),
    message: record.message,
    active: isActiveRecord(record, now),
  }
}

/** 메시지 정규화 — blank(공백만)면 null(스펙 EC6, backend 정규화 미러). 문자열이 아니면 null 취급. */
function normalizeMessage(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length === 0 ? null : trimmed
}

/** 메시지 최대 길이 — 스펙 NFR3 */
const MESSAGE_MAX_LENGTH = 500

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/ooo
// ─────────────────────────────────────────────────────────────────────────────

const getMyOooHandler = http.get('/api/v1/users/me/ooo', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const record = oooStore.get(userId)
  const now = Date.now()
  if (record === undefined || isEnded(record, now)) {
    return HttpResponse.json(emptyOooResponse())
  }

  return HttpResponse.json(toOooResponse(record, now))
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/users/me/ooo — 원자적 교체(replace, EC7)
// ─────────────────────────────────────────────────────────────────────────────

const patchMyOooHandler = http.patch('/api/v1/users/me/ooo', async ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  let body: Record<string, unknown>
  try {
    body = (await request.json()) as Record<string, unknown>
  } catch {
    return HttpResponse.json(errorBody('잘못된 요청 본문입니다.'), { status: 400 })
  }

  const startsAtRaw = typeof body['startsAt'] === 'string' ? body['startsAt'] : null
  const endsAtRaw = typeof body['endsAt'] === 'string' ? body['endsAt'] : null
  const delegateUserId = typeof body['delegateUserId'] === 'string' ? body['delegateUserId'] : null
  const message = normalizeMessage(body['message'])

  if (startsAtRaw === null || endsAtRaw === null) {
    return HttpResponse.json(errorBody('시작·종료 시각은 필수입니다.'), { status: 400 })
  }

  const start = new Date(startsAtRaw).getTime()
  const end = new Date(endsAtRaw).getTime()
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return HttpResponse.json(errorBody('기간 형식이 올바르지 않습니다.'), { status: 400 })
  }
  if (end <= start) {
    return HttpResponse.json(errorBody('종료 시각은 시작 시각 이후여야 합니다.'), { status: 400 })
  }
  if (end <= Date.now()) {
    return HttpResponse.json(errorBody('종료 시각은 현재보다 미래여야 합니다.'), { status: 400 })
  }
  if (delegateUserId !== null) {
    if (delegateUserId === userId) {
      return HttpResponse.json(errorBody('자기 자신을 대리자로 지정할 수 없습니다.'), { status: 400 })
    }
    if (!DELEGATE_DIRECTORY.has(delegateUserId)) {
      return HttpResponse.json(errorBody('존재하지 않는 대리자입니다.'), { status: 400 })
    }
  }
  if (typeof body['message'] === 'string' && body['message'].length > MESSAGE_MAX_LENGTH) {
    return HttpResponse.json(errorBody('메시지가 너무 깁니다.'), { status: 400 })
  }

  const next: OooFixture = { userId, startsAt: startsAtRaw, endsAt: endsAtRaw, delegateUserId, message }
  oooStore.set(userId, next)
  return HttpResponse.json(toOooResponse(next, Date.now()))
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/users/me/ooo — 해제(멱등, EC8)
// ─────────────────────────────────────────────────────────────────────────────

const deleteMyOooHandler = http.delete('/api/v1/users/me/ooo', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  oooStore.delete(userId)
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 부재중(Out of Office) BC MSW 핸들러 배열 */
export const oooHandlers = [getMyOooHandler, patchMyOooHandler, deleteMyOooHandler]
