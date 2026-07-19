// 상단바 컴포넌트 — 사이드바 토글·로고·검색·만들기·알림·도움말·설정·계정 드롭다운 (FR-UX-06 PR11 Task 6, 트리 미배선)
import { Link, useNavigate } from '@tanstack/react-router'
import { PanelLeftClose, PanelLeftOpen, Search, Plus, HelpCircle, Settings } from 'lucide-react'
import { navLabels } from '@/i18n/nav-labels'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { InboxBell } from '@/components/inbox/InboxBell'
import { AccountMenu } from './AccountMenu'

/** TopBar 컴포넌트 props */
export interface TopBarProps {
  /**
   * 도움말 버튼 클릭 시 호출되는 콜백 — `ShortcutsHelpDialog`(FR-UX-05)는 상위(ShellLayout)가
   * 소유하므로 TopBar는 열기 트리거만 제공한다. 미전달 시 버튼은 아무 동작도 하지 않는다.
   */
  onHelpClick?: () => void
}

/**
 * 상단바(48px 고정) — 좌→우: 사이드바 토글 · 로고(Atlas, →`/dashboards`) · 검색
 * (`aria-label="검색"`, 상단바 단일) · 만들기(→`/issues/new`) · 알림(`InboxBell`) · 도움말 ·
 * 설정(기존 서브라우트 `/settings/account-links` — `/settings` 인덱스 라우트 부재라 죽은 링크 금지) ·
 * 계정 드롭다운(`AccountMenu`).
 *
 * 컴포넌트만 생성 — 트리 배선은 T7(ShellLayout 확장)이 담당한다. 검색·InboxBell·계정 드롭다운
 * 로직은 `Header.tsx`(축소 예정)를 재현한다 — Header 삭제 시 발생하는 일시 중복은 의도됨.
 */
export function TopBar({ onHelpClick }: TopBarProps) {
  const navigate = useNavigate()
  const { collapsed, toggle } = useSidebarCollapsed()

  return (
    <header className="flex h-12 items-center gap-1 border-b bg-background px-3">
      <button
        type="button"
        className="rounded-md p-1.5 hover:bg-accent"
        aria-label={collapsed ? navLabels.expandSidebar : navLabels.collapseSidebar}
        onClick={toggle}
      >
        {collapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
      </button>

      <Link to="/dashboards" className="ml-1 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <span className="flex size-6 items-center justify-center rounded bg-primary text-xs font-bold text-primary-foreground">
          A
        </span>
        Atlas
      </Link>

      <button
        type="button"
        className="ml-2 rounded-md p-1.5 hover:bg-accent"
        aria-label={navLabels.search}
        onClick={() => { void navigate({ to: '/search' }) }}
      >
        <Search className="size-4" />
      </button>

      <div className="flex-1" />

      <button
        type="button"
        className="flex items-center gap-1 rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        onClick={() => { void navigate({ to: '/issues/new' }) }}
      >
        <Plus className="size-4" />
        {navLabels.create}
      </button>

      <InboxBell />

      <button
        type="button"
        className="rounded-md p-1.5 hover:bg-accent"
        aria-label="도움말"
        onClick={onHelpClick}
      >
        <HelpCircle className="size-4" />
      </button>

      {/* 🔴 /settings 인덱스 라우트 부재 — 죽은 링크 방지를 위해 실재 서브라우트로 랜딩(PR13서 전용 인덱스 도입 예정) */}
      <Link to="/settings/account-links" className="rounded-md p-1.5 hover:bg-accent" aria-label="설정">
        <Settings className="size-4" />
      </Link>

      <AccountMenu />
    </header>
  )
}
