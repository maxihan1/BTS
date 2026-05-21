// 대시보드 페이지 — 인증 사용자 환영 메시지. requireAuth 가드는 router.ts dashboardRoute.beforeLoad에 연결
import { useAuthUser } from '@/auth/authStore'

export const DashboardPage = () => {
  const user = useAuthUser()

  return (
    <div className="p-8">
      <h1 className="text-2xl font-semibold">
        환영합니다, {user?.username ?? ''}
      </h1>
    </div>
  )
}
