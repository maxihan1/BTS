// AQL 검색 페이지(SearchPage) 단위 테스트 — FR-SR-02 D6 Task-5
import type { JSX } from 'react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { searchHandlers } from '@/mocks/search-handlers'
import {
  searchAqlSyntaxErrorHandler,
  searchAqlEmptyHandler,
  searchAqlUnsupportedFieldHandler,
  searchRefreshFailHandler,
} from '@/mocks/search-handlers'
import { DEFAULT_SEARCH_PAGE, SEARCH_HIT_BUG, SEARCH_HIT_UNASSIGNED } from '@/mocks/search-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { SearchPage } from './search'
import type { AqlHighlighterProps } from '@/components/search/AqlHighlighter'

// ─────────────────────────────────────────────────────────────────────────────
// AqlHighlighter mock — 테스트 관심사는 SearchPage 로직, 하이라이터 렌더 아님
// Props 타입을 실제 인터페이스로 사용해 contract drift 방지
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/search/AqlHighlighter', () => ({
  AqlHighlighter: ({ value, onChange, onSubmit, placeholder }: AqlHighlighterProps): JSX.Element => (
    <div data-testid="aql-highlighter">
      <textarea
        data-testid="aql-input"
        value={value}
        onChange={(e) => onChange(e.currentTarget.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault()
            onSubmit()
          }
        }}
        placeholder={placeholder}
        aria-label="AQL 쿼리 입력"
      />
    </div>
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SearchPage를 QueryClient context 안에서 렌더.
 * SearchPage는 props 기반(라우터 비의존)이라 RouterProvider 없이 테스트 가능.
 */
function renderSearchPage(
  initialQ = '',
  initialPage = 0,
  projectKey = 'ATLAS',
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  const onPageChange = vi.fn()
  const onNavigate = vi.fn()
  const onQueryChange = vi.fn()
  const onSearch = vi.fn()

  const result = render(
    <QueryClientProvider client={queryClient}>
      <SearchPage
        projectKey={projectKey}
        q={initialQ}
        page={initialPage}
        onPageChange={onPageChange}
        onNavigate={onNavigate}
        onQueryChange={onQueryChange}
        onSearch={onSearch}
      />
    </QueryClientProvider>,
  )

  return { onPageChange, onNavigate, onQueryChange, onSearch, queryClient, ...result }
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 setup/teardown
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...searchHandlers)
  useAuthStore.getState().setAccessToken('mock-access-token-test')
})

