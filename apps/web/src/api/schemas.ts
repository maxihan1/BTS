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
  /**
   * 표시 이름 — FR-PR-01 user_profiles.display_name 그대로 노출. PAT(개인용 액세스 토큰) 분기는
   * 대화형 UI 대상이 아니라 null(기존 username="" 관례와 동일, backend WhoamiController 참고).
   * `.nullable().optional()`인 이유 — 키 부재도 허용해야 하기 때문. authMethod: 를 참조하는
   * 인라인 whoami mock이 ~37개 파일에 산재돼 있어 required로 강화하면 전부 z.parse 실패로 깨진다
   * (zod-schema-strengthen-inline-mock-fanout 사고 재발 방지). 실 백엔드는 항상 키를 포함해 응답한다.
   */
  displayName: z.string().nullable().optional(),
  /**
   * 아바타 조회 URL — 저장값이 아니라 avatar_object_key 존재 여부에서 파생된 `/api/v1/users/{userId}/avatar`.
   * PAT 분기는 displayName과 동일하게 null. STATELESS JWT라 `<img src>` 직접 사용 금지 —
   * apiFetch로 blob 인증 fetch 후 objectURL로 렌더한다(AttachmentPreviewModal 선례).
   * optional 사유는 displayName과 동일(인라인 mock blast-radius 회피).
   */
  avatarUrl: z.string().nullable().optional(),
  /**
   * 상태 이모지 — FR-PR-02 `user_statuses.emoji` 노출(활성 상태만, 만료/미설정/PAT는 null).
   * `.nullable().optional()`인 이유 — 키 부재도 허용해야 하기 때문. authMethod: 를 참조하는
   * 인라인 whoami mock이 다수 파일에 산재돼 있어 required로 강화하면 전부 z.parse 실패로 깨진다
   * (zod-schema-strengthen-inline-mock-fanout 사고 재발 방지, FR-PR-01 displayName/avatarUrl 선례).
   * 실 백엔드는 항상 키를 포함해 응답한다.
   */
  statusEmoji: z.string().nullable().optional(),
  /**
   * 상태 텍스트 — FR-PR-02 `user_statuses.text` 노출(활성 상태만, 만료/미설정/PAT는 null).
   * optional 사유는 statusEmoji와 동일(인라인 mock blast-radius 회피).
   */
  statusText: z.string().nullable().optional(),
  /**
   * 부재중(Out of Office) 활성 여부 — FR-PR-03 `user_ooo` 노출(활성 판정 `startsAt<=now<endsAt`,
   * 미설정/종료/미래예약이면 false, PAT 분기도 항상 false).
   * `.optional()`인 이유 — 키 부재도 허용해야 하기 때문. authMethod:를 참조하는 인라인 whoami mock이
   * 다수 파일에 산재돼 있어 required로 강화하면 전부 z.parse 실패로 깨진다
   * (zod-schema-strengthen-inline-mock-fanout 사고 재발 방지, FR-PR-01/02 displayName/statusEmoji 선례).
   * 실 백엔드는 항상 키를 포함해 응답한다.
   */
  oooActive: z.boolean().optional(),
  /**
   * 부재중 종료 시각(ISO-8601 문자열) — FR-PR-03. `oooActive`가 true일 때만 값을 갖고 그 외는 null이다.
   * optional 사유는 oooActive와 동일(인라인 mock blast-radius 회피).
   */
  oooUntil: z.string().nullable().optional(),
  /**
   * 테마 설정 — FR-PF-01 `user_preferences.theme` 노출(백엔드 view-layer는 plain String,
   * 프론트 강한 enum 검증은 `api/preferences.ts`의 `preferencesSchema`가 담당).
   * `.optional()`인 이유 — 키 부재도 허용해야 하기 때문. authMethod:를 참조하는 인라인 whoami
   * mock이 다수 파일에 산재돼 있어 required로 강화하면 전부 z.parse 실패로 깨진다
   * (zod-schema-strengthen-inline-mock-fanout 사고 재발 방지, FR-PR-01~03 displayName/statusEmoji/
   * oooActive 선례와 동일한 패턴). ⚠️ `.default()`는 z.infer 출력 타입에서 필드를 non-optional로
   * 만들어 기존 리터럴 whoami mock ~40개 파일이 "필드 누락" 컴파일 에러로 깨지는 것을 실측 확인
   * (tsc 81 errors) — 그래서 `.default()` 대신 다른 FR-PR 필드와 동일하게 `.optional()`을 쓴다.
   * 미인증/부재 시 폴백은 소비측(`useDateFormat` 훅의 `isDatePreset` 가드)이 담당한다.
   * 실 백엔드는 항상 키를 포함해 응답한다.
   */
  theme: z.string().optional(),
  /**
   * 언어(locale) 설정 — FR-PF-01 `user_preferences.locale` 노출. UI 문자열 번역(i18next)은
   * 이번 범위 밖이며 저장 값 + `<html lang>` 반영에만 쓰인다. optional 사유는 theme과 동일
   * (인라인 mock blast-radius 회피).
   */
  locale: z.string().optional(),
  /**
   * 날짜 표시 프리셋 — FR-PF-01 `user_preferences.date_format` 노출(iso/kr/us/eu).
   * optional 사유는 theme과 동일(인라인 mock blast-radius 회피).
   */
  dateFormat: z.string().optional(),
  /**
   * 시작 페이지 설정 — FR-PF-02 `user_preferences.start_page` 노출(로그인 후 자동 라우팅 목적지의
   * 논리 키, 백엔드 view-layer는 plain String). 프론트 강한 화이트리스트 검증은 `lib/start-page.ts`의
   * `isStartPage`/`resolveStartPageNav`가 담당(오픈 리다이렉트 차단, 화이트리스트 밖 값은 `/dashboards`
   * 폴백). `.optional()`인 이유 — 키 부재도 허용해야 하기 때문. authMethod:를 참조하는 인라인 whoami
   * mock이 다수 파일에 산재돼 있어 required로 강화하면 전부 z.parse 실패로 깨진다
   * (zod-schema-strengthen-inline-mock-fanout 사고 재발 방지, FR-PF-01 theme/locale/dateFormat 선례와
   * 동일한 패턴 — `.default()`는 z.infer 출력을 non-optional화해 기존 mock을 깨뜨리므로 금지).
   * 실 백엔드는 항상 키를 포함해 응답한다.
   */
  startPage: z.string().optional(),
  /**
   * 프로젝트 생성 권한 보유 여부 — FR-PJ-01/FR-PM-10 `SystemPermissionResolver.hasGlobalPermission
   * (userId, CREATE_PROJECT)` 판정 결과 노출(백엔드 WhoamiController.kt 참고). PAT 분기는 조회 없이
   * false 고정. `.optional()`인 이유 — 키 부재도 허용해야 하기 때문. authMethod:를 참조하는 인라인
   * whoami mock이 ~40개 파일에 산재돼 있어 required로 강화하면 전부 z.parse 실패로 깨진다
   * (zod-schema-strengthen-inline-mock-fanout 사고 재발 방지, FR-PF-01/02/03 선례와 동일한 패턴).
   * 부재(undefined) 시 소비측은 false로 취급한다(생성 버튼 숨김, 안전한 기본값).
   * 실 백엔드는 항상 키를 포함해 응답한다.
   */
  canCreateProject: z.boolean().optional(),
})

