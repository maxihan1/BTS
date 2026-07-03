// Cycle Time / Lead Time 분포 차트 API 클라이언트 — Zod 스키마 + fetch + isCycleTimeEmpty (FR-RP-04 D6/D7)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO(SampleResponse/MetricResponse/CycleTimeResponse,
// backend/modules/issue-tracking/.../cycletime/web/dto/CycleTimeResponse.kt)와 필드명·타입 1:1 grep 대조
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cycle/Lead Time 지표의 단일 이슈 표본 Zod 스키마.
 * 백엔드 SampleResponse DTO와 1:1 대응.
 * - issueKey: 표본에 해당하는 이슈 키
 * - seconds: 소요 시간(초)
 */
export const sampleResponseSchema = z.object({
  issueKey: z.string(),
  seconds: z.number(),
})

/** 단일 이슈 표본 타입 — Zod 스키마에서 추론 */
export type SampleResponse = z.infer<typeof sampleResponseSchema>

/**
 * Cycle/Lead Time 지표 하나(요약 통계 + 개별 표본) Zod 스키마.
 * 백엔드 MetricResponse DTO와 1:1 대응.
 * - count: 표본 개수
 * - min/max/avg/p25/p50/p75/p90: count===0이면 표본이 없다는 뜻이라 전부 null (값 0과 구분)
 * - samples: 소요 시간(초) 오름차순 개별 이슈 표본 목록
 */
export const metricResponseSchema = z.object({
  count: z.number(),
  min: z.number().nullable(),
  max: z.number().nullable(),
  avg: z.number().nullable(),
  p25: z.number().nullable(),
  p50: z.number().nullable(),
  p75: z.number().nullable(),
  p90: z.number().nullable(),
  samples: z.array(sampleResponseSchema),
})

/** Cycle/Lead Time 지표 타입 — Zod 스키마에서 추론 */
export type MetricResponse = z.infer<typeof metricResponseSchema>

/**
 * 프로젝트 Cycle/Lead Time 응답 Zod 스키마.
 * GET /api/v1/projects/{projectKey}/cycle-time 의 data 필드 형식.
 * - projectKey: 조회한 프로젝트 키
 * - from: 실제 적용된 조회 창 시작일 (ISO "YYYY-MM-DD")
 * - to: 실제 적용된 조회 창 종료일 (ISO "YYYY-MM-DD")
 * - cycleTime: Cycle Time(첫 IN_PROGRESS → 마지막 DONE) 지표
 * - leadTime: Lead Time(생성 → 마지막 DONE) 지표
 */
export const cycleTimeResponseSchema = z.object({
  projectKey: z.string(),
  from: z.string(),
  to: z.string(),
  cycleTime: metricResponseSchema,
  leadTime: metricResponseSchema,
})

/** 프로젝트 Cycle/Lead Time 응답 타입 — Zod 스키마에서 추론 */
export type CycleTimeResponse = z.infer<typeof cycleTimeResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) => z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트의 Cycle Time / Lead Time 분포를 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/cycle-time → 200 { data: CycleTimeResponse }
 * from/to 쿼리 파라미터는 노출하지 않고 백엔드 기본 창(최근 30일)을 사용한다.
 *
 * @param projectKey 조회할 프로젝트 키 (예: "BTS")
 * @returns 프로젝트 Cycle/Lead Time 응답
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 프로젝트 BROWSE 권한 없음
 */
export async function fetchProjectCycleTime(projectKey: string): Promise<CycleTimeResponse> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/cycle-time`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(cycleTimeResponseSchema).parse(raw).data
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cycle/Lead Time 응답이 비어 있는지(그릴 데이터가 없는지) 판별하는 순수 함수.
 *
 * cycleTime과 leadTime 두 지표의 count가 모두 0이면 true를 반환한다.
 * 차트 렌더링 여부를 결정하는 데 사용한다.
 *
 * @param response 프로젝트 Cycle/Lead Time 응답
 * @returns 두 지표 모두 표본이 없으면 true, 하나라도 표본이 있으면 false
 */
export function isCycleTimeEmpty(response: CycleTimeResponse): boolean {
  return response.cycleTime.count === 0 && response.leadTime.count === 0
}
