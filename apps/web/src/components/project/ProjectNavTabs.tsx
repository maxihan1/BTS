// 프로젝트 뷰 전환 탭바 — 정본 9탭 + 폭 부족 시 「더 보기」 오버플로 (Jira 패리티 J5)
import { useState, type JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { navLabels } from '@/i18n/nav-labels'
import { projectViewLabels } from '@/i18n/project-view-labels'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useTabOverflow } from '@/hooks/use-tab-overflow'
import {
  PROJECT_VIEW_TABS,
  resolveActiveTabIndex,
  type ProjectViewTab,
} from '@/components/project/project-view-tabs'

/** 탭 링크 공통 스타일 — 활성 표시는 TanStack 이 붙이는 `.active` 클래스에 건다(사이드바 관례) */
const TAB_LINK_CLASS =
  'inline-flex whitespace-nowrap rounded-md px-3 py-1.5 text-sm text-muted-foreground ' +
  'hover:bg-accent hover:text-accent-foreground ' +
  '[&.active]:font-semibold [&.active]:text-foreground'

/** 「더 보기」 트리거 스타일 — 탭과 같은 높이로 맞춘다 */
const MORE_TRIGGER_CLASS =
  'inline-flex shrink-0 whitespace-nowrap rounded-md px-3 py-1.5 text-sm text-muted-foreground ' +
  'hover:bg-accent hover:text-accent-foreground'

/** {@link ProjectNavTabs} Props */
export interface ProjectNavTabsProps {
  /** 현재 프로젝트 키 — `$projectKey` param 을 받는 탭에 공통 적용 */
  readonly projectKey: string
  /**
   * 현재 URL 의 pathname. **오버플로 핀 고정 전용**이다.
   *
   * 시각·ARIA 활성 표시는 `Link` 가 라우터에게 직접 묻는다 — 이 값으로 그리지 않는다.
   * 두 층을 나눠 둔 이유와 그 둘이 어긋나지 않는다는 보증은 `project-view-tabs.ts` 의
   * `resolveActiveTabIndex` JSDoc 과 그 짝 판별식에 있다.
   */
  readonly pathname: string
  /**
   * 보드 스코프 `?board=` 를 탭별로 실은 것 (편차 X7 · #432 승계).
   *
   * **탭마다 규칙이 다르다** — 보드 탭은 종류를 가리지 않고, 백로그 탭은 스크럼일 때만 받는다.
   * 정본 판정은 `resolveBoardTabSearch`·`resolveBacklogTabSearch` 가 하고 이 컴포넌트는
   * 싣기만 한다. 비어 있으면 쿼리 없이 이동한다.
   */
  readonly boardScope?: {
    readonly board?: Readonly<Record<string, string>>
    readonly backlog?: Readonly<Record<string, string>>
  }
}

/**
 * 탭 하나가 실을 URL search. 없으면 `undefined`.
 *
 * 보드·백로그 탭만 보드 스코프를 승계한다(편차 X7). 나머지 7탭은 경로에 프로젝트가 들어 있어
 * search 가 필요 없다 — 이슈 탭이 `?projectKey=` 를 싣던 것은 편차 X9 시절의 일이고,
 * 지금은 `/projects/$projectKey/issues` 가 params 로 받는다.
 */
function tabSearch(
  tab: ProjectViewTab,
  boardScope: ProjectNavTabsProps['boardScope'],
): Readonly<Record<string, string>> | undefined {
  if (tab.key === 'board') return boardScope?.board
  if (tab.key === 'backlog') return boardScope?.backlog
  return undefined
}

/** 탭 링크 1개 — 가시 목록과 오버플로 목록이 같은 것을 쓴다(두 벌로 갈리지 않게) */
function ProjectViewTabLink(props: {
  readonly tab: ProjectViewTab
  readonly projectKey: string
  readonly boardScope: ProjectNavTabsProps['boardScope']
}): JSX.Element {
  const { tab, projectKey, boardScope } = props
  return (
    <Link
      to={tab.to}
      {...(tab.usesProjectParam ? { params: { projectKey } } : {})}
      search={tabSearch(tab, boardScope)}
      activeOptions={{ exact: tab.exact }}
      activeProps={{ 'aria-current': 'page' }}
      className={TAB_LINK_CLASS}
    >
      {tab.label}
    </Link>
  )
}

