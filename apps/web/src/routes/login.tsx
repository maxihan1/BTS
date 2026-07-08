// 로그인 페이지 — LoginForm 마운트 + 카드 래퍼 + 가운데 정렬
import { useNavigate } from '@tanstack/react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { LoginForm } from '@/auth/LoginForm'
import { useAuthStore } from '@/auth/authStore'
import { isSafeReturnTo } from '@/auth/routeGuard'
import { resolveStartPageNav } from '@/lib/start-page'
import { loginPageStrings } from '@/i18n/ko'

export const LoginPage = () => {
  const navigate = useNavigate()

  /**
   * 로그인 성공 후 목적지 우선순위(게이트1 확정, FR-PF-02 Task 7).
   * returnTo(안전 검증 통과) > start_page 매핑 > /dashboards.
   * redirectIfAuth(routeGuard.ts)와 동일한 파싱·우선순위를 사용한다(중복 정의 금지 — isSafeReturnTo 재사용).
   */
  function handleSuccess() {
    const rawReturnTo = new URLSearchParams(window.location.search).get('returnTo')
    if (rawReturnTo !== null && isSafeReturnTo(rawReturnTo)) {
      void navigate({ to: rawReturnTo })
      return
    }

    const user = useAuthStore.getState().user
    void navigate(resolveStartPageNav(user?.startPage, user?.userId))
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <Card className="w-full max-w-sm sm:max-w-md">
        <CardHeader>
          <CardTitle>
            <h1 className="text-xl font-semibold">{loginPageStrings.heading}</h1>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <LoginForm onSuccess={handleSuccess} />
        </CardContent>
      </Card>
    </div>
  )
}
