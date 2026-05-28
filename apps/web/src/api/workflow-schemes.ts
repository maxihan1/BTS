// 워크플로우 스킴 backend API 클라이언트 + 에러 클래스 + sentinel 유틸리티
import { z } from 'zod'
import { apiFetch, apiGet, apiPost, ApiError } from './client'
import {
  schemeResponseSchema,
  schemeDetailResponseSchema,
  mappingResponseSchema,
  assignmentResponseSchema,
  dataOf,
} from './workflow-schemes.types'
import type {
  SchemeResponse,
  SchemeDetailResponse,
  MappingResponse,
  AssignmentResponse,
  CreateSchemeInput,
  UpdateSchemeInput,
  AddMappingInput,
  AssignSchemeInput,
} from './workflow-schemes.types'

export {
  schemeResponseSchema,
  schemeDetailResponseSchema,
  mappingResponseSchema,
  assignmentResponseSchema,
} from './workflow-schemes.types'

export type {
  SchemeResponse,
  SchemeDetailResponse,
  MappingResponse,
  AssignmentResponse,
  CreateSchemeInput,
  UpdateSchemeInput,
  AddMappingInput,
  AssignSchemeInput,
} from './workflow-schemes.types'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 클래스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 API 전용 에러.
 * RFC 7807 `code` 필드(errorCode)를 보존해 UI에서 에러 종류를 구분할 수 있다.
 *
 * 주요 errorCode.
 * - SCHEME_IN_USE — 프로젝트에서 사용 중인 스킴은 삭제 불가 (409)
 * - MAPPING_DUPLICATE — 동일 issueTypeKey에 중복 매핑 시도 (409)
 * - MAPPING_DEFAULT_DUPLICATE — default 매핑이 이미 존재할 때 재추가 시도 (409)
 */
