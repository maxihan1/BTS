// 백엔드 API fetch wrapper — Authorization 헤더 자동 주입 + Zod 응답 파싱 + 401 인터셉터
/// <reference types="vite/client" />
import type { ZodSchema } from 'zod'
import { useAuthStore } from '@/auth/authStore'
import { WhoamiResponseSchema } from '@/api/schemas'
import type { WhoamiResponse } from '@/api/schemas'

export interface ApiFetchOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  body?: unknown
  headers?: HeadersInit
  /**
   * 요청 취소 신호. 지정하면 abort 시 fetch 가 `AbortError` 로 reject 한다.
   *
   * **선택 필드다** — 기존 호출자는 전량 무변경이고, 취소가 필요한 호출만 넘긴다
   * (`lib/delete-timeout.ts` 의 상한이 첫 소비자다). 401 재시도에도 같은 signal 이 실려
   * 재시도 요청까지 함께 끊긴다.
   */
  signal?: AbortSignal
}

/** 비-2xx 응답 시 throw되는 에러 — status와 응답 body를 포함 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
  ) {
    super(`API ${status}`)
    this.name = 'ApiError'
  }
}

/** 환경 변수에서 base URL 조회 — 미설정 시 빈 문자열 (dev proxy 의존) */
function getBaseUrl(): string {
  return import.meta.env['VITE_API_BASE_URL'] ?? ''
}

/**
 * 진행 중인 refresh 요청을 캐싱하는 전역 Promise.
 * null이면 현재 refresh 중이 아님.
 * 복수 요청이 동시에 401을 받아도 단 1회만 /refresh를 호출하도록 보장 (race lock).
 */
let refreshPromise: Promise<string> | null = null

/** 현재 refresh가 진행 중인지 반환하는 헬퍼 */
export function isRefreshing(): boolean {
  return refreshPromise !== null
}

/**
 * /api/v1/auth/refresh를 호출해 새 access token을 발급받는다.
 * 성공하면 authStore에 토큰을 저장하고 새 토큰을 반환.
 * 실패하면 authStore를 초기화하고 에러를 throw.
 * 이미 진행 중인 refresh가 있으면 그 Promise를 공유해 중복 호출을 방지한다 (race lock).
 */
