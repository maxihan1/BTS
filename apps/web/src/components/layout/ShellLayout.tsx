// 인증 앱 영역의 pathless 레이아웃 셸 — 상단바(TopBar)+사이드바(Sidebar)+콘텐츠 크롬 조립 (FR-UX-06 PR11 Task 7)
import { type JSX } from 'react'
import { Outlet } from '@tanstack/react-router'
import { useIsAuthenticated } from '@/auth/authStore'
import { useTrackActiveProject } from '@/hooks/use-track-active-project'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { useContextShortcuts } from '@/components/keyboard-shortcuts/useContextShortcuts'
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
 * 셸 크롬 랜드마크 소유 맵(PR12 이후).
 * - `banner` — `TopBar`의 `<header>` (인증 분기만, `_shell` 크롬 최상단)
 * - `main` — 이 컴포넌트(`ShellLayout`)가 양 분기 모두 직접 소유(셸 레벨 단일 main). `login.tsx`는
 *   `_shell` 밖(rootRoute 직속)이라 ShellLayout의 main을 물려받지 못하므로 별도로 자체 `<main>`을 렌더한다.
 * - `complementary` — `Sidebar`의 `<aside>` (인증 분기만)
 * `RootLayout`(`__root.tsx`)은 더 이상 `<main>`을 렌더하지 않는다 — 렌더하면 header/aside가 그
 * `<main>` 안에 중첩돼 banner/complementary landmark role이 오염된다. C3의 목적은 이 크롬 landmark
 * 무결성 회복이다.
 *
 * ⚠️ **범위 한정(정직한 계약)**: 이 셸이 보장하는 것은 "크롬(header/aside)이 main 밖" + "셸 레벨 main
 * 1개"까지다. 일부 페이지(`issues.$key.tsx`·`admin.workflow-schemes.tsx`·`admin.workflow-schemes.$schemeKey.tsx`)는
 * 자체 `<main>`을 가져 이 main 안에 **중첩**된다(문서당 main 1개 위반, WCAG 1.3.1). 이 중첩은 PR12
 * **이전에도** `RootLayout`의 main 아래 동일하게 존재하던 PRE_EXISTING 조건이며 PR12는 외곽 main을
 * 이동만 했다(중첩 수 불변, 신규 유발/악화 아님). 페이지 레벨 `<main>`을 `<section>`/`<div>`로 강등하는
 * 정리는 별도 후속 작업이다 — 그러므로 이 컴포넌트/`ShellLayout.test`·`landmark.spec`은 자체 main이
 * 없는 clean 페이지(`/dashboards` 등) 기준으로만 "main 1개"를 단언한다.
 *
 * ★ 도움말 버튼(TopBar `onHelpClick`)은 전달하지 않는다 — `ShortcutsHelpDialog`와 그 열림
 * 상태는 여전히 `RootLayout`이 소유하며(FR6, G1), `ShellLayout`은 `Outlet`을 통해 렌더되는
 * 자손이라 그 상태에 prop으로 접근할 수 없다. `TopBar`는 `onHelpClick` 미전달 시 도움말
 * 버튼을 렌더하지 않으므로(동작 안 하는 버튼 방지) 이 이슈는 시각적 버튼 없이 `?` 단축키로만
 * 도움말에 접근하는 형태로 PR11에서 이연된다.
 */
export function ShellLayout(): JSX.Element {
  const isAuthenticated = useIsAuthenticated()
  // ★selector 로 액션만 뽑는다. 구조분해(`const { toggle } = useSidebarCollapsed()`)는
  // 스토어 전체를 구독해 `collapsed` 가 바뀔 때마다 이 컴포넌트가 재렌더되고, 여기엔
  // `<Outlet/>` 이 있어 라우트 본문까지 재렌더 경로에 오른다. 사이드바 토글은 `[` 키와
  // 버튼 양쪽에서 나므로 빈도도 낮지 않다. `toggle` 은 스토어 생성 시 한 번 만들어진
  // 안정 참조라 이 구독은 재렌더를 유발하지 않는다(`useAuthStore((s) => s.clearSession)` 선례).
  // 다른 소비처(TopBar·Sidebar·ProjectTree·RecentIssuesMenu)가 구조분해를 쓰는 것은
  // 그쪽이 `collapsed` 값을 실제로 그려서다 — 재렌더가 목적이라 정당하다.
  const toggleSidebar = useSidebarCollapsed((state) => state.toggle)

  // FR-UX-07 — `/projects/$projectKey/*` 를 볼 때 그 키를 활성 프로젝트로 기록한다.
  // 전 인증 라우트가 공유하는 유일한 지점이라 여기 둔다(라우트마다 배선하면 빠뜨린다).
  // 조기 반환 앞에서 호출해야 훅 규칙을 지킨다 — 미인증이면 인자로 끈다.
  useTrackActiveProject(isAuthenticated)

  // FR-UX-10 F10 — `app-shell` 컨텍스트(`[` 사이드바 토글). 이 컴포넌트가 곧
  // "셸이 렌더된 화면"의 정의라 여기가 등록 지점이다. 발화는 전역 단일 리스너
  // (`useKeyboardShortcuts`)가 하고, 여기서는 핸들러만 등록한다(ADR D-2).
  //
  // 훅 규칙상 조기 반환 앞에서 호출하되 `isAuthenticated` 를 그대로 넘긴다 —
  // 미인증이면 셸(사이드바)이 렌더되지 않으므로 등록도 하지 않는 것이 맞다
  // (`useTrackActiveProject(isAuthenticated)` 와 같은 형태).
  useContextShortcuts('app-shell', { onToggleSidebar: toggleSidebar }, isAuthenticated)

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
