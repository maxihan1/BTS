// 프로젝트 멤버 CRUD API 클라이언트 — X-XSRF-TOKEN + ProjectMemberApiError 에러코드 보존 (FR-PM-01 Task F1)
import { z } from 'zod'
import { apiFetch } from './client'
import { readXsrfToken } from './sessions'
import {
  memberResponseSchema,
  membersListSchema,
  memberErrorResponseSchema,
  type ProjectMember,
  type ProjectRole,
  type AddMemberInput,
} from './project-members.types'

export {
  memberResponseSchema,
  membersListSchema,
  roleEnum,
} from './project-members.types'

export type {
  ProjectMember,
  ProjectRole,
  ProjectMembersList,
  AddMemberInput,
  ChangeRoleInput,
} from './project-members.types'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 클래스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 API 전용 에러.
 * backend `{ error: snake_case }` 에러코드를 보존해 UI에서 에러 종류를 구분한다.
 *
 * 주요 errorCode.
 * - project_not_found — 프로젝트 미존재 (404)
 * - not_project_admin — 권한 없음 (403)
 * - last_admin_protected — 마지막 관리자 보호 (409)
 * - membership_already_exists — 이미 멤버 (409)
 * - user_not_found — 사용자 미존재 (404)
 * - member_not_found — 멤버 미존재 (404)
 * - invalid_role — 허용되지 않는 역할 (422)
 * - unauthorized — 미인증 (401)
 */
export class ProjectMemberApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly errorCode: string,
  ) {
    super(`ProjectMemberApiError [${errorCode}] HTTP ${status}`)
    this.name = 'ProjectMemberApiError'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸리티
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 비-2xx 응답을 ProjectMemberApiError로 변환해 throw한다.
 * backend `{ error: snake_case }` errorCode를 보존한다.
 */
async function throwMemberApiError(res: Response): Promise<never> {
  const rawBody: unknown = await res.json().catch(() => ({}))
  const parsed = memberErrorResponseSchema.safeParse(rawBody)
  const errorCode = parsed.success ? parsed.data.error : 'unknown_error'
  throw new ProjectMemberApiError(res.status, errorCode)
}

/**
 * 응답 ok 여부 확인 후 Zod 파싱까지 수행하는 헬퍼.
 * 비-2xx → ProjectMemberApiError throw.
 */
async function parseMemberResponse<T>(res: Response, schema: z.ZodSchema<T>): Promise<T> {
  if (!res.ok) {
    return throwMemberApiError(res)
  }
  const raw: unknown = await res.json()
  return schema.parse(raw)
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/members → `{ members: [...] }` 래퍼를 언래핑해 반환.
 * displayName / username은 users 조인 결과이며, orphan 멤버십 시 null이다.
 *
 * @param projectKey 프로젝트 식별 키
 * @returns ProjectMember 배열 — 멤버가 없으면 빈 배열
 * @throws ProjectMemberApiError(404, "project_not_found") 프로젝트 미존재 또는 비멤버
 * @throws ProjectMemberApiError(401, "unauthorized") 미인증
 */
export async function fetchProjectMembers(projectKey: string): Promise<ProjectMember[]> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/members`, { method: 'GET' })
  const wrapped = await parseMemberResponse(res, membersListSchema)
  return wrapped.members
}

/**
 * 프로젝트에 멤버를 추가한다.
 *
 * POST /api/v1/projects/{projectKey}/members → 201 ProjectMemberResponse.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param input userId(UUID) + role
 * @returns 생성된 ProjectMember
 * @throws ProjectMemberApiError(403, "not_project_admin") 권한 없음
 * @throws ProjectMemberApiError(404, "user_not_found") 사용자 미존재
 * @throws ProjectMemberApiError(409, "membership_already_exists") 이미 멤버
 * @throws ProjectMemberApiError(401, "unauthorized") 미인증
 */
export async function addMember(
  projectKey: string,
  input: AddMemberInput,
): Promise<ProjectMember> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/members`, {
    method: 'POST',
    body: input,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  return parseMemberResponse(res, memberResponseSchema)
}

/**
 * 멤버의 역할을 변경한다.
 *
 * PATCH /api/v1/projects/{projectKey}/members/{userId} → 200 ProjectMemberResponse.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param userId 역할을 변경할 사용자 UUID
 * @param role 새 역할
 * @returns 변경된 ProjectMember
 * @throws ProjectMemberApiError(403, "not_project_admin") 권한 없음
 * @throws ProjectMemberApiError(404, "member_not_found") 멤버 미존재
 * @throws ProjectMemberApiError(409, "last_admin_protected") 마지막 관리자 보호
 * @throws ProjectMemberApiError(422, "invalid_role") 허용되지 않는 역할
 * @throws ProjectMemberApiError(401, "unauthorized") 미인증
 */
export async function changeRole(
  projectKey: string,
  userId: string,
  role: ProjectRole,
): Promise<ProjectMember> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/members/${userId}`, {
    method: 'PATCH',
    body: { role },
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  return parseMemberResponse(res, memberResponseSchema)
}

/**
 * 프로젝트에서 멤버를 제거한다.
 *
 * DELETE /api/v1/projects/{projectKey}/members/{userId} → 204 No Content.
 * CSRF 방어를 위해 X-XSRF-TOKEN 헤더를 포함한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param userId 제거할 사용자 UUID
 * @returns void
 * @throws ProjectMemberApiError(403, "not_project_admin") 권한 없음
 * @throws ProjectMemberApiError(404, "member_not_found") 멤버 미존재
 * @throws ProjectMemberApiError(409, "last_admin_protected") 마지막 관리자 보호
 * @throws ProjectMemberApiError(401, "unauthorized") 미인증
 */
export async function removeMember(projectKey: string, userId: string): Promise<void> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/members/${userId}`, {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    return throwMemberApiError(res)
  }
}
