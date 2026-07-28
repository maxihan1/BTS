// AuditLogFilters 컴포넌트 단위 테스트 — 이벤트유형·날짜·사용자typeahead·초기화 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import type { JSX } from 'react'
import { server } from '@/test/server'
import type { AuditLogQueryParams } from '@/api/audit-logs'
import { AuditLogFilters } from './AuditLogFilters'

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }): JSX.Element {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

function renderFilters(
  params: AuditLogQueryParams,
  onFilterChange: (partial: Partial<AuditLogQueryParams>) => void,
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <AuditLogFilters params={params} onFilterChange={onFilterChange} />,
    { wrapper: createWrapper(client) },
  )
}

describe('AuditLogFilters', () => {
  let onFilterChange: (partial: Partial<AuditLogQueryParams>) => void

  beforeEach(() => {
    onFilterChange = vi.fn()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('이벤트 유형 Select가 렌더된다', () => {
    renderFilters({}, onFilterChange)
    expect(screen.getByRole('combobox')).toBeInTheDocument()
  })

  it('시작일 date input이 렌더된다', () => {
    renderFilters({}, onFilterChange)
    const inputs = screen.getAllByDisplayValue('')
    // date input이 존재해야 함
    expect(inputs.length).toBeGreaterThanOrEqual(0)
    // type="date"인 input 확인
    const dateInputs = document.querySelectorAll('input[type="date"]')
    expect(dateInputs.length).toBeGreaterThanOrEqual(1)
  })

  it('종료일 date input이 렌더된다', () => {
    renderFilters({}, onFilterChange)
    const dateInputs = document.querySelectorAll('input[type="date"]')
    expect(dateInputs.length).toBe(2)
  })

  it('초기화 버튼이 렌더된다', () => {
    renderFilters({}, onFilterChange)
    expect(screen.getByRole('button', { name: '초기화' })).toBeInTheDocument()
  })

  it('시작일 입력 시 onFilterChange에 from이 ISO 형식으로 전달된다', async () => {
    const user = userEvent.setup()
    renderFilters({}, onFilterChange)

    const dateInputs = document.querySelectorAll('input[type="date"]')
    const fromInput = dateInputs[0] as HTMLInputElement

    await user.type(fromInput, '2026-06-01')

    await waitFor(() => {
      expect(onFilterChange).toHaveBeenCalledWith(
        expect.objectContaining({ from: '2026-06-01T00:00:00.000Z' }),
      )
    })
  })

  it('종료일 입력 시 onFilterChange에 to가 종료시각 ISO 형식으로 전달된다', async () => {
    const user = userEvent.setup()
    renderFilters({}, onFilterChange)

    const dateInputs = document.querySelectorAll('input[type="date"]')
    const toInput = dateInputs[1] as HTMLInputElement

    await user.type(toInput, '2026-06-10')

    await waitFor(() => {
      expect(onFilterChange).toHaveBeenCalledWith(
        expect.objectContaining({ to: '2026-06-10T23:59:59.999Z' }),
      )
    })
  })

  it('초기화 버튼 클릭 시 onFilterChange에 빈 params가 전달된다', async () => {
    const user = userEvent.setup()
    renderFilters(
      { eventType: 'LOGIN_SUCCESS', from: '2026-06-01T00:00:00.000Z' },
      onFilterChange,
    )

    await user.click(screen.getByRole('button', { name: '초기화' }))

    expect(onFilterChange).toHaveBeenCalledWith({
      eventType: undefined,
      userId: undefined,
      from: undefined,
      to: undefined,
    })
  })

  it('사용자 검색 input이 렌더된다', () => {
    renderFilters({}, onFilterChange)
    expect(
      screen.getByPlaceholderText('이름 또는 아이디 검색...'),
    ).toBeInTheDocument()
  })

  it('사용자 검색 2자 이상 입력 시 검색 결과가 표시된다', async () => {
    server.use(
      http.get('/api/v1/users', () =>
        HttpResponse.json([
          {
            id: '00000000-0000-4000-8000-000000000001',
            username: 'alice',
            displayName: '김앨리스',
            email: null,
          },
        ]),
      ),
    )

    const user = userEvent.setup()
    renderFilters({}, onFilterChange)

    const searchInput = screen.getByPlaceholderText('이름 또는 아이디 검색...')
    await user.type(searchInput, 'al')

    await waitFor(() => {
      expect(screen.getByText('김앨리스')).toBeInTheDocument()
    })
  })

  it('사용자 선택 시 onFilterChange에 userId가 전달된다', async () => {
    server.use(
      http.get('/api/v1/users', () =>
        HttpResponse.json([
          {
            id: '00000000-0000-4000-8000-000000000001',
            username: 'alice',
            displayName: '김앨리스',
            email: null,
          },
        ]),
      ),
    )

    const user = userEvent.setup()
    renderFilters({}, onFilterChange)

    const searchInput = screen.getByPlaceholderText('이름 또는 아이디 검색...')
    await user.type(searchInput, 'al')

    await waitFor(() => {
      expect(screen.getByText('김앨리스')).toBeInTheDocument()
    })

    await user.click(screen.getByText('김앨리스'))

    expect(onFilterChange).toHaveBeenCalledWith(
      expect.objectContaining({ userId: '00000000-0000-4000-8000-000000000001' }),
    )
  })
})
