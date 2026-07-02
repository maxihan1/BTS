// 스프린트 번다운/번업 API 클라이언트 — Zod 스키마 + fetch 함수 (FR-RP-01 D6/D7)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 유니온 타입 — 차트 뷰 종류
// ─────────────────────────────────────────────────────────────────────────────

/** 차트 뷰 종류 — 번다운(잔여) / 번업(완료) */
export type BurndownView = 'burndown' | 'burnup'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO(BurndownResponse/BurndownPointResponse, PR #219)와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 번다운 시계열 단건 포인트 Zod 스키마.
 * 백엔드 BurndownPointResponse DTO와 1:1 대응.
 * - date: 시계열 날짜 (ISO "YYYY-MM-DD")
 * - remainingSeconds: 잔여 추정시간(초) — 미래일 null 가능
 * - idealSeconds: 이상선(초)
 * - completedSeconds: 완료 누계(초) — 미래일 null 가능
 * - scopeSeconds: 해당 시점 범위(초)
 */
export const burndownPointSchema = z.object({
  date: z.string(),
  remainingSeconds: z.number().nullish(),
  idealSeconds: z.number(),
  completedSeconds: z.number().nullish(),
  scopeSeconds: z.number(),
})

/** 번다운 시계열 포인트 타입 — Zod 스키마에서 추론 */
export type BurndownPoint = z.infer<typeof burndownPointSchema>

/**
 * 스프린트 번다운/번업 응답 Zod 스키마.
 * GET /api/v1/sprints/{sprintId}/burndown 의 data 필드 형식.
 * - sprintId: 조회한 스프린트 ID
 * - projectKey: 소속 프로젝트 키
 * - status: 스프린트 상태 (SprintStatus enum 문자열)
 * - startDate/endDate: 스프린트 시작/종료일 (ISO "YYYY-MM-DD")
 * - totalScopeSeconds: 전체 범위 합계(초)
 * - points: 시계열 포인트 배열
 */
export const burndownResponseSchema = z.object({
  sprintId: z.string(),
  projectKey: z.string(),
  status: z.string(),
  startDate: z.string(),
  endDate: z.string(),
  totalScopeSeconds: z.number(),
  points: z.array(burndownPointSchema),
})

/** 스프린트 번다운/번업 응답 타입 — Zod 스키마에서 추론 */
export type BurndownResponse = z.infer<typeof burndownResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트의 번다운/번업 시계열을 조회한다.
 *
 * GET /api/v1/sprints/{sprintId}/burndown → 200 { data: BurndownResponse }
 *
 * @param sprintId 조회할 스프린트 ID (UUID)
 * @returns 번다운/번업 시계열 응답
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 프로젝트 BROWSE 권한 없음
 * @throws ApiError(404) 스프린트 없음
 * @throws ApiError(422) 스프린트 시작/종료일 미설정 (SPRINT_DATES_REQUIRED)
 */
export async function fetchSprintBurndown(sprintId: string): Promise<BurndownResponse> {
  const res = await apiFetch(`/api/v1/sprints/${sprintId}/burndown`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(burndownResponseSchema).parse(raw).data
}
