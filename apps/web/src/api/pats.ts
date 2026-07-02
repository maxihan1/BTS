// PAT(Personal Access Token) 셀프서비스 API 클라이언트 — 발급(raw token 1회)·목록·취소
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// scope 카탈로그 — 백엔드 PatScopeCatalog.SUPPORTED(ADR 확정 5종)와 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/** PAT 발급 가능한 scope 화이트리스트 (백엔드 `PatScopeCatalog.SUPPORTED` 미러). `*`는 전체 권한. */
export const PAT_SCOPE_CATALOG = ['read:issues', 'write:issues', 'read:projects', 'write:projects', '*'] as const

/** {@link PAT_SCOPE_CATALOG} 원소 타입 — scope 문자열 리터럴 유니온 */
export type PatScope = (typeof PAT_SCOPE_CATALOG)[number]

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 PatDtos.kt 응답 DTO와 1:1 정합
// PatDtos.kt 는 @JsonInclude 를 사용하지 않으므로 null 필드도 키가 그대로 직렬화된다.
// 따라서 nullable 필드는 `.nullish()`가 아닌 `.nullable()`로 미러한다(레거시 무기한 PAT의
// expiresAt=null 도 키 자체는 항상 존재 — frontend-zod-backend-dto-contract-gap 재발 방지).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PAT 목록 요약 항목 Zod 스키마 (`PatSummaryResponse` 미러).
 *
 * raw token/`token_hash`/`userId`는 백엔드 응답에 없어 스키마에도 없다.
 * `expiresAt`은 레거시 데이터 호환을 위해 nullable(DB `expires_at` nullable 유지, 발급 경로는 항상 값 부여).
 */
export const PatSchema = z.object({
  /** PAT 식별자 UUID */
  id: z.string().uuid(),
  /** 사용자 지정 레이블 */
  name: z.string(),
  /** 허용 scope 목록 */
  scopes: z.array(z.string()),
  /** 만료 시각(ISO Instant). null=레거시 무기한 PAT(EC-12 배지 표시 대상) */
  expiresAt: z.string().nullable(),
  /** 마지막 사용 시각(ISO Instant). null=미사용 */
  lastUsedAt: z.string().nullable(),
  /** 발급 시각(ISO Instant) */
  createdAt: z.string(),
})

/** PAT 목록 응답 봉투 Zod 스키마 — `{ pats: [...] }` (`PatListResponse` 미러) */
const patListResponseSchema = z.object({
  pats: z.array(PatSchema),
})

/**
 * PAT 발급(201) 응답 Zod 스키마 (`IssuedPatResponse` 미러).
 *
 * `token`은 발급 응답에서만 1회 노출되며 이후 어디에서도 재조회할 수 없다(EC-26).
 * 이 값을 localStorage/sessionStorage 에 저장하는 코드는 절대 두지 않는다 — 호출부 화면 상태로만 취급.
 */
export const PatIssuedSchema = z.object({
  /** 발급된 PAT 식별자 UUID */
  id: z.string().uuid(),
  /** 사용자 지정 레이블 */
  name: z.string(),
  /** 정규화된 scope 목록 */
  scopes: z.array(z.string()),
  /** prefix 포함 raw PAT token — 1회 노출 */
  token: z.string(),
  /** 만료 시각(ISO Instant). null=레거시 무기한(발급 경로는 항상 값 부여) */
  expiresAt: z.string().nullable(),
  /** 발급 시각(ISO Instant) */
  createdAt: z.string(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** PAT 목록 요약 항목 타입 — z.infer로 자동 추론 */
export type Pat = z.infer<typeof PatSchema>

/** PAT 발급 응답 타입(raw token 포함) — z.infer로 자동 추론 */
export type PatIssued = z.infer<typeof PatIssuedSchema>

/** PAT 발급 요청 바디 (`CreatePatRequest` 미러) */
export interface CreatePatRequest {
  /** 사용자 지정 레이블(공백 불가 — 서버 검증) */
  name: string
  /** 요청 scope 목록(카탈로그 화이트리스트 — 서버 검증) */
  scopes: PatScope[]
  /** 만료까지 일수(1..365, 무기한 금지 — 서버 검증) */
  expiresInDays: number
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 새 PAT를 발급하고 raw token을 1회 노출하는 응답을 반환한다.
 *
 * `POST /api/v1/users/me/pats`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다(double submit cookie 패턴).
 * - 반환된 `token`은 이 호출에서만 확인 가능 — 호출부는 화면 상태로만 보관하고
 *   localStorage/sessionStorage 등 영속 저장소에 절대 쓰지 않는다.
 *
 * @param req name/scopes/expiresInDays 발급 요청
 * @returns PatIssued (id, name, scopes, token, expiresAt, createdAt)
 * @throws ApiError(400) invalid_name / invalid_scope / invalid_expiry
 * @throws ApiError(403) quota_exceeded 또는 PAT 인증 호출(session_management_requires_interactive_login)
 * @throws ApiError(401) 미인증
 */
export async function createPat(req: CreatePatRequest): Promise<PatIssued> {
  const res = await apiFetch('/api/v1/users/me/pats', {
    method: 'POST',
    body: req,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return PatIssuedSchema.parse(await res.json())
}

/**
 * 본인 PAT 요약 목록(취소되지 않은 전부, 만료 포함)을 조회한다.
 *
 * `GET /api/v1/users/me/pats` → `{ pats: [...] }` 래퍼를 언래핑해 반환.
 * token/token_hash는 응답에 없다(EC-26).
 *
 * @returns PAT 요약 배열 — 발급 이력이 없으면 빈 배열
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) PAT 인증 호출(session_management_requires_interactive_login)
 */
export async function fetchPats(): Promise<Pat[]> {
  const wrapped = await apiGet('/api/v1/users/me/pats', patListResponseSchema)
  return wrapped.pats
}

/**
 * 지정한 PAT를 폐기(취소)한다 — 멱등(이미 취소된 PAT 재호출도 204).
 *
 * `DELETE /api/v1/users/me/pats/{id}` → 204 No Content.
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - IDOR 방어: 타인 소유/미존재 id는 백엔드가 404(`not_found`)로 일반화한다.
 *
 * @param id 폐기할 PAT UUID
 * @returns void — 204 No Content
 * @throws ApiError(404) not_found — IDOR 또는 미존재
 * @throws ApiError(403) PAT 인증 호출(session_management_requires_interactive_login)
 * @throws ApiError(401) 미인증
 */
export async function revokePat(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/users/me/pats/${id}`, {
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
