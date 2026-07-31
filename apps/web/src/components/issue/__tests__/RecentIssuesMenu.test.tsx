// 사이드바 "최근 항목" 섹션 단위 테스트 — MRU 렌더·403/404 숨김·빈 목록/접힘 미렌더·플리커 방지 (FR-UX-08 PR-B Task 6, RED)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { navLabels } from '@/i18n/nav-labels'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import { useRecentIssues } from '@/hooks/use-recent-issues'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { RecentIssuesMenu } from '../RecentIssuesMenu'

// TanStack Router Link 모킹 — 라우터 컨텍스트 없이 isolation 렌더 (Sidebar.test.tsx 동일 패턴)
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    children,
    className,
  }: {
    to: string
    params?: Record<string, string>
    children: React.ReactNode
    className?: string
  }) => {
    const href = params?.key === undefined ? to : to.replace('$key', params.key)
    return (
      <a href={href} className={className}>
        {children}
      </a>
    )
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 단건 조회 핸들러 — 지정한 키만 200, 나머지는 404.
 *
 * ⚠️ 응답 본문은 **실제 픽스처를 기반으로 만든다.** `fetchIssue`가 Zod로 `IssueResponse`
 * 전체를 검증하므로 `{ key, summary }` 같은 최소 DTO를 지어내면 파싱이 실패하고 쿼리가
 * 에러가 되어 "조회 실패로 숨김" 경로와 구분되지 않는다(DTO invent 금지).
 */
function issueTitleHandlers(titlesByKey: Record<string, string>, forbidden: string[] = []) {
  return [
    http.get('/api/v1/issues/:key', ({ params }) => {
      const key = String(params.key)
      if (forbidden.includes(key)) {
        return HttpResponse.json({ detail: 'forbidden' }, { status: 403 })
      }
      const summary = titlesByKey[key]
      if (summary === undefined) {
        return HttpResponse.json({ detail: 'not found' }, { status: 404 })
      }
      return HttpResponse.json({ data: { ...issueAtlas1Fixture, key, summary } })
    }),
  ]
}

function renderMenu() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <RecentIssuesMenu />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  localStorage.clear()
  useRecentIssues.setState({ recentIssueKeys: [] })
  useSidebarCollapsed.setState({ collapsed: false })
})

