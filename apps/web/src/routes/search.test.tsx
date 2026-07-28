// AQL 검색 페이지(SearchPage) 단위 테스트 + SearchRouteAdapter 통합 테스트 — FR-SR-02 D6 Task-5, FR-SR-03 Task-6
import type { JSX } from 'react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createRoute, createRootRoute, createMemoryHistory } from '@tanstack/react-router'
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
import { savedFilterHandlers, seedSavedFilters, resetSavedFilterStore } from '@/mocks/saved-filter-handlers'
import { useAuthStore } from '@/auth/authStore'
import { projectListHandlers } from '@/mocks/project-list-handlers'
import { useActiveProject } from '@/hooks/use-active-project'
import { SearchPage, SearchRouteAdapter } from './search'
import type { AqlHighlighterProps } from '@/components/search/AqlHighlighter'
import { toast } from 'sonner'

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 mock — sonner / SavedFilterMenu / SaveFilterDialog / AqlHighlighter
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: { error: vi.fn() },
}))

vi.mock('@/components/search/SavedFilterMenu', () => ({
  SavedFilterMenu: (): JSX.Element => <div data-testid="saved-filter-menu-mock" />,
}))

vi.mock('@/components/search/SaveFilterDialog', () => ({
  SaveFilterDialog: ({ open }: { open: boolean }): JSX.Element | null =>
    open ? <div data-testid="save-filter-dialog-mock" /> : null,
}))

