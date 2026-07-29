// 프로젝트 목록 TanStack Query 훅 — 사이드바 프로젝트 트리 데이터 소스 (FR-UX-06 PR12 Task 1)
import { useQuery } from '@tanstack/react-query'
import { listProjects } from '@/api/projects'
import type { Project } from '@/api/projects'

/**
 * 로그인 사용자가 접근 가능한 프로젝트 목록을 조회한다.
 *
 * GET /api/v1/projects?archived={archived} → Project[] (name 오름차순, 백엔드 정렬 신뢰).
 * staleTime 30초 — 빈번한 재조회를 방지한다.
 *
 * 멤버십이 없으면 빈 배열(fail-closed, 에러 아님). API 에러는 쿼리 error 상태로 노출될 뿐
 * throw하지 않으므로(react-query 기본 동작), 소비처(ProjectTree)가 조용한 fail-safe로
 * 처리할 수 있다.
 *
 * @param archived 아카이브 필터 — 기본 false(활성 프로젝트만)
 * @param options.enabled false 면 쿼리를 발사하지 않는다. 미인증 분기에서 마운트되는
 *   소비처(`useTrackActiveProject`)가 401→refresh 연쇄를 일으키지 않게 하려는 것이다.
 */
export function useProjects(archived = false, options: { enabled?: boolean } = {}) {
  return useQuery<Project[]>({
    queryKey: ['projects', archived],
    queryFn: () => listProjects(archived),
    staleTime: 30_000,
    enabled: options.enabled ?? true,
  })
}
