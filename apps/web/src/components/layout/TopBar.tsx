// 상단바 컴포넌트 — 사이드바 토글·로고·검색·만들기·알림·도움말·설정·계정 드롭다운 (FR-UX-06 PR11 Task 6, 트리 미배선)
import { useState, type KeyboardEvent } from 'react'
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
import { Input } from '@/components/ui/input'
import { resolveGlobalSearchInput } from '@/lib/aql-natural'

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
 * {@link ProjectSwitcher}(FR-UX-08 F12, `role="listbox"` — **`<nav>` 아님**) · 전역 검색 입력창
 * (FR-UX-12 F13, `role="searchbox"` + `aria-label="전역 검색"`, 상단바 단일 — `검색` 은
 * AQL 검색 페이지 제출 버튼 전용이라 이름을 분리한다) · 만들기(→`/issues/new`) ·
 * 알림(`InboxBell`) · 도움말 ·
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

  // FR-UX-12 F13 — 상단바 전역 검색. 제출 시에만 이동하고 입력 자체는 네트워크를 부르지 않는다(NFR2).
  const [query, setQuery] = useState('')

  /**
   * Enter 제출 — 판별 결과대로 목적지를 고른다.
   *
   * ★`e.nativeEvent.isComposing` 을 먼저 본다(FR10/S6). 한글 조합 중의 Enter 는 조합 확정이지
   * 제출이 아니다. 이 가드가 없으면 「로그인」을 치는 도중 첫 Enter 에 검색이 나간다.
   * ★`keyCode === 229` 는 **이중 방어**다 — `isComposing` 을 세팅하지 않고 조합 중 keydown 을
   * 229 로만 보내는 브라우저/IME 조합이 있다. 저장소 선례와 같은 형태다
   * (`routes/issues.$key.tsx:741` · `components/issue/IssueDescription.tsx:392`).
   * ★`projectKey` 를 싣지 않는다(FR12) — `/search` 가 4단 해소와 미해소 안내를 이미 소유한다
   * (`routes/search.tsx:480` `useResolvedActiveProject` · `:565` `<ActiveProjectGate>`).
   */
  function handleSearchKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter' || e.nativeEvent.isComposing || e.keyCode === 229) return
    const intent = resolveGlobalSearchInput(query)
    if (intent.kind === 'empty') return
    if (intent.kind === 'issue-key') {
      void navigate({ to: '/issues/$key', params: { key: intent.issueKey } })
      return
    }
    void navigate({ to: '/search', search: { q: intent.query } })
  }

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

      {/* 폭은 남는 공간을 먹되 상·하한을 둔다 (design 리뷰 G2/G4).
          - flex-1  : 좁은 뷰포트에서 남는 공간을 먹는다
          - max-w-md: 448px 초과는 한 줄 스캔이 어렵고 우측 액션과 균형이 깨진다
          - min-w-32: 128px. 한글 4~5자 + 돋보기가 들어가는 최소치 — 이보다 좁으면
                      placeholder 가 잘려 무슨 칸인지 알 수 없다
          ★컨트롤 종류는 어떤 폭에서도 바뀌지 않는다. 좁다고 아이콘 버튼으로 되돌리면
           `searchbox` 가 0개가 돼 유닛·E2E 단언이 뷰포트에 따라 깨지고, 그 버튼의
           접근성 이름을 무엇으로 할지 계약 §2 문제가 되살아난다. */}
      <div className="relative ml-2 min-w-32 max-w-md flex-1">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          type="search"
          value={query}
          aria-label={navLabels.globalSearch}
          placeholder={navLabels.globalSearchPlaceholder}
          className="pl-8"
          onChange={(e) => { setQuery(e.target.value) }}
          onKeyDown={handleSearchKeyDown}
        />
      </div>

      {/* ★정렬 스페이서 — 검색창과 **둘 다** 필요하다 (F13 코드리뷰 BLOCKER-2).
          헤더는 `justify-*` 없는 flex 라, 남는 가로 공간은 `flex-grow` 를 가진 자식만 흡수한다.
          검색창도 grow 하지만 `max-w-md`(448px)에서 성장을 멈추므로, 그 위쪽 뷰포트
          (1440·1920 등)에서는 잔여 공간이 **줄 끝에 그대로 남아** 우측 액션이 화면 오른쪽에
          붙지 않는다. 이 빈 grow 요소가 그 잔여분을 흡수해 우측 액션을 끝으로 민다.
          검색창의 flex-1 만 남기고 이 줄을 지우면 1024 이상에서 회귀한다. */}
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
