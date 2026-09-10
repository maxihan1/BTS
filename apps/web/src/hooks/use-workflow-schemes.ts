// 워크플로우 스킴 CRUD TanStack Query hooks
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchWorkflowSchemes,
  fetchWorkflowScheme,
  fetchAssignableWorkflowSchemes,
  fetchManagedWorkflowSchemes,
  createWorkflowScheme,
  updateWorkflowScheme,
  deleteWorkflowScheme,
  addMapping,
  deleteMapping,
} from '@/api/workflow-schemes'
import type {
  SchemeListItem,
  SchemeDetail,
  SchemeMutationResult,
  MappingDetail,
  MappingCreated,
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
  /**
   * 프로젝트별 할당 가능 스킴 목록 queryKey.
   *
   * ★리소스-우선 구조인 이유. 관리자 뮤테이션(생성·수정·삭제)은 전역 자원을 다루므로
   * `projectKey` 를 모른다. 프로젝트-우선(`['projects', key, ...]`)이면 정확한 키를 만들 수
   * 없어 `['projects']` 전체를 무효화해야 하고, 그러면 사이드바 프로젝트 트리까지 재요청이
   * 번진다. 리소스-우선이면 `assignableAll` prefix 하나로 정확히 이 계열만 잡는다.
   * (`use-components`·`use-boards` 등 이 레포의 지배 관례와도 같은 형태다.)
   */
  assignable: (projectKey: string) => ['assignable-workflow-schemes', projectKey] as const,
  /** 전 프로젝트의 할당 가능 스킴 캐시를 한 번에 무효화하는 prefix (관리자 뮤테이션용) */
  assignableAll: ['assignable-workflow-schemes'] as const,
  /**
   * 프로젝트별 **스킴 관리 목록** queryKey (FR-WF-08).
   *
   * `list`·`detail` 밑에 두지 않는다 — `detail(schemeKey)` 와 모양이 겹쳐
   * `['workflow-schemes', 'ATLAS']` 가 「ATLAS 스킴」인지 「ATLAS 프로젝트 목록」인지
   * 구분되지 않는다. `assignable` 이 독립 prefix 를 쓰는 것과 같은 이유다.
   */
  managed: (projectKey: string) => ['managed-workflow-schemes', projectKey] as const,
  /** 전 프로젝트의 관리 목록 캐시를 한 번에 무효화하는 prefix (뮤테이션용) */
  managedAll: ['managed-workflow-schemes'] as const,
} satisfies Record<string, readonly string[] | ((...args: string[]) => readonly string[])>

// ─────────────────────────────────────────────────────────────────────────────
// Query hooks
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 목록을 조회한다.
 * GET /api/v1/workflow-schemes → SchemeListItem[]
 */
export function useWorkflowSchemes(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: SCHEME_KEYS.list,
    queryFn: fetchWorkflowSchemes,
    staleTime: 30_000,
    // ★끌 수 있어야 한다. 이 목록은 `MANAGE_SCHEME` + `Global` 게이트라 프로젝트 관리자에게
    //   403 이다. 프로젝트 화면이 목록을 직접 주는데도 이 요청이 나가면 콘솔이 403 으로 덮인다.
    enabled: options?.enabled ?? true,
  })
}

/**
 * 워크플로우 스킴 단건(매핑 동봉)을 조회한다.
 * GET /api/v1/workflow-schemes/{schemeKey} → SchemeDetail
 *
 * @param schemeKey 스킴 식별 키
 */
export function useWorkflowSchemeDetail(schemeKey: string | null) {
  return useQuery({
    queryKey: SCHEME_KEYS.detail(schemeKey ?? ''),
    queryFn: () => fetchWorkflowScheme(schemeKey as string),
    staleTime: 30_000,
    // ★「아직 안 골랐다」를 훅이 받는다. 호출부가 `?? ''` 로 메우면 `/api/v1/workflow-schemes/`
    //   로 **후행 슬래시 요청**이 나가고, 빈 세그먼트는 Spring Security 포괄 규칙으로 떨어져
    //   로그인 상태에서도 401 이 된다(프로덕션 실측 2026-09-07).
    //   판별식 `empty-path-segment-guard` 가 그 폴백 자체를 금지한다 — 가드가 나중에 지워져도
    //   폴백이 남으면 다시 새기 때문이다. `useVersions` 선례와 같은 형태.
    enabled: schemeKey !== null && schemeKey !== '',
  })
}

/**
 * 프로젝트에 할당 가능한 워크플로우 스킴 목록을 조회한다.
 * GET /api/v1/projects/{projectKey}/assignable-workflow-schemes → AssignedScheme[]
 *
 * @param projectKey 프로젝트 식별 키
 */
export function useManagedWorkflowSchemes(projectKey: string) {
  return useQuery({
    queryKey: SCHEME_KEYS.managed(projectKey),
    queryFn: () => fetchManagedWorkflowSchemes(projectKey),
    staleTime: 30_000,
    enabled: projectKey !== '',
  })
}

/**
 * 프로젝트에 배정 **가능한** 스킴 목록을 조회한다. 배정 select 옵션 전용.
 *
 * 관리 목록은 [useManagedWorkflowSchemes] 다 — 형태와 권한이 다르다.
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
 * POST /api/v1/workflow-schemes → SchemeMutationResult
 * 성공 시 스킴 목록 캐시를 무효화한다.
 */