function doRefresh(): Promise<string> {
  if (refreshPromise === null) {
    refreshPromise = (async () => {
      try {
        const res = await fetch(`${getBaseUrl()}/api/v1/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
        })
        if (!res.ok) {
          useAuthStore.getState().clearSession()
          throw new ApiError(res.status, await res.json().catch(() => ({})))
        }
        const data = (await res.json()) as { access_token: string }
        const newToken = data.access_token
        useAuthStore.getState().setAccessToken(newToken)
        return newToken
      } finally {
        // refresh 완료(성공/실패 모두) 후 lock 해제
        refreshPromise = null
      }
    })()
  }
  return refreshPromise as Promise<string>
}

/**
 * 기본 fetch wrapper.
 * - credentials: 'include' 고정 (refresh_token Cookie 자동 송수신)
 * - **객체** body면 `JSON.stringify` + Content-Type: application/json 자동 설정.
 *   FormData/문자열 body는 직렬화·Content-Type 자동 설정을 모두 건너뛴다(호출자가 Content-Type 지정)
 * - accessToken 있으면 Authorization: Bearer 헤더 자동 추가
 * - 401 응답 시 자동으로 /refresh 호출 후 1회 retry (race lock 포함)
 */
export async function apiFetch(path: string, options: ApiFetchOptions = {}): Promise<Response> {
  const { method = 'GET', body, headers: extraHeaders, signal } = options

  // 절대 URL이면 그대로, 아니면 base URL 앞에 붙임
  const url = path.startsWith('http://') || path.startsWith('https://') ? path : `${getBaseUrl()}${path}`

  // FormData면 브라우저가 multipart/form-data; boundary=... 를 자동 설정하고,
  // 문자열(예: YAML 원문)이면 호출자가 Content-Type을 직접 지정하므로
  // 두 경우 모두 Content-Type 자동 설정과 JSON.stringify 직렬화를 건너뛴다.
  const isRawBody = body instanceof FormData || typeof body === 'string'

  const buildHeaders = (token: string | null): Headers => {
    const headers = new Headers(extraHeaders)
    // body가 있고 raw body(FormData/문자열)가 아닌 경우에만 Content-Type: application/json 자동 추가
    if (body !== undefined && !isRawBody && !headers.has('content-type')) {
      headers.set('Content-Type', 'application/json')
    }
    // accessToken이 있을 때만 Authorization 헤더 추가
    if (token !== null) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    return headers
  }

  const fetchOptions = {
    method,
    credentials: 'include' as const,
    // FormData/문자열이면 그대로 전달, 객체 body면 JSON.stringify
    body: body !== undefined ? (isRawBody ? body : JSON.stringify(body)) : undefined,
    // 미지정이면 undefined 라 fetch 가 취소 없는 요청으로 취급한다(기존 동작 그대로).
    signal,
  }

  const res = await fetch(url, { ...fetchOptions, headers: buildHeaders(useAuthStore.getState().accessToken) })

  // 401이 아니면 그대로 반환
  if (res.status !== 401) {
    return res
  }

  // 401: refresh 시도 (race lock으로 중복 호출 방지)
  // doRefresh 실패 시 clearSession은 doRefresh 내부에서 처리되고 에러가 그대로 전파된다
  const newToken = await doRefresh()
  // 새 토큰으로 원래 요청 1회 retry
  return fetch(url, { ...fetchOptions, headers: buildHeaders(newToken) })
}

/** 응답을 검사하고 ok가 아니면 ApiError, ok면 Zod 스키마로 파싱해 반환 */
async function parseResponse<T>(res: Response, schema: ZodSchema<T>): Promise<T> {
  if (!res.ok) {
    const errorBody = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return schema.parse(data)
}

/**
 * POST 요청 후 Zod 스키마로 응답 파싱.
 * - 비-2xx → ApiError throw
 * - 스키마 불일치 → ZodError throw
 */
export async function apiPost<T>(path: string, body: unknown, schema: ZodSchema<T>): Promise<T> {
  const res = await apiFetch(path, { method: 'POST', body })
  return parseResponse(res, schema)
}

/**
 * GET 요청 후 Zod 스키마로 응답 파싱.
 * - 비-2xx → ApiError throw
 * - 스키마 불일치 → ZodError throw
 */
export async function apiGet<T>(path: string, schema: ZodSchema<T>): Promise<T> {
  const res = await apiFetch(path, { method: 'GET' })
  return parseResponse(res, schema)
}

/**
 * refresh_token 쿠키로 access token을 갱신하고 whoami를 재조회해
 * store의 세션을 최신 클레임으로 업데이트한다 (FR-MF-04 D6-4 게이트 해제).
 *
 * 동작 순서.
 * 1. `doRefresh()`(race lock 포함) — 새 access token 발급 + store에 저장.
 * 2. 새 토큰으로 `GET /api/v1/users/me/whoami` 호출 → WhoamiResponseSchema 파싱.
 * 3. `setSession({ accessToken, user })` — store 갱신.
 * 4. 갱신된 WhoamiResponse 반환.
 *
 * 실패 시 doRefresh 내부의 clearSession이 전파되며 에러를 삼키지 않는다.
 *
 * @returns 갱신된 WhoamiResponse
 */
export async function refreshSession(): Promise<WhoamiResponse> {
  const newToken = await doRefresh()
  const user = await apiGet('/api/v1/users/me/whoami', WhoamiResponseSchema)
  useAuthStore.getState().setSession({ accessToken: newToken, user })
  return user
}
