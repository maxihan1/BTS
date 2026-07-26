// 워크플로우 스킴 CRUD TanStack Query hooks
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchWorkflowSchemes,
  fetchWorkflowScheme,
  fetchAssignableWorkflowSchemes,
  createWorkflowScheme,
  updateWorkflowScheme,
  deleteWorkflowScheme,
  addMapping,
  deleteMapping,
} from '@/api/workflow-schemes'
import type {
  SchemeResponse,
  SchemeDetailResponse,
  MappingResponse,
  CreateSchemeInput,
  UpdateSchemeInput,
  AddMappingInput,
} from '@/api/workflow-schemes'
import { notifySchemeError, mapWorkflowSchemeError } from './workflow-scheme-error'

export { mapWorkflowSchemeError }

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** queryKey 상수 — 매직 문자열 방지 */
export const SCHEME_KEYS = {
  /** 스킴 목록 queryKey */
  list: ['workflow-schemes'] as const,
  /** 스킴 단건 queryKey */
  detail: (schemeKey: string) => ['workflow-schemes', schemeKey] as const,
  /** 프로젝트별 할당 가능 스킴 목록 queryKey */
  assignable: (projectKey: string) => ['projects', projectKey, 'assignable-workflow-schemes'] as const,
} satisfies Record<string, readonly string[] | ((...args: string[]) => readonly string[])>

// ─────────────────────────────────────────────────────────────────────────────
// Query hooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 목록을 조회한다.
 * GET /api/v1/workflow-schemes → SchemeResponse[]
 */
export function useWorkflowSchemes() {
  return useQuery({
    queryKey: SCHEME_KEYS.list,
    queryFn: fetchWorkflowSchemes,
    staleTime: 30_000,
  })
}

/**
 * 워크플로우 스킴 단건(매핑 동봉)을 조회한다.
 * GET /api/v1/workflow-schemes/{schemeKey} → SchemeDetailResponse
 *
 * @param schemeKey 스킴 식별 키
 */
export function useWorkflowSchemeDetail(schemeKey: string) {
  return useQuery({
    queryKey: SCHEME_KEYS.detail(schemeKey),
    queryFn: () => fetchWorkflowScheme(schemeKey),
    staleTime: 30_000,
  })
}

/**
 * 프로젝트에 할당 가능한 워크플로우 스킴 목록을 조회한다.
 * GET /api/v1/projects/{projectKey}/assignable-workflow-schemes → AssignableSchemeResponse[]
 *
 * @param projectKey 프로젝트 식별 키
 */
export function useAssignableWorkflowSchemes(projectKey: string) {
  return useQuery({
    queryKey: SCHEME_KEYS.assignable(projectKey),
    queryFn: () => fetchAssignableWorkflowSchemes(projectKey),
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation hooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴을 생성한다.
 * POST /api/v1/workflow-schemes → SchemeResponse
 * 성공 시 스킴 목록 캐시를 무효화한다.
 */
export function useCreateWorkflowScheme() {
  const queryClient = useQueryClient()

  return useMutation<SchemeResponse, unknown, CreateSchemeInput>({
    mutationFn: createWorkflowScheme,
    onSuccess: async () => {
      toast.success('스킴이 생성됐습니다')
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.list })
    },
    onError: notifySchemeError,
  })
}

/**
 * 워크플로우 스킴 이름/설명을 수정한다.
 * PUT /api/v1/workflow-schemes/{schemeKey}
 *
 * 낙관적 업데이트: onMutate에서 목록/단건 캐시를 즉시 반영, 실패 시 롤백.
 *
 * @param schemeKey 수정할 스킴 키
 */
export function useUpdateWorkflowScheme(schemeKey: string) {
  const queryClient = useQueryClient()

  return useMutation<SchemeResponse, unknown, UpdateSchemeInput>({
    mutationFn: (input) => updateWorkflowScheme(schemeKey, input),
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
      await queryClient.cancelQueries({ queryKey: SCHEME_KEYS.list })

      const prevDetail = queryClient.getQueryData<SchemeDetailResponse>(
        SCHEME_KEYS.detail(schemeKey),
      )
      const prevList = queryClient.getQueryData<SchemeResponse[]>(SCHEME_KEYS.list)

      if (prevDetail !== undefined) {
        queryClient.setQueryData<SchemeDetailResponse>(SCHEME_KEYS.detail(schemeKey), {
          ...prevDetail,
          ...(input.name !== undefined && { name: input.name }),
          ...(input.description !== undefined && { description: input.description }),
        })
      }

      if (prevList !== undefined) {
        queryClient.setQueryData<SchemeResponse[]>(
          SCHEME_KEYS.list,
          prevList.map((s) =>
            s.schemeKey === schemeKey
              ? {
                  ...s,
                  ...(input.name !== undefined && { name: input.name }),
                  ...(input.description !== undefined && { description: input.description }),
                }
              : s,
          ),
        )
      }

      return { prevDetail, prevList }
    },
    onError: (error, _input, context) => {
      const ctx = context as
        | { prevDetail?: SchemeDetailResponse; prevList?: SchemeResponse[] }
        | undefined

      if (ctx?.prevDetail !== undefined) {
        queryClient.setQueryData(SCHEME_KEYS.detail(schemeKey), ctx.prevDetail)
      }
      if (ctx?.prevList !== undefined) {
        queryClient.setQueryData(SCHEME_KEYS.list, ctx.prevList)
      }
      notifySchemeError(error)
    },
    onSuccess: () => {
      toast.success('스킴이 수정됐습니다')
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.list })
    },
  })
}

