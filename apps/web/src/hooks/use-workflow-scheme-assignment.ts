// 프로젝트-스킴 할당 TanStack Query hooks — UPSERT + 낙관적 업데이트
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { fetchProjectAssignment, assignSchemeToProject } from '@/api/workflow-schemes'
import type { AssignedScheme, AssignmentRecord, AssignSchemeInput } from '@/api/workflow-schemes'
import { notifySchemeError } from './workflow-scheme-error'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트-스킴 할당 queryKey 상수 */
export const ASSIGNMENT_KEYS = {
  /** 프로젝트 할당 queryKey */
  byProject: (projectKey: string) =>
    ['projects', projectKey, 'workflow-scheme'] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// Query hooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 할당된 워크플로우 스킴을 조회한다.
 * GET /api/v1/projects/{projectKey}/workflow-scheme → AssignedScheme | null
 *
 * EC-1: 404는 "미할당" 정상 상태이며 null로 처리한다 (에러가 아님).
 *
 * @param projectKey 프로젝트 식별 키
 */
export function useGetAssignment(projectKey: string) {
  return useQuery<AssignedScheme | null>({
    queryKey: ASSIGNMENT_KEYS.byProject(projectKey),
    queryFn: () => fetchProjectAssignment(projectKey),
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation hooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 워크플로우 스킴을 할당(UPSERT)한다.
 * PUT /api/v1/projects/{projectKey}/workflow-scheme → AssignmentRecord
 *
 * 낙관적 업데이트: onMutate에서 캐시를 즉시 반영, 실패 시 롤백.
 * 성공 시 toast.success로 안내한다.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function useUpdateAssignment(projectKey: string) {
  const queryClient = useQueryClient()

  return useMutation<AssignmentRecord, unknown, AssignSchemeInput>({
    mutationFn: (input) => assignSchemeToProject(projectKey, input),
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: ASSIGNMENT_KEYS.byProject(projectKey) })

      const prevAssignment = queryClient.getQueryData<AssignedScheme | null>(
        ASSIGNMENT_KEYS.byProject(projectKey),
      )

      // 낙관적 객체는 이 캐시가 담는 타입(조회 응답 타입)을 만족해야 한다 (FR C9).
      // PUT 응답은 AssignmentRecord(배정 이력)라 이 캐시에 그대로 쓸 수 없다 — 형태가 다르다.
      // 그래서 키만 즉시 반영하고 나머지는 onSettled 의 invalidate 가 서버 값으로 교체한다.
      // projectKey 는 queryKey(ASSIGNMENT_KEYS.byProject)에 이미 있어 값에서 빼도 정보 손실이 없다.
      const optimistic: AssignedScheme = {
        id: prevAssignment?.id ?? null,
        key: input.schemeKey,
        name: prevAssignment?.name ?? '',
        description: prevAssignment?.description ?? null,
        isStandard: prevAssignment?.isStandard ?? false,
      }

      queryClient.setQueryData<AssignedScheme | null>(
        ASSIGNMENT_KEYS.byProject(projectKey),
        optimistic,
      )

      return { prevAssignment }
    },
    onError: (error, _input, context) => {
      const ctx = context as { prevAssignment?: AssignedScheme | null } | undefined

      if (ctx?.prevAssignment !== undefined) {
        queryClient.setQueryData(
          ASSIGNMENT_KEYS.byProject(projectKey),
          ctx.prevAssignment,
        )
      }
      notifySchemeError(error)
    },
    onSuccess: () => {
      toast.success('프로젝트에 스킴이 할당됐습니다')
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({
        queryKey: ASSIGNMENT_KEYS.byProject(projectKey),
      })
    },
  })
}
