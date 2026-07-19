// 프로젝트 목록 API 클라이언트 — GET /api/v1/projects Zod 스키마 + apiGet 래퍼 (FR-UX-06 PR12 Task 1, 이 앱 첫 소비자)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend ProjectResponse DTO 1:1 대응 (id/key/name 3필드만, 게이트1 확정 YAGNI)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 단건 응답 Zod 스키마.
 * backend `ProjectResponse`(id, key, name) 1:1 대응 — leadUserId/createdAt 등은 이 PR 범위 밖이라
 * DTO에 없다(invent 금지, frontend-zod-backend-dto-contract-gap 재발 방지).
 */
export const projectSchema = z.object({
  id: z.string().uuid(),
  key: z.string(),
  name: z.string(),
})

/** backend `{ data: [...] }` 프로젝트 목록 응답 래퍼 Zod 스키마 */
export const projectListResponseSchema = z.object({
  data: z.array(projectSchema),
})

/** 프로젝트 단건 타입 */
export type Project = z.infer<typeof projectSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

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
  const wrapped = await apiGet(`/api/v1/projects?archived=${archived}`, projectListResponseSchema)
  return wrapped.data
}
