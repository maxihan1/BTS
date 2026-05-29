// 세션 강제 종료 mutation 훅 — 성공 시 세션 캐시 무효화 + 토스트, 실패 시 토스트
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { revokeSession } from '@/api/sessions'
import { ApiError } from '@/api/client'
import { SESSIONS_QUERY_KEY } from './useSessionsQuery'

/**
 * 지정한 세션을 강제 종료하는 mutation 훅.
 *
 * - `DELETE /api/v1/auth/sessions/{sid}` → 204 No Content
 * - 성공 시: `['sessions']` 캐시를 무효화하고 `toast.success`를 표시한다.
 * - 실패 시: `toast.error`로 오류 메시지를 표시한다.
 *   - 409: 현재 세션은 종료할 수 없음 (spec §FR-5)
 *   - 404: IDOR / 미존재 sid (spec §FR-4)
 *   - 403: PAT 인증 호출 (spec §FR-6b)
 *
 * @returns TanStack Query useMutation 반환 객체. `mutate(sid)` 로 호출.
 */
export function useRevokeSessionMutation() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (sid: string) => revokeSession(sid),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SESSIONS_QUERY_KEY })
      toast.success('세션을 종료했습니다.')
    },
    onError: (error: unknown) => {
      if (error instanceof ApiError) {
        if (error.status === 409) {
          toast.error('현재 사용 중인 세션은 종료할 수 없습니다.')
        } else if (error.status === 404) {
          toast.error('세션을 찾을 수 없습니다. 이미 종료된 세션일 수 있습니다.')
        } else if (error.status === 403) {
          toast.error('이 기능은 세션 로그인에서만 사용할 수 있습니다.')
        } else {
          toast.error(`세션 종료에 실패했습니다. (${error.status})`)
        }
      } else {
        toast.error('세션 종료 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
      }
    },
  })
}
