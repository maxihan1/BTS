// issue-tracking BC REST API client + Zod 스키마
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend IssueResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 단건 응답 Zod 스키마 — 12 필드, createdAt/updatedAt nullable */
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
  typeId: z.number().int().positive(),
  typeKey: z.string().min(1),
  typeName: z.string().min(1),
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

/**
 * 이슈 가용 전이 항목 Zod 스키마.
 * backend TransitionItem DTO 직렬화 형태와 1:1 대응.
 * workflows.ts의 workflowTransitionViewSchema와 동일 형태이나
 * 이슈 전이 API 계약에 특화된 독립 스키마로 관리한다.
 */
export const issueTransitionSchema = z.object({
  key: z.string().min(1),
  name: z.string().min(1),
  fromStateKey: z.string().min(1),
  toStateKey: z.string().min(1),
})

/** 이슈 전이 항목 타입 */
export type IssueTransition = z.infer<typeof issueTransitionSchema>

/** 이슈 전이 요청 입력 타입 */
export interface TransitionIssueInput {
  toStatusKey: string
  expectedVersion: number
}

/** 이슈 단건 응답 타입 */
export type IssueResponse = z.infer<typeof issueResponseSchema>

/** 이슈 생성 입력 타입 */
export interface CreateIssueInput {
  projectKey: string
  summary: string
}

/**
 * 이슈 수정 입력 타입.
 * summary · typeId 중 하나 이상을 전달하며, expectedVersion은 낙관적 잠금(OCC)을 위해 필수다.
 */
export interface UpdateIssueInput {
  summary?: string
  /** 변경할 이슈 타입 ID. 미전달 시 타입 유지. */
  typeId?: number
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
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns IssueResponse — 백엔드 `{ data: IssueResponse }` 래퍼를 언래핑해 반환
 * @throws ApiError(404) 해당 key의 이슈가 없을 때
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
 *
 * @param params projectKey · page · size 쿼리 파라미터
 * @returns Spring Page 구조 — content 배열 + 페이징 메타 (래퍼 없음)
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
 *
 * @param input projectKey · summary
 * @returns 생성된 IssueResponse — 백엔드 201 `{ data: IssueResponse }` 언래핑
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
 * Optimistic Concurrency Control을 위해 expectedVersion을 필수로 전달해야 한다.
 *
 * @param key 수정할 이슈 키
 * @param input summary(선택) · expectedVersion(필수)
 * @returns 수정된 IssueResponse — version이 증가된 상태로 반환
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
 *
 * @param key 삭제할 이슈 키
 * @returns void — 204 no content
 */
export async function deleteIssue(key: string): Promise<void> {
  const res = await apiFetch(`/api/v1/issues/${key}`, { method: 'DELETE' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/** backend `{ data: { transitions: [...] } }` 응답 파싱 헬퍼 */
const transitionsResponseSchema = z.object({
  data: z.object({
    transitions: z.array(issueTransitionSchema),
  }),
})

/**
 * 이슈의 현재 상태에서 가용한 전이 목록을 조회한다.
 * GET /api/v1/issues/{key}/transitions → { data: { transitions: IssueTransition[] } }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns 전이 항목 배열 — 현재 상태에서 이동 가능한 전이들
 * @throws ApiError(404) 해당 key의 이슈가 없을 때
 */
export async function fetchIssueTransitions(key: string): Promise<IssueTransition[]> {
  const wrapped = await apiGet(
    `/api/v1/issues/${key}/transitions`,
    transitionsResponseSchema,
  )
  return wrapped.data.transitions
}

/**
 * 이슈 상태를 전이한다.
 * POST /api/v1/issues/{key}/transition body { toStatusKey, expectedVersion }
 * 성공 200 시 변경된 IssueResponse를 반환한다.
 * 낙관적 잠금(OCC) 충돌 시 ApiError(409)를 throw한다.
 *
 * @param key 전이할 이슈 식별 키
 * @param input toStatusKey(목표 상태키) · expectedVersion(현재 버전, OCC용)
 * @returns 전이 완료된 IssueResponse — currentStateKey와 version이 갱신된 상태
 * @throws ApiError(404) 이슈가 없을 때
 * @throws ApiError(409) 낙관적 잠금 충돌 또는 전이 불허 시
 * @throws ApiError(422) 유효하지 않은 전이 요청 시
 */
export async function transitionIssue(key: string, input: TransitionIssueInput): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/transition`, { method: 'POST', body: input })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}
