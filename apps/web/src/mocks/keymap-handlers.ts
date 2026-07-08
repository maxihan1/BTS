// 단축키 커스터마이즈 MSW 핸들러 — GET/PATCH stateful, override만 저장(백엔드 UserKeymapService 미러) (FR-PF-03 Task 7)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'
import { KEYMAP_ACTIONS } from '@/api/keymap'
import type { KeymapActionId, KeymapBinding, KeymapConflictType, KeymapResponse, KeymapTrigger } from '@/api/keymap'

/**
 * 교훈 반영 — msw-mutation-stateful-refetch, msw-derived-behavior-shared-store-e2e.
 *
 * `status-handlers.ts` 선례와 동일하게 userId → override(action별 key_combo, 기본값과 다른 것만)
 * Map을 모듈 상태로 둔다. GET은 이 override를 {@link KEYMAP_ACTIONS} 기본값과 병합해 5종 완비
 * effective 키맵을 계산하고, PATCH는 검증 통과 후 override만 갱신한다(백엔드
 * `UserKeymapService.mergeWithDefaults`/`normalizeOverrides` 1:1 미러).
 */

// ─────────────────────────────────────────────────────────────────────────────
// 기본값 — 백엔드 KeymapAction.DEFAULT_BINDINGS 미러 (값을 바꿀 때 백엔드와 함께 갱신)
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_KEY_COMBO: Readonly<Record<KeymapActionId, string>> = {
  help: '?',
  'create-issue': 'c',
  search: '/',
  'goto-my-issues': 'g i',
  'goto-dashboard': 'g d',
}

/** leader 시퀀스를 여는 키 — 백엔드 `LEADER_KEY`와 동일 값 유지가 계약 */
const LEADER_KEY = 'g'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 — userId → override(action별 key_combo, 기본값과 다른 것만)
// ─────────────────────────────────────────────────────────────────────────────

let keymapStore: Map<string, Partial<Record<KeymapActionId, string>>> = new Map()

