// 스프린트 벨로시티 차트 API 클라이언트 — Zod 스키마 + fetch 함수 (FR-RP-02 D6/D7)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO(VelocityResponse/VelocityPointResponse, PR #222)와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 벨로시티 시계열 단건(스프린트) 포인트 Zod 스키마.
 * 백엔드 VelocityPointResponse DTO와 1:1 대응.
 * - sprintId: 스프린트 ID
 * - name: 스프린트 이름
 * - startDate/endDate: 스프린트 시작/종료일 (ISO "YYYY-MM-DD") — 미설정 시 null
 * - commitmentSeconds: 커밋(계획) 추정시간 합계(초)
 * - completedSeconds: 완료 추정시간 합계(초)
 */
export const velocityPointResponseSchema = z.object({
  sprintId: z.string(),
  name: z.string(),
  startDate: z.string().nullish(),
  endDate: z.string().nullish(),
  commitmentSeconds: z.number(),
  completedSeconds: z.number(),
})

/** 벨로시티 시계열 포인트 타입 — Zod 스키마에서 추론 */
export type VelocityPointResponse = z.infer<typeof velocityPointResponseSchema>

/**
 * 프로젝트 벨로시티 응답 Zod 스키마.
 * GET /api/v1/projects/{projectKey}/velocity 의 data 필드 형식.
 * - projectKey: 조회한 프로젝트 키
 * - averageCommitmentSeconds: 최근 스프린트 평균 커밋 추정시간(초)
 * - averageCompletedSeconds: 최근 스프린트 평균 완료 추정시간(초)
 * - sprints: 스프린트별 벨로시티 포인트 배열
 */
export const velocityResponseSchema = z.object({
  projectKey: z.string(),
  averageCommitmentSeconds: z.number(),
  averageCompletedSeconds: z.number(),
  sprints: z.array(velocityPointResponseSchema),
})

/** 프로젝트 벨로시티 응답 타입 — Zod 스키마에서 추론 */
export type VelocityResponse = z.infer<typeof velocityResponseSchema>

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
 * 프로젝트의 최근 스프린트 벨로시티를 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/velocity → 200 { data: VelocityResponse }
 * limit 쿼리 파라미터는 노출하지 않고 백엔드 기본값(10)을 사용한다.
 *
 * @param projectKey 조회할 프로젝트 키 (예: "BTS")
 * @returns 프로젝트 벨로시티 응답
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 프로젝트 BROWSE 권한 없음
 */
export async function fetchProjectVelocity(projectKey: string): Promise<VelocityResponse> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/velocity`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(velocityResponseSchema).parse(raw).data
}
