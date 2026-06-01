// identity-access BC 사용자 목록 조회 REST API client + Zod 스키마
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend UserSummaryResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 요약 응답 Zod 스키마.
 * GET /api/v1/users 배열 응답 항목 형태 — { data: } 래퍼 없음.
 */
export const userSummarySchema = z.object({
  id: z.string().uuid(),
  username: z.string().min(1),
  /** 표시 이름 — 미설정 시 null */
  displayName: z.string().nullable(),
  /** 이메일 — 미노출 설정 또는 없을 때 null */
  email: z.string().nullable(),
})

/** 사용자 요약 타입 */
export type UserSummary = z.infer<typeof userSummarySchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 목록을 조회한다.
 *
 * @param query 검색 문자열 — 미전달 시 전체 목록(최대 MAX_RESULTS건) 반환.
 *              username/displayName 부분일치 검색.
 * @returns UserSummary[] — 배열 직접 응답 (래퍼 없음)
 * @throws ApiError(401) 인증 실패 시
 */
export async function fetchUsers(query?: string): Promise<UserSummary[]> {
  const params = new URLSearchParams()
  if (query !== undefined && query !== '') {
    params.set('query', query)
  }
  const queryString = params.toString()
  const path = queryString !== '' ? `/api/v1/users?${queryString}` : '/api/v1/users'
  return apiGet(path, z.array(userSummarySchema))
}

/**
 * 사용자 id 다건 조회.
 * 현재 담당자 이름을 안정적으로 표시하기 위해 사용한다 (C1 버그 수정).
 * GET /api/v1/users?ids=<uuid>,<uuid>,... — ids 우선 모드.
 *
 * @param ids UUID 문자열 배열 — 빈 배열이면 네트워크 호출 없이 [] 반환.
 * @returns UserSummary[] — 미존재 id는 결과에서 조용히 제외.
 * @throws ApiError(401) 인증 실패 시
 */
export async function fetchUsersByIds(ids: string[]): Promise<UserSummary[]> {
  if (ids.length === 0) {
    return []
  }
  const path = `/api/v1/users?ids=${ids.join(',')}`
  return apiGet(path, z.array(userSummarySchema))
}
