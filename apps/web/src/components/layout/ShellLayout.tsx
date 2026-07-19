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
 * `isAuthenticated === false`이면 크롬을 억제하되 `<main>`으로 감싼 `Outlet`을 렌더한다(D-D) —
 * 공개 공유 대시보드(`dashboards.shared.$token`)처럼 `_shell` 하위에 있으나 미인증으로 접근
 * 가능한 라우트에서 사이드바가 새어나오지 않으면서도(E1, S-4) main 랜드마크는 보장한다.
 *
 * `<main>` 랜드마크는 PR12부터 `RootLayout`(`__root.tsx`)이 아니라 이 컴포넌트가 소유한다(C3).
 * 인증 분기의 콘텐츠 영역(`min-w-0 flex-1 overflow-y-auto`)을 `<main>`으로 승격해 `TopBar`의
 * `<header>`(banner)·`Sidebar`의 `<aside>`(complementary)와 형제로 배치한다 — 이렇게 하면
 * header/aside가 main 밖에 위치해 landmark가 오염되지 않는다. 콘텐츠 영역은 `overflow-y-auto`로
 * 독립 스크롤한다(NFR2 — 좁은 폭에서도 body가 아닌 컨테이너가 스크롤).
 *
 * 랜드마크 소유 맵(PR12 이후, 페이지당 각 1개).
 * - `banner` — `TopBar`의 `<header>` (인증 분기만, `_shell` 크롬 최상단)
 * - `main` — 이 컴포넌트(`ShellLayout`)가 양 분기 모두 직접 소유. `login.tsx`는 `_shell` 밖
 *   (rootRoute 직속)이라 ShellLayout의 main을 물려받지 못하므로 별도로 자체 `<main>`을 렌더한다.
 * - `complementary` — `Sidebar`의 `<aside>` (인증 분기만)
 * `RootLayout`(`__root.tsx`)은 더 이상 `<main>`을 렌더하지 않는다 — 렌더하면 이 셋이 모두 그
 * `<main>` 안에 중첩돼 header/aside의 landmark role이 오염된다(이중 main 방지 목적도 겸함).
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
    return (
      <main>
        <Outlet />
      </main>
    )
  }

  return (
    <div className="flex h-screen flex-col">
      <TopBar />
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <main className="min-w-0 flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
