// 셸 삽입점 — 경로에 projectKey 가 있을 때만 프로젝트 헤더+탭바를 마운트한다 (Jira 패리티 J5)
import { useState, type JSX, type ReactNode } from 'react'
import { useParams, useRouterState, useSearch } from '@tanstack/react-router'
import { ProjectNavTabs } from '@/components/project/ProjectNavTabs'
import { ProjectViewHeader } from '@/components/project/ProjectViewHeader'
import { ProjectChromeProvider } from '@/components/project/ProjectChrome'
import {
  resolveBacklogTabSearch,
  resolveBoardTabSearch,
} from '@/components/project/project-view-tabs'
import { resolveProjectShellMode } from '@/components/project/project-shell-mode'
import { useBoards } from '@/hooks/use-boards'

/** {@link ProjectViewChrome} Props */
export interface ProjectViewChromeProps {
  /** 크롬 아래에 놓일 페이지 본문. 셸이 `<Outlet/>` 을 넘긴다 */
  readonly children: ReactNode
}

/**
 * 프로젝트 화면 공통 크롬 — **제목 헤더 + 탭바** 2층이다 (Jira 패리티 J5-8·J5-10).
 *
 * ### 왜 페이지가 아니라 셸이 소유하는가
 * 두 층을 각 페이지 컴포넌트에 넣으면 프로젝트 하위 화면 **20여 개**에 같은 줄을 심어야 하고,
 * 하나 빠뜨리면 그 화면만 정체성이 없다(탭바가 정확히 그 상태였다 — 보드·백로그 둘만 있었다).
 * 삽입점을 `ShellLayout` 의 `<main>` 안 한 곳으로 두면 라우트 id 를 건드리지 않고 전 화면이
 * 같은 헤더와 탭을 갖는다.
 *
 * 🛑 **크롬을 sticky 로 만들지 않는다.** `<main>` 이 독립 스크롤 컨테이너라(NFR2) sticky 를
 *    걸면 백로그·보드의 세로 스크롤 계산이 어긋난다.
 *
 * ### 본문을 자식으로 받는 이유
 * `ProjectChromeProvider` 는 **본문까지** 감싸야 한다. 페이지가 그 컨텍스트를 읽어
 * ① 액션 버튼을 헤더로 포털하고(J5-9) ② 셸이 h1 을 가졌으니 자기 제목을 접는다(J5-11).
 * 크롬만 감싸면 두 신호가 페이지에 닿지 않는다.
 *
 * ### 마운트 조건
 * `useParams({ strict: false }).projectKey` 가 있을 때만 헤더·탭바를 그린다. `/projects/new` 는
 * 정적 세그먼트라 `$projectKey` 에 안 걸리고, 전역 `/issues`·`/calendar`·`/dashboards` 에는
 * 그 param 이 없다 — 그 셋은 크롬 없이 본문만 나온다(사이드바에서 들어온 전역 화면이다).
 * **프로젝트 스코프 형제 라우트**(`/projects/$key/issues` 등)가 따로 있고 탭은 그쪽을 가리킨다.
 *
 * ### 설정 서브앱에서는 탭바를 렌더하지 않는다 (편차 X-N1)
 * 설정 화면에도 요약·타임라인·보드…가 떠 있으면 「나는 지금 어느 서브앱에 있나」가 흐려진다.
 * 판정은 **`resolveProjectShellMode` 하나**가 한다 — 사이드바(`Sidebar`)와 이 컴포넌트가 같은
 * 함수를 부르고, 그 함수는 `PROJECT_SETTINGS_NAV` 목록에서 판정을 «유도»한다(완료기준 A-4).
 *
 * 🛑 **여기에 경로 판정식을 다시 쓰지 않는다.** `pathname.includes('/settings/')` 같은 식을
 *    이 파일에 두면 `컴포넌트`·`버전` 탭(편차 X-N2 · 경로가 `/settings/...` 인데 정본 탭이다)을
 *    위해 예외 분기가 붙고, 그 분기는 설정 메뉴 목록과 서로를 검사하지 않는 두 번째 목록이 된다.
 *
 * 🛑 **`ProjectViewHeader` 는 남긴다.** 제목까지 사라지면 설정 화면에 프로젝트 정체성이 0이 된다.
 *    대신 헤더가 `shellMode` 를 받아 「프로젝트 설정」 부제를 낸다(★리뷰 D-3).
 *
 * ### 편차 X7 승계 — 보드 스코프를 백로그 탭으로 실어 나른다
 * `?board=` 가 URL 에 **있을 때만** 보드 목록을 묻는다. 그 파라미터는 보드·백로그 화면에서만
 * 생기고 두 화면은 이미 같은 목록을 캐시에 갖고 있어 추가 요청이 사실상 없다. 없을 때
 * 빈 키를 넘기면 `useBoards` 의 `enabled: projectKey.length > 0` 가 쿼리를 끈다 —
 * 훅을 조건부로 부르지 않으면서 요청만 끄는 이 저장소의 관례다.
 */
export function ProjectViewChrome({ children }: ProjectViewChromeProps): JSX.Element {
  const { projectKey } = useParams({ strict: false }) as { projectKey?: string }
  const { board: boardScopeId } = useSearch({ strict: false }) as { board?: string }
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const [actionHost, setActionHost] = useState<HTMLElement | null>(null)

  // 훅은 조기 반환보다 앞에서 전부 부른다(훅 규칙). 요청 여부는 인자로 끈다.
  const scopeLookupKey = boardScopeId === undefined ? '' : (projectKey ?? '')
  const { data: boards } = useBoards(scopeLookupKey)

  const present = projectKey !== undefined && projectKey !== ''
  const shellMode = resolveProjectShellMode(pathname, projectKey)

  // 두 탭의 규칙이 다르다 — 보드는 종류를 안 가리고, 백로그는 스크럼일 때만 받는다.
  const boardScope = {
    board: resolveBoardTabSearch(boardScopeId),
    backlog: resolveBacklogTabSearch(boards, boardScopeId),
  }

  return (
    <ProjectChromeProvider present={present} actionHost={actionHost}>
      {present && projectKey !== undefined && (
        <>
          <ProjectViewHeader
            projectKey={projectKey}
            onActionHost={setActionHost}
            shellMode={shellMode}
          />
          {shellMode !== 'settings' && (
            <ProjectNavTabs projectKey={projectKey} pathname={pathname} boardScope={boardScope} />
          )}
        </>
      )}
      {children}
    </ProjectChromeProvider>
  )
}
