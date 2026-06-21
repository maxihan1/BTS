// 보드 생성 폼 단위 테스트 — 입력+제출+성공 네비게이션+422 에러 안내 (FR-BD-01 Task 7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router navigate, sonner toast, use-boards 훅
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
  useSearch: () => ({}),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const { mockMutate } = vi.hoisted(() => ({ mockMutate: vi.fn() }))

vi.mock('@/hooks/use-boards', () => ({
  useCreateBoard: () => ({
    mutate: mockMutate,
    isPending: false,
  }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 지연 import — 모듈이 GREEN 단계에서 생성되므로 동적 import 사용
// ─────────────────────────────name: async 테스트에서만 import 가능
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function renderForm(projectKey = 'ATLAS') {
  const { CreateBoardForm } = await import('@/components/board/CreateBoardForm')
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <CreateBoardForm projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('CreateBoardForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  /**
   * T-BD7-1. 보드 이름 입력 필드와 생성 버튼이 렌더된다.
   */
  it('T-BD7-1: 보드 이름 입력 필드와 생성 버튼이 렌더된다', async () => {
    await renderForm()
    expect(screen.getByRole('textbox')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /보드 만들기/i })).toBeInTheDocument()
  })

  /**
   * T-BD7-2. 이름 입력 후 제출하면 useCreateBoard.mutate가 name과 함께 호출된다.
   */
  it('T-BD7-2: 이름 입력 후 제출하면 mutate가 호출된다', async () => {
    const user = userEvent.setup()
    await renderForm()

    await user.type(screen.getByRole('textbox'), '스프린트 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    expect(mockMutate).toHaveBeenCalledWith(
      { name: '스프린트 보드' },
      expect.anything(),
    )
  })

  /**
   * T-BD7-3. 빈 이름으로 제출하면 mutate가 호출되지 않는다.
   */
  it('T-BD7-3: 빈 이름으로 제출하면 mutate가 호출되지 않는다', async () => {
    const user = userEvent.setup()
    await renderForm()

    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    expect(mockMutate).not.toHaveBeenCalled()
  })

  /**
   * T-BD7-4. mutate 성공 콜백에서 navigate가 호출된다.
   * navigate는 search 업데이터 함수를 포함한 객체로 호출된다.
   */
  it('T-BD7-4: mutate 성공 시 navigate가 search 업데이터를 포함해 호출된다', async () => {
    const user = userEvent.setup()
    const newBoardId = 'a1b2c3d4-e5f6-4890-abcd-ef1234567891'

    mockMutate.mockImplementation(
      (_vars: unknown, options: { onSuccess?: (data: { boardId: string }) => void }) => {
        options.onSuccess?.({ boardId: newBoardId })
      },
    )

    await renderForm()

    await user.type(screen.getByRole('textbox'), '새 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    // navigate가 호출됐는지 확인
    expect(mockNavigate).toHaveBeenCalled()

    // navigate에 전달된 search 업데이터 함수가 board 파라미터를 설정하는지 확인
    const callArg = mockNavigate.mock.calls[0]?.[0] as { search?: (prev: Record<string, unknown>) => Record<string, unknown> }
    if (callArg?.search !== undefined && typeof callArg.search === 'function') {
      const result = callArg.search({})
      expect(result).toMatchObject({ board: newBoardId })
    } else {
      expect(callArg).toMatchObject({ search: expect.objectContaining({ board: newBoardId }) })
    }
  })

  /**
   * T-BD7-5. mutate 실패 시 errorCode=AGILE_UNPROCESSABLE이면 워크플로우 안내 메시지가 표시된다.
   */
  it('T-BD7-5: AGILE_UNPROCESSABLE 422 에러 시 워크플로우 안내 메시지가 표시된다', async () => {
    const user = userEvent.setup()

    mockMutate.mockImplementation(
      (_vars: unknown, options: { onError?: (err: unknown) => void }) => {
        options.onError?.(new ApiError(422, { errorCode: 'AGILE_UNPROCESSABLE' }))
      },
    )

    await renderForm()

    await user.type(screen.getByRole('textbox'), '새 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    await waitFor(() => {
      expect(
        screen.getByText(/워크플로우 스킴이 할당되지 않아/i),
      ).toBeInTheDocument()
    })
  })

  /**
   * T-BD7-6. mutate 실패 시 AGILE_UNPROCESSABLE이 아닌 에러는 toast.error를 호출한다.
   */
  it('T-BD7-6: 그 외 에러는 toast.error를 호출한다', async () => {
    const user = userEvent.setup()
    const { toast } = await import('sonner')

    mockMutate.mockImplementation(
      (_vars: unknown, options: { onError?: (err: unknown) => void }) => {
        options.onError?.(new ApiError(500, { errorCode: 'AGILE_INTERNAL_ERROR' }))
      },
    )

    await renderForm()

    await user.type(screen.getByRole('textbox'), '새 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalled()
    })
  })
})
