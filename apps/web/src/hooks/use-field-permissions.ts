// 필드 권한 규칙 BC TanStack Query 훅 — CRUD invalidate-only + 에러 토스트 (FR-PM-07)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchFieldPermissions,
  createFieldPermission,
  deleteFieldPermission,
  extractFieldPermissionErrorCode,
} from '@/api/field-permissions'
import type { FieldPermissionResponse, CreateFieldPermissionInput } from '@/api/field-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 필드 권한 규칙 BC queryKey 팩토리 */
export const FIELD_PERMISSION_KEYS = {
  /** 프로젝트별 필드 권한 규칙 목록 queryKey */
  list: (projectKey: string) => ['field-permissions', projectKey] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 토스트 헬퍼 — 훅 레이어에서 단일 발사
// silent:true 경로(Dialog 인라인)에서는 호출하지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

function notifyFieldPermissionError(error: unknown): void {
  const code = extractFieldPermissionErrorCode(error)
  const message = fieldPermissionErrorMessage(code)
  toast.error(message)
}

/**
 * 백엔드 errorCode를 사용자 노출 메시지로 변환한다.
 *
 * @param errorCode 백엔드 ProblemDetail의 errorCode 필드값 (null 허용)
 * @returns 사용자에게 노출할 한국어 오류 메시지
 */
function fieldPermissionErrorMessage(errorCode: string | null): string {
  switch (errorCode) {
    case 'FIELD_PERMISSION_DUPLICATE':
      return '이미 동일한 필드 권한 규칙이 있습니다.'
    case 'FIELD_PERMISSION_NOT_FOUND':
      return '필드 권한 규칙을 찾을 수 없습니다.'
    case 'FIELD_PERMISSION_PROJECT_NOT_FOUND':
      return '프로젝트를 찾을 수 없습니다.'
    case 'FIELD_PERMISSION_ACCESS_DENIED':
      return '권한이 없습니다.'
    default:
      return '요청을 처리하지 못했습니다.'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useFieldPermissions — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/** useFieldPermissions 옵션 타입 */
export interface UseFieldPermissionsOptions {
  /** false이면 쿼리를 idle 상태로 유지해 fetch를 지연한다. 기본값 true. */
  enabled?: boolean
}

/**
 * 프로젝트 필드 권한 규칙 목록을 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/field-permissions → FieldPermissionResponse[]
 * staleTime 30초 — 빈번한 목록 재조회를 방지한다.
 *
 * @param projectKey 프로젝트 식별 키
 * @param options 쿼리 옵션 — enabled: false이면 즉시 fetch하지 않음 (lazy 로드)
 */
export function useFieldPermissions(projectKey: string, options?: UseFieldPermissionsOptions) {
  return useQuery({
    queryKey: FIELD_PERMISSION_KEYS.list(projectKey),
    queryFn: () => fetchFieldPermissions(projectKey),
    staleTime: 30_000,
    enabled: options?.enabled ?? true,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 mutation 옵션 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useCreateFieldPermission 옵션 타입 */
export interface UseFieldPermissionMutationOptions {
  /**
   * true이면 onError에서 toast를 발사하지 않는다.
   * Dialog 경로처럼 인라인 submitError만 표시하는 경우에 사용한다.
   */
  silent?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// useCreateFieldPermission — 생성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 권한 규칙을 생성한다.
 *
 * POST /api/v1/projects/{projectKey}/field-permissions → 201 FieldPermissionResponse
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지)
 * onError   → silent:false(기본)이면 toast.error 발사, silent:true이면 억제.
 *             Dialog 경로는 silent:true + per-call onError로 인라인 submitError만 표시한다.
 *
 * @param projectKey 필드 권한 규칙을 추가할 프로젝트 키
 * @param options 뮤테이션 옵션 — silent:true이면 onError toast 억제
 */
export function useCreateFieldPermission(
  projectKey: string,
  options?: UseFieldPermissionMutationOptions,
) {
  const queryClient = useQueryClient()
  const listKey = FIELD_PERMISSION_KEYS.list(projectKey)
  const silent = options?.silent ?? false

  return useMutation<FieldPermissionResponse, unknown, CreateFieldPermissionInput>({
    mutationFn: (input) => createFieldPermission(projectKey, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      if (!silent) {
        notifyFieldPermissionError(error)
      }
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteFieldPermission — 삭제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 권한 규칙을 삭제한다.
 *
 * DELETE /api/v1/projects/{projectKey}/field-permissions/{id} → 204 No Content
 *
 * onSuccess → invalidateQueries
 * onError   → toast.error
 *
 * @param projectKey 프로젝트 키
 */
export function useDeleteFieldPermission(projectKey: string) {
  const queryClient = useQueryClient()
  const listKey = FIELD_PERMISSION_KEYS.list(projectKey)

  return useMutation<void, unknown, string>({
    mutationFn: (id) => deleteFieldPermission(projectKey, id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: listKey })
    },
    onError: (error) => {
      notifyFieldPermissionError(error)
    },
  })
}
