// 사용자 검색 API 클라이언트 — GET /api/v1/users?query= + Zod 파싱 (FR-PM-01 Task F1)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend UserSummaryResponse DTO와 1:1 대응
// 필드: id / username / displayName / email
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 요약 단건 응답 Zod 스키마.
 * backend `UserSummaryResponse`: id(UUID) / username / displayName(nullable) / email(nullable).
 * PII 포함(email) — 로그 출력 금지.
 */
export const userSummarySchema = z.object({
  /** 사용자 내부 식별자 UUID — Zod v4 uuid() 정규식 호환을 위해 z.string() 사용 */
  id: z.string(),
  /** 로그인 식별자 (LDAP uid, 이메일 등) */
  username: z.string(),
  /** 화면 표시 이름 — 외부 IdP 미제공 시 null */
  displayName: z.string().nullable(),
  /** 이메일 — 외부 IdP 미제공 시 null */
  email: z.string().nullable(),
})

/** 사용자 요약 응답 배열 Zod 스키마 — 래핑 없는 배열 */
const userSummaryArraySchema = z.array(userSummarySchema)

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 사용자 요약 응답 타입 — z.infer로 자동 추론 */
export type UserSummary = z.infer<typeof userSummarySchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자를 검색한다.
 *
 * GET /api/v1/users?query={query} → 래핑 없는 UserSummary 배열.
 * 담당자 셀렉터 typeahead 용도로 사용된다.
 * PII(email) 포함 — 응답 내용을 로그에 출력하지 말 것.
 *
 * @param query 검색 질의 문자열
 * @returns UserSummary 배열 — 결과 없으면 빈 배열
 * @throws ApiError(401, "unauthorized") 미인증
 */
export async function searchUsers(query: string): Promise<UserSummary[]> {
  const params = new URLSearchParams({ query })
  const res = await apiFetch(`/api/v1/users?${params.toString()}`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return userSummaryArraySchema.parse(raw)
}
