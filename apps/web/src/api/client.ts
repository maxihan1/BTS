// 백엔드 API fetch wrapper — Authorization 헤더 자동 주입 + Zod 응답 파싱
/// <reference types="vite/client" />
import type { ZodSchema } from 'zod'
import { useAuthStore } from '@/auth/authStore'

export interface ApiFetchOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  body?: unknown
  headers?: HeadersInit
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
 * 기본 fetch wrapper.
 * - credentials: 'include' 고정 (refresh_token Cookie 자동 송수신)
 * - body 있으면 Content-Type: application/json 자동 설정
 * - accessToken 있으면 Authorization: Bearer 헤더 자동 추가
 */
export async function apiFetch(path: string, options: ApiFetchOptions = {}): Promise<Response> {
  const { method = 'GET', body, headers: extraHeaders } = options

  // 절대 URL이면 그대로, 아니면 base URL 앞에 붙임
  const url = path.startsWith('http://') || path.startsWith('https://') ? path : `${getBaseUrl()}${path}`

  const headers = new Headers(extraHeaders)

  // body가 있고 Content-Type이 아직 미설정인 경우에만 자동 추가
  if (body !== undefined && !headers.has('content-type')) {
    headers.set('Content-Type', 'application/json')
  }

  // accessToken이 있을 때만 Authorization 헤더 추가
  const accessToken = useAuthStore.getState().accessToken
  if (accessToken !== null) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  return fetch(url, {
    method,
    credentials: 'include',
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
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
