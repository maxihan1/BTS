// 전역 로그인 모달 — 미인증 진입과 세션 만료를 하나의 모달로 처리한다
import { useNavigate, useRouterState } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { LoginForm } from '@/auth/LoginForm'
import { useAuthStore, useIsAuthenticated } from './authStore'
import { useLoginPromptStore } from './loginPromptStore'
import { resolvePostLoginNav } from './routeGuard'
import { loginPageStrings } from '@/i18n/ko'

/**
 * 앱 전역에 하나만 마운트되는 로그인 모달(`RootLayout` 소유).
 *
 * 열림 조건은 두 갈래이고 **모달은 배경을 만들지 않는다**.
 * - 미인증 + `/login` — `requireAuth` 가 이미 보냈고, 배경은 `routes/login.tsx` 의 `AuthBackdrop`
 * - 미인증 + 세션 만료 플래그 — 라우트 이동 없이 사용자가 보던 진짜 화면이 배경
 *
 * 닫기 3경로(ESC · 오버레이 · X)를 전부 봉인한다. 첫 진입에서 닫으면 배경만 남는 막다른 골목이고,
 * 만료 상태에서 닫으면 stale 화면의 모든 조작이 401 을 낳는다. 두 경우의 정책을 가르지 않는 이유는
 * 정책이 갈리면 코드도 갈려 "하나의 모달" 이라는 요구가 깨지기 때문이다.
 * 탈출구는 브라우저 주소창·뒤로가기이며 그것은 막지 않는다.
 *
 * 선례. `AutomationYamlImportDialog.tsx`(3경로 가로채기) · `confirm-dialog.tsx`(확정 중 전면 잠금).
 * `DESIGN.md:482` 의 "Esc 로 모달 닫기(Radix 기본)" 에 대한 의도적 편차다.
 */
export const LoginDialog = () => {
  const isAuthenticated = useIsAuthenticated()
  const sessionExpired = useLoginPromptStore((s) => s.sessionExpired)
  const resetPrompt = useLoginPromptStore((s) => s.reset)
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const open = !isAuthenticated && (pathname === '/login' || sessionExpired)

  /**
   * 로그인 성공 후 목적지. 만료 재로그인은 **이동하지 않는다** — 사용자가 보던 화면을
   * 그대로 두고 쿼리만 무효화해 데이터를 회복시킨다(`settings.account-links.tsx` 의
   * step-up 후 재개와 같은 자리).
   *
   * 첫 진입은 기존 우선순위를 그대로 따른다. returnTo(안전 검증 통과) > start_page > /dashboards.
   * 판정은 {@link resolvePostLoginNav} 하나만 쓴다(중복 정의 금지).
   */
  function handleSuccess() {
    if (sessionExpired) {
      resetPrompt()
      void queryClient.invalidateQueries()
      return
    }
    const rawReturnTo = new URLSearchParams(window.location.search).get('returnTo')
    const user = useAuthStore.getState().user
    void navigate(resolvePostLoginNav(rawReturnTo, user?.startPage, user?.userId))
  }

  return (
    <Dialog open={open}>
      <DialogContent
        // 🛑 aria-label 을 주지 마라. Radix 가 DialogTitle 을 aria-labelledby 로 연결하고
        //    그것이 aria-label 을 이긴다 — 두 경로를 두면 "지정한 이름 ≠ 실제 이름" 이 된다
        //    (confirm-dialog.tsx 가 실측으로 확인한 규칙).
        showCloseButton={false}
        overlayClassName="backdrop-blur-sm"
        // MFA 단계로 전환되면 콘텐츠 높이가 늘어난다. top-1/2 -translate-y-1/2 라 재중앙정렬은
        // 자동이고, max-h 는 SSO 버튼과 MFA 가 겹칠 때의 짧은 뷰포트 방어다.
        className="max-h-[85vh] overflow-y-auto sm:max-w-md"
        onEscapeKeyDown={(e) => {
          e.preventDefault()
        }}
        onPointerDownOutside={(e) => {
          e.preventDefault()
        }}
      >
        <DialogHeader>
          <DialogTitle>{loginPageStrings.heading}</DialogTitle>
          <DialogDescription>
            {sessionExpired
              ? loginPageStrings.sessionExpiredDescription
              : loginPageStrings.description}
          </DialogDescription>
        </DialogHeader>
        <LoginForm onSuccess={handleSuccess} />
      </DialogContent>
    </Dialog>
  )
}
