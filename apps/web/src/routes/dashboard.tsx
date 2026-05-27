// 대시보드 페이지 — 인증 사용자 환영 메시지. requireAuth 가드는 router.ts dashboardRoute.beforeLoad에 연결
import { useAuthUser } from '@/auth/authStore'

export const DashboardPage = () => {
  const user = useAuthUser()

  return (
    <div className="p-8">
      <h1 className="text-2xl font-semibold">
        환영합니다, {user?.username ?? ''}
      </h1>
      <nav className="mt-6 flex gap-4" aria-label="주요 메뉴">
        <a
          href="/issues"
          className="text-sm font-medium text-primary hover:underline"
        >
          이슈
        </a>
      </nav>
    </div>
  )
}
