// 로그인 페이지 — LoginForm 마운트 + 카드 래퍼 + 가운데 정렬
import { useNavigate } from '@tanstack/react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { LoginForm } from '@/auth/LoginForm'
import { loginPageStrings } from '@/i18n/ko'

export const LoginPage = () => {
  const navigate = useNavigate()

  function handleSuccess() {
    void navigate({ to: '/dashboard' })
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
