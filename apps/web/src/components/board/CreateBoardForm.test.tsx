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

/**
 * 폼을 렌더한다.
 *
 * `onCreated` 는 **넘기지 않는 것이 기본**이다 — 그래야 기존 호출 6건이 그대로 남고,
 * 「빈 상태 소비처는 이 prop 을 모른다」는 계약이 헬퍼 기본값으로 드러난다.
 */
async function renderForm(projectKey = 'ATLAS', onCreated?: () => void) {
  const { CreateBoardForm } = await import('@/components/board/CreateBoardForm')
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <CreateBoardForm projectKey={projectKey} onCreated={onCreated} />
    </QueryClientProvider>,
  )
}

/** mutate 를 성공으로 태우는 구현 — onSuccess 에 boardId 를 실어 부른다 */
function mutateSucceedsWith(boardId: string): void {
  mockMutate.mockImplementation(
    (_vars: unknown, options: { onSuccess?: (data: { boardId: string }) => void }) => {
      options.onSuccess?.({ boardId })
    },
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

  /**
   * T-BD7-7. 생성에 성공하면 `onCreated` 를 부른다.
   *
   * 다이얼로그 소비처가 창을 닫는 **유일한 신호**다. 이 배선을 재는 판별자가 e2e 하나뿐이면
   * 그 spec 이 다른 이유로 skip 되는 순간 배선이 조용히 죽는다 — 여기서 문다.
   */
  it('T-BD7-7: 생성에 성공하면 onCreated 를 부른다', async () => {
    const user = userEvent.setup()
    const onCreated = vi.fn()
    mutateSucceedsWith('a1b2c3d4-e5f6-4890-abcd-ef1234567891')

    await renderForm('ATLAS', onCreated)

    await user.type(screen.getByRole('textbox'), '새 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    await waitFor(() => {
      expect(onCreated).toHaveBeenCalled()
    })
  })

  /**
   * T-BD7-8. `onCreated` 를 **넘기지 않아도** 생성이 성공한다.
   *
   * prop 이 선택적이라는 계약이자, 빈 상태 소비처(보드 0개 경로)가 diff 0 인 근거다.
   * 옵셔널 호출을 지우고 무조건 호출로 바꾸면 여기서 TypeError 로 떨어진다.
   */
  it('T-BD7-8: onCreated 를 넘기지 않아도 생성이 성공한다', async () => {
    const user = userEvent.setup()
    const newBoardId = 'b2c3d4e5-f6a7-4890-abcd-ef1234567892'
    mutateSucceedsWith(newBoardId)

    await renderForm()

    await user.type(screen.getByRole('textbox'), '새 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    // 성공 경로가 끝까지 갔다는 관찰 지점 — navigate 가 그 자리다
    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalled()
    })
  })

  /**
   * T-BD7-9. 실패 경로에서는 `onCreated` 를 **부르지 않는다**.
   *
   * 성공 신호가 성공에만 붙는다는 판정이다. 이게 없으면 신호를 `onSettled` 로 옮겨도 통과하고,
   * 그러면 실패한 창이 사유를 남기지 못한 채 닫힌다.
   */
  it('T-BD7-9: 생성에 실패하면 onCreated 를 부르지 않는다', async () => {
    const user = userEvent.setup()
    const onCreated = vi.fn()

    mockMutate.mockImplementation(
      (_vars: unknown, options: { onError?: (err: unknown) => void }) => {
        options.onError?.(new ApiError(422, { errorCode: 'AGILE_UNPROCESSABLE' }))
      },
    )

    await renderForm('ATLAS', onCreated)

    await user.type(screen.getByRole('textbox'), '새 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    // 실패가 화면에 닿은 뒤에 판정한다 — 아직 아무 일도 안 일어난 시점의 not.toHaveBeenCalled 는 공허하다
    await waitFor(() => {
      expect(screen.getByText(/워크플로우 스킴이 할당되지 않아/i)).toBeInTheDocument()
    })
    expect(onCreated).not.toHaveBeenCalled()
  })
})
