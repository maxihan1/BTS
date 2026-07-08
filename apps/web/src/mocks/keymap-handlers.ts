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

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 'true'이면 PATCH 요청 본문 검증과 무관하게
 * 409 KEYMAP_CONFLICT를 강제 반환한다(E2E_FORCE_CREATE_FALSE_KEY 선례와 동일한 시나리오
 * 토글 패턴, project-permission-handlers.ts).
 *
 * KeymapForm의 로컬 검증(`detectLocalViolations`)이 서버 `KeymapValidator` 6종 규칙을
 * 1:1 복제하므로, 실제 사용자 흐름으로는 로컬 검증을 통과하면서 서버가 거부하는 body를
 * 만들 수 없다 — 저장 버튼 자체가 로컬 위반이 있으면 비활성화되기 때문이다. 그 결과
 * `KeymapForm`의 서버 409 배너 렌더 경로(`resolveServerConflicts`)는 정상 UI 흐름만으로는
 * 도달 불가능하다(단위 테스트만 `server.use` 오버라이드로 도달 가능, KeymapForm.test.tsx T12).
 * 이 플래그는 그 경로를 실제 네트워크 왕복으로 검증하기 위한 E2E 전용 시나리오 토글이다.
 *
 * MSW 핸들러는 페이지 메인 스레드에서 실행되므로 localStorage 접근이 가능하다 — Playwright
 * `addInitScript`로 goto 전에 플래그를 심으면 첫 PATCH 시점부터 적용된다.
 */
export const LS_KEY_KEYMAP_FORCE_CONFLICT = '__bts_e2e_keymap_force_conflict'

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

/** `conflicts` 배열 원소 하나 — {@link ConflictErrorBody.conflicts}에서 추출 */
type ConflictEntry = ConflictErrorBody['conflicts'][number]

/** 백엔드 `{code, message}` 에러 봉투 생성(preferences/status-handlers.ts 선례와 동일 형식) */
function validationError(message: string): ValidationErrorBody {
  return { code: 'KEYMAP_VALIDATION_FAILED', message }
}

// ── 개별 검증 함수 — 백엔드 KeymapValidator의 6종 규칙과 1:1 대응(Task 10 확장 시 참조 용이) ──

/** 화이트리스트 완비 검사 — action 5종 누락 또는 화이트리스트 밖 action 존재 여부 */
function hasWhitelistViolation(bindings: RawBindingInput[]): boolean {
  const actionSet = new Set(bindings.map((b) => b.action))
  const whitelist: readonly string[] = KEYMAP_ACTIONS
  return KEYMAP_ACTIONS.some((action) => !actionSet.has(action)) || [...actionSet].some((a) => !whitelist.includes(a))
}

/** 빈값(공백 포함) 금지 검사 */
function hasBlankViolation(bindings: RawBindingInput[]): boolean {
  return bindings.some((b) => b.keyCombo.trim().length === 0)
}

/** key_combo 형식 검사 — 빈 값은 {@link hasBlankViolation}이 담당하므로 제외 */
function hasFormatViolation(bindings: RawBindingInput[]): boolean {
  return bindings.some((b) => b.keyCombo.trim().length > 0 && !hasValidFormat(b.keyCombo))
}

/** 완전 중복 검사 — 같은 key_combo를 가진 action이 둘 이상이면 각각 `duplicate` 위반 */
function findDuplicateConflicts(structurallyValid: RawBindingInput[]): ConflictEntry[] {
  const byCombo = new Map<string, string[]>()
  for (const b of structurallyValid) {
    byCombo.set(b.keyCombo, [...(byCombo.get(b.keyCombo) ?? []), b.action])
  }
  return [...byCombo.entries()]
    .filter(([, actions]) => actions.length > 1)
    .map(([keyCombo, actions]) => ({ type: 'duplicate' as const, actions: [...actions].sort(), keyCombo }))
}

/** leader 접두 충돌 검사 — single `g`와 leader(`g X`)가 공존하면 `leaderPrefix` 위반 */
function findLeaderPrefixConflict(structurallyValid: RawBindingInput[]): ConflictEntry | null {
  const singleLeaderKey = structurallyValid.find((b) => triggerOf(b.keyCombo) === 'single' && b.keyCombo === LEADER_KEY)
  const leaderBindings = structurallyValid.filter((b) => triggerOf(b.keyCombo) === 'leader')
  if (singleLeaderKey === undefined || leaderBindings.length === 0) return null
  return {
    type: 'leaderPrefix',
    actions: [singleLeaderKey.action, ...leaderBindings.map((b) => b.action)].sort(),
    keyCombo: null,
  }
}

/** dead leader combo 검사 — continuation 키가 leader 키와 같은 `g g`면 각각 `deadLeader` 위반 */
function findDeadLeaderConflicts(structurallyValid: RawBindingInput[]): ConflictEntry[] {
  return structurallyValid
    .filter((b) => triggerOf(b.keyCombo) === 'leader' && leaderContinuationKey(b.keyCombo) === LEADER_KEY)
    .map((b) => ({ type: 'deadLeader' as const, actions: [b.action], keyCombo: null }))
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
  if (hasWhitelistViolation(bindings) || hasBlankViolation(bindings) || hasFormatViolation(bindings)) {
    return { status: 400, body: validationError('유효하지 않은 단축키 설정입니다.') }
  }

  const structurallyValid = bindings.filter((b) => b.keyCombo.trim().length > 0 && hasValidFormat(b.keyCombo))
  const leaderPrefixConflict = findLeaderPrefixConflict(structurallyValid)
  const conflicts: ConflictEntry[] = [
    ...findDuplicateConflicts(structurallyValid),
    ...(leaderPrefixConflict !== null ? [leaderPrefixConflict] : []),
    ...findDeadLeaderConflicts(structurallyValid),
  ]

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

  // E2E 강제 409 시나리오 — 본문 검증보다 먼저 확인(로컬 검증을 통과한 유효한 body도 강제 거부)
  if (globalThis.localStorage?.getItem(LS_KEY_KEYMAP_FORCE_CONFLICT) === 'true') {
    const forcedConflict: ConflictErrorBody = {
      code: 'KEYMAP_CONFLICT',
      message: '겹치는 단축키가 있습니다.',
      conflicts: [{ type: 'duplicate', actions: ['create-issue', 'search'], keyCombo: 'x' }],
    }
    return HttpResponse.json(forcedConflict, { status: 409 })
  }

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
