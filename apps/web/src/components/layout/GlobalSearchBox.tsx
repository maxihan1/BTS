// 상단바 전역 검색칸 — Enter 제출(AQL · 이슈 키) + 포커스 패널(최근 이슈 · 최근 프로젝트 · 모든 이슈 보기) (FR-UX-12 F13 · R4 JC-N-1)
import { useState, type FocusEvent, type JSX, type KeyboardEvent } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useQueries } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { fetchIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import { useIssueDetailModalStore } from '@/components/issue/issueDetailModalStore'
import { useOpenIssueDetail } from '@/components/issue/use-open-issue-detail'
import { partitionProjectsForSwitcher } from '@/components/project/project-switcher-order'
import { Input } from '@/components/ui/input'
import { useProjects } from '@/hooks/use-projects'
import { useRecentIssues } from '@/hooks/use-recent-issues'
import { useRecentProjects } from '@/hooks/use-recent-projects'
import { navLabels } from '@/i18n/nav-labels'
import { resolveGlobalSearchInput } from '@/lib/aql-natural'

/** 포커스 패널 문구 — Jira 「최근에 조회 · 최근 보드, 프로젝트 및 필터 · 모든 업무 항목 보기」 대응 */
const globalSearchPanelLabels = {
  panel: '검색 바로가기',
  recentIssues: '최근에 조회한 이슈',
  recentProjects: '최근 프로젝트',
  allIssues: '모든 이슈 보기',
} as const

const PANEL_ID = 'global-search-panel'
const SECTION_HEADER_CLASS = 'px-3 pb-1 pt-2 text-xs font-semibold text-muted-foreground'
const PANEL_LINK_CLASS = 'block truncate px-3 py-1.5 text-sm hover:bg-accent focus-visible:bg-accent focus-visible:outline-none'

/**
 * 포커스 패널 본문 — 입력 전 재방문 경로(JC-N-1).
 *
 * 열렸을 때만 마운트되므로 최근 이슈 제목 조회(최대 5건)도 그때만 나간다. 저장값은 키뿐이고
 * 제목은 사이드바 `RecentIssuesMenu` 와 **같은 queryKey** 로 조회해 캐시에 적중한다. 조회가
 * 403/404 로 떨어진 이슈는 조용히 빠진다(그 메뉴와 같은 가드). 최근 프로젝트는 접근 가능 목록과
 * 대조해 탈락시킨다(`partitionProjectsForSwitcher` — 스위처와 같은 정본).
 *
 * @param onPick 항목을 고른 뒤 패널을 닫는 콜백
 * @returns 패널
 */
function GlobalSearchPanel({ onPick }: { onPick: () => void }): JSX.Element {
  const openIssueDetail = useOpenIssueDetail()
  const recentIssueKeys = useRecentIssues((s) => s.recentIssueKeys)
  const recentProjectKeys = useRecentProjects((s) => s.recentProjectKeys)
  const { data: projects } = useProjects()
  const issueQueries = useQueries({
    queries: recentIssueKeys.map((key) => ({
      queryKey: issueQueryKey(key),
      queryFn: () => fetchIssue(key),
      retry: false,
    })),
  })
  const recentIssues = recentIssueKeys
    .map((key, index) => ({ key, issue: issueQueries[index]?.data }))
    .filter((pair): pair is { key: string; issue: IssueResponse } => pair.issue !== undefined)
  const recentProjects = partitionProjectsForSwitcher(projects ?? [], recentProjectKeys).recent

  return (
    <div
      id={PANEL_ID}
      role="region"
      aria-label={globalSearchPanelLabels.panel}
      // 항목을 누르는 순간 입력칸이 blur 돼 패널이 먼저 닫히지 않게 포커스를 입력칸에 둔다
      // (Safari 는 링크를 클릭해도 포커스를 옮기지 않아 relatedTarget 이 null 이다)
      onMouseDown={(e) => {
        e.preventDefault()
      }}
      className="absolute left-0 right-0 top-full z-50 mt-1 rounded-md border bg-popover py-1 text-popover-foreground shadow-md"
    >
      {recentIssues.length > 0 && (
        <>
          <p className={SECTION_HEADER_CLASS}>{globalSearchPanelLabels.recentIssues}</p>
          <ul aria-label={globalSearchPanelLabels.recentIssues}>
            {recentIssues.map(({ key, issue }) => (
              <li key={key}>
                <Link
                  to="/issues/$key"
                  params={{ key }}
                  className={PANEL_LINK_CLASS}
                  onClick={(e) => {
                    openIssueDetail(key, e)
                    onPick()
                  }}
                >
                  <span className="font-mono text-xs text-muted-foreground">{key}</span> {issue.summary}
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
      {recentProjects.length > 0 && (
        <>
          <p className={SECTION_HEADER_CLASS}>{globalSearchPanelLabels.recentProjects}</p>
          <ul aria-label={globalSearchPanelLabels.recentProjects}>
            {recentProjects.map((project) => (
              <li key={project.key}>
                <Link
                  to="/projects/$projectKey"
                  params={{ projectKey: project.key }}
                  className={PANEL_LINK_CLASS}
                  onClick={onPick}
                >
                  {project.name}
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
      <div className="mt-1 border-t pt-1">
        <Link to="/issues" className={PANEL_LINK_CLASS} onClick={onPick}>
          {globalSearchPanelLabels.allIssues}
        </Link>
      </div>
    </div>
  )
}

/**
 * 상단바 전역 검색칸(FR-UX-12 F13, `role="searchbox"` + `aria-label="전역 검색"`).
 *
 * 입력칸에 포커스하면 값이 비어 있는 동안 {@link GlobalSearchPanel} 이 열린다(Jira 「클릭만 해도
 * 패널」, JC-N-1). 타이핑을 시작하면 패널을 접고 Enter 제출 흐름만 남는다 — 입력 중 결과 미리보기는
 * 이 PR 범위 밖이다. Esc · 바깥으로 포커스 이동 · 항목 선택에 닫힌다.
 *
 * TopBar 의 200줄 상한(`lint-ratchet` R4) 때문에 TopBar 밖 파일로 둔다.
 *
 * @returns 검색칸 + 포커스 패널
 */
export function GlobalSearchBox(): JSX.Element {
  const navigate = useNavigate()
  const openIssueDetailModal = useIssueDetailModalStore((s) => s.open)
  // FR-UX-12 F13 — 제출 시에만 이동하고 입력 자체는 네트워크를 부르지 않는다(NFR2).
  const [query, setQuery] = useState('')
  const [panelOpen, setPanelOpen] = useState(false)
  const showPanel = panelOpen && query.trim() === ''

  /**
   * Enter 제출 — 판별 결과대로 목적지를 고른다. Esc 는 패널만 닫는다.
   *
   * ★`e.nativeEvent.isComposing` 을 먼저 본다(FR10/S6). 한글 조합 중의 Enter 는 조합 확정이지
   * 제출이 아니다. 이 가드가 없으면 「로그인」을 치는 도중 첫 Enter 에 검색이 나간다.
   * ★`keyCode === 229` 는 **이중 방어**다 — `isComposing` 을 세팅하지 않고 조합 중 keydown 을
   * 229 로만 보내는 브라우저/IME 조합이 있다. 저장소 선례와 같은 형태다
   * (`routes/issues.$key.tsx:741` · `components/issue/IssueDescription.tsx:392`).
   * ★`projectKey` 를 싣지 않는다(FR12) — `/search` 가 4단 해소와 미해소 안내를 이미 소유한다
   * (`routes/search.tsx` `useResolvedActiveProject` · `<ActiveProjectGate>`).
   */
  function handleKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Escape') {
      setPanelOpen(false)
      return
    }
    if (e.key !== 'Enter' || e.nativeEvent.isComposing || e.keyCode === 229) return
    const intent = resolveGlobalSearchInput(query)
    if (intent.kind === 'empty') return
    setPanelOpen(false)
    if (intent.kind === 'issue-key') {
      // 이슈 키 직접 입력은 상세 모달로 — 보던 화면을 잃지 않는다(J1).
      openIssueDetailModal(intent.issueKey)
      return
    }
    void navigate({ to: '/search', search: { q: intent.query } })
  }

  /** 포커스가 검색칸 · 패널 밖으로 나가면 닫는다 — Tab 으로 패널 링크에 들어가는 동안은 유지 */
  function handleBlur(e: FocusEvent<HTMLDivElement>) {
    if (!e.currentTarget.contains(e.relatedTarget)) setPanelOpen(false)
  }

  return (
    // 폭 규칙(flex-1 · max-w-md · min-w-32)은 TopBar 주석 참조 — 헤더의 **유일한** grow 요소다
    <div className="relative ml-2 min-w-32 max-w-md flex-1" onBlur={handleBlur}>
      <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
      <Input
        type="search"
        value={query}
        aria-label={navLabels.globalSearch}
        aria-expanded={showPanel}
        aria-controls={showPanel ? PANEL_ID : undefined}
        placeholder={navLabels.globalSearchPlaceholder}
        /* `[&::-webkit-search-cancel-button]:appearance-none` — `type="search"` 의 네이티브
           × 버튼 제거. tailwind preflight 는 `::-webkit-search-decoration` 만 지우고 이건
           남겨서, Chromium 에서 **값이 있을 때만** 디자인 토큰 밖의 브라우저 기본 아이콘이
           입력칸 안에 뜬다 (F13 2차 코드리뷰 I-3). 지움 = 취소는 Esc/직접 삭제로 통일. */
        className="pl-8 [&::-webkit-search-cancel-button]:appearance-none"
        onChange={(e) => {
          setQuery(e.target.value)
        }}
        onFocus={() => {
          setPanelOpen(true)
        }}
        onClick={() => {
          setPanelOpen(true)
        }}
        onKeyDown={handleKeyDown}
      />
      {showPanel && (
        <GlobalSearchPanel
          onPick={() => {
            setPanelOpen(false)
          }}
        />
      )}
    </div>
  )
}
