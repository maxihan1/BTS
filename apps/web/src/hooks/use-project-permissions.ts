// 프로젝트별 현재 사용자 권한을 조회하는 TanStack Query 훅 (FR-PM-02)
import { useQuery } from '@tanstack/react-query'
import { fetchProjectPermissions } from '@/api/project-permissions'
import type { ProjectPermissions } from '@/api/project-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** queryKey 상수 — 매직 문자열 방지 */
export const PROJECT_PERMISSION_KEYS = {
  /** 프로젝트 권한 queryKey */
  detail: (projectKey: string) => ['project-permissions', projectKey] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// useProjectPermissions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 로그인 사용자의 프로젝트별 권한을 조회한다.
 *
 * GET /api/v1/users/me/project-permissions?projectKey={projectKey}
 * queryKey: ['project-permissions', projectKey]
 * staleTime 30초 — 화면 내 중복 호출을 방지한다.
 * enabled: projectKey가 있을 때만 실행한다.
 *
 * @param projectKey 프로젝트 식별 키 (예: ATLAS). 빈 문자열이면 쿼리가 비활성화된다.
 * @returns TanStack Query 결과 — data(ProjectPermissions), isLoading, isError 포함
 */
export function useProjectPermissions(projectKey: string) {
  return useQuery<ProjectPermissions>({
    queryKey: PROJECT_PERMISSION_KEYS.detail(projectKey),
    queryFn: () => fetchProjectPermissions(projectKey),
    enabled: !!projectKey,
    staleTime: 30_000,
  })
}
