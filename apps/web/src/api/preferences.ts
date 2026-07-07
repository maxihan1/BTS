// identity-access 사용자 환경설정(테마/언어/날짜포맷) REST API 클라이언트 — FR-PF-01
import { z } from 'zod'
import { useMutation } from '@tanstack/react-query'
import type { UseMutationResult } from '@tanstack/react-query'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'
import { refreshWhoami } from './useProfile'
import { DATE_PRESETS } from '@/lib/date-preferences'
import type { DatePreset } from '@/lib/date-preferences'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend PreferencesResponse DTO 직렬화 형태와 1:1 대응(PreferencesController.kt).
// ─────────────────────────────────────────────────────────────────────────────

/** 지원 테마 값 3종 — 스펙 §지원 값 */
const THEMES = ['light', 'dark', 'system'] as const

/** {@link THEMES} 중 하나 */
export type Theme = (typeof THEMES)[number]

/** 지원 언어(locale) 값 2종 — 스펙 §지원 값 (UI 번역은 후속 범위, 저장 + `<html lang>`만 반영) */
const LOCALES = ['ko', 'en'] as const

/** {@link LOCALES} 중 하나 */
export type Locale = (typeof LOCALES)[number]

/**
 * 값이 지원하는 {@link Locale}인지 판별하는 타입 가드.
 * whoami 등 외부에서 온 느슨한 string 값을 안전하게 좁힐 때 사용한다
 * (`lib/theme.ts`의 isTheme·`lib/date-preferences.ts`의 isDatePreset 선례와 동일한 패턴).
 *
 * @param value 검사할 문자열
 * @returns Locale 여부
 */
export function isLocale(value: string): value is Locale {
  return (LOCALES as readonly string[]).includes(value)
}

/**
 * 사용자 환경설정 응답 Zod 스키마.
 * `GET`/`PATCH /api/v1/users/me/preferences` 응답 형태 — 래퍼 없음.
 */
export const preferencesSchema = z.object({
  theme: z.enum(THEMES),
  locale: z.enum(LOCALES),
  dateFormat: z.enum(DATE_PRESETS),
})

/** 사용자 환경설정 응답 타입 — Zod 스키마에서 추론 */
export type PreferencesResponse = z.infer<typeof preferencesSchema>

/**
 * 비-2xx 응답이면 body를 파싱해 {@link ApiError}를 throw한다.
 * status.ts/ooo.ts throwIfNotOk 선례(같은 BC 관례)와 동일한 패턴.
 */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// PATCH 요청 바디 타입 — 2-state (부재=미변경, 제공=변경)
// eng-review E-4: enum 필드는 null-delete 상태가 없으므로 profile의 3-state(JsonNode)
// 과설계 없이 부재/제공 2-state로 충분하다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 환경설정 PATCH 요청 바디.
 * 백엔드 `PreferencesPatchRequest`(2-state)와 정합 — 키 부재=미변경.
 */
export interface PreferencesPatchBody {
  theme?: Theme
  locale?: Locale
  dateFormat?: DatePreset
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 본인 환경설정을 조회한다.
 *
 * `GET /api/v1/users/me/preferences` → 200 {@link PreferencesResponse}.
 * preferences 행이 없으면 서버가 기본값(theme=system, locale=ko, dateFormat=iso)을 반환한다
 * (EC1, 서버 lazy upsert — 첫 PATCH 시 행 생성).
 *
 * @returns 현재 유효 환경설정
 * @throws ApiError(401) 미인증
 */
export async function getPreferences(): Promise<PreferencesResponse> {
  return apiGet('/api/v1/users/me/preferences', preferencesSchema)
}

/**
 * 본인 환경설정을 부분 수정한다(제공된 필드만, upsert).
 *
 * `PATCH /api/v1/users/me/preferences` → 200 갱신 후 {@link PreferencesResponse}.
 * X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다(status.ts/ooo.ts 선례,
 * double submit cookie 패턴 — 같은 BC 관례를 따른다).
 *
 * @param body 변경할 필드만 담은 2-state PATCH 바디
 * @returns 갱신 후 환경설정
 * @throws ApiError(400) 잘못된 enum 값
 * @throws ApiError(401) 미인증
 */
export async function patchPreferences(body: PreferencesPatchBody): Promise<PreferencesResponse> {
  const res = await apiFetch('/api/v1/users/me/preferences', {
    method: 'PATCH',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
  return preferencesSchema.parse(await res.json())
}

// ─────────────────────────────────────────────────────────────────────────────
// mutation 훅 — PATCH 성공 시 whoami 재조회로 authStore 갱신 (Task 7)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 환경설정 PATCH mutation 훅.
 *
 * 성공 시 {@link refreshWhoami}(`api/useProfile.ts` 선례)로 authStore.user의
 * theme/locale/dateFormat을 최신화한다 — `PreferencesProvider`가 이 값을 구독해 테마/
 * `<html lang>`을 즉시 재적용한다. PATCH 응답을 캐시에 직접 덮어쓰지 않고 별도 GET으로
 * 재조회하는 이유는 프로필/상태/부재중 mutation과 동일하다(invalidate-only 원칙,
 * memory: mutation-setquerydata-partial-response-flicker).
 *
 * @returns TanStack Query `useMutation` 결과 — `mutate(body)`로 실행
 */
export function usePreferencesMutation(): UseMutationResult<
  PreferencesResponse,
  ApiError,
  PreferencesPatchBody
> {
  return useMutation<PreferencesResponse, ApiError, PreferencesPatchBody>({
    mutationFn: patchPreferences,
    onSuccess: () => refreshWhoami(),
  })
}
