// 전역 좌측 사이드바 — 메인 nav(이슈·대시보드·캘린더·즐겨찾기) + 관리 nav(isSystemAdmin 게이팅, 기본 펼침) — FR-UX-06 PR11 Task 5 (아직 트리 미배선, ShellLayout(T7)이 배선)
import { type JSX } from 'react'
import { Link } from '@tanstack/react-router'
import {
  CircleDot,
  LayoutDashboard,
  Calendar,
  Workflow,
  ScrollText,
  ShieldCheck,
  Bell,
  Webhook,
  MessageSquare,
  UserCheck,
  type LucideIcon,
} from 'lucide-react'
import { useAuthUser } from '@/auth/authStore'
import {
  useSidebarDrawer,
  useSidebarRailCollapsed,
  useSidebarToggle,
  useSidebarShown,
} from '@/hooks/use-sidebar-drawer'
import { FavoritesMenu } from '@/components/favorite/FavoritesMenu'
import { RecentIssuesMenu } from '@/components/issue/RecentIssuesMenu'
import { navLabels } from '@/i18n/nav-labels'
import { ProjectTree } from './ProjectTree'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 사이드바 펼침 폭 (디자인 스펙 §3.1) */
const SIDEBAR_WIDTH_EXPANDED_CLASS = 'w-[264px]'

/** 사이드바 접힘 폭 — 아이콘 레일(FR5) */
const SIDEBAR_WIDTH_COLLAPSED_CLASS = 'w-16'

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

/** 관리 nav 링크 6종 — `Header.tsx` `ADMIN_LINKS` 정본 그대로(FR4). T7에서 Header 삭제로 중복 해소 */
const ADMIN_NAV_LINKS: ReadonlyArray<{ to: string; label: string; Icon: LucideIcon }> = [
  { to: '/admin/workflow-schemes', label: '워크플로우 스킴', Icon: Workflow },
  { to: '/admin/audit-logs', label: '감사 로그', Icon: ScrollText },
  { to: '/admin/global-permissions', label: '전역 권한', Icon: ShieldCheck },
  { to: '/admin/notification-policies', label: '알림 정책', Icon: Bell },
  { to: '/admin/webhooks', label: 'Webhook', Icon: Webhook },
  { to: '/admin/slack', label: 'Slack 연결', Icon: MessageSquare },
]

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 좌측 사이드바 — 264px 고정, 독립 스크롤(`overflow-y-auto`).
 *
 * 렌더 순서는 디자인 스펙 §3.1 사이드바 섹션 순서를 그대로 따른다 — {@link ProjectTree}
 * (섹션2, `프로젝트`)가 `메인 메뉴` nav(섹션3, 이슈·대시보드·캘린더) **위**에 온다
 * (FR-UX-06 PR12 Task 3). `관리 메뉴` nav(섹션4)는 가장 아래다.
 *
 * `메인 메뉴` nav는 항상 렌더되고, `관리 메뉴` nav는 `user.isSystemAdmin === true`일 때만
 * **기본 펼침** 상태로 렌더된다(FR3/FR4). 접힘 상태는 {@link useSidebarCollapsed}로 소비하며,
 * 각 nav 링크는 lucide 아이콘(`aria-hidden`) + 텍스트 라벨로 구성된다 — 펼침 시 아이콘·텍스트
 * 둘 다 보이고, 접힘(64px) 시 텍스트는 `sr-only`로 시각적으로만 숨겨 진짜 아이콘 레일이 되며
 * (잘린 텍스트 노출 방지), DOM에는 남아 있어 `getByRole('link', { name })` 계약(e2e)이
 * 유지된다(FR5). {@link ProjectTree}도 접힘 시 동일한 아이콘 레일 원칙을 따른다(자체 FR6).
 */
export function Sidebar(): JSX.Element {
  const user = useAuthUser()
  const drawerOpen = useSidebarDrawer((state) => state.open)
  const toggle = useSidebarToggle()
  const sidebarShown = useSidebarShown()
  const isAdmin = user?.isSystemAdmin === true
  // 🛑 모바일에서는 collapsed 를 쓰지 않는다 — 접힘은 **데스크톱 아이콘 레일**의 폭이고,
  //    드로어는 열리면 264px 전체가 떠야 한다. 둘을 겹치면 드로어가 64px 레일로 열리고
  //    라벨까지 `sr-only` 로 숨어 아이콘만 남은 드로어가 된다. 아래 `railCollapsed` 가 정본이다.
  const railCollapsed = useSidebarRailCollapsed()
  const widthClass = railCollapsed ? SIDEBAR_WIDTH_COLLAPSED_CLASS : SIDEBAR_WIDTH_EXPANDED_CLASS
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
    <aside
      className={`flex h-full flex-col overflow-y-auto border-r border-sidebar-border bg-sidebar text-sidebar-foreground ${widthClass} ${mobileDrawerClass}`}
    >
      <ProjectTree />

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
          <Link key={to} to={to} activeOptions={ISSUES_ACTIVE_OPTIONS[to]} className={NAV_LINK_CLASS}>
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

      {isAdmin && (
        <nav aria-label={navLabels.adminNav} className="flex flex-col gap-1 p-2">
          {!railCollapsed && (
            <p className="px-2 pt-2 text-xs font-semibold uppercase text-sidebar-foreground/60">
              {navLabels.admin}
            </p>
          )}
          {ADMIN_NAV_LINKS.map(({ to, label, Icon }) => (
            <Link key={to} to={to} className={NAV_LINK_CLASS}>
              <Icon aria-hidden="true" className={NAV_ICON_CLASS} />
              <span className={railCollapsed ? 'sr-only' : undefined}>{label}</span>
            </Link>
          ))}
        </nav>
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
  )
}
