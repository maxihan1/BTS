// 이슈 상세 페이지 단위 테스트 — Task 7 (시안 2 사이드 메타패널, 상태 읽기전용)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'
import { IssueDetailPage } from './issues.$key'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderPage(issueKey: string) {
  const client = makeClient()
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <IssueDetailPage issueKey={issueKey} />
      </QueryClientProvider>,
    ),
  }
}

// navigate mock — useDeleteIssue onSuccess에서 호출
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useParams: () => ({ key: 'ATLAS-1' }),
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function setupIssueFoundHandler(fixture = issueAtlas1Fixture) {
  server.use(
    http.get('/api/v1/issues/:key', ({ params }) => {
      if (params['key'] === fixture.key) {
        return HttpResponse.json({ data: fixture })
      }
      return HttpResponse.json(
        { message: `이슈를 찾을 수 없습니다: ${params['key'] as string}` },
        { status: 404 },
      )
    }),
  )
}

function setupIssueNotFoundHandler() {
  server.use(
    http.get('/api/v1/issues/:key', () =>
      HttpResponse.json({ message: '이슈를 찾을 수 없습니다' }, { status: 404 }),
    ),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 3-상태 분기 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 3-상태 분기', () => {
  /**
   * T7-1. 로딩 상태 — fetchIssue 응답 대기 중 "로딩 중..." 텍스트 렌더.
   */
  it('T7-1: 로딩 상태에서 "로딩 중..." 텍스트를 렌더한다', () => {
    // 응답을 지연시켜 로딩 상태를 유지
    server.use(
      http.get('/api/v1/issues/:key', async () => {
        await new Promise((resolve) => setTimeout(resolve, 10_000))
        return HttpResponse.json({ data: issueAtlas1Fixture })
      }),
    )

    renderPage('ATLAS-1')

    expect(screen.getByText('로딩 중...')).toBeInTheDocument()
  })

  /**
   * T7-2. 에러 상태 — 404 응답 시 role="alert" + "이슈를 찾을 수 없습니다" 렌더.
   * WCAG AA: role=alert로 스크린 리더가 즉시 인식.
   */
  it('T7-2: 404 에러 시 role="alert"와 오류 메시지를 렌더한다', async () => {
    setupIssueNotFoundHandler()

    renderPage('NOT-EXIST')

    await waitFor(() => {
      const alert = screen.getByRole('alert')
      expect(alert).toBeInTheDocument()
      expect(alert).toHaveTextContent('이슈를 찾을 수 없습니다')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 성공 상태 — 레이아웃 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 성공 레이아웃', () => {
  /**
   * T7-3. breadcrumb에 "ATLAS / ATLAS-1" 패턴이 렌더되어야 한다.
   */
  it('T7-3: breadcrumb에 프로젝트 키와 이슈 키를 렌더한다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    await waitFor(() => {
      expect(screen.getByText('ATLAS')).toBeInTheDocument()
      expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    })
  })

  /**
   * T7-4. 이슈 제목(summary)이 h1으로 렌더되어야 한다.
   */
  it('T7-4: 이슈 제목이 h1 요소로 렌더된다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 })
      expect(heading).toHaveTextContent(issueAtlas1Fixture.summary)
    })
  })

  /**
   * T7-5. 메타패널에 상태가 읽기전용 배지로 렌더되어야 한다.
   * 상태 전이 드롭다운/select가 없어야 한다 (D6 제외).
   */
  it('T7-5: 메타패널에 상태 배지가 렌더되고 select/combobox가 없다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    await waitFor(() => {
      // 상태 배지가 렌더되어야 함
      expect(screen.getByTestId('issue-state-badge')).toBeInTheDocument()
      // 상태 전이 드롭다운이 없어야 함 (D6 제외)
      expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    })
  })

  /**
   * T7-6. 메타패널에 보고자, 프로젝트 키, 버전이 렌더되어야 한다.
   */
  it('T7-6: 메타패널에 보고자·프로젝트·버전이 렌더된다', async () => {
    setupIssueFoundHandler()

    const { container } = renderPage('ATLAS-1')

    await waitFor(() => {
      const aside = container.querySelector('aside')
      expect(aside).toBeInTheDocument()
      if (aside === null) return
      expect(within(aside).getByText('보고자')).toBeInTheDocument()
      expect(within(aside).getByText('프로젝트')).toBeInTheDocument()
      expect(within(aside).getByText('버전')).toBeInTheDocument()
    })
  })

  /**
   * T7-7. createdAt이 null인 경우 "—" 표기.
   */
  it('T7-7: createdAt null인 경우 "—"를 렌더한다', async () => {
    const nullDateFixture = { ...issueAtlas1Fixture, createdAt: null, updatedAt: null }
    setupIssueFoundHandler(nullDateFixture)

    const { container } = renderPage('ATLAS-1')

    await waitFor(() => {
      const aside = container.querySelector('aside')
      expect(aside).toBeInTheDocument()
      if (aside === null) return
      // 두 날짜 모두 null이므로 "—"가 두 개 이상 렌더되어야 함
      const dashes = within(aside).getAllByText('—')
      expect(dashes.length).toBeGreaterThanOrEqual(2)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 제목 인라인 편집 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 제목 인라인 편집', () => {
  /**
   * T7-8. "제목 수정" 버튼 클릭 시 input이 나타나고 기존 제목이 채워져 있어야 한다.
   */
  it('T7-8: "제목 수정" 클릭 시 편집 input이 나타나고 기존 제목이 채워진다', async () => {
    setupIssueFoundHandler()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const editButton = screen.getByRole('button', { name: /제목 수정/ })
    await user.click(editButton)

    const input = screen.getByRole('textbox', { name: /제목 편집/ })
    expect(input).toBeInTheDocument()
    expect(input).toHaveValue(issueAtlas1Fixture.summary)
  })

  /**
   * T7-9. 편집 모드에서 저장 시 useUpdateIssueSummary.mutate가 호출된다.
   */
  it('T7-9: 편집 모드에서 저장 시 PATCH 요청이 발생한다', async () => {
    setupIssueFoundHandler()

    let patchCalled = false
    server.use(
      http.patch('/api/v1/issues/:key', async () => {
        patchCalled = true
        return HttpResponse.json({
          data: { ...issueAtlas1Fixture, summary: '수정된 제목', version: 1 },
        })
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const editButton = screen.getByRole('button', { name: /제목 수정/ })
    await user.click(editButton)

    const input = screen.getByRole('textbox', { name: /제목 편집/ })
    await user.clear(input)
    await user.type(input, '수정된 제목')

    const saveButton = screen.getByRole('button', { name: /저장/ })
    await user.click(saveButton)

    await waitFor(() => {
      expect(patchCalled).toBe(true)
    })
  })

  /**
   * T7-10. 편집 모드에서 취소 시 편집 input이 사라지고 h1이 다시 나타난다.
   */
  it('T7-10: 편집 모드에서 취소 시 h1으로 돌아간다', async () => {
    setupIssueFoundHandler()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const editButton = screen.getByRole('button', { name: /제목 수정/ })
    await user.click(editButton)

    expect(screen.getByRole('textbox', { name: /제목 편집/ })).toBeInTheDocument()

    const cancelButton = screen.getByRole('button', { name: /취소/ })
    await user.click(cancelButton)

    expect(screen.queryByRole('textbox', { name: /제목 편집/ })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 삭제 버튼 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 삭제', () => {
  /**
   * T7-11. "이슈 삭제" 버튼 클릭 시 확인 다이얼로그(confirm)가 나타난다.
   */
  it('T7-11: "이슈 삭제" 버튼 클릭 시 확인 UI가 나타난다', async () => {
    setupIssueFoundHandler()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const deleteButton = screen.getByRole('button', { name: /이슈 삭제/ })
    await user.click(deleteButton)

    // 확인 다이얼로그 또는 확인 버튼이 나타나야 함
    expect(screen.getByRole('button', { name: /확인/ })).toBeInTheDocument()
  })

  /**
   * T7-12. 삭제 확인 후 DELETE 요청이 발생하고 navigate(목록)가 호출된다.
   */
  it('T7-12: 삭제 확인 시 DELETE 요청 후 목록으로 navigate한다', async () => {
    setupIssueFoundHandler()

    let deleteCalled = false
    server.use(
      http.delete('/api/v1/issues/:key', () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    mockNavigate.mockClear()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const deleteButton = screen.getByRole('button', { name: /이슈 삭제/ })
    await user.click(deleteButton)

    const confirmButton = screen.getByRole('button', { name: /확인/ })
    await user.click(confirmButton)

    await waitFor(() => {
      expect(deleteCalled).toBe(true)
      expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues' })
    })
  })
})
