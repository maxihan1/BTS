// 계정 연결(Account Linking) API 클라이언트 — Zod 스키마 + 타입 + CSRF 수동 전송 함수 (FR-AU-08/08b)
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO와 1:1 정합 (필드 invent 절대 금지)
// AccountLinksController / AccountLinkingController 응답 기준
// @JsonInclude(NON_NULL) 적용 — nullable 필드는 z.nullable()로 처리
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 인증 공급자 유형 Zod 열거형.
 * 백엔드 ProviderType enum 6값과 1:1 정합.
 */
export const providerTypeSchema = z.enum(['LOCAL', 'LDAP', 'SAML', 'OIDC', 'PAT', 'OAUTH'])

/**
 * 계정 연결 단건 응답 Zod 스키마.
 * 백엔드 AccountLinkResponse DTO와 1:1 정합.
 */
export const accountLinkSchema = z.object({
  /** 연결 식별자 UUID */
  id: z.string().uuid(),
  /** 인증 공급자 식별자 UUID */
  providerId: z.string().uuid(),
  /** 인증 공급자 표시 이름 — 공급자 삭제 시 null */
  providerName: z.string().nullable(),
  /** 인증 공급자 유형 — 공급자 삭제 시 null */
  providerType: providerTypeSchema.nullable(),
  /** 공급자 활성화 여부 */
  providerEnabled: z.boolean(),
  /** 외부 식별자 마스킹 값 (예: joh***@example.com) */
  externalSubjectMasked: z.string(),
  /** 연결 생성 시각 (ISO 8601) */
  linkedAt: z.string(),
  /** 이 연결로 마지막 로그인한 시각 (ISO 8601) — 로그인 이력 없으면 null */
  lastLoginAt: z.string().nullable(),
})

/**
 * 계정 연결 목록 응답 Zod 스키마.
 * 백엔드 AccountLinksResponse DTO와 1:1 정합.
 */
export const accountLinksResponseSchema = z.object({
  /** 현재 계정에 연결된 공급자 목록 */
  links: z.array(accountLinkSchema),
  /** 로컬 패스워드 설정 여부 (언링크 시 최소 1 인증수단 보장 검사용) */
  hasLocalPassword: z.boolean(),
})

/**
 * step-up 재인증 응답 Zod 스키마.
 * 백엔드 ReauthResponse DTO와 1:1 정합.
 */
export const reauthResponseSchema = z.object({
  /** step-up 세션 만료 시각 (ISO 8601) */
  stepUpExpiresAt: z.string(),
})

/**
 * SSO 연결/재인증 시작 응답 Zod 스키마.
 * 백엔드 SsoLinkStartResponse DTO와 1:1 정합.
 */
export const ssoLinkStartResponseSchema = z.object({
  /** IdP로 리디렉트할 URL (SAML AuthnRequest 또는 OIDC authorization_endpoint) */
  authorizeUrl: z.string(),
})

/**
 * 연결 가능한 공급자 discriminated union Zod 스키마.
 * 백엔드 LinkableProvider sealed class + @JsonInclude(NON_NULL) 정합.
 * - LDAP: providerId 있음, registrationId 키 없음
 * - SAML/OIDC: registrationId 있음, providerId 키 없음
 */
export const linkableProviderSchema = z.discriminatedUnion('kind', [
  z.object({
    kind: z.literal('LDAP'),
    providerId: z.string().uuid(),
    displayName: z.string(),
  }),
  z.object({
    kind: z.literal('SAML'),
    registrationId: z.string(),
    displayName: z.string(),
  }),
  z.object({
    kind: z.literal('OIDC'),
    registrationId: z.string(),
    displayName: z.string(),
  }),
])

