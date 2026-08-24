// 상단바 컴포넌트 — 사이드바 토글·로고·검색·만들기·알림·도움말·설정·계정 드롭다운 (FR-UX-06 PR11 Task 6, 트리 미배선)
import { useState, type KeyboardEvent } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { PanelLeftClose, PanelLeftOpen, Search, Plus, HelpCircle, Settings } from 'lucide-react'
import { navLabels } from '@/i18n/nav-labels'
import { useSidebarToggle, useSidebarShown } from '@/hooks/use-sidebar-drawer'
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
  // 🛑 `useSidebarCollapsed().toggle` 직접 사용 금지 — 모바일에서 무동작 버튼이 된다.
  //    폭에 따른 대상 선택·표시 방향은 `use-sidebar-drawer` 훅 두 개가 소유한다.
  const toggle = useSidebarToggle()
  const sidebarShown = useSidebarShown()

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
        aria-label={sidebarShown ? navLabels.collapseSidebar : navLabels.expandSidebar}
        onClick={toggle}
      >
        {sidebarShown ? <PanelLeftClose className="size-4" /> : <PanelLeftOpen className="size-4" />}
      </Button>

      {/* 모바일(max-md)에서는 워드마크 텍스트만 감춘다 — A 마크는 남겨 홈 링크를 잃지 않는다.
          🛑 `Atlas` 를 DOM 에서 빼지 마라. `getByRole('link', { name: 'Atlas' })` 계약이 깨진다.
             `sr-only` 는 접근가능 이름을 보존한 채 자리만 돌려준다. */}
      <Link to="/dashboards" className="ml-1 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <span className="flex size-6 items-center justify-center rounded bg-primary text-xs font-bold text-primary-foreground">
          A
        </span>
        <span className="max-md:sr-only">Atlas</span>
      </Link>

      {/* 프로젝트 전환기는 모바일에서 감춘다 — 390px 에 4개 컨트롤이 다 들어가지 않는다.
          같은 전환 경로가 사이드바 드로어의 `ProjectTree` 에 있어 기능이 사라지지 않는다. */}
      <div className="max-md:hidden">
        <ProjectSwitcher />
      </div>

      {/* 폭은 남는 공간을 먹되 상·하한을 둔다 (design 리뷰 G2/G4).
          - flex-1  : 남는 공간을 먹는다. ★헤더에서 **유일한** grow 요소여야 한다 (아래 참조)
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
          /* `[&::-webkit-search-cancel-button]:appearance-none` — `type="search"` 의 네이티브
             × 버튼 제거. tailwind preflight 는 `::-webkit-search-decoration` 만 지우고 이건
             남겨서, Chromium 에서 **값이 있을 때만** 디자인 토큰 밖의 브라우저 기본 아이콘이
             입력칸 안에 뜬다 (F13 2차 코드리뷰 I-3). 지움 = 취소는 Esc/직접 삭제로 통일. */
          className="pl-8 [&::-webkit-search-cancel-button]:appearance-none"
          onChange={(e) => { setQuery(e.target.value) }}
          onKeyDown={handleSearchKeyDown}
        />
      </div>

      {/* ★`ml-auto` 로 우측 액션을 끝에 붙인다 — **빈 `flex-1` 스페이서를 다시 넣지 말 것**
          (F13 1차 코드리뷰 BLOCKER-2 → 2차 CONCERNS-1 의 왕복 이력).

          경위. 헤더는 `justify-*` 없는 flex 라 잔여 가로 공간을 누가 흡수할지 정해야 한다.
          1차에서 검색창 뒤에 `<div className="flex-1" />` 스페이서를 넣어 해결했는데, 그러면
          grow 요소가 **둘**이 된다. `flex-1` = `flex: 1 1 0%` 라 basis 0·grow 1 인 형제 둘은
          잔여 공간을 **정확히 반씩** 나눈다. 그 결과 검색창이 `max-w-md`(448px)에 도달하는
          것은 ≳1334px 부터고, 그 아래 모든 뷰포트에서 검색칸은 **빈 스페이서와 항상 같은 폭**
          이었다 (1024px → 검색 277px + 빈칸 277px).

          `auto` 마진은 flex-grow 해소가 **끝난 뒤** 남은 free space 를 먹는다. 그래서
          ① 검색창이 448px 에 걸려 멈춘 넓은 화면 → 잔여분 전부를 이 마진이 흡수해 우측 액션이
            화면 오른쪽 끝에 붙는다 (BLOCKER-2 회귀 없음)
          ② 검색창이 아직 크는 좁은 화면 → grow 가 free space 를 0 으로 만들어 마진은 0이 되고
            검색창이 그 공간을 전부 가져간다 (CONCERNS-1 해소)
          즉 grow 요소는 **하나**여야 `max-w-md` 가 모든 폭에서 의미를 갖는다. */}
      <Button
        type="button"
        variant="default"
        size="default"
        className="ml-auto gap-1 rounded-md px-3 hover:bg-primary/90 max-md:px-2"
        onClick={() => { setCreateOpen(true) }}
      >
        <Plus className="size-4" />
        {/* 모바일은 아이콘만 — 컨트롤 종류(button)는 그대로라 §계약 「컨트롤 종류 불변」에 걸리지
            않는다. `sr-only` 라 접근가능 이름 「만들기」도 폭과 무관하게 유지된다. */}
        <span className="max-md:sr-only">{navLabels.create}</span>
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

      {/* 설정 톱니는 모바일에서 감춘다 — 같은 허브 진입점이 **계정 메뉴의 「모든 설정」**에 있다.
          🛑 「사이드바에도 있다」고 적지 마라 — 거짓이다. 사이드바 관리 메뉴는 `isSystemAdmin`
             전용이고 `/settings` 루트를 링크하지 않는다. 한때 그 거짓 근거로 이 톱니를 감췄다가
             `password`·`sessions`·`notifications`·`account-links` 4개가 모바일에서 도달
             불가가 됐다. 계정 메뉴의 그 항목을 지우면 이 `max-md:hidden` 도 함께 없애야 한다 —
             짝 판별식 = `__tests__/settings-reachability.test.ts` 가 그 조합을 red 로 잡는다. */}
      <Link to="/settings" className="rounded-md p-1.5 hover:bg-accent max-md:hidden" aria-label="설정">
        <Settings className="size-4" />
      </Link>

      <AccountMenu />
    </header>
  )
}
