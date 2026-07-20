// 프로젝트 단건 조회 TanStack Query 훅 — queryKey ['project', idOrKey] (FR-PJ PR-5 Task 3)
import { useQuery } from '@tanstack/react-query'
import { getProject } from '@/api/projects'
import type { Project } from '@/api/projects'

/**
 * 프로젝트 BC queryKey 팩토리 — 매직 문자열 방지.
 * `list`는 기존 `use-projects.ts`(복수형, `['projects', archived]`)가 이미 소유하고 있어
 * 여기서는 재정의하지 않는다 — `list()`의 prefix인 `['projects']`로 invalidate하면 archived
 * 변형(`['projects', true|false]`) 모두 매치된다(TanStack Query 기본 partial match).
 */
export const PROJECT_KEYS = {
  /** 프로젝트 목록 invalidate용 prefix — archived 변형 전체를 포괄한다 */
  list: () => ['projects'] as const,
  /** 프로젝트 단건 조회 queryKey */
  detail: (idOrKey: string) => ['project', idOrKey] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

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
    queryKey: PROJECT_KEYS.detail(idOrKey),
    queryFn: () => getProject(idOrKey),
    enabled: !!idOrKey,
    staleTime: 30_000,
  })
}
