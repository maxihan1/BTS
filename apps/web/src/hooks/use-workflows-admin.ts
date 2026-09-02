// 워크플로우 관리 TanStack Query 훅 — 목록·생성·복제·삭제. 편집은 초안 경로가 맡는다
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchWorkflow } from '@/api/workflows'
import type { WorkflowView } from '@/api/workflows'
import {
  fetchStatuses,
  createStatus,
  createWorkflow,
  deleteWorkflow,
  duplicateWorkflow,
} from '@/api/workflows-admin'
import type {
  StatusCatalogEntry,
  CreatedStatus,
  CreatedWorkflow,
  CreateStatusInput,
  CreateWorkflowInput,
  DuplicateWorkflowInput,
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

/**
 * ★ 정의 편집 훅은 이 파일에 없다.
 *
 * 상태 추가·제거·순서와 전환 CRUD 는 FR-WF-07 D6 에서 **초안 경로**로 옮겼다
 * (`use-workflow-draft` → `PUT /draft` → `POST /publish`). 편집이 곧 배포이던 시절의 훅 일곱은
 * 그때 소비처가 사라져 함께 지웠다 — 남겨 두면 「아직 직접 쓸 수 있다」는 오해를 남긴다.
 *
 * 그 엔드포인트 자체는 백엔드에 여전히 실재하고 MSW 핸들러도 남아 있다. 프론트가 안 부르는
 * 것과 서버에 없는 것은 다른 사실이다.
 */

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

// ─────────────────────────────────────────────────────────────────────────────
// 전환 정의
// ─────────────────────────────────────────────────────────────────────────────

