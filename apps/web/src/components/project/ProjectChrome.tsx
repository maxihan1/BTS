// 프로젝트 크롬 컴포넌트 2종 — 소유권 Provider + 헤더 액션 포털 (Jira 패리티 J5-9 · J5-11)
import { useMemo, type JSX, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import {
  ProjectChromeContext,
  useProjectChrome,
  type ProjectChromeValue,
} from '@/components/project/project-chrome-context'

/** {@link ProjectChromeProvider} Props */
export interface ProjectChromeProviderProps {
  readonly present: boolean
  readonly actionHost: HTMLElement | null
  readonly children: ReactNode
}

/**
 * 프로젝트 크롬의 소유권을 하위 트리에 알린다. `ProjectViewChrome` 하나만 이것을 세운다.
 *
 * 🛑 값 객체를 `useMemo` 로 고정한다 — 인라인 리터럴이면 셸이 리렌더될 때마다 새 참조가 되어
 *    프로젝트 하위 전 화면이 함께 리렌더된다(보드는 컬럼 수십 개짜리 트리다).
 */
export function ProjectChromeProvider({
  present,
  actionHost,
  children,
}: ProjectChromeProviderProps): JSX.Element {
  const value = useMemo<ProjectChromeValue>(() => ({ present, actionHost }), [present, actionHost])
  return <ProjectChromeContext.Provider value={value}>{children}</ProjectChromeContext.Provider>
}

/** {@link ProjectHeaderActions} Props */
export interface ProjectHeaderActionsProps {
  readonly children: ReactNode
}

/**
 * 뷰의 액션 버튼을 프로젝트 헤더 제목행 우측으로 옮긴다 (J5-9).
 *
 * ### 왜 셸이 버튼을 다시 만들지 않는가
 * 보드의 「이슈 추가」는 `boardKeys.detail(currentBoardId)` 무효화를 알고, 이슈 목록의
 * 「새 이슈」는 CREATE 권한 fail-closed 판정을 안다. 셸로 올려 다시 구현하면 셸이 전 화면의
 * 쿼리 키와 권한 규칙을 갖게 된다. **소유는 뷰에 두고 자리만 빌려준다.**
 *
 * ### 크롬이 없으면 제자리에 그린다
 * 🛑 이 폴백이 없으면 전역 `/issues`·`/dashboards` 에서 진입점이 통째로 사라진다 — 두 화면은
 *    프로젝트 스코프 라우트와 컴포넌트를 공유하기 때문이다.
 *
 * 크롬은 있는데 호스트가 아직 `null` 인 첫 프레임에는 **아무것도 그리지 않는다.** 제자리에
 * 그렸다가 다음 렌더에 포털로 옮기면 버튼이 깜빡이며 본문을 한 번 밀어낸다.
 */
export function ProjectHeaderActions({ children }: ProjectHeaderActionsProps): ReactNode {
  const { present, actionHost } = useProjectChrome()

  if (!present) return children
  if (actionHost === null) return null
  return createPortal(children, actionHost)
}