export function useCreateWorkflowScheme() {
  const queryClient = useQueryClient()

  return useMutation<SchemeMutationResult, unknown, CreateSchemeInput>({
    mutationFn: createWorkflowScheme,
    onSuccess: async () => {
      toast.success('스킴이 생성됐습니다')
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.list })
      // 새 스킴은 배정 드롭다운(assignable)에도 즉시 나타나야 한다.
      // 없으면 staleTime 30초 동안 만든 스킴이 배정 화면에 안 보인다.
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.assignableAll })
      // 관리 목록도 함께 걷는다 — 안 걷으면 「만들었는데 사이드바에 안 뜬다」가 된다.
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.managedAll })
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

  return useMutation<SchemeMutationResult, unknown, UpdateSchemeInput>({
    mutationFn: (input) => updateWorkflowScheme(schemeKey, input),
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
      await queryClient.cancelQueries({ queryKey: SCHEME_KEYS.list })

      const prevDetail = queryClient.getQueryData<SchemeDetail>(
        SCHEME_KEYS.detail(schemeKey),
      )
      const prevList = queryClient.getQueryData<SchemeListItem[]>(SCHEME_KEYS.list)

      if (prevDetail !== undefined) {
        queryClient.setQueryData<SchemeDetail>(SCHEME_KEYS.detail(schemeKey), {
          ...prevDetail,
          ...(input.name !== undefined && { name: input.name }),
          ...(input.description !== undefined && { description: input.description }),
        })
      }

      if (prevList !== undefined) {
        queryClient.setQueryData<SchemeListItem[]>(
          SCHEME_KEYS.list,
          prevList.map((s) =>
            s.key === schemeKey
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
        | { prevDetail?: SchemeDetail; prevList?: SchemeListItem[] }
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
      // 이름 수정은 배정 드롭다운의 표시 문자열을 바꾼다.
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.assignableAll })
      // 관리 목록도 함께 걷는다 — 안 걷으면 「만들었는데 사이드바에 안 뜬다」가 된다.
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.managedAll })
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
      // 삭제된 스킴이 배정 드롭다운에 남아 있으면 고를 수 있고, 고르면 404 가 난다.
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.assignableAll })
      // 관리 목록도 함께 걷는다 — 안 걷으면 「만들었는데 사이드바에 안 뜬다」가 된다.
      await queryClient.invalidateQueries({ queryKey: SCHEME_KEYS.managedAll })
      queryClient.removeQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })
    },
    onError: notifySchemeError,
  })
}

/**
 * 스킴에 이슈 타입-워크플로우 매핑을 추가한다.
 * POST /api/v1/workflow-schemes/{schemeKey}/mappings → MappingCreated
 *
 * ★ 서버 응답(MappingCreated: 내부 PK 형태)과 낙관적으로 캐시에 넣는 값(MappingDetail: 키·이름 형태)은
 * 형태가 다르다. 그래서 응답을 캐시에 직접 쓰지 않고 onSettled 의 invalidate 로 서버 형태를 다시 받는다.
 *
 * 낙관적 업데이트: onMutate에서 단건 캐시에 임시 매핑 선반영.
 * 409 MAPPING_DUPLICATE / MAPPING_DEFAULT_DUPLICATE 시 롤백 + toast.error.
 * onSettled에서 단건 캐시 invalidate.
 *
 * @param schemeKey 매핑을 추가할 스킴 키
 */
export function useAddMapping(schemeKey: string) {
  const queryClient = useQueryClient()

  return useMutation<MappingCreated, unknown, AddMappingInput>({
    mutationFn: (input) => addMapping(schemeKey, input),
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: SCHEME_KEYS.detail(schemeKey) })

      const prevDetail = queryClient.getQueryData<SchemeDetail>(
        SCHEME_KEYS.detail(schemeKey),
      )

      if (prevDetail !== undefined) {
        // 임시 ID는 음수 — 서버 응답 후 onSettled invalidate로 교체됨
        const optimisticMapping: MappingDetail = {
          id: -Date.now(),
          issueTypeKey: input.issueTypeKey,
          issueTypeName: input.issueTypeKey,
          workflowKey: input.workflowKey,
          workflowName: input.workflowKey,
          isDefault: input.issueTypeKey === null,
        }

        queryClient.setQueryData<SchemeDetail>(SCHEME_KEYS.detail(schemeKey), {
          ...prevDetail,
          mappings: [...prevDetail.mappings, optimisticMapping],
          mappingsCount: prevDetail.mappingsCount + 1,
        })
      }

      return { prevDetail }
    },
    onError: (error, _input, context) => {
      const ctx = context as { prevDetail?: SchemeDetail } | undefined

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

      const prevDetail = queryClient.getQueryData<SchemeDetail>(
        SCHEME_KEYS.detail(schemeKey),
      )

      if (prevDetail !== undefined) {
        queryClient.setQueryData<SchemeDetail>(SCHEME_KEYS.detail(schemeKey), {
          ...prevDetail,
          mappings: prevDetail.mappings.filter((m) => m.id !== mappingId),
          mappingsCount: Math.max(0, prevDetail.mappingsCount - 1),
        })
      }

      return { prevDetail }
    },
    onError: (error, _mappingId, context) => {
      const ctx = context as { prevDetail?: SchemeDetail } | undefined

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
