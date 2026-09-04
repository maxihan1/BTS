// 셸 삽입점 — 경로에 projectKey 가 있을 때만 프로젝트 뷰 탭바를 마운트한다 (Jira 패리티 J5)
import type { JSX } from 'react'
import { useParams, useRouterState, useSearch } from '@tanstack/react-router'
import { ProjectNavTabs } from '@/components/project/ProjectNavTabs'
import {
  resolveBacklogTabSearch,
  resolveBoardTabSearch,
} from '@/components/project/project-view-tabs'
import { useBoards } from '@/hooks/use-boards'

/**
 * 프로젝트 화면 공통 크롬 — 지금은 탭바 하나다.
 *
 * ### 왜 페이지가 아니라 셸이 소유하는가
 * 탭바를 각 페이지 컴포넌트에 넣으면 프로젝트 하위 화면 **20여 개**에 같은 줄을 심어야 하고,
 * 하나 빠뜨리면 그 화면만 탭이 없다(지금까지가 정확히 그 상태였다 — 보드·백로그 둘만 있었다).
 * 삽입점을 `ShellLayout` 의 `<main>` 안 한 곳으로 두면 라우트 id 를 건드리지 않고 전 화면이
 * 같은 탭을 갖는다.
 *
 * 🛑 **탭바를 sticky 로 만들지 않는다.** `<main>` 이 독립 스크롤 컨테이너라(NFR2) sticky 를
 *    걸면 백로그·보드의 세로 스크롤 계산이 어긋난다.
 *
 * ### 마운트 조건
 * `useParams({ strict: false }).projectKey` 가 있을 때만 렌더한다. `/projects/new` 는 정적
 * 세그먼트라 `$projectKey` 에 안 걸리고, `/issues`·`/calendar`·`/dashboards` 에는 애초에
 * 그 param 이 없다 — 그래서 편차 X9 의 세 탭을 누르면 탭바가 사라진다(의도된 결과).
 *
 * ### 편차 X7 승계 — 보드 스코프를 백로그 탭으로 실어 나른다
 * `?board=` 가 URL 에 **있을 때만** 보드 목록을 묻는다. 그 파라미터는 보드·백로그 화면에서만
 * 생기고 두 화면은 이미 같은 목록을 캐시에 갖고 있어 추가 요청이 사실상 없다. 없을 때
 * 빈 키를 넘기면 `useBoards` 의 `enabled: projectKey.length > 0` 가 쿼리를 끈다 —
 * 훅을 조건부로 부르지 않으면서 요청만 끄는 이 저장소의 관례다.
 */
export function ProjectViewChrome(): JSX.Element | null {
  const { projectKey } = useParams({ strict: false }) as { projectKey?: string }
  const { board: boardScopeId } = useSearch({ strict: false }) as { board?: string }
  const pathname = useRouterState({ select: (state) => state.location.pathname })

  // 훅은 조기 반환보다 앞에서 전부 부른다(훅 규칙). 요청 여부는 인자로 끈다.
  const scopeLookupKey = boardScopeId === undefined ? '' : (projectKey ?? '')
  const { data: boards } = useBoards(scopeLookupKey)

  if (projectKey === undefined || projectKey === '') return null

  // 두 탭의 규칙이 다르다 — 보드는 종류를 안 가리고, 백로그는 스크럼일 때만 받는다.
  const boardScope = {
    board: resolveBoardTabSearch(boardScopeId),
    backlog: resolveBacklogTabSearch(boards, boardScopeId),
  }
  return <ProjectNavTabs projectKey={projectKey} pathname={pathname} boardScope={boardScope} />
}
