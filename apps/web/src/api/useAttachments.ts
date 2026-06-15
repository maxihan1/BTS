// 첨부 파일 React Query 훅 — 목록 쿼리 + 업로드/삭제 mutation (invalidate-only 동기화)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import {
  fetchAttachments,
  uploadAttachment,
  deleteAttachment,
} from './attachments'
import type { AttachmentResponse } from './attachments'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 첨부 파일 목록 쿼리 키 생성 헬퍼.
 * invalidateQueries 호출 시 동일한 키 구조를 보장하기 위해 함수로 중앙화한다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns TanStack Query queryKey 배열 (예: `["attachments", "ATLAS-1"]`)
 */
export const ATTACHMENTS_QUERY_KEY = (key: string): [string, string] => ['attachments', key]

// ─────────────────────────────────────────────────────────────────────────────
// 에러 메시지 (Task 4에서 i18n 키로 교체 예정)
// ─────────────────────────────────────────────────────────────────────────────

const ERROR_MESSAGES = {
  uploadForbidden: '첨부 파일 업로드 권한이 없습니다.',
  uploadTooLarge: '파일 크기가 너무 큽니다. 100MB 이하의 파일만 업로드할 수 있습니다.',
  uploadDefault: '파일 업로드 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  deleteForbidden: '첨부 파일 삭제 권한이 없습니다.',
  deleteNotFound: '삭제하려는 첨부 파일을 찾을 수 없습니다.',
  deleteDefault: '첨부 파일 삭제 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 첨부 파일 목록 조회 훅.
 *
 * - queryKey: `["attachments", key]`
 * - 업로드/삭제 mutation의 onSuccess가 이 키를 invalidate해 자동 갱신한다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns TanStack Query useQuery 결과
 */
export function useAttachmentList(key: string) {
  return useQuery<AttachmentResponse[]>({
    queryKey: ATTACHMENTS_QUERY_KEY(key),
    queryFn: () => fetchAttachments(key),
    staleTime: 30_000,
  })
}

/**
 * 첨부 파일 업로드 mutation 훅.
 *
 * - mutate(file) 호출로 업로드를 실행한다.
 * - 성공 시 `["attachments", key]` 쿼리를 invalidate한다 (캐시 머지 없이 refetch 유도).
 * - 실패 시 HTTP 상태 코드별 sonner toast.error를 표시한다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns TanStack Query useMutation 결과
 */
export function useUploadAttachment(key: string) {
  const queryClient = useQueryClient()

  return useMutation<AttachmentResponse, unknown, File>({
    mutationFn: (file: File) => uploadAttachment(key, file),

    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ATTACHMENTS_QUERY_KEY(key) })
    },

    onError: (error: unknown) => {
      if (error instanceof ApiError) {
        if (error.status === 403) {
          toast.error(ERROR_MESSAGES.uploadForbidden)
        } else if (error.status === 413) {
          toast.error(ERROR_MESSAGES.uploadTooLarge)
        } else {
          toast.error(ERROR_MESSAGES.uploadDefault)
        }
      } else {
        toast.error(ERROR_MESSAGES.uploadDefault)
      }
    },
  })
}

/**
 * 첨부 파일 삭제 mutation 훅.
 *
 * 하드삭제이므로 호출 전 UI에서 확인 단계 필수.
 *
 * - mutate(attachmentId) 호출로 삭제를 실행한다.
 * - 성공 시 `["attachments", key]` 쿼리를 invalidate한다.
 * - 실패 시 HTTP 상태 코드별 sonner toast.error를 표시한다.
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 * @returns TanStack Query useMutation 결과
 */
export function useDeleteAttachment(key: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, string>({
    mutationFn: (attachmentId: string) => deleteAttachment(key, attachmentId),

    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ATTACHMENTS_QUERY_KEY(key) })
    },

    onError: (error: unknown) => {
      if (error instanceof ApiError) {
        if (error.status === 403) {
          toast.error(ERROR_MESSAGES.deleteForbidden)
        } else if (error.status === 404) {
          toast.error(ERROR_MESSAGES.deleteNotFound)
        } else {
          toast.error(ERROR_MESSAGES.deleteDefault)
        }
      } else {
        toast.error(ERROR_MESSAGES.deleteDefault)
      }
    },
  })
}