/**
 * 워크플로우 스킴을 삭제한다.
 * DELETE /api/v1/workflow-schemes/{schemeKey} → 204
 *
 * 409 SCHEME_IN_USE / SCHEME_STANDARD_NOT_DELETABLE 시 toast.error로 한국어 메시지를 표시한다.
 */
export function useDeleteWorkflowScheme() {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, string>({
    mutationFn: deleteWorkflowScheme,
    onSuccess: async (_data, schemeKey) => {
      toast.success('스킴이 삭제됐습니다')
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.list })
      queryClient.removeQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
    },
    onError: notifySchemeError,
  })
}

/**
 * 스킴에 이슈 타입-워크플로우 매핑을 추가한다.
 * POST /api/v1/workflow-schemes/{schemeKey}/mappings → MappingResponse
 *
 * 낙관적 업데이트: onMutate에서 단건 캐시에 임시 매핑 선반영.
 * 409 MAPPING_DUPLICATE / MAPPING_DEFAULT_DUPLICATE 시 롤백 + toast.error.
 * onSettled에서 단건 캐시 invalidate.
 *
 * @param schemeKey 매핑을 추가할 스킴 키
 */
export function useAddMapping(schemeKey: string) {
  const queryClient = useQueryClient()

  return useMutation<MappingResponse, unknown, AddMappingInput>({
    mutationFn: (input) => addMapping(schemeKey, input),
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })

      const prevDetail = queryClient.getQueryData<SchemeDetailResponse>(
        SCHEME_KEYS.detail(schemeKey),
      )

      if (prevDetail !== undefined) {
        // 임시 ID는 음수 — 서버 응답 후 onSettled invalidate로 교체됨
        const optimisticMapping: MappingResponse = {
          id: -Date.now(),
          issueTypeKey: input.issueTypeKey,
          issueTypeName: input.issueTypeKey,
          workflowKey: input.workflowKey,
          workflowName: input.workflowKey,
          isDefault: input.issueTypeKey === null,
        }

        queryClient.setQueryData<SchemeDetailResponse>(SCHEME_KEYS.detail(schemeKey), {
          ...prevDetail,
          mappings: [...prevDetail.mappings, optimisticMapping],
          mappingsCount: prevDetail.mappingsCount + 1,
        })
      }

      return { prevDetail }
    },
    onError: (error, _input, context) => {
      const ctx = context as { prevDetail?: SchemeDetailResponse } | undefined

      if (ctx?.prevDetail !== undefined) {
        queryClient.setQueryData(SCHEME_KEYS.detail(schemeKey), ctx.prevDetail)
      }
      notifySchemeError(error)
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
    },
  })
}

/**
 * 스킴에서 매핑을 삭제한다.
 * DELETE /api/v1/workflow-schemes/{schemeKey}/mappings/{mappingId} → 204
 *
 * 낙관적 업데이트: onMutate에서 단건 캐시에서 즉시 제거.
 * 실패 시 롤백, onSettled에서 invalidate.
 *
 * @param schemeKey 스킴 키
 */
export function useRemoveMapping(schemeKey: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, number>({
    mutationFn: (mappingId) => deleteMapping(schemeKey, mappingId),
    onMutate: async (mappingId) => {
      await queryClient.cancelQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })

      const prevDetail = queryClient.getQueryData<SchemeDetailResponse>(
        SCHEME_KEYS.detail(schemeKey),
      )

      if (prevDetail !== undefined) {
        queryClient.setQueryData<SchemeDetailResponse>(SCHEME_KEYS.detail(schemeKey), {
          ...prevDetail,
          mappings: prevDetail.mappings.filter((m) => m.id !== mappingId),
          mappingsCount: Math.max(0, prevDetail.mappingsCount - 1),
        })
      }

      return { prevDetail }
    },
    onError: (error, _mappingId, context) => {
      const ctx = context as { prevDetail?: SchemeDetailResponse } | undefined

      if (ctx?.prevDetail !== undefined) {
        queryClient.setQueryData(SCHEME_KEYS.detail(schemeKey), ctx.prevDetail)
      }
      notifySchemeError(error)
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
    },
  })
}
