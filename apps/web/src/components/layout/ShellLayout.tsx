// 인증 앱 영역의 pathless 레이아웃 셸 — 현재는 Outlet passthrough (전역 사이드바는 PR11에서 채운다)
import { type JSX } from 'react'
import { Outlet } from '@tanstack/react-router'

/**
 * `_shell` pathless 라우트의 컴포넌트.
 *
 * PR10에서는 자식 라우트를 그대로 렌더하는 passthrough(추가 DOM 0)다. 크롬(Header 등)은
 * 여전히 `RootLayout`이 `isAuthenticated` 기준으로 분기하므로 렌더 결과가 재부모화 전과 동일하다.
 * PR11에서 이 컴포넌트가 사이드바 + 콘텐츠 레이아웃으로 확장된다.
 */
export function ShellLayout(): JSX.Element {
  return <Outlet />
}
