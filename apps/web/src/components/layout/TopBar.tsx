// 상단바 컴포넌트 — 사이드바 토글·로고·검색·만들기·알림·도움말·설정·계정 드롭다운 (FR-UX-06 PR11 Task 6, 트리 미배선)
import { useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { PanelLeftClose, PanelLeftOpen, Search, Plus, HelpCircle, Settings } from 'lucide-react'
import { navLabels } from '@/i18n/nav-labels'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'
import { toast } from 'sonner'
import { issueCreateStrings } from '@/i18n/ko'
import { InboxBell } from '@/components/inbox/InboxBell'
import { ProjectSwitcher } from '@/components/project/ProjectSwitcher'
import { AccountMenu } from './AccountMenu'
import { Button } from '@/components/ui/button'

/** TopBar 컴포넌트 props */
export interface TopBarProps {
  /**
   * 도움말 버튼 클릭 시 호출되는 콜백 — `ShortcutsHelpDialog`(FR-UX-05)는 상위(ShellLayout)가
   * 소유하므로 TopBar는 열기 트리거만 제공한다. 미전달 시 버튼은 아무 동작도 하지 않는다.
   */
  onHelpClick?: () => void
}

/**
 * 상단바(48px 고정) — 좌→우: 사이드바 토글 · 로고(Atlas, →`/dashboards`) ·
 * {@link ProjectSwitcher}(FR-UX-08 F12, `role="listbox"` — **`<nav>` 아님**) · 검색
 * (`aria-label="검색"`, 상단바 단일) · 만들기(→`/issues/new`) · 알림(`InboxBell`) · 도움말 ·
 * 설정(→`/settings` 인덱스) ·
 * 계정 드롭다운(`AccountMenu`).
 *
 * `ShellLayout`(T7)이 트리에 배선한다. 검색·InboxBell·계정 드롭다운 로직은 옛 `Header.tsx`를
 * 재현한다 — Header는 T7에서 삭제됐으므로 더 이상 중복이 아니다.
 *
 * 도움말 버튼은 `onHelpClick`이 전달됐을 때만 렌더한다 — `ShortcutsHelpDialog` 열림 상태는
 * `RootLayout`이 소유하는데 `ShellLayout`은 그 자손(Outlet 경유)이라 prop으로 전달받을 수
 * 없다. `ShellLayout`은 현재 `onHelpClick`을 전달하지 않으므로(PR11 이연) 이 버튼은 조립된
 * 화면에 나타나지 않는다 — 클릭해도 아무 동작을 하지 않는 죽은 버튼을 방지한다.
 */
export function TopBar({ onHelpClick }: TopBarProps) {
  // FR-12 — 생성 모달을 제자리에서 연다 (URL 불변)
  const [createOpen, setCreateOpen] = useState(false)
  const navigate = useNavigate()
  const { collapsed, toggle } = useSidebarCollapsed()

  return (
    <header className="flex h-12 items-center gap-1 border-b bg-background px-3">
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        className="rounded-md hover:bg-accent"
        aria-label={collapsed ? navLabels.expandSidebar : navLabels.collapseSidebar}
        onClick={toggle}
      >
        {collapsed ? <PanelLeftOpen className="size-4" /> : <PanelLeftClose className="size-4" />}
      </Button>

      <Link to="/dashboards" className="ml-1 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <span className="flex size-6 items-center justify-center rounded bg-primary text-xs font-bold text-primary-foreground">
          A
        </span>
        Atlas
      </Link>

      <ProjectSwitcher />

      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        className="ml-2 rounded-md hover:bg-accent"
        aria-label={navLabels.search}
        onClick={() => { void navigate({ to: '/search' }) }}
      >
        <Search className="size-4" />
      </Button>

      <div className="flex-1" />

      <Button
        type="button"
        variant="default"
        size="default"
        className="gap-1 rounded-md px-3 hover:bg-primary/90"
        onClick={() => { setCreateOpen(true) }}
      >
        <Plus className="size-4" />
        {navLabels.create}
      </Button>

      {/* 생성 모달 — 제자리에서 연다. 보드를 보던 사용자가 이슈 하나 만들려다
          화면을 떠나면 맥락이 끊긴다 (FR-12, design 리뷰 D8). */}
      <CreateIssueDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(key) => {
          // 제자리에 머무는 대신 만든 이슈로 갈 길을 토스트로 남긴다
          toast(`${key} ${issueCreateStrings.createdToast}`, {
            action: {
              label: issueCreateStrings.createdToastAction,
              onClick: () => { void navigate({ to: '/issues/$key', params: { key } }) },
            },
          })
        }}
        onCreateProject={() => { void navigate({ to: '/projects/new' }) }}
      />

      <InboxBell />

      {onHelpClick !== undefined && (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          className="rounded-md hover:bg-accent"
          aria-label="도움말"
          onClick={onHelpClick}
        >
          <HelpCircle className="size-4" />
        </Button>
      )}

      <Link to="/settings" className="rounded-md p-1.5 hover:bg-accent" aria-label="설정">
        <Settings className="size-4" />
      </Link>

      <AccountMenu />
    </header>
  )
}
