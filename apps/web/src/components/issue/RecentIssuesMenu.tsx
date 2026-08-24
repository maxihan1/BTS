// 사이드바 "최근 항목" 섹션 — 최근 본 이슈 MRU 렌더 + 제목 마운트 조회 + 403/404 자동 탈락 (FR-UX-08 PR-B Task 6, FR13/FR13-b)
import { type JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { useQueries } from '@tanstack/react-query'
import { fetchIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import { navLabels } from '@/i18n/nav-labels'
import { useRecentIssues } from '@/hooks/use-recent-issues'
import { useSidebarRailCollapsed } from '@/hooks/use-sidebar-drawer'

// ─────────────────────────────────────────────────────────────────────────────
// 스타일 — 사이드바 선례 (스펙 §8-A D-C)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 그룹 헤더 — `Sidebar.tsx`의 관리 메뉴 헤더와 동일 타이포.
 * 사이드바 안에서 그룹 제목은 전부 이 모양이라 새 어휘를 만들지 않는다.
 */
const SECTION_HEADER_CLASS =
  'px-2 pt-2 text-xs font-semibold uppercase text-sidebar-foreground/60'

/**
 * 하위 링크 — `ProjectTree.tsx`의 `TREE_SUB_LINK_CLASS` 관례.
 *
 * **아이콘을 달지 않는다**(§8-A D-C). 드롭다운(`FavoritesMenu`)은 항목마다 아이콘을 달지만
 * 사이드바 하위 링크는 들여쓰기만 한다. 같은 화면 안 이웃(프로젝트 트리 하위 항목)과 맞춰야
 * 사이드바가 한 시스템으로 읽히고, 똑같은 아이콘 5개의 세로 반복은 구분에 기여하지 않는
 * 장식이다.
 */
const RECENT_LINK_CLASS =
  'block truncate rounded-md px-2 py-1 text-sm text-sidebar-foreground/70 ' +
  'hover:bg-sidebar-accent hover:text-sidebar-accent-foreground ' +
  '[&.active]:bg-sidebar-accent [&.active]:text-sidebar-accent-foreground'

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사이드바 "최근 항목" 섹션 — 최근 본 이슈 최대 5건.
 *
 * `Sidebar`의 `메인 메뉴` `<nav>` **안 최하단**에 렌더된다(§8-A D-A). **새 `<nav>`를 만들지
 * 않는다**(FR13-b) — ADR §D5의 `<nav>` 추가 금지, NFR3의 `getByRole('navigation')` 개수 불변,
 * `navigation-contract.test.tsx`의 aria-label 4종 가드가 동시에 걸린다. 링크 묶음의 접근성
 * 이름은 `<ul aria-label>`로 준다(`list` 롤이라 `navigation` 개수에 영향이 없다).
 *
 * **저장값은 이슈 키뿐이고 제목은 여기서 조회한다**(ADR §D4 / NFR1). 로그아웃이 localStorage를
 * 지우지 않으므로 제목을 저장하면 같은 브라우저의 다음 사용자가 읽는다. 조회가 403/404로
 * 떨어지는 항목은 **조용히 숨긴다** — 삭제됐거나 권한이 회수된 이슈가 자동 탈락하므로 별도
 * stale 정리 로직이 필요 없다(E2/S9).
 *
 * **전체 settle 전에는 아무것도 렌더하지 않는다**(§8-A D-D). 헤더만 먼저 뜨면 목록이 나타날 때
 * 레이아웃이 튀고, 전부 403/404인 경우 빈 헤더만 남는다. `FavoritesMenu.FilterFavoritesGroup`이
 * 같은 이유로 같은 방식(`allSettled` 게이트)을 쓴다.
 *
 * **데스크톱 아이콘 레일(64px)에서는 섹션 전체를 렌더하지 않는다**(§8-A D-B). E10의 "아이콘만
 * 노출 + 텍스트 `sr-only`"는 **단일 링크** 관례지 5개짜리 목록 관례가 아니다 — `ProjectTree`의
 * 첫 글자 뱃지를 쓰면 `ATLAS-12`·`ATLAS-13`이 둘 다 `A`라 구분이 불가능하다. 레일 사용자가 최근
 * 항목에 접근할 수 없는 것은 **의도된 대가**이며 `TODOS.md`에 후속 과제로 등재돼 있다.
 *
 * 🛑 그 판정에 `useSidebarCollapsed().collapsed` 를 **직접 읽지 마라.** 모바일 드로어는 264px
 *    전체 폭으로 열리므로 위 근거(뱃지 구분 불가)가 성립하지 않는데, 저장된 `collapsed` 가 true
 *    인 채로 폭만 좁아지면 **드로어 안에서 이 섹션만 통째로 사라진다**(F24 회귀, 실측). 폭까지
 *    함께 보는 [useSidebarRailCollapsed] 가 유일한 정본이고, 형제 소비처(`Sidebar`·`ProjectTree`
 *    ·`FavoritesMenu`)도 전부 그것을 쓴다. 짝 판별식 =
 *    `hooks/__tests__/sidebar-collapsed-consumer-allowlist.test.ts`.
 *
 * 제목 조회는 `routes/issues.$key.tsx`와 **같은 `queryKey`**를 쓰므로 세션 중에는 캐시에
 * 적중한다(스펙 L2 완화).
 */
export function RecentIssuesMenu(): JSX.Element | null {
  const recentIssueKeys = useRecentIssues((s) => s.recentIssueKeys)
  const railCollapsed = useSidebarRailCollapsed()

  // 훅은 조건부로 호출할 수 없으므로 조회는 항상 배선하고, 렌더만 아래에서 가른다.
  // 레일일 때는 키 목록을 비워 실제 요청이 나가지 않게 한다.
  const keysToResolve = railCollapsed ? [] : recentIssueKeys

  const queries = useQueries({
    queries: keysToResolve.map((key) => ({
      queryKey: issueQueryKey(key),
      queryFn: () => fetchIssue(key),
      // 403/404는 재시도해도 결과가 같다 — 낭비이자 사이드바 표시 지연이다
      retry: false,
    })),
  })

  if (railCollapsed) return null
  if (recentIssueKeys.length === 0) return null

  const allSettled = queries.every((q) => q.status !== 'pending')
  if (!allSettled) return null

  const visible = keysToResolve
    .map((key, index) => ({ key, issue: queries[index]?.data }))
    .filter((pair): pair is { key: string; issue: IssueResponse } => pair.issue !== undefined)

  if (visible.length === 0) return null

  return (
    <div className="flex flex-col gap-1">
      <p className={SECTION_HEADER_CLASS}>{navLabels.recent}</p>
      <ul aria-label={navLabels.recent} className="flex flex-col gap-0.5">
        {visible.map(({ key, issue }) => (
          <li key={key}>
            <Link to="/issues/$key" params={{ key }} className={RECENT_LINK_CLASS}>
              {`${key} ${issue.summary}`}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}
