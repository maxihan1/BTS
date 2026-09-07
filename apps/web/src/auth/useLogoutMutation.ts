// 로그아웃 mutation 훅 — POST /api/v1/auth/logout 호출 후 세션 정리 + /login 이동
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { apiFetch, ApiError } from '@/api/client'
import { useAuthStore } from './authStore'

/**
 * 로그아웃 mutation.
 *
 * ### 🛑 이동은 **이 훅이** 한다 — 호출자의 `mutate(_, { onSettled })` 가 아니다
 *
 * 종전에는 `AccountMenu` 가 `mutate(undefined, { onSettled: () => navigate({to:'/login'}) })`
 * 로 이동을 걸었고, **그 콜백이 한 번도 실행되지 않았다.** 세션만 지워지고 URL 은 그대로라
 * 로그인 모달(`LoginDialog`)이 뜨지 않았다 — 그 모달의 열림 조건이 `pathname === '/login'`
 * 이기 때문이다.
 *
 * 이유. `clearSession()` 이 `ShellLayout` 의 `!isAuthenticated` 조기 반환을 켜서 `TopBar` →
 * `AccountMenu` 가 그 자리에서 언마운트된다. TanStack Query 는 **`mutate` 에 넘긴 콜백을
 * 옵저버가 살아 있을 때만** 부르므로(`hasListeners()` 게이트) 그 시점에 이동 콜백이 통째로
 * 버려진다. 반면 `useMutation` 에 준 콜백은 mutation 자신이 들고 있어 언마운트와 무관하게 돈다.
 *
 * 🛑 순서를 뒤집지 마라. 이동을 먼저 하면 아직 인증 상태라 `/login` 의 `redirectIfAuth` 가
 *    곧바로 되돌려 보낸다. **지우고 → 옮긴다.**
 *
 * ### 이 결함은 우회가 가리고 있었다
 * `start-page.spec.ts`·`active-project.spec.ts` 가 「navigate 가 안 일어난다」를 헤더에 적어 두고
 * `popstate` 를 수동 재발행해 로그인 폼에 도달했다. 두 스펙이 초록이라 아무도 다시 보지 않았다.
 * 짝 판별식 = `e2e/logout-login-modal.spec.ts` — **우회를 쓰지 않는다.**
 */
export function useLogoutMutation() {
  const clearSession = useAuthStore((s) => s.clearSession)
  const navigate = useNavigate()

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
      // 이어서 /login 으로 옮긴다. 여기서 하지 않으면 아무도 하지 않는다(위 KDoc).
      void navigate({ to: '/login' })
    },
  })
}
