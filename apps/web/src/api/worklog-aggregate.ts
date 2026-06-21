// 워크로그 집계(issue-tracking BC) REST API 클라이언트 — Zod 스키마 + fetch 함수
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 유니온 타입 — 집계 차원 / 기간 단위
// ─────────────────────────────────────────────────────────────────────────────

/** 집계 차원 — 이슈별/사용자별/기간별 */
export type AggregateDimension = 'issue' | 'user' | 'period'

/** 기간 집계 단위 — 일/주/월 */
export type AggregateGranularity = 'day' | 'week' | 'month'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크로그 집계 버킷 단건 Zod 스키마.
 * 백엔드 WorklogAggregateBucket DTO와 1:1 대응.
 * - key: 버킷 식별자 (이슈키 / userId / 기간 문자열)
 * - label: 화면 표시용 라벨
 * - timeSpentSeconds: 버킷 내 총 소요 시간 (초)
 * - worklogCount: 버킷 내 워크로그 수
 */
export const worklogAggregateBucketSchema = z.object({
  key: z.string(),
  label: z.string(),
  timeSpentSeconds: z.number(),
  worklogCount: z.number(),
})

/** 워크로그 집계 버킷 타입 — Zod 스키마에서 추론 */
export type WorklogAggregateBucket = z.infer<typeof worklogAggregateBucketSchema>

/**
 * 워크로그 집계 응답 Zod 스키마.
 * GET /api/v1/worklogs/aggregate 의 data 필드 형식.
 * - by: 집계 차원 ("issue" | "user" | "period")
 * - granularity: 기간 단위 — by=period일 때만 존재 (@JsonInclude NON_NULL → .optional())
 * - from/to: 조회 기간 — 요청에 전달한 경우만 존재 (@JsonInclude NON_NULL → .optional())
 * - buckets: 집계 버킷 배열
 * - totalTimeSpentSeconds: 전체 합계 소요 시간 (초)
 */
export const worklogAggregateResponseSchema = z.object({
  by: z.string(),
  granularity: z.string().optional(),
  from: z.string().optional(),
  to: z.string().optional(),
  buckets: z.array(worklogAggregateBucketSchema),
  totalTimeSpentSeconds: z.number(),
})

/** 워크로그 집계 응답 타입 — Zod 스키마에서 추론 */
export type WorklogAggregateResponse = z.infer<typeof worklogAggregateResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 요청 파라미터 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 워크로그 집계 조회 파라미터 */
export interface FetchWorklogAggregateParams {
  /** 집계 차원 — 필수 */
  by: AggregateDimension
  /** 기간 단위 — by=period일 때 사용 */
  granularity?: AggregateGranularity
  /** 조회 시작일 (yyyy-MM-dd) */
  from?: string
  /** 조회 종료일 (yyyy-MM-dd) */
  to?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트의 워크로그 집계를 조회한다.
 *
 * GET /api/v1/worklogs/aggregate?project={projectKey}&by={by}[&granularity=...][&from=...][&to=...]
 * → 200 { data: WorklogAggregateResponse }
 *
 * undefined 파라미터(granularity/from/to)는 쿼리스트링에 포함하지 않는다.
 *
 * @param projectKey 프로젝트 키 (예: "BTS")
 * @param params 집계 조회 파라미터 (by 필수, 나머지 선택)
 * @returns 집계 버킷 배열과 합계
 * @throws ApiError(400) 잘못된 파라미터
 * @throws ApiError(403) 권한 없음
 */
export async function fetchWorklogAggregate(
  projectKey: string,
  params: FetchWorklogAggregateParams,
): Promise<WorklogAggregateResponse> {
  const qs = buildQueryString(projectKey, params)
  const res = await apiFetch(`/api/v1/worklogs/aggregate?${qs}`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(worklogAggregateResponseSchema).parse(raw).data
}

/**
 * 쿼리스트링을 조립한다. undefined 값은 포함하지 않는다.
 *
 * @param projectKey 프로젝트 키
 * @param params 집계 조회 파라미터
 * @returns URL 인코딩된 쿼리스트링 문자열
 */
function buildQueryString(projectKey: string, params: FetchWorklogAggregateParams): string {
  const qs = new URLSearchParams()
  qs.set('project', projectKey)
  qs.set('by', params.by)
  if (params.granularity !== undefined) {
    qs.set('granularity', params.granularity)
  }
  if (params.from !== undefined) {
    qs.set('from', params.from)
  }
  if (params.to !== undefined) {
    qs.set('to', params.to)
  }
  return qs.toString()
}
