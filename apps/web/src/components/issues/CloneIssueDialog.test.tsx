// CloneIssueDialog 컴포넌트 단위 테스트 — 렌더/옵션/submit/상태 초기화 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import { http, HttpResponse } from 'msw'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
import { server } from '@/test/server'
import { CloneIssueDialog } from './CloneIssueDialog'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'

// sonner toast mock
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// navigate mock
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

const clonedIssueFixture = {
  ...issueAtlas1Fixture,
  key: 'ATLAS-99',
  id: 'f1e2d3c4-b5a6-4789-8def-0123456789ab',
  version: 0,
}

function renderDialog(props: {
  issueKey?: string
  open?: boolean
  onOpenChange?: (o: boolean) => void
}) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const {
    issueKey = 'ATLAS-1',
    open = true,
    onOpenChange = vi.fn(),
  } = props

  return {
    queryClient,
    ...render(
      createElement(
        QueryClientProvider,
        { client: queryClient },
        createElement(CloneIssueDialog, { issueKey, open, onOpenChange }),
      ),
    ),
    onOpenChange,
  }
}

describe('CloneIssueDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    server.use(
      http.post('/api/v1/issues/:key/clone', () =>
        HttpResponse.json({ data: clonedIssueFixture }, { status: 201 }),
      ),
    )
  })

  it('흡수 후 우상단 X 닫기 버튼(Jira 시각 통일)이 렌더된다', () => {
    renderDialog({ open: true })

    // ui/dialog 래퍼로 흡수되면 DialogContent가 우상단 X(sr-only "Close")를 강제 렌더한다.
    expect(screen.getByRole('button', { name: /close/i })).toBeTruthy()
  })

  it('open=true일 때 Dialog 제목과 체크박스, 텍스트 입력이 렌더된다', () => {
    renderDialog({ open: true })

    expect(screen.getByText('이슈 클론')).toBeTruthy()
    expect(screen.getByRole('checkbox', { name: '담당자 포함' })).toBeTruthy()
    expect(screen.getByRole('textbox', { name: '새 이슈 제목 (선택)' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '클론 생성' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '취소' })).toBeTruthy()
  })

  it('open=false일 때 Dialog 내용이 렌더되지 않는다', () => {
    renderDialog({ open: false })

    expect(screen.queryByText('이슈 클론')).toBeNull()
  })

  it('includeAssignee 체크박스는 기본값 체크 상태이다', () => {
    renderDialog({ open: true })

    const checkbox = screen.getByRole('checkbox', { name: '담당자 포함' }) as HTMLInputElement
    expect(checkbox.checked).toBe(true)
  })

  it('summaryOverride 입력 필드는 maxLength=255를 가진다', () => {
    renderDialog({ open: true })

    const input = screen.getByRole('textbox', { name: '새 이슈 제목 (선택)' }) as HTMLInputElement
    expect(input.maxLength).toBe(255)
  })

  it('클론 생성 버튼 클릭 시 mutation이 호출되고 성공 후 Dialog가 닫힌다', async () => {
    const user = userEvent.setup({ delay: null })
    const onOpenChange = vi.fn()
    renderDialog({ open: true, onOpenChange })

    const submitButton = screen.getByRole('button', { name: '클론 생성' })
    await user.click(submitButton)

    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  it('summaryOverride 입력 후 클론 생성 시 입력값이 전달된다', async () => {
    const user = userEvent.setup({ delay: null })
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', async ({ request }) => {
        capturedBody = await request.clone().json()
        return HttpResponse.json({ data: clonedIssueFixture }, { status: 201 })
      }),
    )

    renderDialog({ open: true })

    const input = screen.getByRole('textbox', { name: '새 이슈 제목 (선택)' })
    await user.type(input, '복제 이슈 제목')

    const submitButton = screen.getByRole('button', { name: '클론 생성' })
    await user.click(submitButton)

    await waitFor(() => {
      expect(capturedBody).toMatchObject({ summaryOverride: '복제 이슈 제목' })
    })
  })

  it('summaryOverride에 공백만 입력하면 body에서 제외된다 (원본 summary 폴백, EC-3)', async () => {
    const user = userEvent.setup({ delay: null })
    let capturedBody: Record<string, unknown> | null = null
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', async ({ request }) => {
        capturedBody = (await request.clone().json()) as Record<string, unknown>
        return HttpResponse.json({ data: clonedIssueFixture }, { status: 201 })
      }),
    )

    renderDialog({ open: true })

    const input = screen.getByRole('textbox', { name: '새 이슈 제목 (선택)' })
    await user.type(input, '   ')

    const submitButton = screen.getByRole('button', { name: '클론 생성' })
    await user.click(submitButton)

    await waitFor(() => {
      expect(capturedBody).not.toBeNull()
    })
    // 공백만이면 summaryOverride 키 자체가 전송되지 않아야 한다 (백엔드가 원본 summary 사용).
    expect(capturedBody).not.toHaveProperty('summaryOverride')
  })

  it('includeAssignee 체크박스 해제 후 클론 생성 시 false가 전달된다', async () => {
    const user = userEvent.setup({ delay: null })
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', async ({ request }) => {
        capturedBody = await request.clone().json()
        return HttpResponse.json({ data: clonedIssueFixture }, { status: 201 })
      }),
    )

    renderDialog({ open: true })

    const checkbox = screen.getByRole('checkbox', { name: '담당자 포함' })
    await user.click(checkbox)

    const submitButton = screen.getByRole('button', { name: '클론 생성' })
    await user.click(submitButton)

    await waitFor(() => {
      expect(capturedBody).toMatchObject({ includeAssignee: false })
    })
  })

  it('Dialog가 닫혔다 다시 열릴 때 상태가 초기화된다', async () => {
    const user = userEvent.setup({ delay: null })
    const { rerender, queryClient } = renderDialog({ open: true })

    // summaryOverride 입력
    const input = screen.getByRole('textbox', { name: '새 이슈 제목 (선택)' })
    await user.type(input, '임시 제목')

    // includeAssignee 해제
    const checkbox = screen.getByRole('checkbox', { name: '담당자 포함' })
    await user.click(checkbox)
    expect((checkbox as HTMLInputElement).checked).toBe(false)

    // Dialog 닫기 (open=false)
    rerender(
      createElement(
        QueryClientProvider,
        { client: queryClient },
        createElement(CloneIssueDialog, {
          issueKey: 'ATLAS-1',
          open: false,
          onOpenChange: vi.fn(),
        }),
      ),
    )

    // Dialog 다시 열기 (open=true)
    rerender(
      createElement(
        QueryClientProvider,
        { client: queryClient },
        createElement(CloneIssueDialog, {
          issueKey: 'ATLAS-1',
          open: true,
          onOpenChange: vi.fn(),
        }),
      ),
    )

    // 상태 초기화 확인
    const reopenedInput = screen.getByRole('textbox', { name: '새 이슈 제목 (선택)' }) as HTMLInputElement
    expect(reopenedInput.value).toBe('')

    const reopenedCheckbox = screen.getByRole('checkbox', { name: '담당자 포함' }) as HTMLInputElement
    expect(reopenedCheckbox.checked).toBe(true)
  })

  it('진행 중일 때 클론 생성 버튼이 disabled 상태이다', async () => {
    // 응답을 지연시켜 pending 상태를 테스트
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', async () => {
        await new Promise((resolve) => setTimeout(resolve, 100))
        return HttpResponse.json({ data: clonedIssueFixture }, { status: 201 })
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderDialog({ open: true })

    const submitButton = screen.getByRole('button', { name: '클론 생성' })
    await user.click(submitButton)

    // 버튼이 비활성화되어야 함 (pending 중)
    expect(submitButton).toBeDisabled()

    await settlePendingMutations()
  })

  it('에러 발생 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', () =>
        HttpResponse.json(
          { errorCode: 'ACCESS_DENIED', message: '접근 거부' },
          { status: 403 },
        ),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderDialog({ open: true })

    const submitButton = screen.getByRole('button', { name: '클론 생성' })
    await user.click(submitButton)

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled()
    })
  })

  it('취소 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
    const user = userEvent.setup({ delay: null })
    const onOpenChange = vi.fn()
    renderDialog({ open: true, onOpenChange })

    const cancelButton = screen.getByRole('button', { name: '취소' })
    await user.click(cancelButton)

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
