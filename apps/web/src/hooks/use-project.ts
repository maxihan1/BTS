// 프로젝트 단건 조회 TanStack Query 훅 — queryKey ['project', idOrKey] (FR-PJ PR-5 Task 3)
import { useQuery } from '@tanstack/react-query'
import { getProject } from '@/api/projects'
import type { Project } from '@/api/projects'

/**
 * 프로젝트 단건을 조회한다.
 *
 * GET /api/v1/projects/{idOrKey} → Project (archived 필드 포함).
 * staleTime 30초 — 빈번한 재조회를 방지한다.
 *
 * idOrKey가 빈 문자열이면 쿼리를 idle 상태로 유지한다(라우트 파라미터 미확정 대응).
 *
 * @param idOrKey 프로젝트 UUID 또는 key
 */
export function useProject(idOrKey: string) {
  return useQuery<Project>({
    queryKey: ['project', idOrKey],
    queryFn: () => getProject(idOrKey),
    enabled: !!idOrKey,
    staleTime: 30_000,
  })
}
