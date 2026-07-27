// 프로젝트-스킴 할당 TanStack Query hooks — UPSERT + 낙관적 업데이트
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { fetchProjectAssignment, assignSchemeToProject } from '@/api/workflow-schemes'
import type { AssignedScheme, AssignmentRecord, AssignSchemeInput } from '@/api/workflow-schemes'
import { notifySchemeError } from './workflow-scheme-error'
// 배정 후보 목록의 queryKey — 낙관값을 그 캐시에서 끌어오려면 같은 키를 써야 한다.
// (use-workflow-schemes 는 이 파일을 import 하지 않으므로 순환 의존이 아니다.)
import { SCHEME_KEYS } from './use-workflow-schemes'

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
      //
      // ★ 예전에는 `key` 만 새 스킴으로 갈아끼우고 name/description/isStandard 는 직전 스킴 값을
      //   남겼다. 그러면 **한 객체가 두 스킴을 가리켜** 배정 카드에 옛 이름이 표시됐다.
      //   정답은 같은 화면이 이미 들고 있다 — useAssignableWorkflowSchemes 가 채운 배정 후보
      //   캐시에 정합한 객체가 통째로 있다. 그것을 그대로 쓴다.
      const candidates = queryClient.getQueryData<AssignedScheme[]>(
        SCHEME_KEYS.assignable(projectKey),
      )
      const optimistic = candidates?.find((c) => c.key === input.schemeKey)

      // ★ 쓰기와 되돌리기를 **조건 일치가 아니라 구조로** 짝짓는다.
      //   예전에는 쓰기는 무조건, 롤백은 `prevAssignment !== undefined` 조건부라 비대칭이었다.
      //   캐시 엔트리가 없을 때(조회 완료 전 배정) 쓰기는 없던 항목을 만들고 롤백은 건너뛰어,
      //   실패했는데 낙관값이 남았다. `applied` 플래그를 넘기면 두 지점이 같은 사실을 본다.
      //
      //   후보를 못 찾으면 낙관적 쓰기를 **생략**한다 — 틀린 이름을 보여주느니
      //   재조회가 끝날 때까지 옛 카드를 유지하는 편이 낫다.
      if (optimistic === undefined) {
        return { prevAssignment, applied: false }
      }

      queryClient.setQueryData<AssignedScheme | null>(
        ASSIGNMENT_KEYS.byProject(projectKey),
        optimistic,
      )

      return { prevAssignment, applied: true }
    },
    onError: (error, _input, context) => {
      const ctx = context as
        | { prevAssignment?: AssignedScheme | null; applied?: boolean }
        | undefined

      // 쓴 경우에만 되돌린다. 안 썼으면 되돌릴 것도 없다 (위 `applied` 주석 참조).
      if (ctx?.applied === true) {
        if (ctx.prevAssignment === undefined) {
          // 착수 전 캐시 엔트리 자체가 없었다 — 값을 쓰는 게 아니라 엔트리를 없애야 원상복구다.
          queryClient.removeQueries({ queryKey: ASSIGNMENT_KEYS.byProject(projectKey) })
        } else {
          queryClient.setQueryData(
            ASSIGNMENT_KEYS.byProject(projectKey),
            ctx.prevAssignment,
          )
        }
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