/** 단축키 override 저장소를 초기 상태(override 없음)로 리셋한다 — 각 테스트 beforeEach에서 호출 */
export function resetKeymapStore(): void {
  keymapStore = new Map()
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — Authorization Bearer 토큰 파싱 (status-handlers.ts와 동일 규약)
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_TOKEN_PREFIX = 'mock-access-token-'

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
// key_combo 형식/발화 방식 파생 — 백엔드 KeymapBinding(action-key_combo 값 객체) 미러
// ─────────────────────────────────────────────────────────────────────────────

/** [keyCombo]를 leader 구분자(공백) 기준으로 나눈 토큰 */
function tokensOf(keyCombo: string): string[] {
  return keyCombo.split(' ')
}

/** single(1글자) 또는 `"g <key>"` leader(공백 1칸, 2토큰) 형식을 만족하는지 검사 */
function hasValidFormat(keyCombo: string): boolean {
  if (keyCombo.trim().length === 0) return false
  const tokens = tokensOf(keyCombo)
  if (tokens.length === 1) return keyCombo.length === 1
  if (tokens.length === 2) return tokens[0] === LEADER_KEY && tokens[1]?.length === 1
  return false
}

/** key_combo 형식에서 발화 방식을 파생한다 — 형식 유효성 자체는 검사하지 않는다 */
function triggerOf(keyCombo: string): KeymapTrigger {
  const tokens = tokensOf(keyCombo)
  return tokens.length === 2 && tokens[0] === LEADER_KEY ? 'leader' : 'single'
}

/** leader continuation 키(두 번째 토큰) — leader가 아니면 null */
function leaderContinuationKey(keyCombo: string): string | null {
  const tokens = tokensOf(keyCombo)
  return triggerOf(keyCombo) === 'leader' ? (tokens[1] ?? null) : null
}

/** override를 {@link DEFAULT_KEY_COMBO}와 병합해 action 5종 완비 effective 키맵을 계산한다 */
function toEffectiveBindings(overrides: Partial<Record<KeymapActionId, string>>): KeymapBinding[] {
  return KEYMAP_ACTIONS.map((action) => {
    const keyCombo = overrides[action] ?? DEFAULT_KEY_COMBO[action]
    return {
      action,
      keyCombo,
      trigger: triggerOf(keyCombo),
      customized: keyCombo !== DEFAULT_KEY_COMBO[action],
    }
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// PATCH 검증 — 백엔드 KeymapValidator.validate 미러 (완비 → 빈값 → 형식 → 중복 → leader접두 → dead-leader)
// ─────────────────────────────────────────────────────────────────────────────

/** 원시 PATCH 요청 바인딩 하나(검증 전, action/keyCombo 문자열) */
interface RawBindingInput {
  action: string
  keyCombo: string
}

/** 400 검증 실패 에러 바디 */
interface ValidationErrorBody {
  code: string
  message: string
}

/** 409 충돌 에러 바디 */
interface ConflictErrorBody extends ValidationErrorBody {
  conflicts: Array<{ type: KeymapConflictType; actions: string[]; keyCombo: string | null }>
}

/** 백엔드 `{code, message}` 에러 봉투 생성(preferences/status-handlers.ts 선례와 동일 형식) */
function validationError(message: string): ValidationErrorBody {
  return { code: 'KEYMAP_VALIDATION_FAILED', message }
}

/**
 * [bindings]를 백엔드 `KeymapValidator` 6종 규칙으로 검증한다.
 *
 * 검증 우선 원칙(백엔드 `UserKeymapService` 미러) — VALIDATION 위반(화이트리스트/형식/빈값)이
 * 하나라도 있으면 400, 그 외 CONFLICT 위반(완전중복/leader 접두/dead-leader)만 있으면 409.
 * 위반이 없으면 null.
 */
function validateBindings(
  bindings: RawBindingInput[],
): { status: 400; body: ValidationErrorBody } | { status: 409; body: ConflictErrorBody } | null {
  const actionSet = new Set(bindings.map((b) => b.action))
  const whitelist: readonly string[] = KEYMAP_ACTIONS
  const missing = KEYMAP_ACTIONS.filter((action) => !actionSet.has(action))
  const unknown = [...actionSet].filter((action) => !whitelist.includes(action))
  const blank = bindings.filter((b) => b.keyCombo.trim().length === 0)
  const malformed = bindings.filter((b) => b.keyCombo.trim().length > 0 && !hasValidFormat(b.keyCombo))

  if (missing.length > 0 || unknown.length > 0 || blank.length > 0 || malformed.length > 0) {
    return { status: 400, body: validationError('유효하지 않은 단축키 설정입니다.') }
  }

  const structurallyValid = bindings.filter((b) => b.keyCombo.trim().length > 0 && hasValidFormat(b.keyCombo))
  const conflicts: ConflictErrorBody['conflicts'] = []

  // 완전 중복 — 같은 key_combo를 가진 action이 둘 이상
  const byCombo = new Map<string, string[]>()
  for (const b of structurallyValid) {
    byCombo.set(b.keyCombo, [...(byCombo.get(b.keyCombo) ?? []), b.action])
  }
  for (const [keyCombo, actions] of byCombo) {
    if (actions.length > 1) {
      conflicts.push({ type: 'duplicate', actions: [...actions].sort(), keyCombo })
    }
  }

  // leader 접두 충돌 — single `g`와 leader(`g X`) 공존
  const singleLeaderKey = structurallyValid.find((b) => triggerOf(b.keyCombo) === 'single' && b.keyCombo === LEADER_KEY)
  const leaderBindings = structurallyValid.filter((b) => triggerOf(b.keyCombo) === 'leader')
  if (singleLeaderKey !== undefined && leaderBindings.length > 0) {
    conflicts.push({
      type: 'leaderPrefix',
      actions: [singleLeaderKey.action, ...leaderBindings.map((b) => b.action)].sort(),
      keyCombo: null,
    })
  }

  // dead leader combo — continuation 키가 leader 키와 같은 `g g`
  for (const b of structurallyValid) {
    if (triggerOf(b.keyCombo) === 'leader' && leaderContinuationKey(b.keyCombo) === LEADER_KEY) {
      conflicts.push({ type: 'deadLeader', actions: [b.action], keyCombo: null })
    }
  }

  if (conflicts.length > 0) {
    return {
      status: 409,
      body: { code: 'KEYMAP_CONFLICT', message: '겹치는 단축키가 있습니다.', conflicts },
    }
  }

  return null
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/keymap
// ─────────────────────────────────────────────────────────────────────────────

const getMyKeymapHandler = http.get('/api/v1/users/me/keymap', ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  const overrides = keymapStore.get(userId) ?? {}
  return HttpResponse.json({ bindings: toEffectiveBindings(overrides) } satisfies KeymapResponse)
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/users/me/keymap — replace-all(action 5종 완비 필수)
// ─────────────────────────────────────────────────────────────────────────────

const patchMyKeymapHandler = http.patch('/api/v1/users/me/keymap', async ({ request }) => {
  const userId = resolveUserIdFromRequest(request)
  if (userId === null) return new HttpResponse(null, { status: 401 })

  let body: Record<string, unknown>
  try {
    body = (await request.json()) as Record<string, unknown>
  } catch {
    return HttpResponse.json(validationError('잘못된 요청 본문입니다.'), { status: 400 })
  }

  const rawBindings = Array.isArray(body['bindings']) ? body['bindings'] : []
  const bindings: RawBindingInput[] = rawBindings
    .filter((b): b is Record<string, unknown> => typeof b === 'object' && b !== null)
    .map((b) => ({
      action: typeof b['action'] === 'string' ? b['action'] : '',
      keyCombo: typeof b['keyCombo'] === 'string' ? b['keyCombo'] : '',
    }))

  const violation = validateBindings(bindings)
  if (violation !== null) {
    return HttpResponse.json(violation.body, { status: violation.status })
  }

  const overrides: Partial<Record<KeymapActionId, string>> = {}
  for (const b of bindings) {
    const action = b.action as KeymapActionId // 화이트리스트 검증 통과 후라 안전
    if (b.keyCombo !== DEFAULT_KEY_COMBO[action]) {
      overrides[action] = b.keyCombo
    }
  }
  keymapStore.set(userId, overrides)

  return HttpResponse.json({ bindings: toEffectiveBindings(overrides) } satisfies KeymapResponse)
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 단축키 커스터마이즈 BC MSW 핸들러 배열 */
export const keymapHandlers = [getMyKeymapHandler, patchMyKeymapHandler]
