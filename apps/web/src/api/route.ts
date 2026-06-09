// 도메인 기반 인증 라우팅 API 클라이언트 — GET /api/v1/auth/route?domain={domain} (FR-AU-07)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 RouteResponse DTO와 1:1 정합 (Zod↔DTO drift 차단)
// matched:true  → type, registrationId, displayName 필드 존재
// matched:false → 추가 필드 없음
// discriminated union으로 타입 좁히기(type narrowing) 가능
// ─────────────────────────────────────────────────────────────────────────────

/** 도메인 매칭 성공 스키마 — SAML 또는 OIDC provider 정보 포함 */
const routeMatchSchema = z.object({
  matched: z.literal(true),
  /** 인증 프로토콜 타입 */
  type: z.enum(['SAML', 'OIDC']),
  /** Spring Security registration identifier */
  registrationId: z.string(),
  /** 로그인 화면에 표시되는 provider 이름 */
  displayName: z.string(),
})

/** 도메인 매칭 실패 스키마 — 추가 정보 없음 */
const routeNoMatchSchema = z.object({
  matched: z.literal(false),
})

/**
 * 도메인 라우팅 결과 스키마 — discriminated union.
 *
 * matched 필드로 두 케이스를 구분한다.
 * TypeScript if (result.matched) 가드로 자동으로 타입이 좁혀진다.
 */
export const routeResultSchema = z.discriminatedUnion('matched', [
  routeMatchSchema,
  routeNoMatchSchema,
])

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 도메인 매칭 성공 타입 — z.infer로 자동 추론 */
export type RouteMatch = z.infer<typeof routeMatchSchema>

/** 도메인 라우팅 결과 타입 — matched:true | matched:false */
export type RouteResult = z.infer<typeof routeResultSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이메일 도메인 기반으로 인증 provider를 조회한다.
 *
 * `GET /api/v1/auth/route?domain={domain}` — 미인증 상태에서 호출 가능 (로그인 화면).
 *
 * 결과가 `matched:true`이면 해당 SAML/OIDC provider로 리디렉션할 수 있다.
 * `matched:false`이면 LOCAL/LDAP 폼 로그인으로 폴백해야 한다.
 *
 * @param domain - 조회할 이메일 도메인 (예: "partner.com")
 * @returns 라우팅 결과 — 매칭 시 provider 정보 포함, 미매칭 시 `{matched: false}`
 * @throws ApiError 서버 오류 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function fetchRoute(domain: string): Promise<RouteResult> {
  const path = `/api/v1/auth/route?domain=${encodeURIComponent(domain)}`
  return apiGet(path, routeResultSchema)
}
