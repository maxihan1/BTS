// 프로젝트 목록/단건/생성/이름변경/아카이브 API 클라이언트 — Zod 스키마 + apiGet/apiFetch 래퍼 (FR-UX-06 PR12 Task 1, FR-PJ PR-5 Task 3)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend ProjectResponse DTO 1:1 대응 (id/key/name/archived, FR-PJ-04 BE-1)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 단건 응답 Zod 스키마.
 * backend `ProjectResponse`(id, key, name, archived) 1:1 대응 — leadUserId/createdAt 등은 이 PR
 * 범위 밖이라 DTO에 없다(invent 금지, frontend-zod-backend-dto-contract-gap 재발 방지).
 *
 * `archived`는 `.optional()`이다 — 실 백엔드는 `Boolean` 원시타입이라 항상 이 키를 포함해 응답하지만,
 * 기존 `mocks/project-list-handlers.ts`의 `projectListFixtures`(FR-UX-06 PR12, id/key/name 3필드만)가
 * archived 없이 이미 여러 테스트(use-projects/Sidebar/ProjectTree/navigation-contract)에서 소비되고
 * 있어 required로 강화하면 전부 z.parse 실패로 깨진다(zod-schema-strengthen-inline-mock-fanout 사고
 * 재발 방지). 부재(undefined) 시 소비측이 false로 취급한다.
 */
export const projectSchema = z.object({
  id: z.string().uuid(),
  key: z.string(),
  name: z.string(),
  archived: z.boolean().optional(),
})

/** backend `{ data: [...] }` 프로젝트 목록 응답 래퍼 Zod 스키마 */
export const projectListResponseSchema = z.object({
  data: z.array(projectSchema),
})

/** backend `{ data: {...} }` 프로젝트 단건 응답 래퍼 Zod 스키마 — GET 단건/POST 생성 공용 */
const projectDataSchema = z.object({
  data: projectSchema,
})

/**
 * 프로젝트 아카이브/아카이브 해제 결과 Zod 스키마.
 * backend `ProjectArchiveResponse`(projectId, projectKey, archivedAt) 1:1 대응 — `projectSchema`와
 * 필드명이 다르다(id/key가 아니라 projectId/projectKey, ProjectArchiveController.kt 실측 확인).
 * archivedAt은 archive 응답에서 non-null, unarchive 응답에서 null이다.
 */
export const projectArchiveResultSchema = z.object({
  projectId: z.string().uuid(),
  projectKey: z.string(),
  archivedAt: z.string().nullable(),
})

