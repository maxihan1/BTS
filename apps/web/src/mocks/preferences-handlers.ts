// 사용자 환경설정(테마/언어/날짜포맷/시작 페이지) MSW 핸들러 — GET/PATCH stateful, whoami와 동일 객체 공유 (FR-PF-01 Task 9, FR-PF-02 Task 8)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'
import { isTheme } from '@/lib/theme'
import { isDatePreset } from '@/lib/date-preferences'
import { isLocale } from '@/api/preferences'
import type { PreferencesResponse } from '@/api/preferences'
import { isStartPage } from '@/lib/start-page'
import type { WhoamiResponse } from '@/api/schemas'

/**
 * 교훈 반영 — msw-mutation-stateful-refetch.
 *
 * PATCH는 AUTH_USERS[username](= auth-handlers.ts의 whoamiHandler가 `{ ...user }`로 스프레드하는
 * 것과 동일한 객체 참조)의 theme/locale/dateFormat/startPage 필드를 직접 mutate한다. auth-handlers.ts를
 * 건드리지 않고도 whoamiHandler가 갱신값을 자동으로 반영한다 — 값 소유권은 auth-fixtures.ts의
 * AUTH_USERS 객체 하나(단일 진실 출처)에 있다.
 *
 * 이 store는 순수 모듈 상태라 새로고침/page.goto() 하드 네비게이션에는 리셋된다. 하지만 실제
 * 앱은 whoami를 부팅 시 재조회하지 않고 authStore의 sessionStorage 영속(`bts.auth`)에서만
 * user를 복원하므로, 새로고침 후에도 값이 유지되는지는 이 mock과 무관한 진짜 클라이언트 영속
 * 메커니즘으로 검증된다(preferences.spec.ts 참고).
 */

const MOCK_TOKEN_PREFIX = 'mock-access-token-'

/** Authorization Bearer 헤더에서 현재 사용자(AUTH_USERS 원본 참조)를 도출한다(auth-handlers.ts 동일 규약). */
function resolveUserFromRequest(request: Request): WhoamiResponse | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null

  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MOCK_TOKEN_PREFIX)) return null

  const username = token.slice(MOCK_TOKEN_PREFIX.length)
  const user = AUTH_USERS[username]
  return user === undefined ? null : user
}

/**
 * whoami fixture(느슨한 optional string)를 강타입 {@link PreferencesResponse}로 정규화한다.
 * 필드 부재/알 수 없는 값은 서버 기본값(EC1 — theme=system, locale=ko, dateFormat=iso,
 * startPage=dashboards)으로 폴백한다.
 */
function toPreferencesResponse(user: WhoamiResponse): PreferencesResponse {
  const { theme, locale, dateFormat, startPage } = user
  return {
    theme: theme !== undefined && isTheme(theme) ? theme : 'system',
    locale: locale !== undefined && isLocale(locale) ? locale : 'ko',
    dateFormat: dateFormat !== undefined && isDatePreset(dateFormat) ? dateFormat : 'iso',
    startPage: startPage !== undefined && isStartPage(startPage) ? startPage : 'dashboards',
  }
}

/** 백엔드 `{code, message}` 에러 봉투 생성(ooo-handlers.ts 선례와 동일 형식) */
function errorBody(message: string): { code: string; message: string } {
  return { code: 'PREFERENCES_VALIDATION_FAILED', message }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/preferences
// ─────────────────────────────────────────────────────────────────────────────

const getMyPreferencesHandler = http.get('/api/v1/users/me/preferences', ({ request }) => {
  const user = resolveUserFromRequest(request)
  if (user === null) return new HttpResponse(null, { status: 401 })

  return HttpResponse.json(toPreferencesResponse(user))
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/users/me/preferences — 2-state(키 부재=미변경) 부분 수정
// ─────────────────────────────────────────────────────────────────────────────

const patchMyPreferencesHandler = http.patch('/api/v1/users/me/preferences', async ({ request }) => {
  const user = resolveUserFromRequest(request)
  if (user === null) return new HttpResponse(null, { status: 401 })

  let body: Record<string, unknown>
  try {
    body = (await request.json()) as Record<string, unknown>
  } catch {
    return HttpResponse.json(errorBody('잘못된 요청 본문입니다.'), { status: 400 })
  }

  if ('theme' in body) {
    const value = body['theme']
    if (typeof value !== 'string' || !isTheme(value)) {
      return HttpResponse.json(errorBody('지원하지 않는 테마입니다.'), { status: 400 })
    }
    user.theme = value
  }
  if ('locale' in body) {
    const value = body['locale']
    if (typeof value !== 'string' || !isLocale(value)) {
      return HttpResponse.json(errorBody('지원하지 않는 언어입니다.'), { status: 400 })
    }
    user.locale = value
  }
  if ('dateFormat' in body) {
    const value = body['dateFormat']
    if (typeof value !== 'string' || !isDatePreset(value)) {
      return HttpResponse.json(errorBody('지원하지 않는 날짜 형식입니다.'), { status: 400 })
    }
    user.dateFormat = value
  }
  if ('startPage' in body) {
    const value = body['startPage']
    if (typeof value !== 'string' || !isStartPage(value)) {
      return HttpResponse.json(errorBody('지원하지 않는 시작 페이지입니다.'), { status: 400 })
    }
    user.startPage = value
  }

  return HttpResponse.json(toPreferencesResponse(user))
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 사용자 환경설정(테마/언어/날짜포맷) BC MSW 핸들러 배열 */
export const preferencesHandlers = [getMyPreferencesHandler, patchMyPreferencesHandler]
