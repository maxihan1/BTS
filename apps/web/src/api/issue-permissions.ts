// 이슈별 권한 조회 API 클라이언트 — GET /api/v1/users/me/issue-permissions + Zod 파싱
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 스키마
// ─────────────────────────────────────────────────────────────────────────────

export const issuePermissionsSchema = z.object({
  issueKey: z.string(),
  permissions: z.object({
    UPDATE: z.boolean(),
    SOFT_DELETE: z.boolean(),
    TRANSITION: z.boolean(),
  }),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

export type IssuePermissions = z.infer<typeof issuePermissionsSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 로그인 사용자의 이슈별 권한을 조회한다.
 *
 * GET /api/v1/users/me/issue-permissions?issueKey={issueKey}
 *
 * @param issueKey 이슈 식별 키 (예: ATLAS-1)
 * @returns IssuePermissions — issueKey + UPDATE/SOFT_DELETE/TRANSITION 권한 맵
 * @throws ApiError(403) 권한 없음
 * @throws ApiError(401) 미인증
 * @throws ZodError 응답 스키마 불일치
 */
export async function fetchIssuePermissions(issueKey: string): Promise<IssuePermissions> {
  return apiGet(
    `/api/v1/users/me/issue-permissions?issueKey=${encodeURIComponent(issueKey)}`,
    issuePermissionsSchema,
  )
}
