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

export function useProjectPermissions(projectKey: string) {
  return useQuery<ProjectPermissions>({
    queryKey: PROJECT_PERMISSION_KEYS.detail(projectKey),
    queryFn: () => fetchProjectPermissions(projectKey),
    enabled: !!projectKey,
    staleTime: 30_000,
  })
}
