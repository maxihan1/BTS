// IssueScheduleFields 컴포넌트 단위 테스트 — 시작일·마감일·목표일 편집 + updateIssue 호출 검증
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { ReactNode, JSX } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import * as issuesApi from '@/api/issues'
import { IssueScheduleFields } from './IssueScheduleFields'

// updateIssue 모킹 — 실제 API 호출 없이 캡처만
vi.mock('@/api/issues', async (importOriginal) => {
  const actual = await importOriginal<typeof issuesApi>()
  return {
    ...actual,
    updateIssue: vi.fn(),
  }
})

/** QueryClientProvider wrapper 팩토리 — 테스트마다 독립된 캐시 보장 */
function makeWrapper(): ({ children }: { children: ReactNode }) => JSX.Element {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

/** 테스트용 최소 이슈 픽스처 */
const makeIssue = (overrides?: Partial<issuesApi.IssueResponse>): issuesApi.IssueResponse => ({
  key: 'ATLAS-1',
  id: '00000000-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  summary: '테스트 이슈',
  currentStateKey: 'OPEN',
  reporterId: '00000000-0000-4000-8000-000000000002',
  assigneeId: null,
  componentIds: [],
  version: 1,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  typeId: 1,
  typeKey: 'BUG',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  resolution: undefined,
  securityLevelId: undefined,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
  affectsVersionIds: [],
  fixVersionIds: [],
  parent: undefined,
  startDate: null,
  dueDate: null,
  targetDate: null,
  ...overrides,
})

describe('IssueScheduleFields', () => {
  const mockUpdateIssue = vi.mocked(issuesApi.updateIssue)

  beforeEach(() => {
    mockUpdateIssue.mockClear()
    mockUpdateIssue.mockResolvedValue(makeIssue())
  })

  // ── 렌더 ───────────────────────────────────────────────────────────────────

  it('시작일·마감일·목표일 3개 날짜 입력 필드를 렌더한다', () => {
    const issue = makeIssue()
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    expect(screen.getByLabelText('시작일')).toBeInTheDocument()
    expect(screen.getByLabelText('마감일')).toBeInTheDocument()
    expect(screen.getByLabelText('목표일')).toBeInTheDocument()
  })

  it('input[type=date] 네이티브 입력을 사용한다', () => {
    const issue = makeIssue()
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    // date input은 role=textbox 대신 직접 type 확인
    const startDateInput = screen.getByLabelText('시작일')
    const dueDateInput = screen.getByLabelText('마감일')
    const targetDateInput = screen.getByLabelText('목표일')

    expect(startDateInput).toHaveAttribute('type', 'date')
    expect(dueDateInput).toHaveAttribute('type', 'date')
    expect(targetDateInput).toHaveAttribute('type', 'date')
  })

  it('기존 날짜값을 input에 표시한다', () => {
    const issue = makeIssue({
      startDate: '2026-06-01',
      dueDate: '2026-06-30',
      targetDate: '2026-07-15',
    })
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    expect(screen.getByLabelText('시작일')).toHaveValue('2026-06-01')
    expect(screen.getByLabelText('마감일')).toHaveValue('2026-06-30')
    expect(screen.getByLabelText('목표일')).toHaveValue('2026-07-15')
  })

  // ── 날짜 선택 → updateIssue 호출 ──────────────────────────────────────────

  it('시작일 선택 후 저장하면 updateIssue({ startDate: "yyyy-MM-dd", ... })를 호출한다', async () => {
    const user = userEvent.setup()
    const issue = makeIssue({ version: 5 })
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    const startDateInput = screen.getByLabelText('시작일')
    await user.clear(startDateInput)
    await user.type(startDateInput, '2026-06-19')

    const saveButton = screen.getByTestId('schedule-save')
    await user.click(saveButton)

    await waitFor(() => {
      expect(mockUpdateIssue).toHaveBeenCalledWith(
        'ATLAS-1',
        expect.objectContaining({
          startDate: '2026-06-19',
          expectedVersion: 5,
        }),
      )
    })
  })

  it('마감일 선택 후 저장하면 updateIssue({ dueDate: "yyyy-MM-dd", ... })를 호출한다', async () => {
    const user = userEvent.setup()
    const issue = makeIssue({ version: 3 })
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    const dueDateInput = screen.getByLabelText('마감일')
    await user.clear(dueDateInput)
    await user.type(dueDateInput, '2026-07-31')

    const saveButton = screen.getByTestId('schedule-save')
    await user.click(saveButton)

    await waitFor(() => {
      expect(mockUpdateIssue).toHaveBeenCalledWith(
        'ATLAS-1',
        expect.objectContaining({
          dueDate: '2026-07-31',
          expectedVersion: 3,
        }),
      )
    })
  })

  // ── C2 — 비우기 시 키 존재 + 값 null (키 생략 아님) ──────────────────────

  it('기존 마감일을 비우면 updateIssue 호출 시 dueDate 키가 존재하고 값이 null이다', async () => {
    const user = userEvent.setup()
    const issue = makeIssue({ dueDate: '2026-06-30', version: 2 })
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    // jsdom의 input[type=date]는 user.clear()가 실제 비우기 동작을 안 함 — fireEvent.change로 직접 '' 전달
    const dueDateInput = screen.getByLabelText('마감일')
    fireEvent.change(dueDateInput, { target: { value: '' } })

    const saveButton = screen.getByTestId('schedule-save')
    await user.click(saveButton)

    await waitFor(() => {
      expect(mockUpdateIssue).toHaveBeenCalledWith(
        'ATLAS-1',
        expect.objectContaining({
          expectedVersion: 2,
        }),
      )
      const callArg = mockUpdateIssue.mock.calls[0]?.[1]
      expect(callArg).toBeDefined()
      // 키가 존재하고 값이 null이어야 한다 (키 생략 아님 — 키 생략=무변경이지만 null=클리어)
      expect(Object.prototype.hasOwnProperty.call(callArg, 'dueDate')).toBe(true)
      expect(callArg?.dueDate).toBeNull()
    })
  })

  it('기존 시작일을 비우면 updateIssue 호출 시 startDate 키가 존재하고 값이 null이다', async () => {
    const user = userEvent.setup()
    const issue = makeIssue({ startDate: '2026-05-01', version: 4 })
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    // jsdom의 input[type=date]는 user.clear()가 실제 비우기 동작을 안 함 — fireEvent.change로 직접 '' 전달
    const startDateInput = screen.getByLabelText('시작일')
    fireEvent.change(startDateInput, { target: { value: '' } })

    const saveButton = screen.getByTestId('schedule-save')
    await user.click(saveButton)

    await waitFor(() => {
      const callArg = mockUpdateIssue.mock.calls[0]?.[1]
      expect(callArg).toBeDefined()
      expect(Object.prototype.hasOwnProperty.call(callArg, 'startDate')).toBe(true)
      expect(callArg?.startDate).toBeNull()
    })
  })

  it('기존 목표일을 비우면 updateIssue 호출 시 targetDate 키가 존재하고 값이 null이다', async () => {
    const user = userEvent.setup()
    const issue = makeIssue({ targetDate: '2026-08-15', version: 7 })
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    // jsdom의 input[type=date]는 user.clear()가 실제 비우기 동작을 안 함 — fireEvent.change로 직접 '' 전달
    const targetDateInput = screen.getByLabelText('목표일')
    fireEvent.change(targetDateInput, { target: { value: '' } })

    const saveButton = screen.getByTestId('schedule-save')
    await user.click(saveButton)

    await waitFor(() => {
      const callArg = mockUpdateIssue.mock.calls[0]?.[1]
      expect(callArg).toBeDefined()
      expect(Object.prototype.hasOwnProperty.call(callArg, 'targetDate')).toBe(true)
      expect(callArg?.targetDate).toBeNull()
    })
  })

  // ── 3필드 동시 저장 ────────────────────────────────────────────────────────

  it('3필드 모두 입력 후 저장하면 3필드를 포함한 updateIssue를 호출한다', async () => {
    const user = userEvent.setup()
    const issue = makeIssue({ version: 1 })
    render(<IssueScheduleFields issue={issue} />, { wrapper: makeWrapper() })

    await user.type(screen.getByLabelText('시작일'), '2026-06-01')
    await user.type(screen.getByLabelText('마감일'), '2026-06-30')
    await user.type(screen.getByLabelText('목표일'), '2026-07-15')

    await user.click(screen.getByTestId('schedule-save'))

    await waitFor(() => {
      expect(mockUpdateIssue).toHaveBeenCalledWith(
        'ATLAS-1',
        expect.objectContaining({
          startDate: '2026-06-01',
          dueDate: '2026-06-30',
          targetDate: '2026-07-15',
          expectedVersion: 1,
        }),
      )
    })
  })
})
