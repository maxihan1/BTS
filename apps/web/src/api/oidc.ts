// OIDC provider 목록 조회 API 클라이언트 — GET /api/v1/auth/oidc/providers (미인증 허용)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 OidcProviderResponse DTO와 1:1 정합 (Zod↔DTO drift 차단)
// registrationId: Spring Security OAuth2 registration id
// displayName: 로그인 화면 표시 이름
// ─────────────────────────────────────────────────────────────────────────────

/** OIDC provider 단건 스키마 */
export const oidcProviderSchema = z.object({
  /** Spring Security OAuth2 registration identifier */
  registrationId: z.string(),
  /** 로그인 화면에 표시되는 provider 이름 */
  displayName: z.string(),
})

/** OIDC provider 목록 응답 래퍼 스키마 — `{ providers: [...] }` */
const oidcProviderListResponseSchema = z.object({
  providers: z.array(oidcProviderSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** OIDC provider 단건 타입 — z.infer로 자동 추론 */
export type OidcProvider = z.infer<typeof oidcProviderSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성화된 OIDC provider 목록을 조회한다.
 *
 * `GET /api/v1/auth/oidc/providers` → `{ providers: [...] }` 래퍼를 언래핑해 반환.
 * - 미인증 상태에서 호출 가능 (로그인 화면에서 사용).
 * - 활성화된 provider만 반환 (백엔드 필터링).
 *
 * @returns 활성 OIDC provider 배열 — 설정된 provider가 없으면 빈 배열
 * @throws ApiError 서버 오류 시
 */
export async function fetchOidcProviders(): Promise<OidcProvider[]> {
  const wrapped = await apiGet('/api/v1/auth/oidc/providers', oidcProviderListResponseSchema)
  return wrapped.providers
}
