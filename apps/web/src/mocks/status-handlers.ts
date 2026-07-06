// 사용자 상태 메시지 BC MSW 핸들러 — stateful GET/PATCH + 만료 lazy 필터 (FR-PR-02 Task 6)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'
import { STATUS_FIXTURES } from './status-fixtures'
import type { StatusFixture } from './status-fixtures'
import type { StatusResponse } from '@/api/schemas'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 — userId → StatusFixture Map (msw-derived-behavior-shared-store 선례,
// profile-handlers.ts와 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

function seedStatusStoreMap(): Map<string, StatusFixture> {
  const map = new Map<string, StatusFixture>()
  for (const fixture of STATUS_FIXTURES) {
    map.set(fixture.userId, { ...fixture })
  }
  return map
}

let statusStore: Map<string, StatusFixture> = seedStatusStoreMap()

/**
 * 상태 저장소를 초기 시드 상태(`STATUS_FIXTURES`)로 리셋한다 — 각 테스트 beforeEach에서 호출.
 * 테스트 간 state leak을 방지한다(profile-handlers.resetProfileStore 선례).
 */
export function resetStatusStore(): void {
  statusStore = seedStatusStoreMap()
}

/**
 * 특정 사용자의 상태 store 레코드를 검증 없이 직접 주입한다.
 *
 * PATCH 핸들러는 과거 `expiresAt`을 거부하므로(EC4), 이미 만료된 상태(S6 — lazy 필터
 * 시나리오)는 정상 API 흐름으로 재현할 수 없다. 이 헬퍼로 store에 직접 시드해 GET의
 * 만료 필터 동작만 독립적으로 테스트한다. `resetStatusStore` 호출 이후에 사용해야
 * 시드가 유지된다.
 *
 * @param fixture 주입할 상태 레코드(만료 여부와 무관하게 그대로 저장)
 */
export function seedStatusRecord(fixture: StatusFixture): void {
  statusStore.set(fixture.userId, { ...fixture })
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — Authorization Bearer 토큰 파싱 (profile-handlers.ts와 동일 규약)
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

/** 백엔드 `{code, message}` 에러 봉투 생성 */
function errorBody(code: string, message: string): { code: string; message: string } {
  return { code, message }
}

/** all-null 상태 응답 — EC1(row 없음)/해제/만료 필터 공통. */
function emptyStatusResponse(): StatusResponse {
  return { emoji: null, text: null, expiresAt: null }
}

/** StatusFixture(store 레코드) → {@link StatusResponse} 변환(래퍼 없음, 1:1). */
function toStatusResponse(record: StatusFixture): StatusResponse {
  return { emoji: record.emoji, text: record.text, expiresAt: record.expiresAt }
}

/**
 * 상태 문자열 정규화 — blank(공백만)면 null(스펙 EC3, backend UserStatusService.normalize 미러).
 * 문자열이 아니면(누락/다른 타입) null 취급한다.
 */
function normalize(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length === 0 ? null : trimmed
}

/** 상태가 만료됐는지 판정 — expiresAt이 있고 현재 시각 이하이면 true(S6 lazy 필터). */
function isExpired(record: StatusFixture): boolean {
  if (record.expiresAt === null) return false
  return new Date(record.expiresAt).getTime() <= Date.now()
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/status
// ─────────────────────────────────────────────────────────────────────────────

const getMyStatusHandler = http.get('/api/v1/users/me/status', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const record = statusStore.get(userId)
  if (record === undefined || isExpired(record)) {
    return HttpResponse.json(emptyStatusResponse())
  }

  return HttpResponse.json(toStatusResponse(record))
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/users/me/status — 원자적 교체(replace, 3-state 병합 아님)
// ─────────────────────────────────────────────────────────────────────────────

/** 상태 텍스트 최대 길이 — 스펙 NFR3(Slack 관례) */
const TEXT_MAX_LENGTH = 100
/** 상태 이모지 최대 길이 — 스펙 NFR3(단일 이모지+변형선택자 수용) */
const EMOJI_MAX_LENGTH = 32

const patchMyStatusHandler = http.patch('/api/v1/users/me/status', async ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  let body: Record<string, unknown>
  try {
    body = (await request.json()) as Record<string, unknown>
  } catch {
    return HttpResponse.json(
      errorBody('STATUS_VALIDATION_FAILED', '잘못된 요청 본문입니다.'),
      { status: 400 },
    )
  }

  const emoji = normalize(body['emoji'])
  const text = normalize(body['text'])
  const rawExpiresAt = typeof body['expiresAt'] === 'string' ? (body['expiresAt'] as string) : null

  if (emoji !== null && emoji.length > EMOJI_MAX_LENGTH) {
    return HttpResponse.json(
      errorBody('STATUS_VALIDATION_FAILED', '이모지가 너무 깁니다.'),
      { status: 400 },
    )
  }
  if (text !== null && text.length > TEXT_MAX_LENGTH) {
    return HttpResponse.json(
      errorBody('STATUS_VALIDATION_FAILED', '상태 텍스트가 너무 깁니다.'),
      { status: 400 },
    )
  }
  if (rawExpiresAt !== null) {
    const parsed = new Date(rawExpiresAt)
    if (Number.isNaN(parsed.getTime())) {
      return HttpResponse.json(
        errorBody('STATUS_VALIDATION_FAILED', '만료 시각 형식이 올바르지 않습니다.'),
        { status: 400 },
      )
    }
    if (parsed.getTime() <= Date.now()) {
      return HttpResponse.json(
        errorBody('STATUS_VALIDATION_FAILED', '만료 시각은 미래여야 합니다.'),
        { status: 400 },
      )
    }
  }

  // emoji·text 정규화 결과 둘 다 null → 해제(row 삭제, expiresAt 유무 무관, S5)
  if (emoji === null && text === null) {
    statusStore.set(userId, { userId, emoji: null, text: null, expiresAt: null })
    return HttpResponse.json(emptyStatusResponse())
  }

  const next: StatusFixture = { userId, emoji, text, expiresAt: rawExpiresAt }
  statusStore.set(userId, next)
  return HttpResponse.json(toStatusResponse(next))
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 사용자 상태 메시지 BC MSW 핸들러 배열 */
export const statusHandlers = [getMyStatusHandler, patchMyStatusHandler]
