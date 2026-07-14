// RuleExecutionHistoryDialog 단위 테스트 — 목록 조회/필터/더보기/재실행 통합(토스트+자동펼침)·상태 리셋 (FR-AT-05 D6/D7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import { RuleExecutionHistoryDialog } from './RuleExecutionHistoryDialog'
import type { RuleExecutionHistoryDialogProps } from './RuleExecutionHistoryDialog'
import type { RuleExecutionSummary, RuleExecutionDetail } from '@/api/automation-executions.types'

vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — useAutomationExecutions.test.tsx / RuleExecutionTraceRow.test.tsx와 동형 v4 UUID
// (Zod v4 uuid() 검증 통과 형식)
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const RULE_ID = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e'
const OTHER_RULE_ID = 'c3d4e5f6-a7b8-4c9d-8e0f-2a3b4c5d6e7f'
const RULE_NAME = '테스트 룰'

/** n을 4자리 hex로 넣어 서로 다른 v4 형식 UUID를 생성 — 버전(4)/변형(8) 니블은 고정 */
function uuidFromIndex(n: number): string {
  const segment = n.toString(16).padStart(4, '0')
  return `aaaaaaaa-${segment}-4aaa-8aaa-aaaaaaaaaaaa`
}

const EXEC_A_ID = uuidFromIndex(1)
const EXEC_B_ID = uuidFromIndex(2)
const REPLAY_SOURCE_ID = uuidFromIndex(3)
const REPLAYED_ID = uuidFromIndex(4)
const UNAVAILABLE_SOURCE_ID = uuidFromIndex(5)

function buildSummary(overrides: Partial<RuleExecutionSummary> = {}): RuleExecutionSummary {
  return {
    id: EXEC_A_ID,
    ruleId: RULE_ID,
    triggerType: 'ISSUE_CREATED',
    issueKey: 'ATLAS-100',
    status: 'SUCCESS',
    actionCount: 1,
    successCount: 1,
    startedAt: '2026-07-10T09:00:00Z',
    finishedAt: '2026-07-10T09:00:01Z',
    replayedFrom: null,
    ...overrides,
  }
}

const EXEC_A = buildSummary({ id: EXEC_A_ID, issueKey: 'ATLAS-100', startedAt: '2026-07-10T09:05:00Z' })
const EXEC_B = buildSummary({ id: EXEC_B_ID, issueKey: 'ATLAS-200', startedAt: '2026-07-10T09:00:00Z' })

const REPLAY_SOURCE = buildSummary({ id: REPLAY_SOURCE_ID, issueKey: 'ATLAS-300', startedAt: '2026-07-10T08:00:00Z' })
const UNAVAILABLE_SOURCE = buildSummary({
  id: UNAVAILABLE_SOURCE_ID,
  issueKey: 'ATLAS-400',
  startedAt: '2026-07-10T07:00:00Z',
})

function buildDetail(overrides: Partial<RuleExecutionDetail> = {}): RuleExecutionDetail {
  return {
    id: REPLAY_SOURCE_ID,
    ruleId: RULE_ID,
    projectKey: PROJECT_KEY,
    triggerType: 'ISSUE_CREATED',
    triggerEvent: { issueKey: 'ATLAS-300', type: 'ISSUE_CREATED' },
    issueKey: 'ATLAS-300',
    status: 'SUCCESS',
    outcomes: [{ position: 0, actionType: 'SET_FIELD', success: true, error: null }],
    replayedFrom: null,
    startedAt: '2026-07-10T08:00:00Z',
    finishedAt: '2026-07-10T08:00:01Z',
    ...overrides,
  }
}

const REPLAY_SOURCE_DETAIL = buildDetail()
const UNAVAILABLE_SOURCE_DETAIL = buildDetail({ id: UNAVAILABLE_SOURCE_ID, issueKey: 'ATLAS-400' })
const REPLAYED_DETAIL = buildDetail({
  id: REPLAYED_ID,
  status: 'PARTIAL',
  outcomes: [{ position: 0, actionType: 'ADD_COMMENT', success: true, error: null }],
  replayedFrom: REPLAY_SOURCE_ID,
  startedAt: '2026-07-10T11:00:00Z',
  finishedAt: '2026-07-10T11:00:02Z',
})

// 더보기(페이지네이션) 전용 대량 fixture — useAutomationExecutions.test.tsx 선례 동형
const PAGE_LIMIT = 50
const PAGED_TOTAL = 51
const PAGED_BASE_TIME_MS = Date.parse('2026-07-10T10:00:00Z')

function buildPagedExecution(index: number): RuleExecutionSummary {
  return buildSummary({
    id: uuidFromIndex(1000 + index),
    issueKey: `ATLAS-${500 + index}`,
    startedAt: new Date(PAGED_BASE_TIME_MS - index * 60_000).toISOString(),
    finishedAt: new Date(PAGED_BASE_TIME_MS - index * 60_000 + 1_000).toISOString(),
  })
}

