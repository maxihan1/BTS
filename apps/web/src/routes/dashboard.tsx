// 대시보드 페이지 — requireAuth 가드 적용 완료 (router.ts dashboardRoute.beforeLoad 연결), T15에서 헤더 + 환영 메시지 추가
import { requireAuth } from '@/auth/routeGuard'

// router.ts 의 dashboardRoute 에 beforeLoad: requireAuth 를 연결하기 위해 re-export
export { requireAuth }

export const DashboardPage = () => {
  return <div>대시보드 (placeholder — T15에서 헤더 + 환영 메시지 추가)</div>
}
