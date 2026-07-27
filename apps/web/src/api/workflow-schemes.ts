// 워크플로우 스킴 backend API 클라이언트 + 에러 클래스 + sentinel 유틸리티
import { z } from 'zod'
import { apiFetch, apiGet, apiPost, ApiError } from './client'
import {
  schemeListItemSchema,
  schemeDetailSchema,
  schemeMutationResultSchema,
  mappingCreatedSchema,
  assignedSchemeSchema,
  assignmentRecordSchema,
  dataOf,
} from './workflow-schemes.types'
import type {
  SchemeListItem,
  SchemeDetail,
  SchemeMutationResult,
  MappingCreated,
  AssignedScheme,
  AssignmentRecord,
  CreateSchemeInput,
  UpdateSchemeInput,
  AddMappingInput,
  AssignSchemeInput,
} from './workflow-schemes.types'

export {
  schemeListItemSchema,
  schemeDetailSchema,
  schemeMutationResultSchema,
  mappingDetailSchema,
  mappingCreatedSchema,
  assignedSchemeSchema,
  assignmentRecordSchema,
} from './workflow-schemes.types'

export type {
  SchemeListItem,
  SchemeDetail,
  SchemeMutationResult,
  MappingDetail,
  MappingCreated,
  AssignedScheme,
  AssignmentRecord,
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
 * GET /api/v1/workflow-schemes → { data: SchemeListItem[] }
 */
export async function fetchWorkflowSchemes(): Promise<SchemeListItem[]> {
  const wrapped = await apiGet(
    '/api/v1/workflow-schemes',
    dataOf(z.array(schemeListItemSchema)),
  )
  return wrapped.data
}

/**
 * 워크플로우 스킴 단건(매핑 동봉)을 조회한다.
 * GET /api/v1/workflow-schemes/{schemeKey} → { data: SchemeDetail }
 *
 * @param schemeKey 스킴 식별 키
 * @throws ApiError(404) 스킴이 없을 때
 */
export async function fetchWorkflowScheme(schemeKey: string): Promise<SchemeDetail> {
  const wrapped = await apiGet(
    `/api/v1/workflow-schemes/${schemeKey}`,
    dataOf(schemeDetailSchema),
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
 * POST /api/v1/workflow-schemes → { data: SchemeMutationResult }
 *
 * @param input key(필수) + name(필수) + description(선택)
 * @returns 생성된 SchemeMutationResult
 */
export async function createWorkflowScheme(input: CreateSchemeInput): Promise<SchemeMutationResult> {
  const wrapped = await apiPost(
    '/api/v1/workflow-schemes',
    input,
    dataOf(schemeMutationResultSchema),
  )
  return wrapped.data
}

/**
 * 워크플로우 스킴 이름/설명을 수정한다.
 * PUT /api/v1/workflow-schemes/{schemeKey} → { data: SchemeMutationResult }
 *
 * @param schemeKey 수정할 스킴 키
 * @param input name(필수) + description(선택)
 */
export async function updateWorkflowScheme(schemeKey: string, input: UpdateSchemeInput): Promise<SchemeMutationResult> {
  const res = await apiFetch(`/api/v1/workflow-schemes/${schemeKey}`, { method: 'PUT', body: input })
  const wrapped = await parseSchemeResponse(res, dataOf(schemeMutationResultSchema))
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
 * POST /api/v1/workflow-schemes/{schemeKey}/mappings → { data: MappingCreated }
 *
 * @param schemeKey 매핑을 추가할 스킴 키
 * @param input issueTypeKey(null이면 default 매핑) + workflowKey
 * @throws WorkflowSchemeApiError(409, "MAPPING_DUPLICATE") 동일 issueTypeKey 중복 시
 * @throws WorkflowSchemeApiError(409, "MAPPING_DEFAULT_DUPLICATE") default 매핑 중복 시
 */
export async function addMapping(schemeKey: string, input: AddMappingInput): Promise<MappingCreated> {
  const res = await apiFetch(`/api/v1/workflow-schemes/${schemeKey}/mappings`, { method: 'POST', body: input })
  const wrapped = await parseSchemeResponse(res, dataOf(mappingCreatedSchema))
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
 * GET /api/v1/projects/{projectKey}/workflow-scheme → { data: AssignedScheme }
 *
 * ## 404 는 「미할당」이 아니라 「프로젝트 없음」이다
 * 백엔드는 배정이 없는 프로젝트에 404 를 내지 않는다 —
 * `WorkflowSchemeApplicationService.findAssignedScheme` 이 `software-scheme` 을 **자동 배정한 뒤
 * 그것을 반환**한다(EC-1 D10). 그래서 이 엔드포인트가 404 를 내는 경우는
 * `ProjectWorkflowSchemeController.getAssignedScheme` 의 `Project not found` 하나뿐이다.
 *
 * 예전에는 이 404 를 `null`(미할당)로 삼켜, 존재하지 않는 프로젝트 URL 로 들어가면
 * 「스킴 미할당」이라는 **틀린 안내**가 떴다. 지금은 에러로 흘려보내고 화면이
 * 「프로젝트를 찾을 수 없습니다」로 표시한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @returns 배정된 AssignedScheme
 * @throws WorkflowSchemeApiError(404) 프로젝트가 존재하지 않을 때
 */
export async function fetchProjectAssignment(projectKey: string): Promise<AssignedScheme> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/workflow-scheme`, { method: 'GET' })
  if (!res.ok) {
    return throwSchemeApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(assignedSchemeSchema).parse(raw).data
}

/**
 * 프로젝트에 워크플로우 스킴을 할당(UPSERT)한다.
 * PUT /api/v1/projects/{projectKey}/workflow-scheme → { data: AssignmentRecord }
 *
 * @param projectKey 프로젝트 식별 키
 * @param input schemeKey
 */
export async function assignSchemeToProject(projectKey: string, input: AssignSchemeInput): Promise<AssignmentRecord> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/workflow-scheme`, { method: 'PUT', body: input })
  const wrapped = await parseSchemeResponse(res, dataOf(assignmentRecordSchema))
  return wrapped.data
}

/**
 * 프로젝트에 할당 가능한 워크플로우 스킴 목록을 조회한다.
 * GET /api/v1/projects/{projectKey}/assignable-workflow-schemes → { data: AssignedScheme[] }
 * 프로젝트 설정 화면(스킴 선택 UI)에서 사용 — 관리자 전용 fetchWorkflowSchemes와는 별개 엔드포인트.
 *
 * @param projectKey 프로젝트 식별 키
 */
export async function fetchAssignableWorkflowSchemes(projectKey: string): Promise<AssignedScheme[]> {
  const wrapped = await apiGet(
    `/api/v1/projects/${projectKey}/assignable-workflow-schemes`,
    dataOf(z.array(assignedSchemeSchema)),
  )
  return wrapped.data
}
