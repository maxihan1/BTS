// 전역 좌측 사이드바 — 메인 nav(이슈·대시보드·캘린더·즐겨찾기·최근 항목) + 그 아래 스페이스 트리(J2, 설정 서브앱에서는 설정 메뉴로 교체). 관리 nav 는 J9 로 상단바 허브로 이관
import { type JSX } from 'react'
import { Link, useParams, useRouterState } from '@tanstack/react-router'
import { CircleDot, LayoutDashboard, Calendar, UserCheck, type LucideIcon } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { useAuthUser } from '@/auth/authStore'
import {
  useSidebarDrawer,
  useSidebarRailCollapsed,
  useSidebarToggle,
  useSidebarShown,
  useSidebarEffectiveWidth,
} from '@/hooks/use-sidebar-drawer'
import { FavoritesMenu } from '@/components/favorite/FavoritesMenu'
import { RecentIssuesMenu } from '@/components/issue/RecentIssuesMenu'
import { navLabels } from '@/i18n/nav-labels'
import { ProjectSettingsNav } from '@/components/project/ProjectSettingsNav'
import { resolveProjectShellMode } from '@/components/project/project-shell-mode'
import { ProjectTree } from './ProjectTree'
import { SidebarResizeHandle } from './SidebarResizeHandle'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 랜드마크 `id` — 리사이즈 핸들이 `aria-controls` 로 가리킨다.
 *
 * 스크린리더에서 splitter 를 만났을 때 「무엇의 크기를 바꾸는가」가 이어져야 조작할 마음이 든다.
 */
const SIDEBAR_ID = 'app-sidebar'

/**
 * 모바일 드로어 폭 — 264px 고정(디자인 스펙 §3.1).
 *
 * 🛑 데스크톱 폭과 달리 **저장값을 쓰지 않는다.** 드로어는 화면을 덮는 오버레이라
 *    480px 로 맞춰 둔 사용자에게는 화면이 거의 다 가려진다.
 *    판정은 `useSidebarEffectiveWidth` 가 `undefined` 를 돌려주는 것으로 하고,
 *    실제 폭은 이 클래스가 준다.
 */
const MOBILE_DRAWER_WIDTH_CLASS = 'max-md:w-[264px]'

/**
 * nav 링크 공통 스타일. `[&.active]` — 활성 라우트 시각 강조(Header.tsx 관례 계승).
 * `gap-2` — 아이콘·텍스트 간격. 접힘(64px) 시 텍스트는 `sr-only`로 시각적으로만 숨기므로
 * (DOM에서 제거하지 않음) `getByRole('link', { name })` 접근가능 이름 계약(FR5)이 유지된다.
 */
const NAV_LINK_CLASS =
  'flex items-center gap-2 rounded-md px-2 py-1.5 text-sm font-medium ' +
  'text-sidebar-foreground/80 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground ' +
  '[&.active]:bg-sidebar-accent [&.active]:text-sidebar-accent-foreground [&.active]:font-semibold'

/** nav 아이콘 공통 스타일 — 장식용이므로 `aria-hidden`으로 스크린리더에서 제외한다 */
const NAV_ICON_CLASS = 'size-4 shrink-0'

/** 메인 nav 실 라우트 링크 — FR3, 백킹 없는 항목(내 작업·최근·필터) 제외(S3) */
const MAIN_NAV_LINKS: ReadonlyArray<{ to: string; label: string; Icon: LucideIcon }> = [
  { to: '/issues', label: navLabels.issues, Icon: CircleDot },
  { to: '/dashboards', label: navLabels.dashboards, Icon: LayoutDashboard },
  { to: '/calendar', label: navLabels.calendar, Icon: Calendar },
]

