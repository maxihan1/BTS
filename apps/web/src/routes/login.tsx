// 로그인 페이지 — LoginForm 마운트 + 카드 래퍼 + 가운데 정렬
import { useNavigate } from '@tanstack/react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { LoginForm } from '@/auth/LoginForm'
import { useAuthStore } from '@/auth/authStore'
import { resolvePostLoginNav } from '@/auth/routeGuard'
import { loginPageStrings } from '@/i18n/ko'

export const LoginPage = () => {
  const navigate = useNavigate()

  /**
   * 로그인 성공 후 목적지 해석. 우선순위는 {@link resolvePostLoginNav} 참조
   * (returnTo(안전 검증 통과) > start_page 매핑 > /dashboards).
   * redirectIfAuth(routeGuard.ts)와 동일한 우선순위 로직을 공유한다(중복 정의 금지).
   */
  function handleSuccess() {
    const rawReturnTo = new URLSearchParams(window.location.search).get('returnTo')
    const user = useAuthStore.getState().user
    void navigate(resolvePostLoginNav(rawReturnTo, user?.startPage, user?.userId))
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
