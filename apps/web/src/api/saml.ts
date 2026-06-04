// SAML IdP 목록 조회 API 클라이언트 — GET /api/v1/auth/saml/idps (미인증 허용)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 SamlIdpListResponse DTO와 1:1 정합 (Zod↔DTO drift 차단)
// registrationId: Spring Security SAML2 registration id
// displayName: 로그인 화면 표시 이름
// ─────────────────────────────────────────────────────────────────────────────

/** SAML IdP 단건 스키마 */
export const samlIdpSchema = z.object({
  /** Spring Security SAML2 registration identifier */
  registrationId: z.string(),
  /** 로그인 화면에 표시되는 IdP 이름 */
  displayName: z.string(),
})

/** SAML IdP 목록 응답 래퍼 스키마 — `{ idps: [...] }` */
const samlIdpListResponseSchema = z.object({
  idps: z.array(samlIdpSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** SAML IdP 단건 타입 — z.infer로 자동 추론 */
export type SamlIdp = z.infer<typeof samlIdpSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성화된 SAML IdP 목록을 조회한다.
 *
 * `GET /api/v1/auth/saml/idps` → `{ idps: [...] }` 래퍼를 언래핑해 반환.
 * - 미인증 상태에서 호출 가능 (로그인 화면에서 사용).
 * - 활성화된 IdP만 반환 (백엔드 필터링).
 *
 * @returns 활성 SAML IdP 배열 — 설정된 IdP가 없으면 빈 배열
 * @throws ApiError 서버 오류 시
 */
export async function fetchSamlIdps(): Promise<SamlIdp[]> {
  const wrapped = await apiGet('/api/v1/auth/saml/idps', samlIdpListResponseSchema)
  return wrapped.idps
}
