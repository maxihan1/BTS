// issue-tracking BC REST API client + Zod 스키마
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend IssueResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 단건 응답 Zod 스키마 — 9 필드, createdAt/updatedAt nullable */
export const issueResponseSchema = z.object({
  key: z.string().min(1),
  id: z.string().uuid(),
  projectKey: z.string().min(1),
  summary: z.string().min(1),
  currentStateKey: z.string().min(1),
  reporterId: z.string().uuid(),
  version: z.number().int().nonnegative(),
  createdAt: z.string().nullable(),
  updatedAt: z.string().nullable(),
})

/** Spring Page 응답 Zod 스키마 — 래퍼 없음 (DataResponse 감싸지 않음) */
const pageSchema = <T>(itemSchema: z.ZodSchema<T>) =>
  z.object({
    content: z.array(itemSchema),
    totalElements: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
    size: z.number().int().positive(),
    number: z.number().int().nonnegative(),
    first: z.boolean(),
    last: z.boolean(),
    empty: z.boolean(),
  })

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 단건 응답 타입 */
export type IssueResponse = z.infer<typeof issueResponseSchema>

/** 이슈 생성 입력 타입 */
export interface CreateIssueInput {
  projectKey: string
  summary: string
}

/** 이슈 수정 입력 타입 */
export interface UpdateIssueInput {
  summary?: string
  expectedVersion: number
}

/** fetchIssues 쿼리 파라미터 */
export interface FetchIssuesParams {
  projectKey: string
  page: number
  size: number
}

/** Spring Page<IssueResponse> 타입 */
export type IssuePage = z.infer<ReturnType<typeof pageSchema<IssueResponse>>>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 단건을 조회한다.
 * GET /api/v1/issues/{key} → { data: IssueResponse }
 * 404 시 ApiError(404, ...) throw.
 */
export async function fetchIssue(key: string): Promise<IssueResponse> {
  const wrapped = await apiGet(
    `/api/v1/issues/${key}`,
    dataResponseSchema(issueResponseSchema),
  ).catch((err: unknown) => {
    if (err instanceof ApiError && err.status === 404) {
      throw new ApiError(404, { message: `이슈를 찾을 수 없습니다: ${key}` })
    }
    throw err
  })
  return wrapped.data
}

/**
 * 프로젝트 이슈 목록을 페이징 조회한다.
 * GET /api/v1/issues?projectKey=&page=&size= → Page<IssueResponse> (래퍼 없음)
 */
export async function fetchIssues(params: FetchIssuesParams): Promise<IssuePage> {
  const query = new URLSearchParams({
    projectKey: params.projectKey,
    page: String(params.page),
    size: String(params.size),
  })
  return apiGet(
    `/api/v1/issues?${query.toString()}`,
    pageSchema(issueResponseSchema),
  )
}

/**
 * 새 이슈를 생성한다.
 * POST /api/v1/issues body { projectKey, summary } → 201 { data: IssueResponse }
 */
export async function createIssue(input: CreateIssueInput): Promise<IssueResponse> {
  const wrapped = await apiPost(
    '/api/v1/issues',
    input,
    dataResponseSchema(issueResponseSchema),
  )
  return wrapped.data
}

/**
 * 이슈 요약을 수정한다.
 * PATCH /api/v1/issues/{key} body { summary?, expectedVersion } → 200 { data: IssueResponse }
 */
export async function updateIssue(key: string, input: UpdateIssueInput): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}`, { method: 'PATCH', body: input })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈를 삭제한다.
 * DELETE /api/v1/issues/{key} → 204 no content
 */
export async function deleteIssue(key: string): Promise<void> {
  const res = await apiFetch(`/api/v1/issues/${key}`, { method: 'DELETE' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
