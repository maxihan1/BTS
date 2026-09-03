// FR-CA-01 캘린더 월/주 뷰 컴포넌트 테스트 — 그리드 렌더/이벤트 배치/네비/전역 nav 진입점 (Task 7)
import type { ReactNode } from 'react'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { CalendarResponse } from '@/api/calendar'
import { calendarLabels } from '@/i18n/calendar-labels'
import { CalendarView } from './CalendarView'
import { useIssueDetailModalStore } from '@/components/issue/issueDetailModalStore'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router 모킹 — 라우터 컨텍스트 없이 단위 테스트 가능 (Header.test.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  Link: ({
    to,
    params,
    children,
    className,
  }: {
    to: string
    params?: Record<string, string>
    children: ReactNode
    className?: string
  }) => {
    let href = to
    if (params !== undefined) {
      for (const [token, value] of Object.entries(params)) {
        href = href.replace(`$${token}`, value)
      }
    }
    return (
      <a href={href} className={className}>
        {children}
      </a>
    )
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

/** 2026-07-08(수) 고정 — MSW 시드(2026-07 이벤트)와 정합되는 결정론적 기준일 */
const FIXED_TODAY = new Date(2026, 6, 8)

function renderCalendar(initialDate: Date = FIXED_TODAY) {
  return render(<CalendarView initialDate={initialDate} />, { wrapper: createWrapper() })
}

// ─────────────────────────────────────────────────────────────────────────────
// 시드 픽스처 — MSW calendar-handlers.ts(Task 6) 기본 시드와 동일 값
// ─────────────────────────────────────────────────────────────────────────────

const SEED_ISSUE_EVENTS: CalendarResponse['issueEvents'] = [
  {
    key: 'ATLAS-12',
    summary: '결제 모듈 리팩터링',
    issueType: 'task',
    currentStateKey: 'in_progress',
    startDate: '2026-07-03',
    dueDate: '2026-07-10',
  },
  {
    key: 'ATLAS-30',
    summary: '릴리스 노트',
    issueType: 'task',
    currentStateKey: 'todo',
    startDate: null,
    dueDate: '2026-07-25',
  },
]

const SEED_WORKLOG_EVENTS: CalendarResponse['worklogEvents'] = [
  {
    id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    issueKey: 'ATLAS-12',
    issueSummary: '결제 모듈 리팩터링',
    date: '2026-07-05',
    timeSpentSeconds: 10800,
  },
]

/** GET /api/v1/users/me/calendar 핸들러를 override한다 — overrides로 이벤트 목록을 대체 가능 */
function stubCalendar(overrides?: Partial<Pick<CalendarResponse, 'issueEvents' | 'worklogEvents'>>) {
  server.use(
    http.get('/api/v1/users/me/calendar', ({ request }) => {
      const url = new URL(request.url)
      const from = url.searchParams.get('from') ?? ''
      const to = url.searchParams.get('to') ?? ''
      const response: CalendarResponse = {
        from,
        to,
        timezone: 'Asia/Seoul',
        issueEvents: overrides?.issueEvents ?? SEED_ISSUE_EVENTS,
        worklogEvents: overrides?.worklogEvents ?? SEED_WORKLOG_EVENTS,
        truncated: false,
      }
      return HttpResponse.json(response)
    }),
  )
}

/** 날짜 문자열("2026-07-10")로 grid cell(role=gridcell)을 찾는다 — data-date 속성 기반 */
function findCellByDate(container: HTMLElement, dateStr: string): HTMLElement {
  const cell = container.querySelector<HTMLElement>(`[data-date="${dateStr}"]`)
  if (cell === null) {
    throw new Error(`gridcell not found for date ${dateStr}`)
  }
  return cell
}

beforeEach(() => {
  // 모달 스토어는 모듈 전역이라 테스트 간에 새지 않도록 매번 닫는다.
  useIssueDetailModalStore.setState({ openKey: null })
  mockNavigate.mockReset()
  stubCalendar()
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. 월 뷰 6주(42셀) 그리드
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarView — S1 월 뷰 그리드', () => {
  it('S1a: 42개 gridcell(6주×7일)을 렌더한다', async () => {
    renderCalendar()
    const cells = await screen.findAllByRole('gridcell')
    expect(cells).toHaveLength(42)
  })

  it('S1b: 요일 헤더 7개(월~일)를 렌더한다', async () => {
    renderCalendar()
    await screen.findAllByRole('gridcell')
    expect(screen.getAllByRole('columnheader')).toHaveLength(7)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 주 뷰 7일 그리드
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarView — S2 주 뷰 그리드', () => {
  it('S2a: "주" 토글 클릭 시 7개 gridcell을 렌더한다', async () => {
    const user = userEvent.setup()
    renderCalendar()
    await screen.findAllByRole('gridcell')

    await user.click(screen.getByRole('button', { name: calendarLabels.toolbar.weekView }))

    const cells = await screen.findAllByRole('gridcell')
    expect(cells).toHaveLength(7)
  })

  it('S2b: 주 뷰에서 "월" 토글 클릭 시 다시 42개 gridcell로 돌아온다', async () => {
    const user = userEvent.setup()
    renderCalendar()
    await screen.findAllByRole('gridcell')
    await user.click(screen.getByRole('button', { name: calendarLabels.toolbar.weekView }))
    await screen.findAllByRole('gridcell')

    await user.click(screen.getByRole('button', { name: calendarLabels.toolbar.monthView }))

    const cells = await screen.findAllByRole('gridcell')
    expect(cells).toHaveLength(42)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 이벤트가 올바른 날짜 셀에 배치
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarView — S3 이벤트 배치', () => {
  it('S3a: 마감일(2026-07-10) 셀에 ATLAS-12가 배치된다', async () => {
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-10')
    expect(within(cell).getByText(/ATLAS-12/)).toBeInTheDocument()
  })

  it('S3b: 시작(2026-07-03)~마감(2026-07-10) 구간의 모든 날짜에 걸쳐 막대가 표시된다', async () => {
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    for (const dateStr of ['2026-07-03', '2026-07-05', '2026-07-08', '2026-07-10']) {
      const cell = findCellByDate(container, dateStr)
      // 2026-07-05는 같은 날 Worklog 칩도 렌더돼 "ATLAS-12" 매치가 2건일 수 있으므로 getAllByText 사용
      expect(within(cell).getAllByText(/ATLAS-12/).length).toBeGreaterThan(0)
    }
  })

  it('S3c: 구간 밖 날짜(2026-07-02)에는 ATLAS-12가 없다', async () => {
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-02')
    expect(within(cell).queryByText(/ATLAS-12/)).not.toBeInTheDocument()
  })

  it('S3d: 시작일 없이 마감일만 있는 이슈(ATLAS-30)는 2026-07-25 셀에 마감일 칩으로 표시된다', async () => {
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-25')
    expect(within(cell).getByText('ATLAS-30')).toBeInTheDocument()
  })

  it('S3e: Worklog 이벤트(2026-07-05)가 해당 날짜 셀에 표시된다', async () => {
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-05')
    // 2026-07-05는 ATLAS-12 기간 막대(중간 날짜)와 Worklog 칩이 함께 렌더되므로 2건 매치돼야 한다
    expect(within(cell).getAllByText(/ATLAS-12/)).toHaveLength(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 이슈 이벤트 클릭 → /issues/{key} 네비게이션
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarView — S4 이슈 클릭 네비게이션', () => {
  it('S4a: 이슈 기간 막대 클릭 시 /issues/{key}로 이동한다', async () => {
    const user = userEvent.setup()
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-03')
    await user.click(within(cell).getByText(/ATLAS-12/))

    expect(useIssueDetailModalStore.getState().openKey).toBe('ATLAS-12')
  })

  it('S4b: 마감일 칩 클릭 시 /issues/{key}로 이동한다', async () => {
    const user = userEvent.setup()
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-25')
    await user.click(within(cell).getByText('ATLAS-30'))

    expect(useIssueDetailModalStore.getState().openKey).toBe('ATLAS-30')
  })

  it('S4c: 이슈 막대에서 Enter 키 입력 시 /issues/{key}로 이동한다', async () => {
    const user = userEvent.setup()
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-03')
    const bar = within(cell).getByRole('button', { name: /ATLAS-12/ })
    bar.focus()
    await user.keyboard('{Enter}')

    expect(useIssueDetailModalStore.getState().openKey).toBe('ATLAS-12')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. from/to → useCalendar 조회창 갱신
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarView — S5 조회창(from/to) 전달·갱신', () => {
  it('S5a: 월 뷰는 42일 그리드 전체(from~to)를 조회창으로 사용한다', async () => {
    let capturedFrom = ''
    let capturedTo = ''
    server.use(
      http.get('/api/v1/users/me/calendar', ({ request }) => {
        const url = new URL(request.url)
        capturedFrom = url.searchParams.get('from') ?? ''
        capturedTo = url.searchParams.get('to') ?? ''
        return HttpResponse.json({
          from: capturedFrom,
          to: capturedTo,
          timezone: 'Asia/Seoul',
          issueEvents: SEED_ISSUE_EVENTS,
          worklogEvents: SEED_WORKLOG_EVENTS,
          truncated: false,
        })
      }),
    )

    renderCalendar()
    await screen.findAllByRole('gridcell')

    expect(capturedFrom).toBe('2026-06-29')
    expect(capturedTo).toBe('2026-08-09')
  })

  it('S5b: "다음" 클릭 시 from/to가 다음 달 조회창으로 갱신된다', async () => {
    let capturedFrom = ''
    server.use(
      http.get('/api/v1/users/me/calendar', ({ request }) => {
        const url = new URL(request.url)
        capturedFrom = url.searchParams.get('from') ?? ''
        return HttpResponse.json({
          from: capturedFrom,
          to: url.searchParams.get('to') ?? '',
          timezone: 'Asia/Seoul',
          issueEvents: [],
          worklogEvents: [],
          truncated: false,
        })
      }),
    )
    const user = userEvent.setup()
    renderCalendar()
    await screen.findAllByRole('gridcell')
    await waitFor(() => expect(capturedFrom).toBe('2026-06-29'))

    await user.click(screen.getByRole('button', { name: calendarLabels.toolbar.next }))

    await waitFor(() => expect(capturedFrom).toBe('2026-07-27'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarView — S6 빈 상태', () => {
  it('S6a: 이벤트가 0건이면 안내 메시지를 표시한다', async () => {
    stubCalendar({ issueEvents: [], worklogEvents: [] })
    renderCalendar()

    expect(await screen.findByText(calendarLabels.empty.message)).toBeInTheDocument()
  })

  it('S6b: 빈 상태에서도 그리드(42셀)는 계속 렌더된다', async () => {
    stubCalendar({ issueEvents: [], worklogEvents: [] })
    renderCalendar()

    await screen.findByText(calendarLabels.empty.message)
    expect(screen.getAllByRole('gridcell')).toHaveLength(42)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. Worklog issueSummary=null 마스킹
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarView — S7 Worklog 마스킹(issueSummary=null)', () => {
  it('S7a: issueSummary가 null이면 이슈 키만 표시된다', async () => {
    stubCalendar({
      issueEvents: [],
      worklogEvents: [
        {
          id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
          issueKey: 'ATLAS-99',
          issueSummary: null,
          date: '2026-07-05',
          timeSpentSeconds: 3600,
        },
      ],
    })
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-05')
    expect(within(cell).getByText(/ATLAS-99/)).toBeInTheDocument()
    expect(within(cell).getByLabelText('ATLAS-99, 비공개 이슈')).toBeInTheDocument()
  })

  it('S7b: issueSummary가 null인 Worklog 칩은 클릭 가능한 button role이 아니다(이동 불가)', async () => {
    stubCalendar({
      issueEvents: [],
      worklogEvents: [
        {
          id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
          issueKey: 'ATLAS-99',
          issueSummary: null,
          date: '2026-07-05',
          timeSpentSeconds: 3600,
        },
      ],
    })
    const { container } = renderCalendar()
    await screen.findAllByRole('gridcell')

    const cell = findCellByDate(container, '2026-07-05')
    expect(within(cell).queryByRole('button')).not.toBeInTheDocument()
  })
})

// S8(★C3, 전역 nav 캘린더 진입점)은 Header 삭제(FR-UX-06 PR11 Task 7)로 Sidebar가 흡수했다 —
// 커버리지는 Sidebar.test.tsx(FR3)·navigation-contract.test.tsx로 이관됐다(중복 제거, 최소 변경).