afterEach(() => {
  server.resetHandlers()
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────────────────────────────────────
// ① 정상 검색 → 결과 목록 + 페이지네이션
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ① 정상 검색', () => {
  it('검색 버튼 클릭 시 결과 목록이 표시된다', async () => {
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')

    const searchBtn = screen.getByRole('button', { name: '검색' })
    await user.click(searchBtn)

    await waitFor(() => {
      expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
      expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
      expect(screen.getByText('ATLAS-3')).toBeInTheDocument()
    })
  })

  it('결과 카드에 key · summary · 상태가 표시된다', async () => {
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByText(SEARCH_HIT_BUG.key)).toBeInTheDocument()
      expect(screen.getByText(SEARCH_HIT_BUG.summary)).toBeInTheDocument()
      expect(screen.getByText(SEARCH_HIT_BUG.currentStateKey)).toBeInTheDocument()
    })
  })

  it('우선순위가 priorityName으로 표시된다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json({
          ...DEFAULT_SEARCH_PAGE,
          content: [
            { ...SEARCH_HIT_BUG, priority: 2, priorityName: '높음' },
            { ...SEARCH_HIT_UNASSIGNED, priority: 3, priorityName: '보통' },
          ],
          totalElements: 2,
          totalPages: 1,
        }),
      ),
    )
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'priority IN (2, 3)')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByText('높음')).toBeInTheDocument()
      expect(screen.getByText('보통')).toBeInTheDocument()
    })
  })

  it('결과 카드 클릭 시 onNavigate가 해당 key로 호출된다', async () => {
    const user = userEvent.setup()
    const { onNavigate } = renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    })

    const card = screen.getByRole('link', { name: /ATLAS-1/ })
    await user.click(card)
    expect(onNavigate).toHaveBeenCalledWith('ATLAS-1')
  })

  it('totalPages > 1 이면 페이지네이션 컴포넌트가 표시된다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json({
          ...DEFAULT_SEARCH_PAGE,
          totalElements: 40,
          totalPages: 2,
          first: true,
          last: false,
        }),
      ),
    )
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByRole('navigation', { name: '페이지 탐색' })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ② 0건 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ② 0건 빈 상태', () => {
  it('0건 결과 시 결과 영역에 안내 텍스트가 표시된다', async () => {
    server.use(searchAqlEmptyHandler)
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = closed')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByText(/검색 결과가 없습니다/)).toBeInTheDocument()
    })
  })

  it('0건 빈 상태는 role=alert를 사용하지 않는다 (에러 아님)', async () => {
    server.use(searchAqlEmptyHandler)
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = closed')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByText(/검색 결과가 없습니다/)).toBeInTheDocument()
    })

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ③ 문법 오류 — 입력창 하단 detail + position
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ③ 문법 오류', () => {
  it('문법 오류 시 입력창 하단에 role=alert가 표시된다', async () => {
    server.use(searchAqlSyntaxErrorHandler)
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status === open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  it('문법 오류 메시지에 detail 내용이 표시된다', async () => {
    server.use(searchAqlSyntaxErrorHandler)
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status === open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      const alert = screen.getByRole('alert')
      expect(alert).toHaveTextContent('Unexpected token at position 7')
    })
  })

  it('문법 오류 후 이전 성공 결과가 유지된다 (keepPrevious)', async () => {
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    })

    server.use(searchAqlSyntaxErrorHandler)
    await user.clear(input)
    await user.type(input, 'bad query!!!')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
  })

  it('position이 에러 메시지에 포함된다', async () => {
    server.use(searchAqlSyntaxErrorHandler)
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'bad query')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      const alert = screen.getByRole('alert')
      expect(alert).toHaveTextContent('7')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ④ 미지원 필드 안내
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ④ 미지원 필드', () => {
  it('미지원 필드 오류 시 입력창 하단에 안내 메시지가 표시된다', async () => {
    server.use(searchAqlUnsupportedFieldHandler)
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'assignee = alice')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      const alert = screen.getByRole('alert')
      expect(alert).toBeInTheDocument()
      expect(alert).toHaveTextContent(/지원/)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑤ 401 / 403 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ⑤ 인증/권한 오류', () => {
  it('401 미인증(refresh 실패 포함) 시 에러 alert가 표시된다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json({ errorCode: 'SEARCH_UNAUTHENTICATED' }, { status: 401 }),
      ),
      searchRefreshFailHandler,
    )
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  it('403 권한 없음 시 에러 alert가 표시된다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(
          { errorCode: 'SEARCH_ACCESS_DENIED', detail: 'BROWSE permission required' },
          { status: 403 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑥ 빈 쿼리 버튼 disable
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ⑥ 빈 쿼리 버튼 disable', () => {
  it('쿼리가 비어 있으면 검색 버튼이 비활성화된다', () => {
    renderSearchPage('')
    const searchBtn = screen.getByRole('button', { name: '검색' })
    expect(searchBtn).toBeDisabled()
  })

  it('공백만 있는 쿼리도 검색 버튼이 비활성화된다', async () => {
    const user = userEvent.setup()
    renderSearchPage()
    const input = screen.getByTestId('aql-input')
    await user.type(input, '   ')
    const searchBtn = screen.getByRole('button', { name: '검색' })
    expect(searchBtn).toBeDisabled()
  })

  it('쿼리를 입력하면 검색 버튼이 활성화된다', async () => {
    const user = userEvent.setup()
    renderSearchPage()
    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    const searchBtn = screen.getByRole('button', { name: '검색' })
    expect(searchBtn).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑦ Cmd/Ctrl+Enter 트리거 + 힌트 텍스트
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ⑦ Cmd/Ctrl+Enter 트리거', () => {
  it('Ctrl+Enter 키 입력 시 검색이 트리거된다', async () => {
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    await user.keyboard('{Control>}{Enter}{/Control}')

    await waitFor(() => {
      expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    })
  })

  it('Cmd/Ctrl+Enter 힌트 텍스트가 표시된다', () => {
    renderSearchPage()
    expect(screen.getByText(/Cmd\/Ctrl\+Enter/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑧ 쿼리 변경 시 onQueryChange 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ⑧ 쿼리 변경 콜백', () => {
  it('쿼리가 변경되면 onQueryChange가 호출된다', async () => {
    const user = userEvent.setup()
    const { onQueryChange } = renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'a')

    expect(onQueryChange).toHaveBeenCalledWith('a')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑨ 로딩 "검색 중..." + 입력 보존
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ⑨ 로딩 상태', () => {
  it('검색 중에 "검색 중..." 텍스트가 표시되고 입력값이 보존된다', async () => {
    let resolveSearch!: () => void
    server.use(
      http.post('/api/v1/search/aql', () =>
        new Promise<Response>((resolve) => {
          resolveSearch = () =>
            resolve(HttpResponse.json(DEFAULT_SEARCH_PAGE) as unknown as Response)
        }),
      ),
    )

    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'status = open')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByText('검색 중...')).toBeInTheDocument()
    })

    // 입력창 값이 로딩 중에도 보존됨
    expect(input).toHaveValue('status = open')

    resolveSearch()

    await waitFor(() => {
      expect(screen.queryByText('검색 중...')).not.toBeInTheDocument()
    })
  })
})
