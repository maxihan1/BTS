// 상단바 전역 검색칸 포커스 패널 테스트 — 최근 이슈 · 최근 프로젝트 · 모든 이슈 보기 (R4 · JC-N-1)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { navLabels } from '@/i18n/nav-labels'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import { projectListHandlers } from '@/mocks/project-list-handlers'
import { useRecentIssues } from '@/hooks/use-recent-issues'
import { useRecentProjects } from '@/hooks/use-recent-projects'
import { GlobalSearchBox } from '../GlobalSearchBox'

// 라우터 컨텍스트 없이 렌더 — Link 는 params 를 채운 href 로 그린다(RecentIssuesMenu.test.tsx 패턴)
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  Link: ({
    to,
    params,
    children,
    className,
    onClick,
  }: {
    to: string
    params?: Record<string, string>
    children: React.ReactNode
    className?: string
    onClick?: (e: React.MouseEvent) => void
  }) => {
    let href = to
    for (const [name, value] of Object.entries(params ?? {})) href = href.replace(`$${name}`, value)
    return (
      <a href={href} className={className} onClick={onClick}>
        {children}
      </a>
    )
  },
}))

/** 이슈 단건 조회 — 지정한 키만 200, 나머지는 404(조회 실패 → 패널에서 조용히 빠진다) */
function issueTitleHandler(titlesByKey: Record<string, string>) {
  return http.get('/api/v1/issues/:key', ({ params }) => {
    const key = String(params.key)
    const summary = titlesByKey[key]
    if (summary === undefined) return HttpResponse.json({ detail: 'not found' }, { status: 404 })
    return HttpResponse.json({ data: { ...issueAtlas1Fixture, key, summary } })
  })
}

function renderBox() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <GlobalSearchBox />
    </QueryClientProvider>,
  )
  return screen.getByRole('searchbox', { name: navLabels.globalSearch })
}

beforeEach(() => {
  useAuthStore.getState().setAccessToken('mock-access-token-alice')
  server.use(...projectListHandlers, issueTitleHandler({ 'ATLAS-1': '로그인 버튼 고장' }))
  useRecentIssues.setState({ recentIssueKeys: [] })
  useRecentProjects.setState({ recentProjectKeys: [] })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
})

describe('GlobalSearchBox 포커스 패널', () => {
  it('포커스만 해도 패널이 열리고 「모든 이슈 보기」가 /issues 로 간다', async () => {
    const user = userEvent.setup()
    const box = renderBox()

    expect(screen.queryByRole('region', { name: '검색 바로가기' })).toBeNull()
    await user.click(box)

    const panel = screen.getByRole('region', { name: '검색 바로가기' })
    expect(within(panel).getByRole('link', { name: '모든 이슈 보기' })).toHaveAttribute('href', '/issues')
    expect(box).toHaveAttribute('aria-expanded', 'true')
  })

  it('최근 본 이슈를 키 + 제목으로 보여 주고, 조회가 실패한 키는 뺀다', async () => {
    useRecentIssues.setState({ recentIssueKeys: ['ATLAS-1', 'GONE-9'] })
    const user = userEvent.setup()
    await user.click(renderBox())

    const list = await screen.findByRole('list', { name: '최근에 조회한 이슈' })
    const link = await within(list).findByRole('link', { name: /ATLAS-1 로그인 버튼 고장/ })
    expect(link).toHaveAttribute('href', '/issues/ATLAS-1')
    expect(within(list).queryByText(/GONE-9/)).toBeNull()
  })

  it('최근 프로젝트는 접근 가능한 것만 프로젝트 요약으로 잇는다', async () => {
    useRecentProjects.setState({ recentProjectKeys: ['GONE', 'ATLAS'] })
    const user = userEvent.setup()
    await user.click(renderBox())

    const list = await screen.findByRole('list', { name: '최근 프로젝트' })
    expect(within(list).getByRole('link', { name: 'Atlas 프로젝트' })).toHaveAttribute('href', '/projects/ATLAS')
    expect(within(list).getAllByRole('link')).toHaveLength(1)
  })

  it('타이핑을 시작하면 패널을 접는다', async () => {
    const user = userEvent.setup()
    const box = renderBox()
    await user.click(box)

    await user.type(box, '로그인')

    expect(screen.queryByRole('region', { name: '검색 바로가기' })).toBeNull()
  })

  it('Esc 를 누르면 패널을 닫는다', async () => {
    const user = userEvent.setup()
    await user.click(renderBox())

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('region', { name: '검색 바로가기' })).toBeNull()
  })

  it('항목을 고르면 패널을 닫는다', async () => {
    const user = userEvent.setup()
    await user.click(renderBox())

    await user.click(screen.getByRole('link', { name: '모든 이슈 보기' }))

    expect(screen.queryByRole('region', { name: '검색 바로가기' })).toBeNull()
  })
})