// AqlHighlighter mock — 테스트 관심사는 SearchPage 로직, 하이라이터 렌더 아님
// Props 타입을 실제 인터페이스로 사용해 contract drift 방지
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
          data: [
            { ...SEARCH_HIT_BUG, priority: 2, priorityName: 'High' },
            { ...SEARCH_HIT_UNASSIGNED, priority: 3, priorityName: 'Medium' },
          ],
          meta: { page: { ...DEFAULT_SEARCH_PAGE.meta.page, totalElements: 2, totalPages: 1 } },
        }),
      ),
    )
    const user = userEvent.setup()
    renderSearchPage()

    const input = screen.getByTestId('aql-input')
    await user.type(input, 'priority IN (2, 3)')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(screen.getByText('High')).toBeInTheDocument()
      expect(screen.getByText('Medium')).toBeInTheDocument()
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
          meta: { page: { ...DEFAULT_SEARCH_PAGE.meta.page, totalElements: 40, totalPages: 2 } },
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

  it('문법 오류 후에도 입력창 값이 보존된다', async () => {
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
    // 입력창 값이 보존됨
    expect(input).toHaveValue('bad query!!!')
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

// ─────────────────────────────────────────────────────────────────────────────
// SearchRouteAdapter 통합 테스트 — Task-6 (FR-SR-03)
// ─────────────────────────────────────────────────────────────────────────────

/** alice fixture userId (RFC4122 v4 형식 — Zod v4 uuid 통과) */
const ALICE_ID = '00000000-0000-4000-8000-000000000001'

/** 테스트용 저장 필터 UUID (RFC4122 v4 형식 — Zod v4 uuid 통과) */
const FILTER_UUID = '00000000-0000-4000-8000-000000000099'

/**
 * SearchRouteAdapter를 TanStack Router context 안에서 렌더한다.
 *
 * SearchPage와 달리 Adapter는 useSearch/useNavigate를 사용하므로
 * RouterProvider를 통해 테스트 라우터를 제공해야 한다.
 * strict:false 덕분에 validateSearch 없이도 URL 파라미터를 읽는다.
 */
function renderSearchAdapter(initialUrl = '/search') {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const rootRoute = createRootRoute()
  const adapterRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/search',
    component: SearchRouteAdapter,
  })
  const memHistory = createMemoryHistory({ initialEntries: [initialUrl] })
  const testRouter = createRouter({
    routeTree: rootRoute.addChildren([adapterRoute]),
    history: memHistory,
    defaultPreload: false,
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={testRouter} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ⑩ SearchRouteAdapter — 저장 버튼 + SavedFilterMenu 마운트 (C2)
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchRouteAdapter — ⑩ 저장 버튼 + SavedFilterMenu 마운트', () => {
  beforeEach(() => {
    server.use(...searchHandlers, ...projectListHandlers)
    useAuthStore.getState().setAccessToken('mock-access-token-alice')
  })
  afterEach(() => {
    useAuthStore.getState().clearSession()
    vi.clearAllMocks()
  })

  it('SavedFilterMenu가 adapter에 마운트된다(C2)', async () => {
    renderSearchAdapter('/search')
    await waitFor(() => {
      expect(screen.getByTestId('saved-filter-menu-mock')).toBeInTheDocument()
    })
  })

  it('q가 비어 있으면 저장 버튼이 비활성화된다(EC1)', async () => {
    renderSearchAdapter('/search')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '현재 검색 저장' })).toBeDisabled()
    })
  })

  it('q가 있는 URL로 진입하면 저장 버튼이 활성화된다', async () => {
    renderSearchAdapter('/search?q=status+%3D+open')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '현재 검색 저장' })).not.toBeDisabled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑪ SearchRouteAdapter — filterId 딥링크 자동 실행 (C1)
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchRouteAdapter — ⑪ filterId 딥링크 자동 실행', () => {
  beforeEach(() => {
    resetSavedFilterStore()
    server.use(...savedFilterHandlers, ...searchHandlers, ...projectListHandlers)
    useAuthStore.getState().setAccessToken('mock-access-token-alice')
  })
  afterEach(() => {
    resetSavedFilterStore()
    useAuthStore.getState().clearSession()
    vi.clearAllMocks()
  })

  it('/search?filterId=<uuid> 진입 시 필터 해석 후 검색 결과 행이 렌더된다(C1)', async () => {
    seedSavedFilters(ALICE_ID, [
      {
        id: FILTER_UUID,
        ownerId: ALICE_ID,
        name: '내 필터',
        aqlQuery: 'status = open',
        projectKey: 'ATLAS',
        createdAt: null,
        updatedAt: null,
        version: 0,
        shares: [],
      },
    ])

    renderSearchAdapter(`/search?filterId=${FILTER_UUID}`)

    // C1: "q 세팅"만 단언하면 vacuous green — 실제 결과 행이 렌더되어야 통과
    await waitFor(
      () => {
        expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
      },
      { timeout: 3000 },
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑫ SearchRouteAdapter — filterId 에러 처리 (EC6, N3)
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchRouteAdapter — ⑫ filterId 에러 처리', () => {
  beforeEach(() => {
    resetSavedFilterStore()
    server.use(...savedFilterHandlers, ...searchHandlers, ...projectListHandlers)
    useAuthStore.getState().setAccessToken('mock-access-token-alice')
  })
  afterEach(() => {
    resetSavedFilterStore()
    useAuthStore.getState().clearSession()
    vi.clearAllMocks()
  })

  it('filterId 404(필터 없음) → toast.error 호출 + 일반 검색 화면 유지(EC6)', async () => {
    // filter를 시드하지 않음 → MSW 404 반환
    renderSearchAdapter(`/search?filterId=${FILTER_UUID}`)

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith(expect.any(String))
    })
    // 일반 검색 화면(검색 버튼)이 표시된다
    expect(screen.getByRole('button', { name: '검색' })).toBeInTheDocument()
  })

  it('filterId 400 등 비-404 에러 → toast.error 호출 + 일반 검색 화면 유지(N3 에러 일반화)', async () => {
    // 400 에러를 강제로 반환해 에러 일반화 검증 (404 가정 금지)
    server.use(
      http.get('/api/v1/filters/:id', () =>
        HttpResponse.json({ errorCode: 'SEARCH_VALIDATION_FAILED' }, { status: 400 }),
      ),
    )
    renderSearchAdapter(`/search?filterId=${FILTER_UUID}`)

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith(expect.any(String))
    })
    expect(screen.getByRole('button', { name: '검색' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ⑦ FR-SR-04 — 전문 검색(FTS) placeholder 예시 노출
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchPage — ⑦ FTS placeholder', () => {
  it('입력창 placeholder에 text ~ 전문 검색 예시가 포함된다', () => {
    renderSearchPage()
    const input = screen.getByTestId('aql-input')
    expect(input).toHaveAttribute('placeholder', expect.stringContaining('text ~'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-07 — SearchRouteAdapter 활성 프로젝트 해소 (DEFAULT_PROJECT_KEY 제거)
// ─────────────────────────────────────────────────────────────────────────────

describe('SearchRouteAdapter — 활성 프로젝트 (FR-UX-07)', () => {
  /** POST /api/v1/search/aql 본문의 projectKey 를 기록한다 */
  let searchedProjectKeys: string[] = []

  /**
   * ★ 등록 순서 — capture 를 인자 맨 앞에 둬야 searchHandlers 를 이긴다.
   * 한 번의 server.use(a,b,c) 안에서는 앞선 인자가 우선(첫 매칭이 이긴다).
   */
  function useCaptureHandlers(extra: Parameters<typeof server.use> = []) {
    server.use(
      ...extra,
      http.post('/api/v1/search/aql', async ({ request }) => {
        const body = (await request.json()) as { projectKey?: string }
        if (typeof body.projectKey === 'string') searchedProjectKeys.push(body.projectKey)
        return HttpResponse.json({ data: DEFAULT_SEARCH_PAGE })
      }),
      ...searchHandlers,
      ...projectListHandlers,
    )
  }

  beforeEach(() => {
    searchedProjectKeys = []
    localStorage.clear()
    useActiveProject.setState({ activeProjectKey: null })
    useAuthStore.getState().setAccessToken('mock-access-token-alice')
  })

  afterEach(() => {
    useAuthStore.getState().clearSession()
    vi.clearAllMocks()
  })

  it('SA1: ?projectKey=ZETA 면 그 프로젝트로 검색한다 (기존 동작 무회귀)', async () => {
    useCaptureHandlers()
    renderSearchAdapter('/search?q=text+~+%22a%22&projectKey=ZETA')

    await waitFor(() => expect(searchedProjectKeys).toContain('ZETA'))
  })

  it('SA2 (S2): URL 에 projectKey 가 없으면 저장값 프로젝트로 검색한다', async () => {
    useActiveProject.setState({ activeProjectKey: 'ZETA' })
    useCaptureHandlers()
    renderSearchAdapter('/search?q=text+~+%22a%22')

    await waitFor(() => expect(searchedProjectKeys).toContain('ZETA'))
    expect(searchedProjectKeys).not.toContain('ATLAS')
  })

  it('SA3 (S3): URL·저장값 둘 다 없으면 목록의 첫 프로젝트로 검색한다', async () => {
    useCaptureHandlers()
    renderSearchAdapter('/search?q=text+~+%22a%22')

    await waitFor(() => expect(searchedProjectKeys).toContain('ATLAS'))
  })

  it('SA4 (S5/B4): 프로젝트가 0개면 검색하지 않고 빈 상태를 보여준다', async () => {
    useCaptureHandlers([http.get('/api/v1/projects', () => HttpResponse.json({ data: [] }))])
    renderSearchAdapter('/search?q=text+~+%22a%22')

    await waitFor(() =>
      expect(screen.getByText(/접근 가능한 프로젝트가 없습니다/)).toBeInTheDocument(),
    )
    expect(searchedProjectKeys).toHaveLength(0)
    // B4 — SearchPage·SaveFilterDialog·ExportDialog 3소비처 모두 non-nullable 계약이라
    // 어댑터가 조기 반환으로 흡수해야 한다. 툴바(저장·내보내기)도 함께 사라진다.
    expect(screen.queryByRole('button', { name: '현재 검색 저장' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '검색 결과 내보내기' })).not.toBeInTheDocument()
  })

  it('SA5 (E2): 프로젝트 목록 조회 실패 시 검색하지 않고 에러를 표시한다', async () => {
    useCaptureHandlers([
      http.get('/api/v1/projects', () => new HttpResponse(null, { status: 500 })),
    ])
    renderSearchAdapter('/search?q=text+~+%22a%22')

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(searchedProjectKeys).toHaveLength(0)
  })
})
