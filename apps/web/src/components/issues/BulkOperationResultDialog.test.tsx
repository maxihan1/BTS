// 일괄 작업 결과 패널 Dialog 컴포넌트 단위 테스트 — 폴링·결과·에러 시나리오
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { BulkOperationResultDialog } from './BulkOperationResultDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BULK_ID = 'aaaabbbb-0000-4000-8000-000000000001'

const PENDING_RESPONSE = {
  data: {
    id: BULK_ID,
    operationType: 'BULK_EDIT',
    status: 'PENDING',
    payload: { priority: 2, impact: null },
    totalCount: 3,
    processedCount: 0,
    succeededCount: 0,
    failedCount: 0,
    items: [],
  },
}

const RUNNING_RESPONSE = {
  data: {
    id: BULK_ID,
    operationType: 'BULK_EDIT',
    status: 'RUNNING',
    payload: { priority: 2, impact: null },
    totalCount: 4,
    processedCount: 2,
    succeededCount: 2,
    failedCount: 0,
    items: [],
  },
}

const COMPLETED_RESPONSE = {
  data: {
    id: BULK_ID,
    operationType: 'BULK_EDIT',
    status: 'COMPLETED',
    payload: { priority: 2, impact: null },
    totalCount: 3,
    processedCount: 3,
    succeededCount: 2,
    failedCount: 1,
    items: [
      { issueKey: 'PROJ-1', status: 'SUCCEEDED', failureReasonCode: null },
      { issueKey: 'PROJ-2', status: 'SUCCEEDED', failureReasonCode: null },
      { issueKey: 'PROJ-3', status: 'FAILED', failureReasonCode: 'FORBIDDEN' },
    ],
  },
}

const FAILED_RESPONSE = {
  data: {
    id: BULK_ID,
    operationType: 'BULK_TRANSITION',
    status: 'FAILED',
    payload: { toStateKey: 'IN_PROGRESS' },
    totalCount: 2,
    processedCount: 2,
    succeededCount: 0,
    failedCount: 2,
    items: [
      { issueKey: 'PROJ-10', status: 'FAILED', failureReasonCode: 'TRANSITION_NOT_ALLOWED' },
      { issueKey: 'PROJ-11', status: 'FAILED', failureReasonCode: 'VERSION_CONFLICT' },
    ],
  },
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
}

interface RenderOptions {
  readonly bulkOperationId?: string | null
  readonly open?: boolean
  readonly onOpenChange?: (o: boolean) => void
}

function renderDialog(opts: RenderOptions = {}) {
  const {
    bulkOperationId = BULK_ID,
    open = true,
    onOpenChange = vi.fn(),
  } = opts

  const queryClient = makeQueryClient()

  render(
    <QueryClientProvider client={queryClient}>
      <BulkOperationResultDialog
        bulkOperationId={bulkOperationId}
        open={open}
        onOpenChange={onOpenChange}
      />
    </QueryClientProvider>,
  )

  return { onOpenChange, queryClient }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('BulkOperationResultDialog', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessToken: 'test-token', user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  // BORD-1: open=false면 폴링 미발생
  it('BORD-1: open=false이면 fetchBulkOperation을 호출하지 않는다', async () => {
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => {
        callCount++
        return HttpResponse.json(PENDING_RESPONSE)
      }),
    )

    renderDialog({ open: false })

    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(callCount).toBe(0)
  })

  // BORD-2: open=false면 Dialog가 화면에 보이지 않는다
  it('BORD-2: open=false이면 Dialog 콘텐츠가 DOM에 없다', () => {
    renderDialog({ open: false, bulkOperationId: BULK_ID })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  // BORD-3: bulkOperationId=null이면 폴링 미발생
  it('BORD-3: bulkOperationId=null이면 fetchBulkOperation을 호출하지 않는다', async () => {
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () => {
        callCount++
        return HttpResponse.json(PENDING_RESPONSE)
      }),
    )

    renderDialog({ bulkOperationId: null, open: true })

    await new Promise((resolve) => setTimeout(resolve, 100))
    expect(callCount).toBe(0)
  })

  // BORD-4: PENDING 상태에서 진행률 aria-live 표시
  it('BORD-4: PENDING 상태에서 진행률이 aria-live 영역에 표시된다', async () => {
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json(PENDING_RESPONSE),
      ),
    )

    renderDialog()

    await waitFor(() => {
      expect(screen.getByRole('status')).toBeInTheDocument()
    })
  })

  // BORD-5: RUNNING 상태에서 진행률 분수 표시 (2/4)
  it('BORD-5: RUNNING 상태에서 processedCount/totalCount 진행률이 표시된다', async () => {
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json(RUNNING_RESPONSE),
      ),
    )

    renderDialog()

    await waitFor(() => {
      expect(screen.getByText(/2\s*\/\s*4/)).toBeInTheDocument()
    }, { timeout: 10000 })
  }, 12000)

  // BORD-6: COMPLETED 후 성공/실패 카운트 표시
  it('BORD-6: COMPLETED 시 succeededCount와 failedCount가 표시된다', async () => {
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json(COMPLETED_RESPONSE),
      ),
    )

    renderDialog()

    await waitFor(() => {
      expect(screen.getByText(/성공.*2|2.*성공/)).toBeInTheDocument()
    })
    expect(screen.getByText(/실패.*1|1.*실패/)).toBeInTheDocument()
  })

  // BORD-7: COMPLETED 후 실패 이슈 목록에 issueKey와 failureReasonLabels 한국어 표시
  it('BORD-7: COMPLETED 시 실패 이슈 목록에 issueKey와 한국어 실패사유가 표시된다', async () => {
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json(COMPLETED_RESPONSE),
      ),
    )

    renderDialog()

    await waitFor(() => {
      expect(screen.getByText('PROJ-3')).toBeInTheDocument()
    })
    // FORBIDDEN → "권한 없음"
    expect(screen.getByText('권한 없음')).toBeInTheDocument()
  })

  // BORD-8: FAILED 상태에서 여러 실패 이슈 목록과 각 한국어 실패사유 표시
  it('BORD-8: FAILED 상태에서 여러 실패 이슈와 한국어 사유가 표시된다', async () => {
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json(FAILED_RESPONSE),
      ),
    )

    renderDialog()

    await waitFor(() => {
      expect(screen.getByText('PROJ-10')).toBeInTheDocument()
    })
    expect(screen.getByText('허용되지 않는 전이')).toBeInTheDocument()
    expect(screen.getByText('PROJ-11')).toBeInTheDocument()
    expect(screen.getByText('다른 요청이 먼저 수정함')).toBeInTheDocument()
  })

  // BORD-9: 폴링 에러(403) 시 에러 메시지 표시
  it('BORD-9: 폴링 에러(403) 시 에러 메시지가 표시된다', async () => {
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json({ status: 403, detail: '권한이 없습니다.' }, { status: 403 }),
      ),
    )

    renderDialog()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  // BORD-10: Dialog에 접근성 제목이 있다
  it('BORD-10: Dialog에 접근성 제목(DialogTitle)이 있다', async () => {
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_ID}`, () =>
        HttpResponse.json(PENDING_RESPONSE),
      ),
    )

    renderDialog()

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    const dialog = screen.getByRole('dialog')
    // DialogTitle이 있으면 aria-labelledby 또는 aria-label이 있어야 한다
    expect(
      dialog.hasAttribute('aria-labelledby') || dialog.hasAttribute('aria-label'),
    ).toBe(true)
  })
})