/**
 * 사용자 상태 메시지(이모지+텍스트+만료) 응답 Zod 스키마 — FR-PR-02.
 * `GET`/`PATCH /api/v1/users/me/status` 응답 형태 — 래퍼 없음.
 * 미설정/만료/해제 시 세 필드 모두 null(강제 생성 안 함, backend UserStatusController 참고).
 */
export const statusResponseSchema = z.object({
  /** 상태 이모지 — 미설정/만료 시 null */
  emoji: z.string().nullable(),
  /** 상태 텍스트 — 미설정/만료 시 null */
  text: z.string().nullable(),
  /** 만료 시각(ISO 8601 Instant) — 만료 없음/미설정 시 null */
  expiresAt: z.string().nullable(),
})

/**
 * 부재중(Out of Office) 응답 Zod 스키마 — FR-PR-03.
 * `GET`/`PATCH /api/v1/users/me/ooo` 응답 형태 — 래퍼 없음.
 * 미설정/종료 시 다섯 필드 모두 null·active:false(강제 row 생성 안 함, backend
 * OutOfOfficeController/OooResponse 참고).
 */
export const oooResponseSchema = z.object({
  /** 부재 시작 시각(ISO 8601 Instant) — 미설정/종료 시 null */
  startsAt: z.string().nullable(),
  /** 부재 종료 시각(ISO 8601 Instant) — 미설정/종료 시 null */
  endsAt: z.string().nullable(),
  /** 대체 담당자 사용자 id — 미지정 시 null */
  delegateUserId: z.string().uuid().nullable(),
  /** 대체 담당자 표시 이름(파생) — 대리자 삭제/미지정 시 null */
  delegateName: z.string().nullable(),
  /** 안내 메시지(평문) — 미설정 시 null */
  message: z.string().nullable(),
  /** 활성 여부(`startsAt<=now<endsAt`, Clock 기준) */
  active: z.boolean(),
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
export type StatusResponse = z.infer<typeof statusResponseSchema>
export type OooResponse = z.infer<typeof oooResponseSchema>
