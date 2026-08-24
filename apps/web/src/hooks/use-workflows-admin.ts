// 워크플로우 관리 TanStack Query 훅 — 조회·쓰기 + 무효화 결선 (FR-WF-04 D6)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { fetchWorkflow } from '@/api/workflows'
import type { WorkflowView } from '@/api/workflows'
import {
  fetchStatuses,
  createStatus,
  createWorkflow,
  updateWorkflow,
  deleteWorkflow,
  duplicateWorkflow,
  addWorkflowStatus,
  removeWorkflowStatus,
  reorderWorkflowStatuses,
  createTransition,
  updateTransition,
  deleteTransition,
} from '@/api/workflows-admin'
import type {
  StatusCatalogEntry,
  CreatedStatus,
  CreatedWorkflow,
  TransitionDefinition,
  CreateStatusInput,
  CreateWorkflowInput,
  UpdateWorkflowInput,
  DuplicateWorkflowInput,
  AddWorkflowStatusInput,
  TransitionDefinitionInput,
} from '@/api/workflows-admin'
import { WORKFLOW_QUERY_KEY } from './use-workflows'
import { notifyWorkflowAdminError } from './workflow-admin-error'

export { mapWorkflowAdminError, notifyWorkflowAdminError } from './workflow-admin-error'

/** 카탈로그 캐시 유지 시간 (ms) — 전역 상태는 자주 안 바뀐다 */
const STALE_TIME_MS = 30_000

/**
 * queryKey 정본.
 *
 * ★ `list` 는 `use-workflows` 의 키를 **그대로 재사용한다**. 관리 화면이 자기 키를 따로
 * 만들면 같은 서버 자원에 캐시가 둘 생겨 서로를 무효화하지 못한다.
 */
export const WORKFLOW_ADMIN_KEYS = {
  /** 워크플로우 목록 — `use-workflows` 와 공유 */
  list: WORKFLOW_QUERY_KEY,
  /** 워크플로우 단건 */
  detail: (key: string) => ['workflows', 'detail', key] as const,
  /** 전역 상태 카탈로그 */
  statusCatalog: ['statuses'] as const,
} as const

/** 목록·상세를 함께 무효화한다. 쓰기 훅 전부가 이 한 곳을 지난다. */
async function invalidateWorkflow(client: QueryClient, key: string): Promise<void> {
  await client.invalidateQueries({ queryKey: WORKFLOW_ADMIN_KEYS.list })
  await client.invalidateQueries({ queryKey: WORKFLOW_ADMIN_KEYS.detail(key) })
}

// ─────────────────────────────────────────────────────────────────────────────
// 조회
// ─────────────────────────────────────────────────────────────────────────────

/** 전역 상태 카탈로그를 조회한다. 상태 선택 다이얼로그의 원본이다. */
export function useStatusCatalog() {
  return useQuery<StatusCatalogEntry[]>({
    queryKey: WORKFLOW_ADMIN_KEYS.statusCatalog,
    queryFn: () => fetchStatuses(),
    staleTime: STALE_TIME_MS,
  })
}

/** 워크플로우 단건을 조회한다. `key` 가 비면 조회하지 않는다. */
export function useWorkflowDetail(key: string) {
  return useQuery<WorkflowView>({
    queryKey: WORKFLOW_ADMIN_KEYS.detail(key),
    queryFn: () => fetchWorkflow(key),
    enabled: key.length > 0,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 워크플로우 쓰기
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우를 만든다. */
export function useCreateWorkflow() {
  const client = useQueryClient()
  return useMutation<CreatedWorkflow, unknown, CreateWorkflowInput>({
    mutationFn: (input) => createWorkflow(input),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: WORKFLOW_ADMIN_KEYS.list })
    },
    onError: notifyWorkflowAdminError,
  })
}

/** 이름·설명을 고친다. */
export function useUpdateWorkflow(key: string) {
  const client = useQueryClient()
  return useMutation<void, unknown, UpdateWorkflowInput>({
    mutationFn: (input) => updateWorkflow(key, input),
    onSuccess: () => invalidateWorkflow(client, key),
    onError: notifyWorkflowAdminError,
  })
}

