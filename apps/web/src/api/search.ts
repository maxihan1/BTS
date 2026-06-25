// search-export-import BC AQL 검색 API 클라이언트 — FR-SR-02 D6
import { z } from 'zod'
import { apiPost } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — 백엔드 SearchErrorCode 열거값 정본
// backend 정본: search-export-import SearchController.kt — 변경 시 동반 수정
// ─────────────────────────────────────────────────────────────────────────────

/** AQL 검색 관련 백엔드 에러 코드 */
export const SEARCH_ERROR_CODES = {
  SYNTAX_ERROR: 'SEARCH_SYNTAX_ERROR',
  UNKNOWN_FIELD: 'SEARCH_UNKNOWN_FIELD',
  FIELD_NOT_YET_SUPPORTED: 'SEARCH_FIELD_NOT_YET_SUPPORTED',
  VALIDATION_FAILED: 'SEARCH_VALIDATION_FAILED',
  UNAUTHENTICATED: 'SEARCH_UNAUTHENTICATED',
  ACCESS_DENIED: 'SEARCH_ACCESS_DENIED',
  INTERNAL_ERROR: 'SEARCH_INTERNAL_ERROR',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 AqlSearchHit DTO와 1:1 대응
// backend 정본: search-export-import AqlSearchHit.kt
// labels 필드 없음 — backend DTO에 없으므로 invent 금지 (frontend-zod-backend-dto-contract-gap)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AQL 검색 결과 단건 Zod 스키마.
 * 백엔드 AqlSearchHit DTO 직렬화 형태와 1:1 대응.
 *
 * - assigneeId: UUID 또는 null (미할당 이슈)
 * - priority: int 1..5
 * - updatedAt: ISO 8601 Instant 문자열
 */
export const aqlSearchHitSchema = z.object({
  key: z.string().min(1),
  summary: z.string(),
  typeKey: z.string().min(1),
  currentStateKey: z.string().min(1),
  assigneeId: z.string().uuid().nullable(),
  priority: z.number().int().min(1).max(5),
  priorityName: z.string().min(1),
  projectKey: z.string().min(1),
  updatedAt: z.string().min(1),
})

/** AQL 검색 결과 단건 타입 */
export type AqlSearchHit = z.infer<typeof aqlSearchHitSchema>

/**
 * Spring Page<AqlSearchHit> 응답 Zod 스키마.
 * issues.ts pageSchema 패턴 미러 — 래퍼 없음 (DataResponse 감싸지 않음, C2).
 * backend Spring Page 직렬화 형태와 1:1 대응.
 */
export const aqlSearchPageSchema = z.object({
  content: z.array(aqlSearchHitSchema),
  totalElements: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  number: z.number().int().nonnegative(),
  first: z.boolean(),
  last: z.boolean(),
  empty: z.boolean(),
})

/** AQL 검색 결과 페이지 타입 */
export type AqlSearchPage = z.infer<typeof aqlSearchPageSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 파라미터 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** searchAql 호출 파라미터 */
export interface SearchAqlParams {
  /** 검색 대상 프로젝트 키 */
  projectKey: string
  /** AQL 쿼리 문자열 (최대 2000자) */
  query: string
  /** 페이지 번호 (0-based, 기본값 0) */
  page?: number
  /** 페이지 크기 (1..100, 기본값 50) */
  size?: number
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/search/aql — AQL 쿼리로 이슈를 검색한다.
 *
 * CSRF 불필요 — SPA는 Bearer 토큰 전송, Spring SecurityConfig가 Bearer 요청 CSRF skip.
 * 기존 boards/bulk POST와 동일하게 apiPost 재사용.
 *
 * @param params 검색 파라미터 (projectKey, query, page?, size?)
 * @returns Page<AqlSearchHit> — Spring Page 래퍼 형태 그대로
 * @throws ApiError 400(문법오류/미지원필드), 401(미인증), 403(권한없음), 500
 */
export async function searchAql(params: SearchAqlParams): Promise<AqlSearchPage> {
  const body: Record<string, unknown> = {
    projectKey: params.projectKey,
    query: params.query,
  }
  if (params.page !== undefined) {
    body['page'] = params.page
  }
  if (params.size !== undefined) {
    body['size'] = params.size
  }

  return apiPost('/api/v1/search/aql', body, aqlSearchPageSchema)
}
