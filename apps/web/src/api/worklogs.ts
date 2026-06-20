// 워크로그(issue-tracking BC) REST API 클라이언트 — CRUD 4함수 + Zod 스키마
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크로그 단건 응답 Zod 스키마.
 * 백엔드 WorklogResponse DTO와 1:1 대응.
 * - id: UUID (워크로그 고유 식별자)
 * - issueKey: 이슈 키 (예: "ATLAS-1")
 * - authorId: 작성자 UUID
 * - timeSpentSeconds: 소요 시간 (초)
 * - startedAt: 작업 시작 시각 (ISO 8601 Instant)
 * - comment: 코멘트 (null = 코멘트 없음)
 * - createdAt: 생성 시각 (ISO 8601)
 * - updatedAt: 수정 시각 (ISO 8601)
 */
export const worklogResponseSchema = z.object({
  id: z.string().uuid(),
  issueKey: z.string(),
  authorId: z.string().uuid(),
  timeSpentSeconds: z.number(),
  startedAt: z.string(),
  comment: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
})

/** 워크로그 단건 응답 타입 — Zod 스키마에서 추론 */
export type WorklogResponse = z.infer<typeof worklogResponseSchema>

/**
 * 워크로그 집계 요약 Zod 스키마.
 * 백엔드 WorklogSummary DTO와 1:1 대응.
 * - originalEstimateSeconds: 원래 추정 초 (null = 미설정)
 * - timeSpentSeconds: 총 소요 시간 (초)
 * - remainingEstimateSeconds: 남은 추정 초 (null = 미설정)
 *
 * 백엔드에서 @JsonInclude(NON_NULL) 미적용 — 키는 항상 존재.
 * IssueResponse 3필드와 달리 .optional() 붙이지 않음 (회귀 감지 강화).
 */
export const worklogSummarySchema = z.object({
  originalEstimateSeconds: z.number().nullable(),
  timeSpentSeconds: z.number(),
  remainingEstimateSeconds: z.number().nullable(),
})

/** 워크로그 집계 요약 타입 — Zod 스키마에서 추론 */
export type WorklogSummary = z.infer<typeof worklogSummarySchema>

/**
 * 워크로그 목록 응답 Zod 스키마.
 * GET /api/v1/issues/{key}/worklogs 의 data 필드 형식.
 */
export const worklogListResponseSchema = z.object({
  worklogs: z.array(worklogResponseSchema),
  summary: worklogSummarySchema,
})

/** 워크로그 목록 응답 타입 — Zod 스키마에서 추론 */
export type WorklogListResponse = z.infer<typeof worklogListResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 요청 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 워크로그 추가 요청 body 타입 */
export interface AddWorklogInput {
  /** 소요 시간 (초) — 필수 */
  timeSpentSeconds: number
  /** 작업 시작 시각 (ISO 8601 Instant) — 필수 */
  startedAt: string
  /** 코멘트 — 생략 시 미전송 */
  comment?: string | null
  /** 잔여 추정 시간 갱신 (초) — 생략 시 자동 계산 */
  newRemainingEstimateSeconds?: number | null
}

/**
 * 워크로그 수정 요청 body 타입.
 *
 * 주의: `comment: null`은 "코멘트 무변경"을 의미하며 코멘트 클리어가 아님.
 * 백엔드에서 null = 무변경으로 처리한다. 코멘트 클리어 sentinel은 없음.
 * 빈 문자열("")을 전송하면 코멘트가 빈 값으로 설정된다.
 * 수정하지 않는 필드는 undefined로 생략한다 (body에 포함되지 않음).
 */
export interface UpdateWorklogInput {
  /** 소요 시간 (초) — 생략 시 기존 값 유지 */
  timeSpentSeconds?: number
  /** 작업 시작 시각 (ISO 8601 Instant) — 생략 시 기존 값 유지 */
  startedAt?: string
  /** 코멘트 — null=무변경, ""=빈값으로 설정, 생략 시 기존 값 유지 */
  comment?: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈의 워크로그 목록과 집계 요약을 조회한다.
 *
 * GET /api/v1/issues/{key}/worklogs → 200 { data: WorklogListResponse }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns 워크로그 목록과 집계 요약 — `{ worklogs, summary }`
 * @throws ApiError(403) 권한 없음
 * @throws ApiError(404) 이슈 없음
 */
export async function fetchWorklogs(key: string): Promise<WorklogListResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/worklogs`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(worklogListResponseSchema).parse(raw).data
}

/**
 * 이슈에 워크로그를 추가한다.
 *
 * POST /api/v1/issues/{key}/worklogs → 201 { data: WorklogResponse }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param input 워크로그 추가 입력값 (timeSpentSeconds, startedAt 필수)
 * @returns 생성된 워크로그
 * @throws ApiError(400) 입력값 검증 실패
 * @throws ApiError(403) UPDATE 권한 없음
 * @throws ApiError(404) 이슈 없음
 */
export async function addWorklog(key: string, input: AddWorklogInput): Promise<WorklogResponse> {
  // undefined 필드는 JSON.stringify에서 자동 제외됨
  const body: Record<string, unknown> = {
    timeSpentSeconds: input.timeSpentSeconds,
    startedAt: input.startedAt,
  }
  if (input.comment !== undefined) {
    body['comment'] = input.comment
  }
  if (input.newRemainingEstimateSeconds !== undefined) {
    body['newRemainingEstimateSeconds'] = input.newRemainingEstimateSeconds
  }

  const res = await apiFetch(`/api/v1/issues/${key}/worklogs`, {
    method: 'POST',
    body,
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(worklogResponseSchema).parse(raw).data
}

/**
 * 워크로그를 수정한다.
 *
 * PATCH /api/v1/issues/{key}/worklogs/{worklogId} → 200 { data: WorklogResponse }
 *
 * 수정하지 않는 필드는 undefined로 생략한다 (body에 포함되지 않음).
 * `comment: null`은 "무변경"이며 코멘트 클리어가 아님.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param worklogId 워크로그 UUID
 * @param input 수정할 필드만 포함 (생략 필드는 기존 값 유지)
 * @returns 수정된 워크로그
 * @throws ApiError(403) 권한 없음 (타인 워크로그 수정)
 * @throws ApiError(404) 워크로그 없음
 */
export async function updateWorklog(
  key: string,
  worklogId: string,
  input: UpdateWorklogInput,
): Promise<WorklogResponse> {
  const body: Record<string, unknown> = {}
  if (input.timeSpentSeconds !== undefined) {
    body['timeSpentSeconds'] = input.timeSpentSeconds
  }
  if (input.startedAt !== undefined) {
    body['startedAt'] = input.startedAt
  }
  if (input.comment !== undefined) {
    body['comment'] = input.comment
  }

  const res = await apiFetch(`/api/v1/issues/${key}/worklogs/${worklogId}`, {
    method: 'PATCH',
    body,
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(worklogResponseSchema).parse(raw).data
}

/**
 * 워크로그를 삭제한다.
 *
 * DELETE /api/v1/issues/{key}/worklogs/{worklogId} → 204 (본문 없음)
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param worklogId 워크로그 UUID
 * @throws ApiError(403) 권한 없음 (타인 워크로그 삭제)
 * @throws ApiError(404) 워크로그 없음
 */
export async function deleteWorklog(key: string, worklogId: string): Promise<void> {
  const res = await apiFetch(`/api/v1/issues/${key}/worklogs/${worklogId}`, {
    method: 'DELETE',
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