/** backend `{ data: {...} }` 아카이브/아카이브 해제 결과 응답 래퍼 Zod 스키마 */
const projectArchiveDataSchema = z.object({
  data: projectArchiveResultSchema,
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 단건 타입 */
export type Project = z.infer<typeof projectSchema>

/** 프로젝트 아카이브/아카이브 해제 결과 타입 */
export type ProjectArchiveResult = z.infer<typeof projectArchiveResultSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 목록 bare 엔드포인트 — 하위리소스(`/api/v1/projects/{projectIdOrKey}/...`)와 구분되는 정확 경로 */
const PROJECTS_BASE_PATH = '/api/v1/projects'

/** 프로젝트 단건 하위리소스 경로 헬퍼 */
function projectPath(idOrKey: string): string {
  return `${PROJECTS_BASE_PATH}/${idOrKey}`
}

/**
 * 로그인 사용자가 접근 가능한 프로젝트 목록을 조회한다.
 *
 * GET /api/v1/projects?archived={archived} → `{ data: [...] }` 언래핑 후 반환.
 * name 오름차순 정렬은 백엔드가 보장한다(프론트 재정렬 없음).
 * 멤버십이 없으면 빈 배열(fail-closed, 404 아님).
 *
 * @param archived 아카이브 필터 — 기본 false(활성 프로젝트만)
 * @returns Project 배열
 * @throws ApiError(401) 미인증 시
 */
export async function listProjects(archived = false): Promise<Project[]> {
  const wrapped = await apiGet(
    `${PROJECTS_BASE_PATH}?archived=${archived}`,
    projectListResponseSchema,
  )
  return wrapped.data
}

/**
 * 프로젝트 단건을 조회한다.
 *
 * GET /api/v1/projects/{idOrKey} → `{ data: {...} }` 언래핑 후 반환.
 *
 * @param idOrKey 프로젝트 UUID 또는 key
 * @returns Project
 * @throws ApiError(404, ISSUE_PROJECT_NOT_FOUND) 프로젝트 미존재 시
 * @throws ApiError(403, ISSUE_PROJECT_FORBIDDEN) BROWSE 권한 없음 시
 */
export async function getProject(idOrKey: string): Promise<Project> {
  const wrapped = await apiGet(projectPath(idOrKey), projectDataSchema)
  return wrapped.data
}

/**
 * 새 프로젝트를 생성한다.
 *
 * POST /api/v1/projects body `{key, name}` → 201 `{ data: {...} }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param key 프로젝트 식별 접두사 — 대문자로 시작, 대문자+숫자 2~10자
 * @param name 프로젝트 이름
 * @returns 생성된 Project
 * @throws ApiError(409, ISSUE_PROJECT_KEY_ALREADY_EXISTS) key 중복 시
 * @throws ApiError(403, ISSUE_PROJECT_FORBIDDEN) CREATE_PROJECT 권한 없음 시
 * @throws ApiError(400, ISSUE_PROJECT_VALIDATION_FAILED) key/name 형식 위반 시
 */
export async function createProject(key: string, name: string): Promise<Project> {
  const res = await apiFetch(PROJECTS_BASE_PATH, {
    method: 'POST',
    body: { key, name },
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = projectDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 프로젝트 이름을 변경한다.
 *
 * PATCH /api/v1/projects/{idOrKey} body `{name}` → 204 No Content(바디 없음).
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param idOrKey 프로젝트 UUID 또는 key
 * @param name 새 이름
 * @returns void
 * @throws ApiError(404, ISSUE_PROJECT_SETTINGS_NOT_FOUND) 프로젝트 미존재 시
 * @throws ApiError(403, ISSUE_PROJECT_SETTINGS_FORBIDDEN) PROJECT_ADMIN 아닌 경우
 * @throws ApiError(400, ISSUE_PROJECT_SETTINGS_VALIDATION_FAILED) name 형식 위반 시
 */
export async function updateProjectName(idOrKey: string, name: string): Promise<void> {
  const res = await apiFetch(projectPath(idOrKey), {
    method: 'PATCH',
    body: { name },
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 프로젝트를 아카이브한다.
 *
 * POST /api/v1/projects/{idOrKey}/archive → 200 `{ data: {...} }` 언래핑 후 반환(archivedAt non-null).
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다. 이미 아카이브된 프로젝트도 멱등하게 200을 반환한다.
 *
 * @param idOrKey 프로젝트 UUID 또는 key
 * @returns ProjectArchiveResult
 * @throws ApiError(404, ISSUE_PROJECT_ARCHIVE_NOT_FOUND) 프로젝트 미존재 시
 * @throws ApiError(403, ISSUE_PROJECT_ARCHIVE_FORBIDDEN) PROJECT_ADMIN 아닌 경우
 */
export async function archiveProject(idOrKey: string): Promise<ProjectArchiveResult> {
  const res = await apiFetch(`${projectPath(idOrKey)}/archive`, {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = projectArchiveDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 프로젝트의 아카이브를 해제한다.
 *
 * POST /api/v1/projects/{idOrKey}/unarchive → 200 `{ data: {...} }` 언래핑 후 반환(archivedAt null).
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다. 이미 활성 상태인 프로젝트도 멱등하게 200을 반환한다.
 *
 * @param idOrKey 프로젝트 UUID 또는 key
 * @returns ProjectArchiveResult
 * @throws ApiError(404, ISSUE_PROJECT_ARCHIVE_NOT_FOUND) 프로젝트 미존재 시
 * @throws ApiError(403, ISSUE_PROJECT_ARCHIVE_FORBIDDEN) PROJECT_ADMIN 아닌 경우
 */
export async function unarchiveProject(idOrKey: string): Promise<ProjectArchiveResult> {
  const res = await apiFetch(`${projectPath(idOrKey)}/unarchive`, {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = projectArchiveDataSchema.parse(raw)
  return wrapped.data
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 프로젝트 BC errorCode를 추출한다.
 *
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 *
 * extractComponentErrorCode/extractProjectLeadErrorCode 동형 패턴.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractProjectErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