/** 소프트 삭제한다. 상세 캐시는 무효화가 아니라 제거한다 — 되살릴 대상이 없다. */
export function useDeleteWorkflow() {
  const client = useQueryClient()
  return useMutation<void, unknown, string>({
    mutationFn: (key) => deleteWorkflow(key),
    onSuccess: async (_data, key) => {
      await client.invalidateQueries({ queryKey: WORKFLOW_ADMIN_KEYS.list })
      client.removeQueries({ queryKey: WORKFLOW_ADMIN_KEYS.detail(key) })
    },
    onError: notifyWorkflowAdminError,
  })
}

/**
 * 상태 편성·전환까지 복제한다.
 *
 * 원본 키를 **mutate 시점에** 받는다. 훅 생성 시점에 묶으면 행마다 원본이 다른 목록
 * 화면에서 쓸 수 없다 — 한 훅 인스턴스가 한 원본에만 매이기 때문이다.
 */
export function useDuplicateWorkflow() {
  const client = useQueryClient()
  return useMutation<CreatedWorkflow, unknown, DuplicateWorkflowInput & { sourceKey: string }>({
    mutationFn: ({ sourceKey, ...input }) => duplicateWorkflow(sourceKey, input),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: WORKFLOW_ADMIN_KEYS.list })
    },
    onError: notifyWorkflowAdminError,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 전역 상태 카탈로그 쓰기
// ─────────────────────────────────────────────────────────────────────────────

/** 카탈로그에 상태를 새로 만든다. 상태 선택 다이얼로그의 「새 상태 만들기」가 쓴다. */
export function useCreateStatus() {
  const client = useQueryClient()
  return useMutation<CreatedStatus, unknown, CreateStatusInput>({
    mutationFn: (input) => createStatus(input),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: WORKFLOW_ADMIN_KEYS.statusCatalog })
    },
    onError: notifyWorkflowAdminError,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 상태 편성
// ─────────────────────────────────────────────────────────────────────────────

/** 카탈로그의 상태를 이 워크플로우에 편성한다. */
export function useAddWorkflowStatus(key: string) {
  const client = useQueryClient()
  return useMutation<void, unknown, AddWorkflowStatusInput>({
    mutationFn: (input) => addWorkflowStatus(key, input),
    onSuccess: () => invalidateWorkflow(client, key),
    onError: notifyWorkflowAdminError,
  })
}

/** 편성을 뗀다. 마지막 상태·이슈 사용 중·전환 참조 시 실패하고 사유별 토스트가 뜬다. */
export function useRemoveWorkflowStatus(key: string) {
  const client = useQueryClient()
  return useMutation<void, unknown, string>({
    mutationFn: (statusId) => removeWorkflowStatus(key, statusId),
    onSuccess: () => invalidateWorkflow(client, key),
    onError: notifyWorkflowAdminError,
  })
}

/** 표시 순서를 통째로 다시 정한다. 그 워크플로우의 상태 전부를 담아 보내야 한다. */
export function useReorderWorkflowStatuses(key: string) {
  const client = useQueryClient()
  return useMutation<void, unknown, string[]>({
    mutationFn: (statusIds) => reorderWorkflowStatuses(key, statusIds),
    onSuccess: () => invalidateWorkflow(client, key),
    onError: notifyWorkflowAdminError,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 전환 정의
// ─────────────────────────────────────────────────────────────────────────────

/** 전환 정의를 만든다. */
export function useCreateTransition(key: string) {
  const client = useQueryClient()
  return useMutation<TransitionDefinition, unknown, TransitionDefinitionInput>({
    mutationFn: (input) => createTransition(key, input),
    onSuccess: () => invalidateWorkflow(client, key),
    onError: notifyWorkflowAdminError,
  })
}

/** 전환 정의를 통째로 갈아 끼운다. */
export function useUpdateTransition(key: string) {
  const client = useQueryClient()
  return useMutation<TransitionDefinition, unknown, { transitionId: string; input: TransitionDefinitionInput }>({
    mutationFn: ({ transitionId, input }) => updateTransition(key, transitionId, input),
    onSuccess: () => invalidateWorkflow(client, key),
    onError: notifyWorkflowAdminError,
  })
}

/** 전환 정의를 지운다. 최초 전환은 지울 수 없다. */
export function useDeleteTransition(key: string) {
  const client = useQueryClient()
  return useMutation<void, unknown, string>({
    mutationFn: (transitionId) => deleteTransition(key, transitionId),
    onSuccess: () => invalidateWorkflow(client, key),
    onError: notifyWorkflowAdminError,
  })
}