export class WorkflowSchemeApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly errorCode: string,
    public readonly detail: string,
  ) {
    super(`WorkflowSchemeApiError [${errorCode}] ${detail}`)
    this.name = 'WorkflowSchemeApiError'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸리티
// ─────────────────────────────────────────────────────────────────────────────

/** RFC 7807 에러 응답 Zod 스키마 — code 필드 보존 */
const rfc7807ErrorSchema = z.object({
  code: z.string().default('UNKNOWN'),
  detail: z.string().default(''),
})

/**
 * 비-2xx 응답을 WorkflowSchemeApiError로 변환해 throw한다.
 * RFC 7807 `code` 필드 → errorCode 보존.
 */
async function throwSchemeApiError(res: Response): Promise<never> {
  const rawBody: unknown = await res.json().catch(() => ({}))
  const parsed = rfc7807ErrorSchema.safeParse(rawBody)
  const errorCode = parsed.success ? parsed.data.code : 'UNKNOWN'
  const detail = parsed.success ? parsed.data.detail : String(rawBody)
  throw new WorkflowSchemeApiError(res.status, errorCode, detail)
}

/**
 * 응답 ok 여부 확인 후 Zod 파싱까지 수행하는 헬퍼.
 * 비-2xx → WorkflowSchemeApiError throw.
 */
async function parseSchemeResponse<T>(res: Response, schema: z.ZodSchema<T>): Promise<T> {
  if (!res.ok) {
    return throwSchemeApiError(res)
  }
  const raw: unknown = await res.json()
  return schema.parse(raw)
}

// ─────────────────────────────────────────────────────────────────────────────
// Sentinel 유틸리티 (form select → POST body 직렬화 시 사용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * form select의 sentinel 값 `"__default__"`을 null로 변환한다.
 * default 매핑 선택 시 issueTypeKey를 null로 직렬화해야 하므로,
 * select 컴포넌트가 반환하는 `"__default__"` 문자열을 POST body 전 이 함수로 변환할 것.
 *
 * @example
 * toNullableIssueTypeKey("__default__") // null
 * toNullableIssueTypeKey("bug")         // "bug"
 */
export function toNullableIssueTypeKey(value: string): string | null {
  return value === '__default__' ? null : value
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 목록을 조회한다.
 * GET /api/v1/workflow-schemes → { data: SchemeResponse[] }
 */
export async function fetchWorkflowSchemes(): Promise<SchemeResponse[]> {
  const wrapped = await apiGet(
    '/api/v1/workflow-schemes',
    dataOf(z.array(schemeResponseSchema)),
  )
  return wrapped.data
}

/**
 * 워크플로우 스킴 단건(매핑 동봉)을 조회한다.
 * GET /api/v1/workflow-schemes/{schemeKey} → { data: SchemeDetailResponse }
 *
 * @param schemeKey 스킴 식별 키
 * @throws ApiError(404) 스킴이 없을 때
 */
export async function fetchWorkflowScheme(schemeKey: string): Promise<SchemeDetailResponse> {
  const wrapped = await apiGet(
    `/api/v1/workflow-schemes/${schemeKey}`,
    dataOf(schemeDetailResponseSchema),
  ).catch((err: unknown) => {
    if (err instanceof ApiError && err.status === 404) {
      throw new ApiError(404, { message: `워크플로우 스킴을 찾을 수 없습니다: ${schemeKey}` })
    }
    throw err
  })
  return wrapped.data
}

/**
 * 워크플로우 스킴을 생성한다.
 * POST /api/v1/workflow-schemes → { data: SchemeResponse }
 *
 * @param input name(필수) + description(선택)
 * @returns 생성된 SchemeResponse
 */
export async function createWorkflowScheme(input: CreateSchemeInput): Promise<SchemeResponse> {
  const wrapped = await apiPost(
    '/api/v1/workflow-schemes',
    input,
    dataOf(schemeResponseSchema),
  )
  return wrapped.data
}

/**
 * 워크플로우 스킴 이름/설명을 수정한다.
 * PUT /api/v1/workflow-schemes/{schemeKey} → { data: SchemeResponse }
 *
 * @param schemeKey 수정할 스킴 키
 * @param input name(선택) + description(선택)
 */
export async function updateWorkflowScheme(schemeKey: string, input: UpdateSchemeInput): Promise<SchemeResponse> {
  const res = await apiFetch(`/api/v1/workflow-schemes/${schemeKey}`, { method: 'PUT', body: input })
  const wrapped = await parseSchemeResponse(res, dataOf(schemeResponseSchema))
  return wrapped.data
}

/**
 * 워크플로우 스킴을 삭제한다.
 * DELETE /api/v1/workflow-schemes/{schemeKey} → 204 no content
 *
 * @throws WorkflowSchemeApiError(409, "SCHEME_IN_USE") 프로젝트가 사용 중인 스킴 삭제 시도 시
 */
export async function deleteWorkflowScheme(schemeKey: string): Promise<void> {
  const res = await apiFetch(`/api/v1/workflow-schemes/${schemeKey}`, { method: 'DELETE' })
  if (!res.ok) {
    return throwSchemeApiError(res)
  }
}

/**
 * 스킴에 이슈 타입-워크플로우 매핑을 추가한다.
 * POST /api/v1/workflow-schemes/{schemeKey}/mappings → { data: MappingResponse }
 *
 * @param schemeKey 매핑을 추가할 스킴 키
 * @param input issueTypeKey(null이면 default 매핑) + workflowKey
 * @throws WorkflowSchemeApiError(409, "MAPPING_DUPLICATE") 동일 issueTypeKey 중복 시
 * @throws WorkflowSchemeApiError(409, "MAPPING_DEFAULT_DUPLICATE") default 매핑 중복 시
 */
export async function addMapping(schemeKey: string, input: AddMappingInput): Promise<MappingResponse> {
  const res = await apiFetch(`/api/v1/workflow-schemes/${schemeKey}/mappings`, { method: 'POST', body: input })
  const wrapped = await parseSchemeResponse(res, dataOf(mappingResponseSchema))
  return wrapped.data
}

/**
 * 스킴에서 매핑을 삭제한다.
 * DELETE /api/v1/workflow-schemes/{schemeKey}/mappings/{mappingId} → 204 no content
 *
 * @param schemeKey 스킴 키
 * @param mappingId 삭제할 매핑 ID
 */
export async function deleteMapping(schemeKey: string, mappingId: number): Promise<void> {
  const res = await apiFetch(
    `/api/v1/workflow-schemes/${schemeKey}/mappings/${mappingId}`,
    { method: 'DELETE' },
  )
  if (!res.ok) {
    return throwSchemeApiError(res)
  }
}

/**
 * 프로젝트에 할당된 워크플로우 스킴을 조회한다.
 * GET /api/v1/projects/{projectKey}/workflow-scheme → { data: AssignmentResponse }
 * 할당이 없을 때 404를 반환한다 (EC-1 — 404는 에러가 아니라 "미할당" 상태).
 *
 * @param projectKey 프로젝트 식별 키
 * @returns AssignmentResponse 또는 null (할당 없음)
 */
export async function fetchProjectAssignment(projectKey: string): Promise<AssignmentResponse | null> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/workflow-scheme`, { method: 'GET' })
  if (res.status === 404) {
    return null
  }
  if (!res.ok) {
    return throwSchemeApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(assignmentResponseSchema).parse(raw).data
}

/**
 * 프로젝트에 워크플로우 스킴을 할당(UPSERT)한다.
 * PUT /api/v1/projects/{projectKey}/workflow-scheme → { data: AssignmentResponse }
 *
 * @param projectKey 프로젝트 식별 키
 * @param input schemeKey
 */
export async function assignSchemeToProject(projectKey: string, input: AssignSchemeInput): Promise<AssignmentResponse> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/workflow-scheme`, { method: 'PUT', body: input })
  const wrapped = await parseSchemeResponse(res, dataOf(assignmentResponseSchema))
  return wrapped.data
}
