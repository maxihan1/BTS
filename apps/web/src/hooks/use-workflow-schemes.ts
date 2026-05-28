// 워크플로우 스킴 CRUD TanStack Query hooks + sonner 토스트 + errorCode 한국어 매핑
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchWorkflowSchemes,
  fetchWorkflowScheme,
  createWorkflowScheme,
  updateWorkflowScheme,
  deleteWorkflowScheme,
  addMapping,
  deleteMapping,
  WorkflowSchemeApiError,
} from '@/api/workflow-schemes'
import type {
  SchemeResponse,
  SchemeDetailResponse,
  MappingResponse,
  CreateSchemeInput,
  UpdateSchemeInput,
  AddMappingInput,
} from '@/api/workflow-schemes'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** queryKey 상수 — 매직 문자열 방지 */
export const SCHEME_KEYS = {
  /** 스킴 목록 queryKey */
  list: ['workflow-schemes'] as const,
  /** 스킴 단건 queryKey */
  detail: (schemeKey: string) => ['workflow-schemes', schemeKey] as const,
} satisfies Record<string, readonly string[] | ((...args: string[]) => readonly string[])>

// ─────────────────────────────────────────────────────────────────────────────
// errorCode 한국어 매핑
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우 스킴 errorCode → 한국어 사용자 메시지 매핑 */
const SCHEME_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  SCHEME_IN_USE: '사용 중인 스킴은 삭제할 수 없습니다',
  MAPPING_DUPLICATE: '이미 매핑된 이슈 타입입니다',
  MAPPING_DEFAULT_DUPLICATE: '기본 매핑은 한 개만 허용됩니다',
  SCHEME_STANDARD_NOT_DELETABLE: '표준 스킴은 삭제할 수 없습니다',
  SCHEME_STANDARD_FIELD_LOCKED: '표준 스킴의 키/이름은 변경할 수 없습니다',
}

const DEFAULT_SCHEME_ERROR_MESSAGE = '요청 처리 중 오류가 발생했습니다'

/**
 * errorCode를 한국어 사용자 메시지로 변환한다.
 * 알 수 없는 코드면 기본 메시지를 반환한다.
 *
 * @param errorCode WorkflowSchemeApiError.errorCode
 */
export function mapWorkflowSchemeError(errorCode: string): string {
  return SCHEME_ERROR_MESSAGES[errorCode] ?? DEFAULT_SCHEME_ERROR_MESSAGE
}

/**
 * errorCode 기반으로 toast.error를 호출한다.
 *
 * @param errorCode WorkflowSchemeApiError.errorCode
 */
function notifySchemeError(errorCode: string): void {
  toast.error(mapWorkflowSchemeError(errorCode))
}

/**
 * 에러에서 errorCode를 추출해 toast.error를 호출한다.
 * WorkflowSchemeApiError가 아니면 기본 메시지를 사용한다.
 */
function notifyErrorFromUnknown(error: unknown): void {
  if (error instanceof WorkflowSchemeApiError) {
    notifySchemeError(error.errorCode)
  } else {
    toast.error(DEFAULT_SCHEME_ERROR_MESSAGE)
  }
}

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
    onError: (error) => {
      notifyErrorFromUnknown(error)
    },
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

      // 낙관적 단건 캐시 업데이트
      if (prevDetail !== undefined) {
        queryClient.setQueryData<SchemeDetailResponse>(SCHEME_KEYS.detail(schemeKey), {
          ...prevDetail,
          ...(input.name !== undefined && { name: input.name }),
          ...(input.description !== undefined && { description: input.description }),
        })
      }

      // 낙관적 목록 캐시 업데이트
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
      notifyErrorFromUnknown(error)
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
 * 409 SCHEME_IN_USE 시 toast.error로 한국어 메시지를 표시한다.
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
    onError: (error) => {
      notifyErrorFromUnknown(error)
    },
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
        // 임시 ID는 음수 — 서버 응답 후 invalidate로 교체됨
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
      notifyErrorFromUnknown(error)
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
    onError: (_error, _mappingId, context) => {
      const ctx = context as { prevDetail?: SchemeDetailResponse } | undefined

      if (ctx?.prevDetail !== undefined) {
        queryClient.setQueryData(SCHEME_KEYS.detail(schemeKey), ctx.prevDetail)
      }
      notifyErrorFromUnknown(_error)
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
    },
  })
}
