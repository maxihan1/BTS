// 버전 BC REST API 클라이언트 — CRUD + 날짜 변경 + 상태 전이 + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-VR-01, FR-VR-02)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  versionResponseSchema,
  dataResponseSchema,
  type Version,
  type CreateVersionInput,
  type UpdateVersionInput,
  type ChangeDatesInput,
  type VersionStatus,
  type ChangeVersionStatusInput,
} from './versions.types'

export type {
  Version,
  CreateVersionInput,
  UpdateVersionInput,
  ChangeDatesInput,
  VersionStatus,
  ChangeVersionStatusInput,
} from './versions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 단건 + 목록 응답 래퍼 스키마 — 내부 전용 */
const versionDataSchema = dataResponseSchema(versionResponseSchema)
const versionListDataSchema = dataResponseSchema(z.array(versionResponseSchema))

/** 기본 경로 헬퍼 */
function basePath(projectIdOrKey: string): string {
  return `/api/v1/projects/${projectIdOrKey}/versions`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 버전 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectIdOrKey}/versions → `{ data: [...] }` 언래핑 후 반환.
 * deleted_at IS NULL 인 활성 버전만 포함되며 name 오름차순으로 정렬된다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @returns Version 배열 — 버전이 없으면 빈 배열
 * @throws ApiError(404) 프로젝트 미존재 시
 */
export async function fetchVersions(projectIdOrKey: string): Promise<Version[]> {
  const wrapped = await apiGet(basePath(projectIdOrKey), versionListDataSchema)
  return wrapped.data
}

/**
 * 버전 단건을 조회한다.
 *
 * GET /api/v1/projects/{projectIdOrKey}/versions/{id} → `{ data: ... }` 언래핑 후 반환.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 버전 UUID
 * @returns Version
 * @throws ApiError(404) 버전 미존재 시
 */
export async function fetchVersion(projectIdOrKey: string, id: string): Promise<Version> {
  const wrapped = await apiGet(`${basePath(projectIdOrKey)}/${id}`, versionDataSchema)
  return wrapped.data
}

/**
 * 버전을 생성한다.
 *
 * POST /api/v1/projects/{projectIdOrKey}/versions → 201 `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param input name(필수) · description(선택) · startDate(선택) · releaseDate(선택)
 * @returns 생성된 Version
 * @throws ApiError(409, VERSION_NAME_DUPLICATE) 이름 중복 시
 * @throws ApiError(404) 프로젝트 미존재 시
 */
export async function createVersion(
  projectIdOrKey: string,
  input: CreateVersionInput,
): Promise<Version> {
  const res = await apiFetch(basePath(projectIdOrKey), {
    method: 'POST',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = versionDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 버전 이름 / 설명을 수정한다.
 *
 * PATCH /api/v1/projects/{projectIdOrKey}/versions/{id} → `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 * null / 생략 = 무변경 (sentinel 정책). 날짜 변경은 changeVersionDates 를 사용한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 버전 UUID
 * @param input name(선택) · description(선택)
 * @returns 수정된 Version
 * @throws ApiError(404) 버전 미존재 시
 * @throws ApiError(409, VERSION_NAME_DUPLICATE) 이름 중복 시
 */
export async function updateVersion(
  projectIdOrKey: string,
  id: string,
  input: UpdateVersionInput,
): Promise<Version> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${id}`, {
    method: 'PATCH',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = versionDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 버전의 시작일과 릴리스 예정일을 지정하거나 해제한다.
 *
 * PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/dates → `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 * startDate / releaseDate 가 null 이면 해제, 문자열(yyyy-MM-dd)이면 지정.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 버전 UUID
 * @param input startDate(null 허용) · releaseDate(null 허용)
 * @returns 변경된 Version
 * @throws ApiError(404) 버전 미존재 시
 */
export async function changeVersionDates(
  projectIdOrKey: string,
  id: string,
  input: ChangeDatesInput,
): Promise<Version> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${id}/dates`, {
    method: 'PATCH',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = versionDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 버전을 소프트 삭제한다.
 *
 * DELETE /api/v1/projects/{projectIdOrKey}/versions/{id} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 삭제할 버전 UUID
 * @returns void
 * @throws ApiError(404) 버전 미존재 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function deleteVersion(projectIdOrKey: string, id: string): Promise<void> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${id}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 버전 상태를 전이한다.
 *
 * PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/status → `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 * changeDates 서브리소스 패턴 동형.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 버전 UUID
 * @param status 전이 대상 상태 (UNRELEASED | RELEASED | ARCHIVED)
 * @returns 전이된 Version
 * @throws ApiError(409, VERSION_TRANSITION_NOT_ALLOWED) 불허 전이 시
 * @throws ApiError(404) 버전 미존재 시
 */
export async function changeVersionStatus(
  projectIdOrKey: string,
  id: string,
  status: VersionStatus,
): Promise<Version> {
  const input: ChangeVersionStatusInput = { status }
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${id}/status`, {
    method: 'PATCH',
    body: input,
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = versionDataSchema.parse(raw)
  return wrapped.data
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 버전 BC errorCode 를 추출한다.
 *
 * ApiError 이면 body.errorCode 를 string 으로 반환한다.
 * ApiError 가 아니거나 errorCode 필드가 없으면 null 을 반환한다.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractVersionErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
