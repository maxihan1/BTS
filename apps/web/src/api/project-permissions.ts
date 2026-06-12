// 프로젝트 권한 조회 API 클라이언트 — GET /api/v1/users/me/project-permissions + Zod 파싱
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 스키마
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `GET /api/v1/users/me/project-permissions` 응답 Zod 스키마.
 *
 * (C4) 백엔드 응답 계약 근거.
 * MyProjectPermissionController (Task 2)는 IssuePermission 및 ComponentPermission/
 * VersionPermission/CustomFieldPermission enum 이름을 permissions 맵의 키로 사용한다.
 * Map<String, Boolean> 직렬화 → `{ "CREATE": ..., "MANAGE_COMPONENTS": ..., "MANAGE_VERSIONS": ..., "MANAGE_CUSTOM_FIELDS": ..., "MANAGE_FIELD_PERMISSIONS": ..., "MANAGE_TEMPLATES": ... }`.
 * 이 스키마의 각 키는 백엔드 enum 이름과 1:1 대응한다.
 *
 * MANAGE_COMPONENTS / MANAGE_VERSIONS / MANAGE_CUSTOM_FIELDS / MANAGE_FIELD_PERMISSIONS / MANAGE_TEMPLATES: PROJECT_ADMIN 전용. 일반 멤버/비멤버는 false.
 */
export const projectPermissionsSchema = z.object({
  projectKey: z.string(),
  permissions: z.object({
    CREATE: z.boolean(),
    MANAGE_COMPONENTS: z.boolean(),
    MANAGE_VERSIONS: z.boolean(),
    MANAGE_CUSTOM_FIELDS: z.boolean(),
    MANAGE_FIELD_PERMISSIONS: z.boolean(),
    MANAGE_TEMPLATES: z.boolean(),
  }),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

export type ProjectPermissions = z.infer<typeof projectPermissionsSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 로그인 사용자의 프로젝트별 권한을 조회한다.
 *
 * GET /api/v1/users/me/project-permissions?projectKey={projectKey}
 *
 * @param projectKey 프로젝트 식별 키 (예: ATLAS)
 * @returns ProjectPermissions — projectKey + CREATE/MANAGE_COMPONENTS/MANAGE_VERSIONS/MANAGE_CUSTOM_FIELDS/MANAGE_FIELD_PERMISSIONS/MANAGE_TEMPLATES 권한 맵
 * @throws ApiError(401) 미인증
 * @throws ApiError(400) 잘못된 요청 (projectKey 누락 등)
 * @throws ZodError 응답 스키마 불일치
 */
export async function fetchProjectPermissions(projectKey: string): Promise<ProjectPermissions> {
  return apiGet(
    `/api/v1/users/me/project-permissions?projectKey=${encodeURIComponent(projectKey)}`,
    projectPermissionsSchema,
  )
}