const PAGED_EXECUTIONS = Array.from({ length: PAGED_TOTAL }, (_, index) => buildPagedExecution(index))

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — 전역 `@/test/server`에 server.use()로 인라인 등록(로컬 setupServer 금지,
// RuleExecutionTraceRow.test.tsx/useAutomationExecutions.test.tsx 선례 — 이중 인스턴스 시 요청이
// 중복 dispatch됨).
// ─────────────────────────────────────────────────────────────────────────────

function registerDefaultHandlers(): void {
  server.use(
    http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', ({ request }) => {
      const url = new URL(request.url)
      const issueKeyParam = url.searchParams.get('issueKey')
      if (issueKeyParam === EXEC_B.issueKey) {
        return HttpResponse.json([EXEC_B])
      }
      if (issueKeyParam !== null) {
        return HttpResponse.json([])
      }
      return HttpResponse.json([EXEC_A, EXEC_B])
    }),
    http.get('/api/v1/automation/executions/:id', ({ params }) => {
      if (params['id'] === REPLAY_SOURCE_ID) return HttpResponse.json(REPLAY_SOURCE_DETAIL)
      if (params['id'] === REPLAYED_ID) return HttpResponse.json(REPLAYED_DETAIL)
      if (params['id'] === UNAVAILABLE_SOURCE_ID) return HttpResponse.json(UNAVAILABLE_SOURCE_DETAIL)
      return HttpResponse.json({ errorCode: 'AUTOMATION_EXECUTION_NOT_FOUND' }, { status: 404 })
    }),
    http.post('/api/v1/automation/executions/:id/replay', ({ params }) => {
      if (params['id'] === REPLAY_SOURCE_ID) return HttpResponse.json(REPLAYED_DETAIL)
      if (params['id'] === UNAVAILABLE_SOURCE_ID) {
        return HttpResponse.json({ errorCode: 'AUTOMATION_RULE_UNAVAILABLE' }, { status: 409 })
      }
      return HttpResponse.json({ errorCode: 'AUTOMATION_EXECUTION_NOT_FOUND' }, { status: 404 })
    }),
  )
}

beforeEach(() => {
  registerDefaultHandlers()
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})

