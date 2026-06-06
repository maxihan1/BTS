// 프로젝트 리드 TanStack Query 훅 — 조회/변경 invalidate-only + 에러 토스트 (FR-CM-04)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchProjectLead,
  changeProjectLead,
  extractProjectLeadErrorCode,
} from '@/api/project-lead'
import type { ProjectLead } from '@/api/project-lead'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 토스트 헬퍼 — 훅 레이어에서 단일 발사, 컴포넌트 중복 금지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백엔드 errorCode를 사용자 노출 메시지로 변환한다.
 * ProblemDetail detail 필드를 직접 사용하지 않으며 이 함수가 단일 출처다.
 */
function projectLeadErrorMessage(errorCode: string | null): string {
  switch (errorCode) {
    case 'PROJECT_LEAD_NOT_FOUND':
      return '선택한 리드 사용자를 찾을 수 없습니다.'
    case 'PROJECT_NOT_FOUND':
      return '프로젝트를 찾을 수 없습니다.'
    case 'VALIDATION_FAILED':
      return '입력값을 확인해 주세요.'
    default:
      return '요청을 처리하지 못했습니다.'
  }
}

function notifyProjectLeadError(error: unknown): void {
  const code = extractProjectLeadErrorCode(error)
  toast.error(projectLeadErrorMessage(code))
}

// ─────────────────────────────────────────────────────────────────────────────
// useProjectLead — 단건 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 리드를 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/lead → ProjectLead
 * staleTime 30초 — 빈번한 재조회를 방지한다.
 *
 * - leadUserId null은 리드 미지정 정상 데이터다(에러 아님).
 * - 프로젝트 404는 에러 상태로 전파된다.
 * - projectKey가 빈 문자열이면 쿼리를 비활성(idle)으로 유지한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @returns UseQueryResult<ProjectLead>
 */
export function useProjectLead(projectKey: string) {
  return useQuery<ProjectLead>({
    queryKey: ['project-lead', projectKey],
    queryFn: () => fetchProjectLead(projectKey),
    enabled: !!projectKey,
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeProjectLead — 변경
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 리드를 변경하거나 해제한다.
 *
 * PATCH /api/v1/projects/{projectKey}/lead → 200 ProjectLead
 *
 * onSuccess → invalidateQueries(['project-lead', projectKey]) + 성공 토스트
 * onError   → extractProjectLeadErrorCode → projectLeadErrorMessage → toast.error
 *
 * @param projectKey 대상 프로젝트 키
 * @returns UseMutationResult<ProjectLead, unknown, string | null>
 */
export function useChangeProjectLead(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<ProjectLead, unknown, string | null>({
    mutationFn: (leadUserId) => changeProjectLead(projectKey, leadUserId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['project-lead', projectKey] })
      toast.success('프로젝트 리드가 변경되었습니다.')
    },
    onError: (error) => {
      notifyProjectLeadError(error)
    },
  })
}
