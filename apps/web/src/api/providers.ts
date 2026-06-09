// 로그인 화면용 인증 공급자(LOCAL/LDAP) 목록 조회 API 클라이언트 — GET /api/v1/auth/providers (FR-AU-06)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 ProvidersController.ProviderEntry DTO와 1:1 정합
// id: 소문자 type값, login 요청에 그대로 전달
// type: 대문자 (LOCAL | LDAP)
// priority: 낮을수록 먼저 표시
// available: false이면 현재 사용 불가 공급자
// ─────────────────────────────────────────────────────────────────────────────

/** 인증 공급자 단건 스키마 */
export const providerEntrySchema = z.object({
  /** login 요청에 그대로 전달되는 공급자 식별자 (예: "ldap", "local") */
  id: z.string(),
  /** 공급자 유형 대문자 (예: "LDAP", "LOCAL") */
  type: z.string(),
  /** 로그인 화면에 표시되는 공급자 이름 */
  displayName: z.string(),
  /** 표시 우선순위 — 낮을수록 먼저 표시 */
  priority: z.number(),
  /** 현재 사용 가능 여부 */
  available: z.boolean(),
})

/** 인증 공급자 목록 응답 래퍼 스키마 — `{ providers: [...] }` */
const providersResponseSchema = z.object({
  providers: z.array(providerEntrySchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 인증 공급자 단건 타입 — z.infer로 자동 추론 */
export type ProviderEntry = z.infer<typeof providerEntrySchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 활성화된 인증 공급자 목록을 조회한다.
 *
 * `GET /api/v1/auth/providers` → `{ providers: [...] }` 래퍼를 언래핑해 반환.
 * - 미인증 상태에서 호출 가능 (로그인 화면에서 사용).
 * - 백엔드가 available 여부와 무관하게 모든 등록된 공급자를 반환하므로
 *   UI에서 `available` 필드로 필터링 필요 시 호출자가 처리한다.
 *
 * @returns 인증 공급자 배열 — 등록된 공급자가 없으면 빈 배열
 * @throws ApiError 서버 오류 시
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function fetchProviders(): Promise<ProviderEntry[]> {
  const wrapped = await apiGet('/api/v1/auth/providers', providersResponseSchema)
  return wrapped.providers
}
