// 프로젝트 요약·활동 조회 TanStack Query 훅 — 화면과 API 사이 단일 경계 (Jira 패리티 J4)
import { useQuery } from '@tanstack/react-query'
import {
  fetchProjectActivity,
  fetchProjectSummary,
  type ProjectActivity,
  type ProjectSummary,
} from '@/api/project-summary'

/**
 * 요약 화면 queryKey 팩토리 — 매직 문자열 방지.
 *
 * 요약과 활동을 **다른 키**로 둔다. 한 키로 묶으면 활동 조회가 403 일 때 요약까지 함께
 * 에러 상태가 되어 위젯 국소 격리가 깨진다.
 */
export const PROJECT_SUMMARY_KEYS = {
  /** 요약 집계 queryKey */
  summary: (projectKey: string) => ['project-summary', projectKey] as const,
  /** 활동 피드 queryKey — limit 이 다르면 다른 캐시다 */
  activity: (projectKey: string, limit: number) =>
    ['project-activity', projectKey, limit] as const,
}

/**
 * 프로젝트 요약 집계를 조회한다.
 *
 * `projectKey` 가 빈 문자열이면 쿼리를 보내지 않는다(라우트 파라미터 미확정 대응 —
 * `useProject` 선례 동형).
 *
 * @param projectKey 조회할 프로젝트 키
 */
export function useProjectSummary(projectKey: string) {
  return useQuery<ProjectSummary>({
    queryKey: PROJECT_SUMMARY_KEYS.summary(projectKey),
    queryFn: () => fetchProjectSummary(projectKey),
    enabled: projectKey !== '',
    staleTime: 30_000,
  })
}

/**
 * 프로젝트 활동 피드를 조회한다.
 *
 * @param projectKey 조회할 프로젝트 키
 * @param limit 최대 항목 수. 기본 20 — 백엔드 상한은 50
 */
export function useProjectActivity(projectKey: string, limit = 20) {
  return useQuery<ProjectActivity>({
    queryKey: PROJECT_SUMMARY_KEYS.activity(projectKey, limit),
    queryFn: () => fetchProjectActivity(projectKey, limit),
    enabled: projectKey !== '',
    staleTime: 30_000,
  })
}