/** 연결 가능한 공급자 목록 응답 래퍼 스키마 — `{ linkable: [...] }` */
const linkableProvidersResponseSchema = z.object({
  linkable: z.array(linkableProviderSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 계정 연결 단건 타입 — z.infer로 자동 추론 */
export type AccountLinkResponse = z.infer<typeof accountLinkSchema>

/** 계정 연결 목록 응답 타입 — z.infer로 자동 추론 */
export type AccountLinksResponse = z.infer<typeof accountLinksResponseSchema>

/** step-up 재인증 응답 타입 — z.infer로 자동 추론 */
export type ReauthResponse = z.infer<typeof reauthResponseSchema>

/** SSO 연결/재인증 시작 응답 타입 — z.infer로 자동 추론 */
export type SsoLinkStartResponse = z.infer<typeof ssoLinkStartResponseSchema>

/** 연결 가능한 공급자 discriminated union 타입 — z.infer로 자동 추론 */
export type LinkableProvider = z.infer<typeof linkableProviderSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 body 타입 (인터페이스 우선)
// ─────────────────────────────────────────────────────────────────────────────

/** reauth 요청 body */
interface ReauthBody {
  method: 'LOCAL' | 'LDAP'
  password: string
  providerId?: string
  username?: string
}

/** linkAccount 요청 body */
interface LinkAccountBody {
  providerId: string
  username: string
  password: string
}

/** ssoLinkStart / ssoReauthStart 공통 요청 body */
interface SsoStartBody {
  registrationId: string
  providerType: 'SAML' | 'OIDC'
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** mutation용 공통 POST 헬퍼 — CSRF 헤더 자동 추가 + 비-2xx 시 ApiError throw */
async function csrfPost<T>(path: string, body: unknown, schema: z.ZodSchema<T>): Promise<T> {
  const res = await apiFetch(path, {
    method: 'POST',
    body,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const data: unknown = await res.json()
  return schema.parse(data)
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 인증 사용자의 계정 연결 목록을 조회한다.
 *
 * `GET /api/v1/auth/account/links` → AccountLinksResponse 직접 반환 (봉투 없음).
 *
 * @returns 계정 연결 목록과 로컬 패스워드 여부
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) PAT 인증 호출
 */
export async function fetchAccountLinks(): Promise<AccountLinksResponse> {
  return apiGet('/api/v1/auth/account/links', accountLinksResponseSchema)
}

/**
 * 현재 계정에 연결 가능한 공급자 목록을 조회하고 linkable 배열을 언래핑해 반환한다.
 *
 * `GET /api/v1/auth/account/linkable-providers` → `{ linkable: [...] }` 언래핑.
 *
 * @returns 연결 가능한 공급자 배열 — 없으면 빈 배열
 * @throws ApiError(401) 미인증
 */
export async function fetchLinkableProviders(): Promise<LinkableProvider[]> {
  const wrapped = await apiGet(
    '/api/v1/auth/account/linkable-providers',
    linkableProvidersResponseSchema,
  )
  return wrapped.linkable
}

/**
 * step-up 재인증을 수행하고 만료 시각을 반환한다.
 *
 * `POST /api/v1/auth/account/reauth` — X-XSRF-TOKEN 헤더 필수.
 * method가 LDAP이면 providerId/username도 전달한다.
 *
 * @param body 재인증 요청 body (method/password 필수, LDAP 시 providerId/username 추가)
 * @returns step-up 세션 만료 시각
 * @throws ApiError(401) 인증 실패 / 미인증
 * @throws ApiError(403) PAT 인증 호출 또는 CSRF 누락
 */
export async function reauth(body: ReauthBody): Promise<ReauthResponse> {
  return csrfPost('/api/v1/auth/account/reauth', body, reauthResponseSchema)
}

/**
 * LDAP 계정을 현재 사용자에게 연결한다.
 *
 * `POST /api/v1/auth/account/links` — X-XSRF-TOKEN 헤더 필수.
 *
 * @param body providerId/username/password
 * @returns 생성된 계정 연결 정보
 * @throws ApiError(401) 미인증 또는 step-up 만료
 * @throws ApiError(403) CSRF 누락 또는 권한 없음
 * @throws ApiError(409) 이미 연결된 공급자
 * @throws ApiError(503) LDAP 서버 연결 불가
 */
export async function linkAccount(body: LinkAccountBody): Promise<AccountLinkResponse> {
  return csrfPost('/api/v1/auth/account/links', body, accountLinkSchema)
}

/**
 * 지정한 계정 연결을 해제한다.
 *
 * `DELETE /api/v1/auth/account/links/{id}` → 204 No Content.
 * X-XSRF-TOKEN 헤더 필수 (상태 변경 메서드).
 *
 * @param id 해제할 연결의 UUID
 * @returns void — 204 No Content
 * @throws ApiError(401) 미인증 또는 step-up 만료
 * @throws ApiError(403) CSRF 누락 또는 권한 없음
 * @throws ApiError(404) 존재하지 않는 연결
 * @throws ApiError(409) 마지막 인증 수단 해제 불가
 */
export async function unlinkAccount(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/auth/account/links/${id}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * SSO 공급자 계정 연결 흐름을 시작한다.
 *
 * `POST /api/v1/auth/account/links/sso/start` — X-XSRF-TOKEN 헤더 필수.
 * 응답의 authorizeUrl로 리디렉트해 IdP 인증을 완료하면 콜백으로 연결이 완성된다.
 *
 * @param body registrationId/providerType
 * @returns IdP 인증 URL
 * @throws ApiError(401) 미인증 또는 step-up 만료
 * @throws ApiError(403) CSRF 누락 또는 권한 없음
 */
export async function ssoLinkStart(body: SsoStartBody): Promise<SsoLinkStartResponse> {
  return csrfPost('/api/v1/auth/account/links/sso/start', body, ssoLinkStartResponseSchema)
}

/**
 * SSO step-up 재인증 흐름을 시작한다.
 *
 * `POST /api/v1/auth/account/reauth/sso/start` — X-XSRF-TOKEN 헤더 필수.
 * mutation이므로 ssoLinkStart와 동일하게 CSRF 면제 없음 (주의).
 *
 * @param body registrationId/providerType
 * @returns IdP 인증 URL
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) CSRF 누락 또는 권한 없음
 */
export async function ssoReauthStart(body: SsoStartBody): Promise<SsoLinkStartResponse> {
  return csrfPost('/api/v1/auth/account/reauth/sso/start', body, ssoLinkStartResponseSchema)
}