/**
 * 경로별 활성 판정 옵션 (FR-UX-08 PR-B, 스펙 E8 — 2026-07-31 Maxi 확정 A안).
 *
 * `/issues` 만 **정확히 일치**할 때 활성이다. 기본 판정(`exact: false`)은 경로 접두사만 보므로
 * `/issues?assignee=<userId>`("내 작업")에서 **"이슈"까지 함께 강조**된다 — 링크의 `search`가
 * 비어 있으면 언제나 현재 search 의 부분집합이라 `includeSearch` 로는 배제할 수 없다.
 *
 * **대가 (의도된 것).** 이슈 상세(`/issues/ATLAS-1`)에서 사이드바 "이슈"가 더 이상 강조되지
 * 않는다. 그 화면의 위치 안내는 상단 탐색 경로(`Breadcrumb`)가 맡는다. 이 상태에 의존하는
 * 기존 단언은 실측 결과 **0건**이었다.
 */
const ISSUES_ACTIVE_OPTIONS: Record<string, { exact: boolean } | undefined> = {
  '/issues': { exact: true },
}

/*
 * 🛑 관리 nav 는 여기 없다 — Jira 패리티 J9 로 **상단바 관리 허브 링크**(`TopBar.tsx`)로 옮겼다.
 *    Jira Cloud 는 전역 관리 항목을 사이드바가 아니라 상단 유틸리티 뒤에 둔다.
 *    링크 목록의 정본은 `routes/admin.index.tsx` 의 `ADMIN_HUB_LINKS` 하나이고,
 *    한때 여기와 그 파일 둘이 같은 7링크를 각자 들고 있었다(중복 소멸).
 */

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 좌측 사이드바 — 264px 고정, 독립 스크롤(`overflow-y-auto`).
 *
 * 렌더 순서는 **Jira 새 네비게이션**을 따른다 (캠페인 PR ⑩ · J2) — `메인 메뉴` nav
 * (내 작업·이슈·대시보드·캘린더)가 먼저고 {@link ProjectTree}(스페이스 목록)가 그 **아래**다.
 * FR-UX-06 PR12 Task 3 이 세운 반대 순서(트리가 위)를 뒤집은 것이다. 트리는 프로젝트 수만큼
 * 길어져 위에 두면 매일 여는 전역 링크가 스크롤 밖으로 밀린다.
 *
 * 🛑 **`관리 메뉴` nav 는 여기 없다.** Jira 패리티 J9 로 상단바 관리 허브 링크(`TopBar.tsx`)로
 * 옮겼고, 링크 목록의 정본은 `routes/admin.index.tsx` 의 `ADMIN_HUB_LINKS` 하나다.
 * 사이드바에 다시 넣지 마라 — 같은 7링크를 두 곳이 들고 있던 상태로 되돌아간다.
 *
 * ### 설정 서브앱에서는 트리 자리만 바뀐다 (JS-2 · ★결정)
 * `resolveProjectShellMode(pathname, projectKey) === 'settings'` 이면 {@link ProjectTree} 대신
 * `ProjectSettingsNav` 가 선다. **`메인 메뉴` nav 는 그대로 남긴다** — Maxi 결정 「사이드바 전체
 * 교체」가 가리키는 것은 «프로젝트 트리» 자리이고, 전역 링크까지 지우면 이 저장소에서는
 * 이슈·대시보드·캘린더가 **1클릭으로 닿지 않게 된다**. Jira 는 전역 항목을 상단바가 함께
 * 이고 있어 설정에서 사이드바를 통째로 갈아도 도달성이 유지되지만, BTS `TopBar` 는
 * 프로젝트 스위처·검색·만들기·관리 허브·계정뿐이라 그 대체 경로가 없다. 도달성을 줄이지
 * 않는 쪽을 골랐고, 이것은 Task 4 도달성 판별식이 지키는 성질과 같은 종류다.
 *
 * `메인 메뉴` nav는 항상 렌더된다. 접힘 상태는 {@link useSidebarRailCollapsed}로 소비하며,
 * 각 nav 링크는 lucide 아이콘(`aria-hidden`) + 텍스트 라벨로 구성된다 — 펼침 시 아이콘·텍스트
 * 둘 다 보이고, 접힘(64px) 시 텍스트는 `sr-only`로 시각적으로만 숨겨 진짜 아이콘 레일이 되며
 * (잘린 텍스트 노출 방지), DOM에는 남아 있어 `getByRole('link', { name })` 계약(e2e)이
 * 유지된다(FR5). {@link ProjectTree}도 접힘 시 동일한 아이콘 레일 원칙을 따른다(자체 FR6).
 */
