// 라벨 자동완성 REST API 클라이언트 — issue-tracking BC 컨벤션 (FR-IS-09)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend DataResponse<List<String>> 계약
// ─────────────────────────────────────────────────────────────────────────────

/** backend `{ data: T }` 래퍼 파싱 헬퍼 — issues.ts의 dataResponseSchema와 동일 패턴 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

/** 라벨 자동완성 응답 스키마 — { data: string[] } */
const labelsResponseSchema = dataResponseSchema(z.array(z.string()))

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 라벨 자동완성 후보를 조회한다.
 *
 * GET /api/v1/labels?q=<prefix> → DataResponse<List<String>>
 * - q 빈 문자열 → 전체 상위 10개 반환
 * - prefix 매칭 없음 → 빈 배열 반환
 * - 빈도순 정렬, 최대 10개
 *
 * @param q 라벨 prefix 검색어 (빈 문자열 허용)
 * @returns 매칭된 라벨 문자열 배열 — backend `{ data: string[] }` 언랩 후 반환
 */
export async function fetchLabels(q: string): Promise<string[]> {
  const query = new URLSearchParams({ q })
  const wrapped = await apiGet(
    `/api/v1/labels?${query.toString()}`,
    labelsResponseSchema,
  )
  return wrapped.data
}
