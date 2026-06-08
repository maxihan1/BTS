// 커스텀 필드 BC TanStack Query 훅 — CRUD invalidate-only + 에러 토스트 (FR-IS-10)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchCustomFields,
  createCustomField,
  updateCustomField,
  deleteCustomField,
  extractCustomFieldErrorCode,
} from '@/api/custom-fields'
import { customFieldErrorMessage } from '@/i18n/custom-field-labels'
import type { CustomField, CreateCustomFieldInput, UpdateCustomFieldInput } from '@/api/custom-fields'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 커스텀 필드 BC queryKey 팩토리 */
export const CUSTOM_FIELD_KEYS = {
  /** 프로젝트별 커스텀 필드 목록 queryKey */
  list: (projectKey: string) => ['custom-fields', projectKey] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 토스트 헬퍼 — 훅 레이어에서 단일 발사, 컴포넌트 중복 금지
// ─────────────────────────────────────────────────────────────────────────────

function notifyCustomFieldError(error: unknown): void {
  const code = extractCustomFieldErrorCode(error)
  toast.error(customFieldErrorMessage(code))
}

// ─────────────────────────────────────────────────────────────────────────────
// useCustomFields — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/** useCustomFields 옵션 타입 */
export interface UseCustomFieldsOptions {
  /** false이면 쿼리를 idle 상태로 유지해 fetch를 지연한다. 기본값 true. */
  enabled?: boolean
}

/**
 * 프로젝트 커스텀 필드 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/custom-fields → CustomField[]
 * staleTime 30초 — 빈번한 목록 재조회를 방지한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param options 쿼리 옵션 — enabled: false이면 즉시 fetch하지 않음 (lazy 로드)
 */
export function useCustomFields(projectKey: string, options?: UseCustomFieldsOptions) {
  return useQuery({
    queryKey: CUSTOM_FIELD_KEYS.list(projectKey),
    queryFn: () => fetchCustomFields(projectKey),
    staleTime: 30_000,
    enabled: options?.enabled ?? true,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useCreateCustomField — 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드를 생성한다.
 *
 * POST /api/v1/projects/{projectKey}/custom-fields → 201 CustomField
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지)
 * onError   → extractCustomFieldErrorCode → customFieldErrorMessage → toast.error
 *
 * @param projectKey 커스텀 필드를 추가할 프로젝트 키
 */
export function useCreateCustomField(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = CUSTOM_FIELD_KEYS.list(projectKey)

  return useMutation<CustomField, unknown, CreateCustomFieldInput>({
    mutationFn: (input) => createCustomField(projectKey, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyCustomFieldError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateCustomField — 수정
// ─────────────────────────────────────────────────────────────────────────────

/** 커스텀 필드 수정 mutation 입력 타입 */
export interface UpdateCustomFieldMutationInput {
  fieldId: string
  input: UpdateCustomFieldInput
}

/**
 * 커스텀 필드를 수정한다 (fieldType·key는 불변).
 *
 * PATCH /api/v1/projects/{projectKey}/custom-fields/{fieldId} → 200 CustomField
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useUpdateCustomField(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = CUSTOM_FIELD_KEYS.list(projectKey)

  return useMutation<CustomField, unknown, UpdateCustomFieldMutationInput>({
    mutationFn: ({ fieldId, input }) => updateCustomField(projectKey, fieldId, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyCustomFieldError(error)
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteCustomField — 삭제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드를 삭제한다.
 *
 * DELETE /api/v1/projects/{projectKey}/custom-fields/{fieldId} → 204 No Content
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useDeleteCustomField(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = CUSTOM_FIELD_KEYS.list(projectKey)

  return useMutation<void, unknown, string>({
    mutationFn: (fieldId) => deleteCustomField(projectKey, fieldId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyCustomFieldError(error)
    },
  })
}