/**
 * 프로젝트 안의 모든 화면이 공유하는 수평 탭바.
 *
 * 🔴 **Radix Tabs 금지** — `role="navigation"`(nav+Link)이 e2e 5건 + 유닛 5건의 계약이다.
 * 뷰 전환은 실제 라우트 이동(URL 변경, 뒤로가기 정상 동작)이므로 같은 라우트 안에서 패널만
 * 바뀌는 탭 위젯이 아니라 네비게이션이 정답이다
 * (`frontend-nav-aria-label-e2e-contract` 학습 노트).
 *
 * 🔴 오버플로 목록에 `role="menu"`/`menuitem` 을 주지 않는다 — 라우트 이동이라 링크가
 * 정답이고, menu role 은 `menuitem` 자식을 요구해 Link 시맨틱을 깬다.
 *
 * `aria-label` 은 `navLabels.projectViewNav`(🔒 e2e 계약 문자열 "프로젝트 뷰 전환")를 그대로
 * 쓴다 — 글자를 바꾸면 안 된다.
 *
 * ### 링크 집합을 이제 **통합한다**
 * 한때 board·backlog 가 각자 다른 집합(2링크·5링크)을 prop 으로 넘겼다. Jira 는 스페이스의
 * 모든 화면이 같은 탭을 공유하므로(J5) 정본 하나로 모으고 `links` prop 을 없앴다.
 *
 * ### Portal 을 nav 안으로 가둔다
 * 오버플로 팝오버는 기본으로 `document.body` 에 그려진다. 그러면 링크가 `<nav>` **밖**에 있게
 * 되어 `within(nav).getAllByRole('link')` 계열 계약이 접힌 탭을 못 본다. `PopoverContent` 의
 * `container` prop(다이얼로그용으로 이미 있던 장치)에 nav 안쪽 노드를 준다.
 *
 * 🛑 그 노드는 `useState` **콜백 ref** 로 잡는다 — 첫 렌더에 `useRef` 는 `null` 이라 그 시점의
 *    Portal 이 body 로 새고, ref 가 채워져도 재렌더가 없어 영영 그대로다.
 */
export function ProjectNavTabs({
  projectKey,
  pathname,
  boardScope,
}: ProjectNavTabsProps): JSX.Element {
  const [portalHost, setPortalHost] = useState<HTMLElement | null>(null)
  const activeIndex = resolveActiveTabIndex(pathname, projectKey)
  const { setContainer, setItem, setMore, visibleIndexes, hiddenIndexes, shouldRenderMore } =
    useTabOverflow(PROJECT_VIEW_TABS.length, activeIndex)

  return (
    <nav
      aria-label={navLabels.projectViewNav}
      className="relative flex items-center border-b border-border px-6"
    >
      {/*
        🛑 측정 대상은 **탭과 트리거를 함께 담는 이 래퍼**다. `<ul>` 에 걸면 안 된다.

        `<ul>` 을 `flex-1` 로 두고 트리거를 형제로 놓으면 트리거가 렌더되는 순간 `<ul>` 이
        그만큼 좁아진다. 그런데 `computeVisibleTabIndexes` 는 예산에서 트리거 폭을 **또** 뺀다
        — 같은 폭을 두 번 빼는 것이다. 결과는 두 가지였다(실측).
        ① **히스테리시스** — 880px 에서 9탭이 보이던 화면을 860px 로 좁혔다 880px 로 되돌리면
           6탭에 고정된다. 「트리거가 있는가」가 폭을 정하고 그 폭이 다시 트리거 유무를 정하는
           되먹임 고리다. 창을 줄였다 되돌리는 평범한 조작으로 전 화면 탭바가 틀어졌다.
        ② **한 칸을 헛되이 접는다** — 오른쪽이 비어 있는데도 마지막 탭이 팝오버로 들어갔다.

        이 래퍼는 nav 의 유일한 신축 자식이라 폭이 트리거 유무와 **무관**하다. 그래야
        순수 함수의 전제(「containerWidth 는 트리거 자리를 포함한 전체 바 폭」)가 성립한다.
        짝 판별식 = `e2e/project-tabs-overflow.spec.ts` 의 「좁혔다 되돌리면 전량 복귀」.
      */}
      <div
        ref={setContainer}
        className="flex min-w-0 flex-1 items-center gap-1 overflow-hidden"
      >
        <ul className="flex min-w-0 items-center gap-1">
          {visibleIndexes.map((index) => {
            const tab = PROJECT_VIEW_TABS[index]
            if (tab === undefined) return null
            return (
              <li key={tab.key} ref={setItem(index)} className="shrink-0">
                <ProjectViewTabLink tab={tab} projectKey={projectKey} boardScope={boardScope} />
              </li>
            )
          })}
        </ul>

        {shouldRenderMore && (
          <Popover>
            <PopoverTrigger ref={setMore} className={MORE_TRIGGER_CLASS}>
              {projectViewLabels.overflowTrigger}
            </PopoverTrigger>
            <PopoverContent container={portalHost} align="end" className="w-48 p-2">
              <ul className="flex flex-col gap-1">
                {hiddenIndexes.map((index) => {
                  const tab = PROJECT_VIEW_TABS[index]
                  if (tab === undefined) return null
                  return (
                    <li key={tab.key}>
                      <ProjectViewTabLink
                        tab={tab}
                        projectKey={projectKey}
                        boardScope={boardScope}
                      />
                    </li>
                  )
                })}
              </ul>
            </PopoverContent>
          </Popover>
        )}
      </div>

      {/* nav 안의 Portal 목적지 — 접힌 탭 링크가 nav 밖으로 새지 않게 한다.
          🛑 측정 래퍼 **밖**이다. 안에 두면 `overflow-hidden` 이 팝오버를 잘라 낸다. */}
      <div ref={setPortalHost} />
    </nav>
  )
}