export function Sidebar(): JSX.Element {
  const user = useAuthUser()
  // ── 셸 모드 — 트리인가 설정 서브앱인가 (JS-2) ────────────────────────────────────
  // 🛑 **판정식을 여기 다시 쓰지 마라.** `pathname.includes('/settings/')` 같은 사본을 두면
  //    `컴포넌트`·`버전`(편차 X-N2) 예외를 위해 분기가 하나 더 붙고, 그 분기는
  //    `PROJECT_SETTINGS_NAV` 와 서로를 검사하지 않는 **두 번째 목록**이 된다.
  //    사이드바와 탭바(`ProjectViewChrome`)가 `resolveProjectShellMode` **한 함수**를 공유하므로
  //    그 함수를 뒤집으면 두 쪽 테스트가 함께 red 다 — 완료기준 A-4 가 그것을 단언한다.
  const { projectKey } = useParams({ strict: false })
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const shellMode = resolveProjectShellMode(pathname, projectKey)
  const drawerOpen = useSidebarDrawer((state) => state.open)
  const toggle = useSidebarToggle()
  const sidebarShown = useSidebarShown()

  // ── 드로어 포커스: 어디서 시작해 어디로 돌아가는지를 정의한다 (F24 · D7) ─────────────
  // 🛑 백드롭이 본문을 **시각적으로** 덮는 순간 이 UI 는 모달로 읽힌다. 포인터 사용자에게는
  //    모달인데 키보드 사용자에게는 아니면, 탭이 가려진 콘텐츠로 들어가 **보이지 않는 채로
  //    포커스만 이동**한다 — 아래 `max-md:invisible` 이 닫힘 상태에서 막는 것과 같은 형태의
  //    결함이다. 한쪽만 막으면 반쪽이다.
  //    여기서 하는 것은 포커스의 **시작과 끝**을 정하는 것까지다. 완전한 포커스 트랩
  //    (`aria-modal` + 뒤 콘텐츠 `inert`)은 셸 전역 동작을 바꾸므로 별건으로 뺐다(TODOS).
  const asideRef = useRef<HTMLElement>(null)
  const returnFocusRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (drawerOpen) {
      // 연 주체(상단바 토글 버튼 등)를 기억해 뒀다가 닫을 때 그리로 돌려준다.
      returnFocusRef.current = document.activeElement as HTMLElement | null
      asideRef.current?.focus()
      return
    }
    // 닫힘 — 기억해 둔 곳으로 돌려준다. 그 요소가 사라졌으면(라우트 이동 등) 아무것도 하지
    // 않는다. 억지로 body 로 옮기면 포커스가 문서 맨 앞으로 튀어 더 나빠진다.
    const target = returnFocusRef.current
    returnFocusRef.current = null
    if (target !== null && document.contains(target)) target.focus()
  }, [drawerOpen])
  // 🛑 모바일에서는 collapsed 를 쓰지 않는다 — 접힘은 **데스크톱 아이콘 레일**의 폭이고,
  //    드로어는 열리면 264px 전체가 떠야 한다. 둘을 겹치면 드로어가 64px 레일로 열리고
  //    라벨까지 `sr-only` 로 숨어 아이콘만 남은 드로어가 된다. 아래 `railCollapsed` 가 정본이다.
  const railCollapsed = useSidebarRailCollapsed()
  // 데스크톱 폭은 인라인 style 이다 — 드래그로 임의 px 이 되므로 Tailwind 클래스로 표현할 수 없다.
  // 모바일에서는 `undefined` 라 위 MOBILE_DRAWER_WIDTH_CLASS 가 그대로 지배한다.
  const effectiveWidth = useSidebarEffectiveWidth()
  // `userId` 부재(PAT 인증 등)면 "내 작업"을 렌더하지 않는다 — 죽은 링크를 만들지 않는다(E9).
  // `useAuthUser()`는 zustand persist 스토어에서 **동기**로 읽으므로(authStore.ts) 비동기
  // 순서 문제가 없다 — 이것이 `projectKey`와 달리 링크에 직접 실을 수 있는 근거다(§1-A).
  const myWorkUserId = user?.userId

  // 모바일 오프캔버스 드로어 — `max-md:` 구간에서만 적용된다. 데스크톱 클래스는 한 자도 바뀌지 않는다.
  // 🛑 닫힘에 `invisible` 을 함께 건다. `-translate-x-full` 만 쓰면 화면 밖으로 밀렸을 뿐
  //    포커스 순서와 스크린리더에는 그대로 남아, 탭을 누르면 보이지 않는 링크로 포커스가 사라진다.
  // 🛑 `inset-y-0` 금지 — 드로어가 상단바까지 덮어 헤더 왼쪽 절반이 잘린 것처럼 보이고,
  //    닫기 토글 버튼 자체가 가려져 백드롭/Esc 말고는 닫을 길이 없어진다.
  //    `top-12` 는 `TopBar` 의 `h-12`(48px)와 짝이다 — 헤더 높이를 바꾸면 여기도 같이 바꾼다.
  const mobileDrawerClass = [
    'max-md:fixed max-md:top-12 max-md:bottom-0 max-md:left-0 max-md:z-50 max-md:shadow-xl',
    'max-md:transition-transform max-md:duration-200 motion-reduce:max-md:transition-none',
    drawerOpen ? 'max-md:translate-x-0' : 'max-md:-translate-x-full max-md:invisible',
  ].join(' ')

  return (
    // 🛑 폭과 모바일 배치를 **wrapper 가** 소유한다. 리사이즈 핸들을 `aside` 안에 절대배치하면
    //    `overflow-y-auto` 의 스크롤 콘텐츠에 딸려 올라가 위로 스크롤한 만큼 사라진다.
    //    스크롤하지 않는 이 wrapper 가 핸들의 기준 상자다.
    <div
      className={`relative shrink-0 ${MOBILE_DRAWER_WIDTH_CLASS} ${mobileDrawerClass}`}
      style={effectiveWidth === undefined ? undefined : { width: effectiveWidth }}
    >
      <aside
        ref={asideRef}
        id={SIDEBAR_ID}
        // 🛑 `tabIndex={-1}` 은 프로그램적 포커스 전용이다 — 탭 순서에 끼어들지 않는다.
        //    드로어가 열릴 때 포커스를 여기로 보내기 위한 최소 장치다.
        tabIndex={-1}
        className="flex h-full w-full flex-col overflow-y-auto border-r border-sidebar-border bg-sidebar text-sidebar-foreground focus:outline-none"
      >
        <nav aria-label={navLabels.mainNav} className="flex flex-col gap-1 p-2">
          {/*
          "내 작업"은 `MAIN_NAV_LINKS` 배열 **밖**에서 렌더한다 — `search`와 조건부 렌더
          (userId 유무)가 필요한데, 배열 타입을 nullable·optional로 넓히면 기존 3항목까지
          복잡해진다(스펙 §8 제약). `<FavoritesMenu />`가 같은 nav 안에서 배열 밖 항목으로
          렌더되는 선례를 따른다.

          **최상단 배치**는 스펙 §8-A D-A — 매일 여는 진입점이라 순서가 곧 중요도 신호다.
          `projectKey`는 싣지 않는다(§1-A) — 사이드바는 `useProjects()` 응답보다 먼저
          렌더되므로 링크가 활성 프로젝트를 계산해 붙이면 한 박자 늦게 바뀌고, 그 사이
          클릭하면 빈 키가 실린다. 프로젝트 스코프는 `/issues` 라우트가 해소한다.

          `activeOptions.includeSearch`는 "이슈"(`/issues`)와 "내 작업"(`/issues?assignee=`)이
          같은 경로라 둘 다 활성 표시되는 것을 막는다(스펙 E8).
        */}
          {myWorkUserId !== undefined && (
            <Link
              to="/issues"
              search={{ assignee: myWorkUserId }}
              activeOptions={{ includeSearch: true }}
              className={NAV_LINK_CLASS}
            >
              <UserCheck aria-hidden="true" className={NAV_ICON_CLASS} />
              <span className={railCollapsed ? 'sr-only' : undefined}>{navLabels.myWork}</span>
            </Link>
          )}

          {MAIN_NAV_LINKS.map(({ to, label, Icon }) => (
            <Link
              key={to}
              to={to}
              activeOptions={ISSUES_ACTIVE_OPTIONS[to]}
              className={NAV_LINK_CLASS}
            >
              <Icon aria-hidden="true" className={NAV_ICON_CLASS} />
              <span className={railCollapsed ? 'sr-only' : undefined}>{label}</span>
            </Link>
          ))}
          <FavoritesMenu />

          {/*
          "최근 항목"은 메인 메뉴 nav **최하단**에 온다(스펙 §8-A D-A) — 되돌아가기 용도라
          매일 여는 진입점(내 작업)과 위아래로 갈라 그룹 성격이 섞이지 않게 한다.
          자체 `<nav>`를 만들지 않고 `<ul aria-label>`을 쓴다(FR13-b) — 접힘·빈 목록·조회
          미완 시 스스로 `null`을 반환하므로 여기서 조건 분기하지 않는다.
        */}
          <RecentIssuesMenu />
        </nav>

        {/* 🛑 스페이스 트리는 `메인 메뉴` nav **아래**다 (Jira 패리티 캠페인 PR ⑩ · J2).
            Jira 새 네비게이션은 전역 항목(내 작업·이슈·대시보드)을 먼저 두고 그 아래에
            스페이스 목록을 늘어놓는다. 트리는 프로젝트 수만큼 길어지므로 위에 두면 매일 여는
            전역 링크가 스크롤 밖으로 밀린다 — 순서가 곧 도달 비용이다.

            설정 서브앱에서는 이 자리가 `ProjectSettingsNav` 로 **교체**된다 (JS-2).
            `projectKey !== undefined` 는 판정이 아니라 **타입 좁히기**다 —
            `resolveProjectShellMode` 가 키 부재를 이미 `'tree'` 로 떨어뜨린다(★리뷰 E-4). */}
        {shellMode === 'settings' && projectKey !== undefined ? (
          <ProjectSettingsNav projectKey={projectKey} />
        ) : (
          <ProjectTree />
        )}

        {/* 🛑 `useSidebarCollapsed().toggle` 을 직접 물리지 마라 — 모바일에서 무동작 버튼이 된다.
          폭에 따른 대상 선택은 `useSidebarToggle` 한 곳이 소유한다(상단바 버튼·`[` 단축키와 동일). */}
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          onClick={toggle}
          aria-label={sidebarShown ? navLabels.collapseSidebar : navLabels.expandSidebar}
          className="mt-auto rounded-md text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
        >
          {sidebarShown ? '«' : '»'}
        </Button>
      </aside>

      {/* 레일·모바일에서는 스스로 null 을 반환한다 — 여기서 조건 분기하지 않는다. */}
      <SidebarResizeHandle controlsId={SIDEBAR_ID} />
    </div>
  )
}
