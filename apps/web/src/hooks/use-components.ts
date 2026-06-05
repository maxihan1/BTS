// 컴포넌트 BC TanStack Query 훅 — CRUD invalidate-only + 에러 토스트 (FR-CM-01)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchComponents,
  createComponent,
  updateComponent,
  changeComponentLead,
  deleteComponent,
  extractComponentErrorCode,
} from '@/api/components'
import { componentErrorMessage } from '@/i18n/component-labels'
import type { Component, CreateComponentInput, UpdateComponentInput } from '@/api/components'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 컴포넌트 BC queryKey 팩토리 */
export const COMPONENT_KEYS = {
  /** 프로젝트별 컴포넌트 목록 queryKey */
  list: (projectKey: string) => ['components', projectKey] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 토스트 헬퍼 — 훅 레이어에서 단일 발사, 컴포넌트 중복 금지
// ─────────────────────────────────────────────────────────────────────────────

function notifyComponentError(error: unknown): void {
  const code = extractComponentErrorCode(error)
  toast.error(componentErrorMessage(code))
}

// ─────────────────────────────────────────────────────────────────────────────
// useComponents — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/** useComponents 옵션 타입 */
export interface UseComponentsOptions {
  /** false이면 쿼리를 idle 상태로 유지해 fetch를 지연한다. 기본값 true. */
  enabled?: boolean
}

/**
 * 프로젝트 컴포넌트 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/components → Component[]
 * staleTime 30초 — 빈번한 목록 재조회를 방지한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param options 쿼리 옵션 — enabled: false이면 즉시 fetch하지 않음 (lazy 로드)
 */
export function useComponents(projectKey: string, options?: UseComponentsOptions) {
  return useQuery({
    queryKey: COMPONENT_KEYS.list(projectKey),
    queryFn: () => fetchComponents(projectKey),
    staleTime: 30_000,
    enabled: options?.enabled ?? true,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useCreateComponent — 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트를 생성한다.
 *
 * POST /api/v1/projects/{projectKey}/components → 201 Component
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지)
 * onError   → extractComponentErrorCode → componentErrorMessage → toast.error
 *
 * @param projectKey 컴포넌트를 추가할 프로젝트 키
 */
export function useCreateComponent(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = COMPONENT_KEYS.list(projectKey)

  return useMutation<Component, unknown, CreateComponentInput>({
    mutationFn: (input) => createComponent(projectKey, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyComponentError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateComponent — 이름/설명 수정
// ─────────────────────────────────────────────────────────────────────────────

/** 컴포넌트 수정 mutation 입력 타입 */
export interface UpdateComponentMutationInput {
  id: string
  input: UpdateComponentInput
}

/**
 * 컴포넌트 이름 / 설명을 수정한다.
 *
 * PATCH /api/v1/projects/{projectKey}/components/{id} → 200 Component
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useUpdateComponent(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = COMPONENT_KEYS.list(projectKey)

  return useMutation<Component, unknown, UpdateComponentMutationInput>({
    mutationFn: ({ id, input }) => updateComponent(projectKey, id, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyComponentError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeComponentLead — 리드 변경
// ─────────────────────────────────────────────────────────────────────────────

/** 컴포넌트 리드 변경 mutation 입력 타입 */
export interface ChangeComponentLeadMutationInput {
  id: string
  leadUserId: string | null
}

/**
 * 컴포넌트 리드를 변경하거나 해제한다.
 *
 * PATCH /api/v1/projects/{projectKey}/components/{id}/lead → 200 Component
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error (422 COMPONENT_LEAD_NOT_FOUND 포함)
 *
 * @param projectKey 프로젝트 키
 */
export function useChangeComponentLead(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = COMPONENT_KEYS.list(projectKey)

  return useMutation<Component, unknown, ChangeComponentLeadMutationInput>({
    mutationFn: ({ id, leadUserId }) => changeComponentLead(projectKey, id, leadUserId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyComponentError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteComponent — 삭제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트를 삭제한다.
 *
 * DELETE /api/v1/projects/{projectKey}/components/{id} → 204 No Content
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useDeleteComponent(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = COMPONENT_KEYS.list(projectKey)

  return useMutation<void, unknown, string>({
    mutationFn: (id) => deleteComponent(projectKey, id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyComponentError(error)
    },
  })
}
