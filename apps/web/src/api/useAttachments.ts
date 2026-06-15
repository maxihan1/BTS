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
import { attachmentLabels } from '@/i18n/attachment-labels'

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

    onError: (error: unknown, file: File) => {
      if (error instanceof ApiError) {
        if (error.status === 403) {
          toast.error(attachmentLabels.uploadForbiddenNamed(file.name))
        } else if (error.status === 413) {
          toast.error(attachmentLabels.uploadTooLargeNamed(file.name))
        } else {
          toast.error(attachmentLabels.uploadDefault)
        }
      } else {
        toast.error(attachmentLabels.uploadDefault)
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
          toast.error(attachmentLabels.deleteForbidden)
        } else if (error.status === 404) {
          toast.error(attachmentLabels.deleteNotFound)
        } else {
          toast.error(attachmentLabels.deleteDefault)
        }
      } else {
        toast.error(attachmentLabels.deleteDefault)
      }
    },
  })
}
