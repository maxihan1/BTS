// 백엔드 인증 API 요청/응답 Zod 스키마 정의
import { z } from 'zod'

// provider는 GET /api/v1/auth/providers 응답의 id값 — 동적이므로 enum 대신 string.
// 구체 값 검증은 백엔드에서 수행한다.
export const LoginRequestSchema = z.object({
  provider: z.string().min(1),
  username: z.string().min(1),
  password: z.string().min(1),
})

export const TokenResponseSchema = z.object({
  access_token: z.string().min(1),
  token_type: z.literal('Bearer'),
  expires_in: z.number(),
})

export const WhoamiResponseSchema = z.object({
  username: z.string(),
  email: z.string(),
  authMethod: z.string(),
  userId: z.string(),
  mustChangePassword: z.boolean(),
  isSystemAdmin: z.boolean(),
  mfaEnrollmentRequired: z.boolean(),
  displayName: z.string().nullable().optional(),
  avatarUrl: z.string().nullable().optional(),
})

export const ApiErrorResponseSchema = z.object({
  error: z.string(),
})

// ─────────────────────────────────────────────────────────────────────────────
// MFA(TOTP) 관련 스키마 — 백엔드 #113 snake_case 필드명 그대로 사용 (NFR-2)
// ─────────────────────────────────────────────────────────────────────────────

/** TOTP setup 응답 스키마 — POST /api/v1/auth/mfa/totp/setup 200 */
export const MfaSetupResponseSchema = z.object({
  /** otpauth URI — Authenticator 앱 QR 연동용 */
  otpauth_uri: z.string().min(1),
  /** PNG data URI — `<img src={...}>` 로 직접 표시 */
  qr_png_data_uri: z.string().min(1),
  /** Base32 인코딩된 TOTP secret — QR 스캔 불가 환경 수동입력 fallback */
  secret_base32: z.string().min(1),
})

/** TOTP 상태 조회 응답 스키마 — GET /api/v1/auth/mfa/totp 200 */
export const MfaStatusResponseSchema = z.object({
  /** TOTP 활성화 여부 */
  enabled: z.boolean(),
})

/**
 * MFA 챌린지 응답 스키마 — 로그인 200 응답 중 MFA 인증이 필요한 경우.
 * mfa_required: true 가 literal로 고정돼 discriminated union 분기의 기준이 된다.
 */
export const MfaRequiredResponseSchema = z.object({
  mfa_required: z.literal(true),
  /** 5분 단명 챌린지 JWT — POST /api/v1/auth/mfa/verify 에서 사용 */
  mfa_challenge_token: z.string().min(1),
  /** 챌린지 만료 초 (300) */
  expires_in: z.number(),
})

/**
 * 로그인 응답 discriminated union 스키마.
 * mfa_required 필드 존재 여부와 값을 기준으로 분기한다.
 * - mfa_required:true → MfaRequiredResponse (MFA 챌린지 진입)
 * - 없음 → TokenResponse (정식 세션)
 * 분기 순서: mfa_required 우선 (access_token 존재 여부로 분기 금지 — security review CONCERN-union)
 */
export const LoginOrMfaResponseSchema = z.union([
  MfaRequiredResponseSchema,
  TokenResponseSchema,
])

// ─────────────────────────────────────────────────────────────────────────────
// 백업코드 관련 스키마 — 백엔드 #117 snake_case 필드명 그대로 사용
// ─────────────────────────────────────────────────────────────────────────────

/** 백업코드 생성 응답 스키마 — POST /api/v1/auth/mfa/backup-codes 200 */
export const BackupCodesResponseSchema = z.object({
  /** 새로 생성된 백업코드 배열 (10개). 이 화면을 닫으면 다시 볼 수 없다. */
  codes: z.array(z.string().min(1)).min(1),
})

/** 백업코드 상태 조회 응답 스키마 — GET /api/v1/auth/mfa/backup-codes 200 */
export const BackupCodesStatusResponseSchema = z.object({
  /** 백업코드를 한 번이라도 생성했는지 여부 */
  generated: z.boolean(),
  /** 남은(미사용) 백업코드 개수 */
  remaining: z.number(),
})

// ─────────────────────────────────────────────────────────────────────────────
// WebAuthn(보안 키) 관련 스키마 — 백엔드 #129 WebAuthnKeyResponse와 1:1 정합
// NON_NULL 미적용이므로 name/lastUsedAt 키가 응답에 포함됨 → .nullable() 사용
// timestamp는 z.string() 무변환 (sessions.ts 선례 — Instant→Date transform 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 등록된 보안 키 단건 응답 스키마 */
export const WebauthnKeySchema = z.object({
  /** 보안 키 식별자 (UUID) */
  id: z.string().uuid(),
  /** 사용자가 지정한 보안 키 별칭 — 미지정 시 null */
  name: z.string().nullable(),
  /** 보안 키 등록 시각 (ISO 8601) */
  createdAt: z.string(),
  /** 보안 키 마지막 사용 시각 (ISO 8601) — 미사용 시 null */
  lastUsedAt: z.string().nullable(),
})

/** 등록된 보안 키 목록 응답 스키마 — GET /api/v1/auth/mfa/webauthn */
export const WebauthnKeysResponseSchema = z.object({
  keys: z.array(WebauthnKeySchema),
})

export type LoginRequest = z.infer<typeof LoginRequestSchema>
export type TokenResponse = z.infer<typeof TokenResponseSchema>
export type WhoamiResponse = z.infer<typeof WhoamiResponseSchema>
export type ApiErrorResponse = z.infer<typeof ApiErrorResponseSchema>
export type MfaSetupResponse = z.infer<typeof MfaSetupResponseSchema>
export type MfaStatusResponse = z.infer<typeof MfaStatusResponseSchema>
export type MfaRequiredResponse = z.infer<typeof MfaRequiredResponseSchema>
export type LoginOrMfaResponse = z.infer<typeof LoginOrMfaResponseSchema>
export type BackupCodesResponse = z.infer<typeof BackupCodesResponseSchema>
export type BackupCodesStatusResponse = z.infer<typeof BackupCodesStatusResponseSchema>
export type WebauthnKey = z.infer<typeof WebauthnKeySchema>
export type WebauthnKeysResponse = z.infer<typeof WebauthnKeysResponseSchema>
