// CreateIssueDialog 단위 테스트 — 제어 컴포넌트 계약 + 고유 접근성 이름 (FR-UX-09 F2 T7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { issueHandlers } from '@/mocks/issue-handlers'
import { projectHandlers } from '@/mocks/project-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'
import { issueCreateStrings } from '@/i18n/ko'
import type { CustomField } from '@/api/custom-fields.types'

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  useParams: () => ({}),
  useSearch: () => ({}),
}))

vi.mock('@/hooks/use-custom-fields', () => ({
  useCustomFields: vi.fn(),
  CUSTOM_FIELD_KEYS: { list: (k: string) => ['custom-fields', k] },
}))

import { useCustomFields } from '@/hooks/use-custom-fields'

const EMPTY_CUSTOM_FIELDS_RESULT = {
  data: [] as CustomField[],
  isLoading: false,
  isError: false,
  isPending: false,
  isSuccess: true,
  error: null,
  status: 'success' as const,
  fetchStatus: 'idle' as const,
  dataUpdatedAt: 0,
  errorUpdatedAt: 0,
  failureCount: 0,
  failureReason: null,
  isFetched: true,
  isFetchedAfterMount: true,
  isFetching: false,
  isInitialLoading: false,
  isLoadingError: false,
  isPlaceholderData: false,
  isRefetchError: false,
  isRefetching: false,
  isStale: false,
  refetch: vi.fn(),
}

beforeEach(() => {
  vi.mocked(useCustomFields).mockReturnValue(EMPTY_CUSTOM_FIELDS_RESULT as never)
  localStorage.clear()
  server.use(...issueHandlers, ...projectHandlers, ...issueTypeHandlers, ...userHandlers)
})

/** 모달을 렌더한다. */
function renderDialog(props: {
  open: boolean
  onOpenChange?: (open: boolean) => void
  onCreated?: (key: string) => void
}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <CreateIssueDialog
        open={props.open}
        onOpenChange={props.onOpenChange ?? vi.fn()}
        onCreated={props.onCreated}
      />
    </QueryClientProvider>,
  )
}

describe('CreateIssueDialog — 제어 컴포넌트 계약 (FR-1)', () => {
  it('open=false 면 아무것도 렌더하지 않는다', () => {
    renderDialog({ open: false })

    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('★고유한 접근성 이름을 갖는다 — role="dialog" 가 e2e 에 164 발생이라 이름이 없으면 충돌한다', async () => {
    renderDialog({ open: true })

    const dialog = await screen.findByRole('dialog', { name: issueCreateStrings.dialogTitle })
    expect(dialog).toBeInTheDocument()
  })

  it('취소를 누르면 onOpenChange(false) 가 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    renderDialog({ open: true, onOpenChange })

    await user.click(await screen.findByRole('button', { name: issueCreateStrings.cancelButton }))

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('Esc 를 누르면 onOpenChange(false) 가 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    renderDialog({ open: true, onOpenChange })

    await screen.findByRole('dialog')
    await user.keyboard('{Escape}')

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('CreateIssueDialog — 생성 성공 (FR-16)', () => {
  it('생성에 성공하면 모달을 닫고 onCreated(key) 를 부른다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    const onCreated = vi.fn()
    renderDialog({ open: true, onOpenChange, onCreated })

    await screen.findByRole('dialog')
    await waitFor(() => {
      const sel = screen.getByLabelText(issueCreateStrings.projectKeyLabel) as HTMLSelectElement
      expect(sel.querySelectorAll('option').length).toBeGreaterThan(1)
    })
    await user.type(screen.getByLabelText(issueCreateStrings.summaryLabel), '모달에서 만든 이슈')
    await user.click(screen.getByRole('button', { name: issueCreateStrings.submitButton }))

    await waitFor(() => expect(onCreated).toHaveBeenCalled())
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

describe('CreateIssueDialog — 스크롤 경계 (NFR-2)', () => {
  it('본문만 스크롤하고 만들기 버튼은 스크롤 영역 밖에 있다', async () => {
    const { container } = renderDialog({ open: true })

    await screen.findByRole('dialog')
    const scrollArea = container.ownerDocument.querySelector('[data-testid="create-issue-scroll"]')
    expect(scrollArea).not.toBeNull()

    const submit = screen.getByRole('button', { name: issueCreateStrings.submitButton })
    expect(scrollArea?.contains(submit)).toBe(false)
  })
})
