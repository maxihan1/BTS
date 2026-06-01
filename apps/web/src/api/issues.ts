// issue-tracking BC REST API client + Zod 스키마
import { z } from 'zod'
import { apiGet, apiPost, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 정의
// backend IssueResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 단건 응답 Zod 스키마 — 21 필드 (12 기존 + 8 FR-IS-04 + 1 FR-IS-03 assigneeId), createdAt/updatedAt nullable */
export const issueResponseSchema = z.object({
  key: z.string().min(1),
  id: z.string().uuid(),
  projectKey: z.string().min(1),
  summary: z.string().min(1),
  currentStateKey: z.string().min(1),
  reporterId: z.string().uuid(),
  /**
   * 담당자 UUID. null이면 미할당.
   * 백엔드 IssueResponse(assigneeId: UUID? = null)는 null이라도 항상 직렬화하므로
   * nullable로 충분하다 — optional은 백엔드가 보내지 않는 형태(키 부재)까지 허용해
   * 계약을 느슨하게 만들어 회귀 감지를 약화시키므로 사용하지 않는다.
   */
  assigneeId: z.string().uuid().nullable(),
  version: z.number().int().nonnegative(),
  createdAt: z.string().nullable(),
  updatedAt: z.string().nullable(),
  typeId: z.number().int().positive(),
  typeKey: z.string().min(1),
  typeName: z.string().min(1),
  /** raw Markdown 본문. nullable — DB에 본문이 없으면 null */
  description: z.string().nullable(),
  /**
   * 렌더+정화된 HTML 본문.
   * nullable — 목록 API는 성능상 null 반환, 단건 GET만 채워짐.
   */
  descriptionHtml: z.string().nullable(),
  /** 우선순위 1(Highest)~5(Lowest). non-null, 기본값 3(Medium) */
  priority: z.number().int().min(1).max(5),
  /** 우선순위 표시 이름 (Highest/High/Medium/Low/Lowest) */
  priorityName: z.string(),
  /** 레이블 목록. 없으면 빈 배열 [] */
  labels: z.array(z.string()),
  /** 재현 환경 메모. nullable */
  environment: z.string().nullable(),
  /** 영향도 1(High)~3(Low). nullable — 설정 전 null */
  impact: z.number().int().min(1).max(3).nullable(),
  /** 영향도 표시 이름 (High/Medium/Low). nullable */
  impactName: z.string().nullable(),
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
 *
 * ### FR-IS-04 신규 필드 — merge-patch 3-state 규칙 (백엔드 UpdateIssueRequest 정본)
 * - `undefined` (필드 미포함) 또는 `null` (JSON null) → 해당 필드 변경 없음
 * - `""` (빈 문자열) → DB NULL로 클리어 (description · environment만 해당)
 * - `[]` (빈 배열) → 전체 제거 (labels만 해당)
 * - 값 전달 → 해당 값으로 설정
 *
 * 예외: `impact`는 클리어 sentinel이 없어 한 번 설정하면 비울 수 없음.
 */
export interface UpdateIssueInput {
  summary?: string
  /** 변경할 이슈 타입 ID. 미전달 시 타입 유지. */
  typeId?: number
  /**
   * 본문 Markdown 텍스트.
   * "" = DB NULL 클리어, null/미전달 = 변경 없음, 값 = 설정. max 65535자.
   */
  description?: string | null
  /**
   * 우선순위 1~5. null = 무변경. 미전달 = 무변경.
   * (서버는 null과 미전달을 동일하게 처리한다.)
   */
  priority?: number | null
  /**
   * 레이블 배열. null = 무변경, [] = 전체 제거, 값 = 교체.
   * 각 레이블 최대 50자, 최대 20개.
   */
  labels?: string[] | null
  /**
   * 재현 환경 메모.
   * "" = DB NULL 클리어, null/미전달 = 변경 없음, 값 = 설정. max 1000자.
   */
  environment?: string | null
  /**
   * 영향도 1~3. null = 무변경. 미전달 = 무변경.
   * 클리어 sentinel 없음 — 한 번 설정 후 비울 수 없음.
   */
  impact?: number | null
  expectedVersion: number
}

/** fetchIssues 쿼리 파라미터 */
export interface FetchIssuesParams {
  projectKey: string
  page: number
  size: number
}

/**
 * 담당자 변경 입력 타입.
 * PATCH /api/v1/issues/{key}/assignee body 형태.
 * - assigneeId=null → 담당자 해제 (3-state null 시맨틱)
 * - expectedVersion 필수 (낙관적 잠금 OCC)
 */
export interface ChangeAssigneeInput {
  /** 담당자 UUID. null이면 해제. */
  assigneeId: string | null
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 */
  expectedVersion: number
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

/** backend `{ data: { transitions: [...] } }` 전이 목록 응답 파싱 헬퍼 (내부 전용) */
const transitionsResponseSchema = z.object({
  data: z.object({ transitions: z.array(issueTransitionSchema) }),
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

/**
 * 이슈 담당자를 변경하거나 해제한다.
 * PATCH /api/v1/issues/{key}/assignee body { assigneeId: UUID|null, expectedVersion: Long }
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @param input assigneeId(UUID 또는 null) · expectedVersion(OCC 버전)
 * @returns 변경된 IssueResponse — assigneeId와 version이 갱신된 상태
 * @throws ApiError(409) 낙관적 잠금 충돌 시
 * @throws ApiError(422) assigneeId가 실재하지 않는 사용자일 때 (ASSIGNEE_NOT_FOUND)
 */
export async function changeAssignee(key: string, input: ChangeAssigneeInput): Promise<IssueResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/assignee`, { method: 'PATCH', body: input })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueResponseSchema).parse(raw)
  return wrapped.data
}
