// 인증 앱 영역의 pathless 레이아웃 셸 — 상단바(TopBar)+사이드바(Sidebar)+콘텐츠 크롬 조립 (FR-UX-06 PR11 Task 7)
import { type JSX } from 'react'
import { Outlet } from '@tanstack/react-router'
import { useIsAuthenticated } from '@/auth/authStore'
import { TopBar } from './TopBar'
import { Sidebar } from './Sidebar'

/**
 * `_shell` pathless 라우트의 컴포넌트 — 앱 크롬(상단바+사이드바) 조립을 담당한다.
 *
 * `isAuthenticated === true`이면 상단바(`TopBar`) + 사이드바(`Sidebar`) + 콘텐츠(`Outlet`)로
 * 구성된 레이아웃을 렌더한다(FR1). 크롬은 PR10까지 `RootLayout`이 `<Header/>`로 소유했으나
 * PR11부터 이 컴포넌트로 이관됐다(FR6) — `RootLayout`은 더 이상 `<Header/>`를 렌더하지 않는다.
 *
 * `isAuthenticated === false`이면 크롬을 완전히 억제한 bare `Outlet`만 렌더한다 — 공개 공유
 * 대시보드(`dashboards.shared.$token`)처럼 `_shell` 하위에 있으나 미인증으로 접근 가능한
 * 라우트에서 사이드바가 새어나오지 않는다(E1, S-4).
 *
 * `<main>` 랜드마크는 `RootLayout`(`__root.tsx`)이 이미 `Outlet` 전체를 감싸 소유하므로,
 * 여기서는 콘텐츠 영역을 `<div>`로 감싼다(E7 — main 중복 방지, 최소 변경). 콘텐츠 영역은
 * `overflow-y-auto`로 독립 스크롤한다(NFR2 — 좁은 폭에서도 body가 아닌 컨테이너가 스크롤).
 *
 * ★ 도움말 버튼(TopBar `onHelpClick`)은 전달하지 않는다 — `ShortcutsHelpDialog`와 그 열림
 * 상태는 여전히 `RootLayout`이 소유하며(FR6, G1), `ShellLayout`은 `Outlet`을 통해 렌더되는
 * 자손이라 그 상태에 prop으로 접근할 수 없다. `TopBar`는 `onHelpClick` 미전달 시 도움말
 * 버튼을 렌더하지 않으므로(동작 안 하는 버튼 방지) 이 이슈는 시각적 버튼 없이 `?` 단축키로만
 * 도움말에 접근하는 형태로 PR11에서 이연된다.
 */
export function ShellLayout(): JSX.Element {
  const isAuthenticated = useIsAuthenticated()

  if (!isAuthenticated) {
    return <Outlet />
  }

  return (
    <div className="flex h-screen flex-col">
      <TopBar />
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <div className="min-w-0 flex-1 overflow-y-auto">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
