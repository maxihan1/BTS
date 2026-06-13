// 신뢰 디바이스 목록 조회 및 취소 API 클라이언트.
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 GET /api/v1/auth/mfa/trusted-devices 응답 DTO와 1:1 정합
// ─────────────────────────────────────────────────────────────────────────────

/** 신뢰 디바이스 단건 응답 Zod 스키마 */
export const trustedDeviceSchema = z.object({
  /** 신뢰 디바이스 식별자 UUID */
  id: z.string().uuid(),
  /** User-Agent 파생 라벨 — 헤더 부재 등록 시 null (최대 256자, 백엔드 cap) */
  label: z.string().nullable(),
  /** 디바이스 등록 시각 (ISO Instant) */
  createdAt: z.string(),
  /** 마지막 사용 시각 (ISO Instant) — 등록 후 미사용 시 null */
  lastUsedAt: z.string().nullable(),
  /** 만료 시각 (ISO Instant) — 등록 시각 + 30일 고정 */
  expiresAt: z.string(),
})

/** 신뢰 디바이스 목록 응답 래퍼 Zod 스키마 — `{ devices: [...] }` */
const trustedDevicesResponseSchema = z.object({
  devices: z.array(trustedDeviceSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 신뢰 디바이스 단건 응답 타입 — z.infer로 자동 추론 */
export type TrustedDevice = z.infer<typeof trustedDeviceSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 인증 사용자의 신뢰 디바이스 목록을 조회한다.
 *
 * `GET /api/v1/auth/mfa/trusted-devices` → `{ devices: [...] }` 래퍼를 언래핑해 반환.
 * verify와 달리 apiFetch/apiGet 사용 — 인증 세션 관리.
 *
 * @returns 신뢰 디바이스 배열 — 등록 디바이스가 없으면 빈 배열
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) PAT 인증 호출 (session_management_requires_interactive_login)
 */
export async function listTrustedDevices(): Promise<TrustedDevice[]> {
  const wrapped = await apiGet('/api/v1/auth/mfa/trusted-devices', trustedDevicesResponseSchema)
  return wrapped.devices
}

/**
 * 지정한 신뢰 디바이스를 취소(삭제)한다.
 *
 * `DELETE /api/v1/auth/mfa/trusted-devices/{id}` → 204 No Content.
 * - 상태 변경 메서드이므로 CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함.
 *   Spring이 발급한 XSRF-TOKEN 쿠키를 double submit cookie 패턴으로 재전송.
 * - IDOR 방어: 타인 id 또는 미존재 id는 백엔드가 404 반환.
 * - PAT 인증 호출: 백엔드가 403 반환.
 * - verify와 달리 apiFetch/apiGet 사용 — 인증 세션 관리.
 *
 * @param id 취소할 신뢰 디바이스 UUID
 * @returns void — 204 No Content
 * @throws ApiError(404) IDOR / 미존재 id (not_found)
 * @throws ApiError(403) PAT 인증 호출 (session_management_requires_interactive_login)
 * @throws ApiError(401) 미인증
 */
export async function revokeTrustedDevice(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/auth/mfa/trusted-devices/${id}`, {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 현재 인증 사용자의 모든 신뢰 디바이스를 일괄 취소한다.
 *
 * `DELETE /api/v1/auth/mfa/trusted-devices` → 204 No Content.
 * - 등록 디바이스가 없어도 204를 반환하는 멱등 동작.
 * - 상태 변경 메서드이므로 CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함.
 * - PAT 인증 호출: 백엔드가 403 반환.
 * - verify와 달리 apiFetch/apiGet 사용 — 인증 세션 관리.
 *
 * @returns void — 204 No Content
 * @throws ApiError(403) PAT 인증 호출 (session_management_requires_interactive_login)
 * @throws ApiError(401) 미인증
 */
export async function revokeAllTrustedDevices(): Promise<void> {
  const res = await apiFetch('/api/v1/auth/mfa/trusted-devices', {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
