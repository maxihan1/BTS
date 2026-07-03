// 누적 흐름도(CFD) 차트 API 클라이언트 — Zod 스키마 + fetch + isCfdEmpty (FR-RP-03 D6/D7)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO(CfdResponse/CfdPointResponse,
// backend/modules/issue-tracking/.../cfd/web/dto/CfdResponse.kt)와 필드명·타입 1:1 grep 대조
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CFD 시계열 단건(일자) 포인트 Zod 스키마.
 * 백엔드 CfdPointResponse DTO와 1:1 대응.
 * - date: 이 지점이 나타내는 캘린더 일자 (ISO "YYYY-MM-DD")
 * - todoCount: 이 날짜에 TODO 카테고리로 분류된 이슈 누적 개수 (non-null)
 * - inProgressCount: 이 날짜에 IN_PROGRESS 카테고리로 분류된 이슈 누적 개수 (non-null)
 * - doneCount: 이 날짜에 DONE 카테고리로 분류된 이슈 누적 개수 (non-null)
 */
export const cfdPointResponseSchema = z.object({
  date: z.string(),
  todoCount: z.number(),
  inProgressCount: z.number(),
  doneCount: z.number(),
})

/** CFD 시계열 포인트 타입 — Zod 스키마에서 추론 */
export type CfdPointResponse = z.infer<typeof cfdPointResponseSchema>

/**
 * 프로젝트 CFD 응답 Zod 스키마.
 * GET /api/v1/projects/{projectKey}/cfd 의 data 필드 형식.
 * - projectKey: 조회한 프로젝트 키
 * - from: 실제 적용된 조회 창 시작일 (ISO "YYYY-MM-DD")
 * - to: 실제 적용된 조회 창 종료일 (ISO "YYYY-MM-DD")
 * - points: 날짜 오름차순 CFD 지점 목록
 */
export const cfdResponseSchema = z.object({
  projectKey: z.string(),
  from: z.string(),
  to: z.string(),
  points: z.array(cfdPointResponseSchema),
})

/** 프로젝트 CFD 응답 타입 — Zod 스키마에서 추론 */
export type CfdResponse = z.infer<typeof cfdResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) => z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트의 CFD(누적 흐름도) 시계열을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/cfd → 200 { data: CfdResponse }
 * from/to 쿼리 파라미터는 노출하지 않고 백엔드 기본 창(최근 30일)을 사용한다.
 *
 * @param projectKey 조회할 프로젝트 키 (예: "BTS")
 * @returns 프로젝트 CFD 응답
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 프로젝트 BROWSE 권한 없음
 */
export async function fetchProjectCfd(projectKey: string): Promise<CfdResponse> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/cfd`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(cfdResponseSchema).parse(raw).data
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CFD 응답이 비어 있는지(그릴 데이터가 없는지) 판별하는 순수 함수.
 *
 * points가 빈 배열이거나, 모든 포인트의 todoCount+inProgressCount+doneCount 합이
 * 0이면 true를 반환한다. 차트 렌더링 여부를 결정하는 데 사용한다.
 *
 * @param response 프로젝트 CFD 응답
 * @returns 비어 있으면 true, 하나라도 카운트가 있으면 false
 */
export function isCfdEmpty(response: CfdResponse): boolean {
  return response.points.every(
    (point) => point.todoCount + point.inProgressCount + point.doneCount === 0,
  )
}