describe('RecentIssuesMenu — 사이드바 "최근 항목" (FR-UX-08 PR-B FR13)', () => {
  it('T-RM-1 (S8): 최근 키를 MRU 순으로 "KEY 제목" 형태로 렌더한다', async () => {
    useRecentIssues.setState({ recentIssueKeys: ['INFRA-3', 'ATLAS-12'] })
    server.use(...issueTitleHandlers({ 'INFRA-3': '배포 파이프라인', 'ATLAS-12': '로그인 버그' }))

    renderMenu()

    const list = await screen.findByRole('list', { name: navLabels.recent })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    // MRU — 맨 앞이 가장 최근
    expect(items[0]?.textContent).toContain('INFRA-3')
    expect(items[0]?.textContent).toContain('배포 파이프라인')
    expect(items[1]?.textContent).toContain('ATLAS-12')
  })

  it('T-RM-2 (S8): 각 항목이 /issues/<key> 로 가는 링크다', async () => {
    useRecentIssues.setState({ recentIssueKeys: ['ATLAS-12'] })
    server.use(...issueTitleHandlers({ 'ATLAS-12': '로그인 버그' }))

    renderMenu()

    const link = await screen.findByRole('link', { name: /ATLAS-12/ })
    expect(link).toHaveAttribute('href', '/issues/ATLAS-12')
  })

  it('T-RM-3 (E2/S9/NFR5): 403·404 항목은 조용히 숨기고 나머지는 렌더한다', async () => {
    useRecentIssues.setState({
      recentIssueKeys: ['A-1', 'A-2', 'A-3', 'A-4', 'A-5'],
    })
    server.use(
      ...issueTitleHandlers(
        { 'A-1': '하나', 'A-3': '셋', 'A-5': '다섯' },
        ['A-2'], // 403
      ),
      // A-4 는 titlesByKey 에 없어 404
    )

    renderMenu()

    const list = await screen.findByRole('list', { name: navLabels.recent })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(list.textContent).not.toContain('A-2')
    expect(list.textContent).not.toContain('A-4')
  })

  it('T-RM-4 (E3): 최근 목록이 비면 섹션 자체를 렌더하지 않는다 — 빈 헤더만 남기지 않는다', () => {
    useRecentIssues.setState({ recentIssueKeys: [] })

    renderMenu()

    expect(screen.queryByRole('list', { name: navLabels.recent })).toBeNull()
    expect(screen.queryByText(navLabels.recent)).toBeNull()
  })

  it('T-RM-5 (E2): 전부 403·404 면 섹션 자체를 렌더하지 않는다', async () => {
    useRecentIssues.setState({ recentIssueKeys: ['DEAD-1', 'DEAD-2'] })
    server.use(...issueTitleHandlers({}))

    renderMenu()

    await waitFor(() => {
      expect(screen.queryByRole('list', { name: navLabels.recent })).toBeNull()
    })
    expect(screen.queryByText(navLabels.recent)).toBeNull()
  })

  it('T-RM-6 (§8-A D-D): 전체 settle 전에는 아무것도 렌더하지 않는다 — 헤더 플리커 방지', () => {
    useRecentIssues.setState({ recentIssueKeys: ['ATLAS-12'] })
    // 응답을 주지 않는 핸들러 — pending 상태에 머문다
    server.use(http.get('/api/v1/issues/:key', () => new Promise(() => {})))

    renderMenu()

    // 조회가 끝나기 전에 헤더만 먼저 뜨면 목록이 나타날 때 레이아웃이 튄다
    expect(screen.queryByText(navLabels.recent)).toBeNull()
    expect(screen.queryByRole('list', { name: navLabels.recent })).toBeNull()
  })

  it('T-RM-7 (§8-A D-B): 사이드바 접힘 시 섹션 전체를 렌더하지 않는다', async () => {
    // ⚠️ 이 테스트는 **양성 대조군이 없으면 공허하다.** 처음엔 collapsed=true 로 바로 렌더하고
    // `waitFor(...toBeNull())` 로 단언했는데, 그건 조회가 pending 인 t=0 에 **즉시 통과**한다
    // (settle 전에는 접힘 여부와 무관하게 null 이다). 뮤테이션(접힘 가드 제거)이 red 가 되지
    // 않아 발각됐다 — FR-UX-07 의 「waitFor t=0 즉시통과」와 같은 양식이다.
    //
    // 그래서 **먼저 펼침 상태로 목록이 실제로 뜨는 것을 확인**하고(= 조회가 끝났다는 증거),
    // 그 다음 접어서 사라지는지 본다. 이러면 가드를 지웠을 때 목록이 남아 red 가 된다.
    useSidebarCollapsed.setState({ collapsed: false })
    useRecentIssues.setState({ recentIssueKeys: ['ATLAS-12'] })
    server.use(...issueTitleHandlers({ 'ATLAS-12': '로그인 버그' }))

    renderMenu()

    // 양성 대조군 — 펼침 상태에서는 확실히 보인다
    await screen.findByRole('list', { name: navLabels.recent })

    // 64px 레일에서 5개 항목은 구분 불가능한 표시 5개가 되므로 정보가 아니라 소음이다.
    // E10 의 "아이콘만 노출 + sr-only" 는 단일 링크 관례지 목록 관례가 아니다.
    act(() => {
      useSidebarCollapsed.setState({ collapsed: true })
    })

    expect(screen.queryByRole('list', { name: navLabels.recent })).toBeNull()
    expect(screen.queryByText(navLabels.recent)).toBeNull()
    expect(screen.queryByRole('link', { name: /ATLAS-12/ })).toBeNull()
  })

  it('T-RM-8 (§8-A D-C): 하위 링크에 아이콘을 달지 않는다 (사이드바 하위항목 관례)', async () => {
    useRecentIssues.setState({ recentIssueKeys: ['ATLAS-12'] })
    server.use(...issueTitleHandlers({ 'ATLAS-12': '로그인 버그' }))

    renderMenu()

    const link = await screen.findByRole('link', { name: /ATLAS-12/ })
    // 똑같은 아이콘 5개가 세로로 반복되면 구분에 기여하지 않는 장식이다.
    expect(link.querySelector('svg')).toBeNull()
  })

  it('T-RM-9 (FR13-b/NFR3): 새 nav 랜드마크를 만들지 않는다', async () => {
    useRecentIssues.setState({ recentIssueKeys: ['ATLAS-12'] })
    server.use(...issueTitleHandlers({ 'ATLAS-12': '로그인 버그' }))

    renderMenu()

    await screen.findByRole('list', { name: navLabels.recent })
    expect(screen.queryAllByRole('navigation')).toHaveLength(0)
  })

  it('T-RM-10 (NFR5): 조회는 최근 목록 길이만큼만 발생한다 (최대 5건)', async () => {
    useRecentIssues.setState({ recentIssueKeys: ['A-1', 'A-2', 'A-3'] })
    const requested: string[] = []
    server.use(
      http.get('/api/v1/issues/:key', ({ params }) => {
        const key = String(params.key)
        requested.push(key)
        return HttpResponse.json({ data: { ...issueAtlas1Fixture, key, summary: `제목 ${key}` } })
      }),
    )

    renderMenu()

    await screen.findByRole('list', { name: navLabels.recent })
    expect(requested).toHaveLength(3)
  })

  it('T-RM-11 (NFR1): 제목을 localStorage 에 되쓰지 않는다', async () => {
    useRecentIssues.setState({ recentIssueKeys: ['ATLAS-12'] })
    server.use(...issueTitleHandlers({ 'ATLAS-12': '민감한 이슈 제목' }))

    renderMenu()

    await screen.findByRole('list', { name: navLabels.recent })
    // 로그아웃이 localStorage 를 지우지 않으므로 제목이 저장되면 다음 사용자가 읽는다(ADR §D4).
    expect(JSON.stringify(localStorage)).not.toContain('민감한 이슈 제목')
  })
})
