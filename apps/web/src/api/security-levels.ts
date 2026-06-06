// identity-access BC 이슈 보안등급 목록 조회 API — FR-PM-06 PR-B
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 ProjectLevelResponse DTO 직렬화 형태와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 보안 등급 단건 Zod 스키마 */
const securityLevelSchema = z.object({
  /** 등급 식별자 UUID */
  id: z.string().uuid(),
  /** 등급 이름 */
  name: z.string().min(1),
  /** 등급 설명. nullable */
  description: z.string().nullable(),
  /** 스킴 기본 등급 여부 */
  isDefault: z.boolean(),
})

/** 프로젝트 보안 등급 목록 응답 Zod 스키마 */
const projectLevelsResponseSchema = z.object({
  levels: z.array(securityLevelSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 보안 등급 타입 */
export type SecurityLevel = z.infer<typeof securityLevelSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 적용된 이슈 보안 스킴의 등급 목록을 조회한다.
 * GET /api/v1/projects/{projectKey}/issue-security-scheme/levels
 *
 * - 인증만 요구 (PROJECT_ADMIN 불요)
 * - 스킴 미적용 프로젝트: 200 + 빈 배열
 * - 없는 프로젝트: ApiError(404)
 *
 * @param projectKey 프로젝트 키 (예: "ATLAS")
 * @returns 보안 등급 배열 — 없으면 빈 배열
 */
export async function fetchProjectSecurityLevels(projectKey: string): Promise<SecurityLevel[]> {
  const result = await apiGet(
    `/api/v1/projects/${projectKey}/issue-security-scheme/levels`,
    projectLevelsResponseSchema,
  )
  return result.levels
}