afterEach(() => {
  vi.clearAllMocks()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼 — rerenderWith로 props 변경(리셋 검증)까지 지원
// ─────────────────────────────────────────────────────────────────────────────

function buildProps(overrides: Partial<RuleExecutionHistoryDialogProps>): RuleExecutionHistoryDialogProps {
  return {
    open: overrides.open ?? true,
    onOpenChange: overrides.onOpenChange ?? vi.fn(),
    projectKey: overrides.projectKey ?? PROJECT_KEY,
    ruleId: overrides.ruleId === undefined ? RULE_ID : overrides.ruleId,
    ruleName: overrides.ruleName === undefined ? RULE_NAME : overrides.ruleName,
  }
}

function renderDialog(overrides: Partial<RuleExecutionHistoryDialogProps> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const initialProps = buildProps(overrides)

  const utils = render(
    <QueryClientProvider client={queryClient}>
      <RuleExecutionHistoryDialog {...initialProps} />
    </QueryClientProvider>,
  )

  function rerenderWith(nextOverrides: Partial<RuleExecutionHistoryDialogProps>): RuleExecutionHistoryDialogProps {
    const nextProps = buildProps({ ...initialProps, ...nextOverrides })
    utils.rerender(
      <QueryClientProvider client={queryClient}>
        <RuleExecutionHistoryDialog {...nextProps} />
      </QueryClientProvider>,
    )
    return nextProps
  }

  return { ...utils, rerenderWith, onOpenChange: initialProps.onOpenChange }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('RuleExecutionHistoryDialog', () => {
  it('open 시 목록을 조회해 제목과 함께 렌더한다', async () => {
    renderDialog()

    expect(screen.getByText(`${RULE_NAME} 실행 이력`)).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('ATLAS-100')).toBeInTheDocument()
    })
    expect(screen.getByText('ATLAS-200')).toBeInTheDocument()
  })

  it('실행 이력이 없으면 빈 상태 문구를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', () => HttpResponse.json([])),
    )
    renderDialog()

    await waitFor(() => {
      expect(screen.getByText('실행 이력이 없습니다.')).toBeInTheDocument()
    })
  })

  it('로딩 중에는 로딩 상태를 표시한다', async () => {
    let resolveList: (() => void) | undefined
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', async () => {
        await new Promise<void>((resolve) => {
          resolveList = resolve
        })
        return HttpResponse.json([EXEC_A, EXEC_B])
      }),
    )
    renderDialog()

    expect(screen.getByRole('status')).toBeInTheDocument()

    resolveList?.()

    await waitFor(() => {
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
  })

  it('403(AUTOMATION_ACCESS_DENIED)이면 권한 없음 메시지를 표시한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_ACCESS_DENIED' }, { status: 403 })),
    )
    renderDialog()

    await waitFor(() => {
      expect(screen.getByText('권한이 없습니다.')).toBeInTheDocument()
    })
  })

  it('issueKey 필터 입력 후 Enter를 누르면 필터된 목록으로 재조회한다', async () => {
    const user = userEvent.setup()
    renderDialog()

    await waitFor(() => {
      expect(screen.getByText('ATLAS-100')).toBeInTheDocument()
    })

    const filterInput = screen.getByLabelText('이슈 키 필터')
    await user.type(filterInput, 'ATLAS-200{Enter}')

    await waitFor(() => {
      expect(screen.queryByText('ATLAS-100')).not.toBeInTheDocument()
    })
    expect(screen.getByText('ATLAS-200')).toBeInTheDocument()
  })

  it('더 보기 클릭 시 다음 페이지를 누적하고 마지막 페이지면 버튼이 사라진다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', ({ request }) => {
        const url = new URL(request.url)
        const before = url.searchParams.get('before')
        if (before === null) {
          return HttpResponse.json(PAGED_EXECUTIONS.slice(0, PAGE_LIMIT))
        }
        const cursorIndex = PAGED_EXECUTIONS.findIndex((item) => item.startedAt === before)
        const startIndex = cursorIndex === -1 ? PAGED_EXECUTIONS.length : cursorIndex + 1
        return HttpResponse.json(PAGED_EXECUTIONS.slice(startIndex, startIndex + PAGE_LIMIT))
      }),
    )
    const user = userEvent.setup()
    renderDialog()

    await waitFor(() => {
      expect(screen.getAllByRole('listitem')).toHaveLength(PAGE_LIMIT)
    })

    await user.click(screen.getByRole('button', { name: '더 보기' }))

    await waitFor(() => {
      expect(screen.getAllByRole('listitem')).toHaveLength(PAGED_TOTAL)
    })
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('재실행 성공 시 토스트를 띄우고 새 실행을 목록 맨 위에 자동 펼침 상태로 추가한다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', () =>
        HttpResponse.json([REPLAY_SOURCE])),
    )
    const user = userEvent.setup()
    renderDialog()

    await waitFor(() => {
      expect(screen.getByText('ATLAS-300')).toBeInTheDocument()
    })

    const toggle = screen.getByRole('button', { expanded: false })
    await user.click(toggle)
    await waitFor(() => {
      expect(screen.getByText('SET_FIELD')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '재실행' }))
    await user.click(screen.getByRole('button', { name: '확정' }))

    await waitFor(() => {
      expect(toast.success).toHaveBeenCalledWith('재실행이 완료되었습니다.')
    })
    // 새로 prepend된 실행이 자동 펼침되어 별도 클릭 없이 outcomes가 보인다.
    await waitFor(() => {
      expect(screen.getByText('ADD_COMMENT')).toBeInTheDocument()
    })
    expect(screen.getByText('재실행됨')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })

  it('재실행이 409(AUTOMATION_RULE_UNAVAILABLE)로 실패하면 전용 토스트를 띄우고 목록은 변하지 않는다', async () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/automation/rules/:ruleId/executions', () =>
        HttpResponse.json([UNAVAILABLE_SOURCE])),
    )
    const user = userEvent.setup()
    renderDialog()

    await waitFor(() => {
      expect(screen.getByText('ATLAS-400')).toBeInTheDocument()
    })

    const toggle = screen.getByRole('button', { expanded: false })
    await user.click(toggle)
    await waitFor(() => {
      expect(screen.getByText('SET_FIELD')).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '재실행' }))
    await user.click(screen.getByRole('button', { name: '확정' }))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith('재실행 대상 자동화 룰을 더 이상 사용할 수 없습니다')
    })
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('Dialog를 닫았다가 다시 열면 필터 입력이 초기화된다', async () => {
    const { rerenderWith } = renderDialog()
    const user = userEvent.setup()

    const filterInput = screen.getByLabelText('이슈 키 필터')
    await user.type(filterInput, 'ATLAS-999')
    expect(filterInput).toHaveValue('ATLAS-999')

    rerenderWith({ open: false })
    expect(screen.queryByTestId('rule-execution-history-dialog')).not.toBeInTheDocument()

    rerenderWith({ open: true })
    await waitFor(() => {
      expect(screen.getByTestId('rule-execution-history-dialog')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('이슈 키 필터')).toHaveValue('')
  })

  it('ruleId가 바뀌면 필터 입력이 초기화된다', async () => {
    const { rerenderWith } = renderDialog()
    const user = userEvent.setup()

    const filterInput = screen.getByLabelText('이슈 키 필터')
    await user.type(filterInput, 'ATLAS-999')
    expect(filterInput).toHaveValue('ATLAS-999')

    rerenderWith({ ruleId: OTHER_RULE_ID })

    await waitFor(() => {
      expect(screen.getByLabelText('이슈 키 필터')).toHaveValue('')
    })
  })

  it('ruleId가 null이면 아무것도 렌더하지 않는다', () => {
    const { container } = renderDialog({ ruleId: null })
    expect(container).toBeEmptyDOMElement()
  })
})
