// 이슈 목록 페이지 단위 테스트 — 3-상태 + 빈 상태 + 페이지네이션 + CREATE 권한 게이트
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { issueHandlers } from '@/mocks/issue-handlers'
import {
  issuePageFixture,
  emptyIssuePageFixture,
  issuePageFirstFixture,
  issuePageLastFixture,
} from '@/mocks/issue-fixtures'
import { nonMemberProjectPermissions } from '@/mocks/project-permission-handlers'
import { IssueListPage } from './issues.index'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 권한 핸들러 — 토큰 없이도 CREATE:true를 반환해 기존 테스트 호환 유지
// ─────────────────────────────────────────────────────────────────────────────

/** 모든 요청에 대해 CREATE:true를 반환하는 기본 권한 핸들러 */
const createTruePermissionHandler = http.get(
  '/api/v1/users/me/project-permissions',
  () => HttpResponse.json({ projectKey: 'ATLAS', permissions: { CREATE: true } }),
)

/** 모든 요청에 대해 CREATE:false를 반환하는 권한 핸들러 (비멤버 시나리오) */
const createFalsePermissionHandler = http.get(
  '/api/v1/users/me/project-permissions',
  () =>
    HttpResponse.json({
      projectKey: 'ATLAS',
      permissions: nonMemberProjectPermissions,
    }),
)

// IssueListPage는 props 기반 — 라우터 없이 단위 테스트 가능

function renderPage(page = 0, onPageChange?: (page: number) => void, onNavigate?: (key: string) => void) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <IssueListPage
        projectKey="ATLAS"
        page={page}
        onPageChange={onPageChange ?? (() => undefined)}
        onNavigate={onNavigate ?? (() => undefined)}
      />
    </QueryClientProvider>,
  )
}

