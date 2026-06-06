// 프로젝트 리드 API 클라이언트 — GET/PATCH + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-CM-04)
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import { dataResponseSchema } from './components.types'
import { projectLeadResponseSchema, type ProjectLead } from './project-lead.types'

export type { ProjectLead } from './project-lead.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 리드 단건 응답 래퍼 스키마 — 내부 전용 */
const projectLeadDataSchema = dataResponseSchema(projectLeadResponseSchema)

/** 기본 경로 헬퍼 */
function leadPath(projectIdOrKey: string): string {
  return `/api/v1/projects/${projectIdOrKey}/lead`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 리드를 조회한다.
 *
 * GET /api/v1/projects/{projectIdOrKey}/lead → `{ data: {...} }` 언래핑 후 반환.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @returns ProjectLead — leadUserId 는 null 이면 리드 미지정
 * @throws ApiError(404) 프로젝트 미존재 시
 */
export async function fetchProjectLead(projectIdOrKey: string): Promise<ProjectLead> {
  const wrapped = await apiGet(leadPath(projectIdOrKey), projectLeadDataSchema)
  return wrapped.data
}

/**
 * 프로젝트 리드를 변경하거나 해제한다.
 *
 * PATCH /api/v1/projects/{projectIdOrKey}/lead → `{ data: {...} }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param leadUserId 새 리드 UUID. null 이면 리드 해제.
 * @returns 변경된 ProjectLead
 * @throws ApiError(404) 프로젝트 미존재 시
 * @throws ApiError(422, PROJECT_LEAD_NOT_FOUND) 리드 사용자 미존재 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function changeProjectLead(
  projectIdOrKey: string,
  leadUserId: string | null,
): Promise<ProjectLead> {
  const res = await apiFetch(leadPath(projectIdOrKey), {
    method: 'PATCH',
    body: { leadUserId },
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = projectLeadDataSchema.parse(raw)
  return wrapped.data
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 프로젝트 리드 BC errorCode 를 추출한다.
 *
 * ApiError 이면 body.errorCode 를 string 으로 반환한다.
 * ApiError 가 아니거나 errorCode 필드가 없으면 null 을 반환한다.
 *
 * extractComponentErrorCode 동형 패턴.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractProjectLeadErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
