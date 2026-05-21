// 로그아웃 mutation 훅 — POST /api/v1/auth/logout 호출 후 클라이언트 세션 정리
import { useMutation } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/api/client'
import { useAuthStore } from './authStore'

export function useLogoutMutation() {
  const clearSession = useAuthStore((s) => s.clearSession)

  return useMutation({
    mutationFn: async () => {
      const res = await apiFetch('/api/v1/auth/logout', { method: 'POST' })
      if (!res.ok) {
        const body: unknown = await res.json().catch(() => ({}))
        throw new ApiError(res.status, body)
      }
    },
    onError: (error: ApiError | Error) => {
      // 서버/네트워크 실패는 사일런트 처리 — clearSession은 onSettled에서 항상 보장
      // 토큰 등 민감 정보는 절대 포함하지 않음
      const status = error instanceof ApiError ? error.status : 'network'
      console.error(`[logout] 서버 요청 실패 status=${status} (클라이언트 세션은 정리됩니다)`)
    },
    onSettled: () => {
      // 성공·실패 무관하게 반드시 클라이언트 세션 정리 — 사용자 로그아웃 의도 우선
      clearSession()
    },
  })
}
