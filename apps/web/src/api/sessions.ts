// 세션 관리 API 클라이언트 — 활성 세션 목록 조회 + 특정 세션 강제 종료
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — spec §FR-2 필드와 1:1 정합 (NFR-3, learnings 2026-05-22 Zod↔DTO drift 차단)
// deviceFingerprint 미포함 (결정 2, Maxi 확정 2026-05-29)
// ─────────────────────────────────────────────────────────────────────────────

/** 세션 단건 응답 Zod 스키마 — spec §FR-2 정의 7 필드 */
export const sessionSchema = z.object({
  /** 세션 식별자 UUID */
  sid: z.string().uuid(),
  /** 인증 제공자 식별자 (예: "local", "ldap") */
  providerId: z.string(),
  /** 접속 기기의 User-Agent 문자열 — 헤더 부재 로그인 시 null (EC-6) */
  userAgent: z.string().nullable(),
  /** 접속 IP 주소 — 헤더 부재 로그인 시 null (EC-6) */
  ipAddress: z.string().nullable(),
  /** 마지막 활동 시각 (ISO 8601) */
  lastSeenAt: z.string(),
  /** 세션 생성(로그인) 시각 (ISO 8601) */
  createdAt: z.string(),
  /** 현재 요청에 사용 중인 세션 여부 — true면 강제 종료 불가 (결정 1) */
  current: z.boolean(),
})

/** 세션 목록 응답 래퍼 Zod 스키마 — `{ sessions: [...] }` */
const sessionsResponseSchema = z.object({
  sessions: z.array(sessionSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 세션 단건 응답 타입 — z.infer로 자동 추론 */
export type Session = z.infer<typeof sessionSchema>

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 헬퍼 — 쿠키에서 XSRF-TOKEN 값을 읽어 반환
// DELETE 등 상태 변경 요청 시 X-XSRF-TOKEN 헤더로 전달해야 함 (spec §제약 조건)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `document.cookie`에서 XSRF-TOKEN 쿠키 값을 읽는다.
 * 쿠키가 없으면 빈 문자열을 반환.
 * Spring Security가 Set-Cookie로 발급한 XSRF-TOKEN을 읽어
 * X-XSRF-TOKEN 헤더로 재전송하는 double submit cookie 패턴.
 */
export function readXsrfToken(): string {
  const match = document.cookie
    .split('; ')
    .find((row) => row.startsWith('XSRF-TOKEN='))
  return match !== undefined ? (match.split('=')[1] ?? '') : ''
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 인증 사용자의 활성 세션 목록을 조회한다.
 *
 * `GET /api/v1/auth/sessions` → `{ sessions: [...] }` 래퍼를 언래핑해 반환.
 * lastSeenAt DESC 정렬은 백엔드에서 보장 (spec §FR-1).
 * PAT 인증 호출 시 백엔드에서 403을 반환한다 (spec §FR-6b).
 *
 * @returns 활성 세션 배열 — 세션이 없으면 빈 배열 (EC-1)
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) PAT 인증 호출 (session_management_requires_interactive_login)
 */
export async function listSessions(): Promise<Session[]> {
  const wrapped = await apiGet('/api/v1/auth/sessions', sessionsResponseSchema)
  return wrapped.sessions
}

/**
 * 지정한 세션을 강제 종료한다.
 *
 * `DELETE /api/v1/auth/sessions/{sid}` → 204 No Content.
 * - 상태 변경 메서드이므로 CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함 (spec §제약 조건).
 *   Spring이 발급한 XSRF-TOKEN 쿠키를 double submit cookie 패턴으로 재전송.
 * - IDOR 방어: 타인 sid 또는 비활성 sid는 백엔드가 404 반환 (spec §FR-4).
 * - 현재 세션 종료 시도: 백엔드가 409 반환 (spec §FR-5).
 * - PAT 인증 호출: 백엔드가 403 반환 (spec §FR-6b).
 *
 * @param sid 강제 종료할 세션 UUID
 * @returns void — 204 No Content
 * @throws ApiError(404) IDOR / 미존재 / 비활성 sid
 * @throws ApiError(409) 현재 세션 종료 시도 (cannot_revoke_current_session)
 * @throws ApiError(403) PAT 인증 호출
 * @throws ApiError(400) 잘못된 UUID 형식 sid (EC-7)
 * @throws ApiError(401) 미인증
 */
export async function revokeSession(sid: string): Promise<void> {
  const xsrfToken = readXsrfToken()
  const res = await apiFetch(`/api/v1/auth/sessions/${sid}`, {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': xsrfToken,
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
