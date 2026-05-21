// 최상위 레이아웃 라우트 — Outlet으로 자식 라우트를 렌더하는 공통 껍데기
import { Outlet } from '@tanstack/react-router'

export const RootLayout = () => {
  return (
    <div>
      {/* T15 Header — 후속 작업 */}
      <main>
        <Outlet />
      </main>
    </div>
  )
}