describe('IssueListPage', () => {
  // B1: IssueListPage가 렌더될 때 권한 API를 발사하므로 모든 테스트에 공통 등록
  beforeEach(() => {
    server.use(createTruePermissionHandler)
  })

  /**
   * T1. 로딩 상태 — "로딩 중..." 텍스트가 즉시 렌더되어야 한다.
   */
  it('T1: 로딩 중일 때 로딩 텍스트를 렌더한다', () => {
    server.use(...issueHandlers)
    renderPage()
    expect(screen.getByText(/로딩 중/)).toBeInTheDocument()
  })

  /**
   * T2. fetch 실패 시 role="alert" + 에러 메시지 렌더.
   */
  it('T2: fetch 실패 시 에러 메시지를 role=alert으로 렌더한다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json({ message: '서버 오류' }, { status: 500 }),
      ),
    )
    renderPage()
    await waitFor(() =>
      expect(screen.getByRole('alert')).toBeInTheDocument(),
    )
  })

  /**
   * T3. 성공 시 이슈 목록의 키 + 요약 + 상태 배지가 DOM에 존재해야 한다.
   */
  it('T3: 성공 시 이슈 키, 요약, 상태 배지를 렌더한다', async () => {
    server.use(...issueHandlers)
    renderPage()

    // 첫 번째 이슈 키가 렌더되어야 한다
    await waitFor(() =>
      expect(screen.getByText('ATLAS-1')).toBeInTheDocument(),
    )

    // 모든 픽스처 이슈 키 확인
    for (const issue of issuePageFixture.content) {
      expect(screen.getByText(issue.key)).toBeInTheDocument()
    }

    // 상태 배지가 존재해야 한다 (첫 번째 이슈: open)
    const badges = screen.getAllByRole('status')
    expect(badges.length).toBeGreaterThan(0)
  })

  /**
   * T4. 빈 목록 — 빈 상태 안내 메시지가 렌더되어야 한다.
   */
  it('T4: 빈 목록일 때 빈 상태 안내를 렌더한다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(emptyIssuePageFixture),
      ),
    )
    renderPage()
    await waitFor(() =>
      expect(screen.getByText(/이슈가 없습니다/)).toBeInTheDocument(),
    )
  })

  /**
   * T5. "새 이슈" 링크(/issues/new)가 DOM에 존재해야 한다.
   */
  it('T5: "새 이슈" 링크를 렌더한다', async () => {
    server.use(...issueHandlers)
    renderPage()

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /새 이슈/ })).toBeInTheDocument(),
    )
    expect(screen.getByRole('link', { name: /새 이슈/ })).toHaveAttribute('href', '/issues/new')
  })

  /**
   * T5-A. CREATE:true → "새 이슈" 버튼이 활성 링크(role=link, href=/issues/new)로 렌더된다.
   * data-testid="new-issue-button" 부여 확인.
   */
  it('T5-A: CREATE:true 일 때 "새 이슈"는 활성 링크로 렌더된다', async () => {
    server.use(...issueHandlers)
    renderPage()

    const button = await screen.findByTestId('new-issue-button')
    expect(button.tagName).toBe('A')
    expect(button).toHaveAttribute('href', '/issues/new')
    expect(button).not.toBeDisabled()
  })

  /**
   * T5-B. CREATE:false → "새 이슈" 버튼이 비활성 버튼(role=button, disabled)으로 렌더된다.
   * 클릭해도 이동하지 않는다(href 없음).
   */
  it('T5-B: CREATE:false 일 때 "새 이슈"는 disabled 버튼으로 렌더된다', async () => {
    server.use(createFalsePermissionHandler, ...issueHandlers)
    renderPage()

    const button = await screen.findByTestId('new-issue-button')
    expect(button.tagName).toBe('BUTTON')
    expect(button).toBeDisabled()
    expect(button).not.toHaveAttribute('href')
  })

  /**
   * T5-C. 권한 로딩 중 → fail-closed: 비활성 버튼으로 렌더된다.
   */
  it('T5-C: 권한 로딩 중일 때 "새 이슈"는 disabled 버튼으로 렌더된다 (fail-closed)', async () => {
    // 권한 응답을 지연시켜 로딩 상태를 강제 — issueHandlers는 즉시 응답
    server.use(
      http.get('/api/v1/users/me/project-permissions', async () => {
        // 응답을 반환하지 않아 pending 상태 유지
        await new Promise<never>(() => undefined)
        return undefined as never
      }),
      ...issueHandlers,
    )
    renderPage()

    // 이슈 목록이 렌더되어도 권한이 아직 로딩 중이면 비활성 버튼이어야 한다
    await waitFor(() =>
      expect(screen.getByTestId('new-issue-button')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('new-issue-button').tagName).toBe('BUTTON')
    expect(screen.getByTestId('new-issue-button')).toBeDisabled()
  })

  /**
   * T5-D. 권한 API 에러 → fail-closed: 비활성 버튼으로 렌더된다.
   */
  it('T5-D: 권한 API 에러 시 "새 이슈"는 disabled 버튼으로 렌더된다 (fail-closed)', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
      ...issueHandlers,
    )
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('new-issue-button')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('new-issue-button').tagName).toBe('BUTTON')
    expect(screen.getByTestId('new-issue-button')).toBeDisabled()
  })

  /**
   * T6. 이슈 항목 클릭 시 onNavigate(key) 콜백이 호출되어야 한다.
   */
  it('T6: 이슈 항목 클릭 시 onNavigate를 호출한다', async () => {
    server.use(...issueHandlers)
    const user = userEvent.setup()
    let navigatedKey = ''
    renderPage(0, undefined, (key) => { navigatedKey = key })

    // 이슈 항목이 렌더될 때까지 대기
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    // ATLAS-1 항목 링크 클릭
    const issueLink = screen.getByRole('link', { name: /ATLAS-1/ })
    await user.click(issueLink)

    expect(navigatedKey).toBe('ATLAS-1')
  })

  /**
   * T7. 마지막 페이지가 아닐 때 "다음" 버튼이 활성화되어야 한다.
   */
  it('T7: 첫 번째 페이지일 때 "다음" 버튼이 활성화되어 있다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(issuePageFirstFixture),
      ),
    )
    renderPage(0)

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    const nextButton = screen.getByRole('button', { name: /다음/ })
    expect(nextButton).not.toBeDisabled()
  })

  /**
   * T8. 첫 번째 페이지일 때 "이전" 버튼이 비활성화되어야 한다.
   */
  it('T8: 첫 번째 페이지일 때 "이전" 버튼이 비활성화되어 있다', async () => {
    server.use(...issueHandlers)
    renderPage(0)

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    const prevButton = screen.getByRole('button', { name: /이전/ })
    expect(prevButton).toBeDisabled()
  })

  /**
   * T9. 마지막 페이지일 때 "다음" 버튼이 비활성화되어야 한다.
   */
  it('T9: 마지막 페이지일 때 "다음" 버튼이 비활성화되어 있다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(issuePageLastFixture),
      ),
    )
    renderPage(1)

    await waitFor(() => expect(screen.getByText('ATLAS-3')).toBeInTheDocument())

    const nextButton = screen.getByRole('button', { name: /다음/ })
    expect(nextButton).toBeDisabled()
  })

  /**
   * T10. 긴 요약 텍스트가 truncate 처리되어야 한다 (CSS 클래스 확인).
   */
  it('T10: 긴 요약 텍스트에 truncate 클래스가 적용된다', async () => {
    server.use(...issueHandlers)
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-2')).toBeInTheDocument())

    // ATLAS-2의 요약이 있는 요소에 truncate 클래스가 있어야 한다
    const summaryEl = screen.getByTestId('issue-summary-ATLAS-2')
    expect(summaryEl).toHaveClass('truncate')
  })
})
