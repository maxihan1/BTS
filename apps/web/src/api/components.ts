// 컴포넌트 BC REST API 클라이언트 — CRUD + X-XSRF-TOKEN + errorCode 추출 헬퍼 (FR-CM-01)
import { z } from 'zod'
import { apiGet, apiFetch, ApiError } from './client'
import { readXsrfToken } from './sessions'
import {
  componentResponseSchema,
  dataResponseSchema,
  type Component,
  type CreateComponentInput,
  type UpdateComponentInput,
} from './components.types'

export type { Component, CreateComponentInput, UpdateComponentInput } from './components.types'
export type { ChangeLeadInput } from './components.types'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 컴포넌트 단건 + 목록 응답 래퍼 스키마 — 내부 전용 */
const componentDataSchema = dataResponseSchema(componentResponseSchema)
const componentListDataSchema = dataResponseSchema(z.array(componentResponseSchema))

/** 기본 경로 헬퍼 */
function basePath(projectIdOrKey: string): string {
  return `/api/v1/projects/${projectIdOrKey}/components`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 컴포넌트 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectIdOrKey}/components → `{ data: [...] }` 언래핑 후 반환.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @returns Component 배열 — 컴포넌트가 없으면 빈 배열
 * @throws ApiError(404) 프로젝트 미존재 시
 */
export async function fetchComponents(projectIdOrKey: string): Promise<Component[]> {
  const wrapped = await apiGet(basePath(projectIdOrKey), componentListDataSchema)
  return wrapped.data
}

/**
 * 컴포넌트 단건을 조회한다.
 *
 * GET /api/v1/projects/{projectIdOrKey}/components/{id} → `{ data: ... }` 언래핑 후 반환.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 컴포넌트 UUID
 * @returns Component
 * @throws ApiError(404) 컴포넌트 미존재 시
 */
export async function fetchComponent(projectIdOrKey: string, id: string): Promise<Component> {
  const wrapped = await apiGet(`${basePath(projectIdOrKey)}/${id}`, componentDataSchema)
  return wrapped.data
}

/**
 * 컴포넌트를 생성한다.
 *
 * POST /api/v1/projects/{projectIdOrKey}/components → 201 `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param input name(필수) · description(선택) · leadUserId(선택)
 * @returns 생성된 Component
 * @throws ApiError(409, COMPONENT_NAME_DUPLICATE) 이름 중복 시
 * @throws ApiError(422, COMPONENT_LEAD_NOT_FOUND) 리드 사용자 미존재 시
 */
export async function createComponent(
  projectIdOrKey: string,
  input: CreateComponentInput,
): Promise<Component> {
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
  const wrapped = componentDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 컴포넌트 이름 / 설명을 수정한다.
 *
 * PATCH /api/v1/projects/{projectIdOrKey}/components/{id} → `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 컴포넌트 UUID
 * @param input name(선택) · description(선택)
 * @returns 수정된 Component
 * @throws ApiError(404) 컴포넌트 미존재 시
 * @throws ApiError(409, COMPONENT_NAME_DUPLICATE) 이름 중복 시
 */
export async function updateComponent(
  projectIdOrKey: string,
  id: string,
  input: UpdateComponentInput,
): Promise<Component> {
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
  const wrapped = componentDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 컴포넌트 리드를 변경하거나 해제한다.
 *
 * PATCH /api/v1/projects/{projectIdOrKey}/components/{id}/lead → `{ data: ... }` 언래핑 후 반환.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 컴포넌트 UUID
 * @param leadUserId 새 리드 UUID. null 이면 리드 해제.
 * @returns 변경된 Component
 * @throws ApiError(404) 컴포넌트 미존재 시
 * @throws ApiError(422, COMPONENT_LEAD_NOT_FOUND) 리드 사용자 미존재 시
 */
export async function changeComponentLead(
  projectIdOrKey: string,
  id: string,
  leadUserId: string | null,
): Promise<Component> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${id}/lead`, {
    method: 'PATCH',
    body: { leadUserId },
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = componentDataSchema.parse(raw)
  return wrapped.data
}

/**
 * 컴포넌트를 삭제한다.
 *
 * DELETE /api/v1/projects/{projectIdOrKey}/components/{id} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectIdOrKey 프로젝트 UUID 또는 키
 * @param id 삭제할 컴포넌트 UUID
 * @returns void
 * @throws ApiError(404) 컴포넌트 미존재 시
 * @throws ApiError(403) 권한 없음 시
 */
export async function deleteComponent(projectIdOrKey: string, id: string): Promise<void> {
  const res = await apiFetch(`${basePath(projectIdOrKey)}/${id}`, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': readXsrfToken() },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 컴포넌트 BC errorCode 를 추출한다.
 *
 * ApiError 이면 body.errorCode 를 string 으로 반환한다.
 * ApiError 가 아니거나 errorCode 필드가 없으면 null 을 반환한다.
 *
 * use-bulk-operation.ts 의 extractDetail 동형 패턴.
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractComponentErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
