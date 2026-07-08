// identity-access 사용자 단축키 커스터마이즈 REST API 클라이언트 — FR-PF-03
import { z } from 'zod'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { UseQueryResult, UseMutationResult } from '@tanstack/react-query'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend KeymapResponse/KeymapConflictView DTO 직렬화 형태와 1:1 대응(KeymapController.kt).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스터마이즈 가능한 단축키 action 화이트리스트 5종 — 백엔드 `KeymapAction` 미러.
 * `mocks/keymap-handlers.ts`가 이 배열을 그대로 순회해 5종 완비 응답을 만든다(중복 정의 방지).
 */
export const KEYMAP_ACTIONS = [
  'help',
  'create-issue',
  'search',
  'goto-my-issues',
  'goto-dashboard',
] as const

/** {@link KEYMAP_ACTIONS} 중 하나 */
export type KeymapActionId = (typeof KEYMAP_ACTIONS)[number]

/** key_combo 발화 방식 2종 — 백엔드 `KeymapTrigger` 소문자 미러 */
const KEYMAP_TRIGGERS = ['single', 'leader'] as const

/** {@link KEYMAP_TRIGGERS} 중 하나 */
export type KeymapTrigger = (typeof KEYMAP_TRIGGERS)[number]

/**
 * 단축키 바인딩 하나의 응답 Zod 스키마.
 * `KeymapBindingView`(action/keyCombo/trigger/customized) 1:1 대응.
 */
export const keymapBindingSchema = z.object({
  action: z.enum(KEYMAP_ACTIONS),
  keyCombo: z.string(),
  trigger: z.enum(KEYMAP_TRIGGERS),
  customized: z.boolean(),
})

/** 단축키 바인딩 응답 타입 — Zod 스키마에서 추론 */
export type KeymapBinding = z.infer<typeof keymapBindingSchema>

/**
 * 단축키 커스터마이즈 응답 Zod 스키마.
 * `GET`/`PATCH /api/v1/users/me/keymap` 응답 형태 — 래퍼 없음, action 5종 완비.
 */
export const keymapResponseSchema = z.object({
  bindings: z.array(keymapBindingSchema),
})

/** 단축키 커스터마이즈 응답 타입 — Zod 스키마에서 추론 */
export type KeymapResponse = z.infer<typeof keymapResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 409 KEYMAP_CONFLICT 에러 바디 Zod 스키마
// `KeymapConflictView`(type/actions/keyCombo) 1:1 대응 — 완전중복(duplicate)만 keyCombo present.
// ─────────────────────────────────────────────────────────────────────────────

/** 충돌 위반 종류 3종 — 백엔드 `KeymapViolation` camelCase 미러 */
const KEYMAP_CONFLICT_TYPES = ['duplicate', 'leaderPrefix', 'deadLeader'] as const

/** {@link KEYMAP_CONFLICT_TYPES} 중 하나 */
export type KeymapConflictType = (typeof KEYMAP_CONFLICT_TYPES)[number]

/**
 * 409 응답의 `conflicts` 배열 원소 하나 Zod 스키마.
 *
 * `keyCombo`는 `type==="duplicate"`일 때만 겹치는 key_combo 값을 갖고, 그 외에는 `null`이다.
 * 백엔드 `KeymapConflictView`(`KeymapController.kt`)는 `@JsonInclude(NON_NULL)`이 없는 평범한
 * data class라 Jackson 기본 동작상 `keyCombo=null`이어도 필드 자체는 항상 포함된다 — 그래서
 * `.optional()`이 아니라 `.nullable()`을 쓴다(필드 부재가 아니라 값이 null).
 */
export const keymapConflictSchema = z.object({
  type: z.enum(KEYMAP_CONFLICT_TYPES),
  actions: z.array(z.string()),
  keyCombo: z.string().nullable(),
})

/** 충돌 위반 하나 타입 — Zod 스키마에서 추론 */
export type KeymapConflict = z.infer<typeof keymapConflictSchema>

/**
 * 409 `KEYMAP_CONFLICT` 에러 바디 전체 Zod 스키마.
 * 호출측이 {@link ApiError.body}를 `keymapConflictErrorSchema.safeParse`로 안전하게 좁혀
 * `conflicts` 배열을 UI에 노출할 때 사용한다(Task 9).
 */
export const keymapConflictErrorSchema = z.object({
  code: z.string(),
  message: z.string(),
  conflicts: z.array(keymapConflictSchema),
})

/** 409 충돌 에러 바디 타입 — Zod 스키마에서 추론 */
export type KeymapConflictError = z.infer<typeof keymapConflictErrorSchema>

/**
 * 비-2xx 응답이면 body를 파싱해 {@link ApiError}를 throw한다.
 * preferences.ts/status.ts/ooo.ts throwIfNotOk 선례(같은 BC 관례)와 동일한 패턴.
 */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// PATCH 요청 바디 타입 — replace-all (2-state 아님, action 5종 완비 필수)
// ─────────────────────────────────────────────────────────────────────────────

/** PATCH 요청의 바인딩 입력 하나 — 백엔드 `KeymapBindingInput` 1:1 대응 */
export interface KeymapBindingInput {
  action: KeymapActionId
  keyCombo: string
}

/**
 * 단축키 커스터마이즈 PATCH 요청 바디.
 * 백엔드 `KeymapPatchRequest`(replace-all)와 정합 — action 5종 완비 필수, 하나라도 빠지면
 * 서버가 화이트리스트 위반(400)으로 판정한다.
 */
export interface KeymapPatchBody {
  bindings: KeymapBindingInput[]
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 본인 effective 단축키 커스터마이즈를 조회한다(override 병합, action 5종 완비).
 *
 * `GET /api/v1/users/me/keymap` → 200 {@link KeymapResponse}.
 *
 * @returns 현재 유효 단축키 커스터마이즈
 * @throws ApiError(401) 미인증
 */
export async function getKeymap(): Promise<KeymapResponse> {
  return apiGet('/api/v1/users/me/keymap', keymapResponseSchema)
}

/**
 * 본인 단축키 커스터마이즈를 replace-all로 수정한다(action 5종 완비 필수).
 *
 * `PATCH /api/v1/users/me/keymap` → 200 갱신 후 {@link KeymapResponse}.
 * X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다(status.ts/ooo.ts/preferences.ts 선례,
 * double submit cookie 패턴 — 같은 BC 관례를 따른다).
 *
 * @param body action 5종 완비 replace-all PATCH 바디
 * @returns 갱신 후 단축키 커스터마이즈
 * @throws ApiError(400) 화이트리스트/형식/빈값 위반(`KEYMAP_VALIDATION_FAILED`)
 * @throws ApiError(409) 완전중복/leader 접두/dead-leader 충돌(`KEYMAP_CONFLICT`, `conflicts` 포함)
 * @throws ApiError(401) 미인증
 */
export async function patchKeymap(body: KeymapPatchBody): Promise<KeymapResponse> {
  const res = await apiFetch('/api/v1/users/me/keymap', {
    method: 'PATCH',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
  return keymapResponseSchema.parse(await res.json())
}

// ─────────────────────────────────────────────────────────────────────────────
// react-query 훅 — queryKey 상수 한 곳에서 관리해 오타·drift 방지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 단축키 커스터마이즈 조회 TanStack Query 캐시 키.
 * mutation onSuccess의 invalidateQueries가 이 키를 그대로 참조한다.
 */
export const KEYMAP_QUERY_KEY = ['keymap', 'me'] as const

/** {@link useKeymap} 옵션 타입 */
export interface UseKeymapOptions {
  /** false이면 쿼리를 idle 상태로 유지해 fetch를 지연한다(비로그인 가드). 기본값 true. */
  enabled?: boolean
}

/**
 * 본인 effective 단축키 커스터마이즈 조회 훅.
 *
 * `GET /api/v1/users/me/keymap` → {@link KeymapResponse}.
 *
 * @param options 쿼리 옵션 — `enabled: false`이면 즉시 fetch하지 않음(비로그인 부트 가드, Task 8)
 * @returns TanStack Query `useQuery` 결과
 */
export function useKeymap(options?: UseKeymapOptions): UseQueryResult<KeymapResponse, ApiError> {
  return useQuery<KeymapResponse, ApiError>({
    queryKey: KEYMAP_QUERY_KEY,
    queryFn: getKeymap,
    enabled: options?.enabled ?? true,
  })
}

/**
 * 단축키 커스터마이즈 replace-all PATCH mutation 훅.
 *
 * 성공 시 {@link KEYMAP_QUERY_KEY} 쿼리를 invalidate해 재조회를 트리거한다(mutation은
 * invalidate-only — 응답으로 캐시를 직접 덮어쓰지 않는다, memory:
 * mutation-setquerydata-partial-response-flicker).
 *
 * @returns TanStack Query `useMutation` 결과 — `mutate(body)`로 실행
 */
export function useUpdateKeymap(): UseMutationResult<KeymapResponse, ApiError, KeymapPatchBody> {
  const queryClient = useQueryClient()

  return useMutation<KeymapResponse, ApiError, KeymapPatchBody>({
    mutationFn: patchKeymap,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: KEYMAP_QUERY_KEY }),
  })
}
