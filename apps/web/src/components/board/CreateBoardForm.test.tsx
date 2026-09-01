// 보드 생성 폼 단위 테스트 — 종류 선택(1단계)+이름 입력(2단계)+제출+성공 네비게이션+422 안내 (FR-BD-01 Task 7 · FR-BD-04 D6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/api/client'
import { boardLabels } from '@/i18n/board-labels'
import type { BoardType } from '@/api/boards'

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

/**
 * `isPending` 은 렌더마다 훅에서 다시 읽히므로 **가변 상자**에 담는다.
 *
 * 상수 `false` 로 두면 제출 중 화면(E5 — 「뒤로」 비활성)을 그릴 방법이 없다.
 * 값은 `beforeEach` 에서 매번 `false` 로 되돌린다 — 켠 채로 새는 테스트가 그다음 테스트의
 * 입력을 조용히 막는다.
 */
const { mockMutate, mockPending } = vi.hoisted(() => ({
  mockMutate: vi.fn(),
  mockPending: { value: false },
}))

vi.mock('@/hooks/use-boards', () => ({
  useCreateBoard: () => ({
    mutate: mockMutate,
    isPending: mockPending.value,
  }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 지연 import — 모듈이 GREEN 단계에서 생성되므로 동적 import 사용
// ─────────────────────────────name: async 테스트에서만 import 가능
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 종류별 라디오 접근성 이름 — 문구 정본은 `i18n/board-labels.ts` 다 */
const TYPE_RADIO_NAME: Record<BoardType, string> = {
  SCRUM: boardLabels.createForm.typeStep.scrumLabel,
  KANBAN: boardLabels.createForm.typeStep.kanbanLabel,
}

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

/**
 * 1단계(종류 선택)를 지나 2단계(이름 입력)로 간다.
 *
 * 제출까지 가는 시나리오는 전부 이 관문을 지난다 — 관문을 헬퍼 하나로 접어야 단계가 또 늘 때
 * 고칠 자리가 한 곳이다. 기본값 `'KANBAN'` 은 화면의 기본 선택(X4)과 같아서, 종류에 관심이
 * 없는 기존 시나리오는 **아무것도 고르지 않은 것과 같은 경로**를 탄다.
 *
 * 이미 선택된 종류를 다시 눌러도 라디오는 그 값을 유지하므로 분기를 두지 않는다.
 *
 * @param user userEvent 세션
 * @param kind 고를 보드 종류 (기본 칸반)
 */
async function goToNameStep(user: UserEvent, kind: BoardType = 'KANBAN'): Promise<void> {
  await user.click(screen.getByRole('radio', { name: TYPE_RADIO_NAME[kind] }))
  await user.click(screen.getByRole('button', { name: boardLabels.createForm.next }))
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
    mockPending.value = false
  })

  afterEach(() => {
    vi.clearAllMocks()
    mockPending.value = false
  })

  /**
   * T-BD7-1. 2단계에 보드 이름 입력 필드와 생성 버튼이 렌더된다.
   */
  it('T-BD7-1: 보드 이름 입력 필드와 생성 버튼이 렌더된다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user)

    expect(screen.getByRole('textbox')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /보드 만들기/i })).toBeInTheDocument()
  })

  /**
   * T-BD7-2. 이름 입력 후 제출하면 useCreateBoard.mutate가 name·boardType과 함께 호출된다.
   */
  it('T-BD7-2: 이름 입력 후 제출하면 mutate가 호출된다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user)

    await user.type(screen.getByRole('textbox'), '스프린트 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    expect(mockMutate).toHaveBeenCalledWith(
      { name: '스프린트 보드', boardType: 'KANBAN' },
      expect.anything(),
    )
  })

  /**
   * T-BD7-3. 빈 이름으로 제출하면 mutate가 호출되지 않는다.
   */
  it('T-BD7-3: 빈 이름으로 제출하면 mutate가 호출되지 않는다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user)

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
    await goToNameStep(user)

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
    await goToNameStep(user)

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
    await goToNameStep(user)

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
    await goToNameStep(user)

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
    await goToNameStep(user)

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
    await goToNameStep(user)

    await user.type(screen.getByRole('textbox'), '새 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    // 실패가 화면에 닿은 뒤에 판정한다 — 아직 아무 일도 안 일어난 시점의 not.toHaveBeenCalled 는 공허하다
    await waitFor(() => {
      expect(screen.getByText(/워크플로우 스킴이 할당되지 않아/i)).toBeInTheDocument()
    })
    expect(onCreated).not.toHaveBeenCalled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 1단계 종류 선택 (FR-BD-04 D6 · J1)
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * T-BD7-10. 최초 렌더는 **1단계**다 — 종류를 먼저 묻는다(J1).
   *
   * 「보드 만들기」 버튼이 1단계에 **없어야** 한다는 것이 즉사 계약이다. 두 단계에 같은 이름의
   * 버튼이 있으면 `e2e/board-manage.spec.ts` 의 `exact: true` 조회가 2개를 잡아 strict mode 로
   * 죽는다 — 그래서 문자열을 직접 적어 단언한다(라벨 상수를 경유하면 상수를 바꾼 순간
   * 테스트도 함께 움직여 계약이 사라진다). `ByRole` 의 문자열 `name` 은 접근성 이름 **전체**와
   * 맞춰 보므로 부분 일치로 새지 않는다.
   */
  it('T-BD7-10: 최초 렌더는 1단계 — 라디오 2개가 보이고 이름 입력은 없다', async () => {
    await renderForm()

    expect(screen.getAllByRole('radio')).toHaveLength(2)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '보드 만들기' })).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: boardLabels.createForm.next }),
    ).toBeInTheDocument()
  })

  /**
   * T-BD7-11. 기본 선택은 **칸반**이다 (plan 의도적 편차 X4).
   *
   * 기존 사용자의 「이름 쓰고 만들기」 흐름이 클릭 1회만 늘고 결과가 같다는 근거이자,
   * 「아무것도 안 고르고 다음」(E1)이 도달 불가라는 근거다.
   */
  it('T-BD7-11: 기본 선택이 칸반이다', async () => {
    await renderForm()

    expect(screen.getByRole('radio', { name: TYPE_RADIO_NAME.KANBAN })).toBeChecked()
    expect(screen.getByRole('radio', { name: TYPE_RADIO_NAME.SCRUM })).not.toBeChecked()
  })

  /**
   * T-BD7-12. 스크럼을 고르면 mutate 가 `boardType: 'SCRUM'` 을 받는다.
   *
   * 화면의 선택이 요청까지 실제로 흐르는지를 재는 유일한 유닛 판정이다. 이게 없으면 라디오는
   * 그려지지만 아무 데도 닿지 않는 장식이 될 수 있다.
   */
  it('T-BD7-12: 스크럼을 고르면 mutate 가 boardType SCRUM 을 받는다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user, 'SCRUM')

    await user.type(screen.getByRole('textbox'), '스크럼 보드1')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    expect(mockMutate).toHaveBeenCalledWith(
      { name: '스크럼 보드1', boardType: 'SCRUM' },
      expect.anything(),
    )
  })

  /**
   * T-BD7-13. **D1** — 2단계는 고른 종류를 보여준다.
   *
   * 종류는 생성 후 바꿀 수 없다(편차 X3). 확정 직전 화면에 그 선택이 안 보이면 사용자는
   * 되돌릴 수 없는 결정을 확인 없이 내리게 된다.
   */
  it('T-BD7-13: 2단계에 고른 종류가 표시된다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user, 'SCRUM')

    expect(screen.getByText(TYPE_RADIO_NAME.SCRUM)).toBeInTheDocument()
  })

  /**
   * T-BD7-14. **D2** — 「다음」을 누르면 이름 입력으로 포커스가 옮겨진다.
   *
   * 포커스를 두고 오면 사라진 버튼에 남아 키보드·스크린리더 사용자가 단계 전환을 감지하지 못한다.
   */
  it('T-BD7-14: 「다음」 후 이름 입력에 포커스가 간다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user)

    await waitFor(() => {
      expect(screen.getByRole('textbox')).toHaveFocus()
    })
  })

  /**
   * T-BD7-15. **E3** — 「뒤로」로 종류를 바꿔도 입력한 이름이 남는다.
   *
   * 이름을 지우면 종류만 갈아끼우러 돌아온 사용자가 같은 문자열을 두 번 친다.
   */
  it('T-BD7-15: 「뒤로」로 종류를 바꿔도 이름이 보존된다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user)

    await user.type(screen.getByRole('textbox'), '유지되는 이름')
    await user.click(screen.getByRole('button', { name: boardLabels.createForm.back }))
    await goToNameStep(user, 'SCRUM')

    expect(screen.getByRole('textbox')).toHaveValue('유지되는 이름')
    // 종류는 바뀌어 있다 — 이름만 남고 선택은 갈아끼워졌다는 판정
    expect(screen.getByText(TYPE_RADIO_NAME.SCRUM)).toBeInTheDocument()
  })

  /**
   * T-BD7-16. **E5** — 제출 중에는 「뒤로」가 비활성이다.
   *
   * 진행 중 요청에 실린 종류와 화면이 보여 주는 종류가 갈리면 안 된다.
   * `isPending` 은 **2단계에서** 켠다 — 1단계는 mutation 을 시작할 수 없어 「1단계 + 제출 중」은
   * 실제로 오지 않는 조합이고, 그 조합을 지키는 테스트는 가짜 그린이다.
   */
  it('T-BD7-16: isPending 중에는 「뒤로」가 비활성이다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user)

    await user.type(screen.getByRole('textbox'), '새 보드')
    mockPending.value = true
    // 훅은 렌더마다 다시 읽힌다 — 상태를 움직이는 입력 한 번으로 재렌더를 일으킨다
    await user.type(screen.getByRole('textbox'), '!')

    expect(screen.getByRole('button', { name: boardLabels.createForm.back })).toBeDisabled()
  })

  /**
   * T-BD7-17. **E4** — 422 안내는 **2단계에** 뜬다.
   *
   * 제출한 화면에 붙어 있어야 한다. 안내가 뜨면서 1단계로 되돌아가면 사용자는 사유를 읽는 동시에
   * 입력한 이름을 잃는다.
   */
  it('T-BD7-17: 422 안내가 2단계에 표시된다', async () => {
    const user = userEvent.setup()

    mockMutate.mockImplementation(
      (_vars: unknown, options: { onError?: (err: unknown) => void }) => {
        options.onError?.(new ApiError(422, { errorCode: 'AGILE_UNPROCESSABLE' }))
      },
    )

    await renderForm()
    await goToNameStep(user)

    await user.type(screen.getByRole('textbox'), '새 보드')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    await waitFor(() => {
      expect(screen.getByText(/워크플로우 스킴이 할당되지 않아/i)).toBeInTheDocument()
    })
    // 여전히 2단계다 — 이름 입력과 「뒤로」가 함께 있다
    expect(screen.getByRole('textbox')).toHaveValue('새 보드')
    expect(
      screen.getByRole('button', { name: boardLabels.createForm.back }),
    ).toBeInTheDocument()
  })

  /**
   * T-BD7-18. **E6** — 언마운트 후 다시 마운트하면 1단계로 돌아온다.
   *
   * 소비처(`routes/projects.$projectKey.board.tsx:295`)의 `Dialog` 는 `forceMount` 없이 `open`
   * 으로만 제어해 닫힐 때 내용을 언마운트한다. 그 전제 위에서 `useState` 초기값이 곧 1단계 복귀다.
   * **누군가 `forceMount` 를 붙이면 전제가 조용히 깨진다** — 이 테스트가 그 회귀 가드다.
   */
  it('T-BD7-18: 언마운트 후 재마운트하면 1단계로 돌아온다', async () => {
    const user = userEvent.setup()
    const view = await renderForm()
    await goToNameStep(user, 'SCRUM')
    expect(screen.getByRole('textbox')).toBeInTheDocument()

    view.unmount()
    await renderForm()

    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByRole('radio', { name: TYPE_RADIO_NAME.KANBAN })).toBeChecked()
  })

  /**
   * T-BD7-20. 「보드가 없습니다」 인트로는 **1단계에만** 붙는다 (plan Design 렌즈 D3).
   *
   * 인트로는 「보드를 만들자」는 진입 유도이고 2단계는 이미 만드는 중이다 — **의도된 동작**이라
   * 여기서 못박는다. 판정이 없으면 「사라진 게 버그 아닌가」로 다음 사람이 되돌려 놓는다.
   */
  it('T-BD7-20: 인트로는 1단계에만 보인다', async () => {
    const user = userEvent.setup()
    await renderForm()

    expect(screen.getByText('보드가 없습니다')).toBeInTheDocument()

    await goToNameStep(user)

    expect(screen.queryByText('보드가 없습니다')).not.toBeInTheDocument()
  })

  /**
   * T-BD7-19. **E2** — 2단계에서 공백뿐인 이름을 제출해도 mutate 가 호출되지 않는다.
   *
   * 제출 버튼이 2단계로 옮겨 가면서 `trimmed === ''` early return 이 빠져도 다른 테스트는
   * 아무것도 안 깨진다(T-BD7-3 은 **빈** 문자열이라 공백 trim 을 지나가지 않는다).
   */
  it('T-BD7-19: 공백뿐인 이름을 제출해도 mutate 가 호출되지 않는다', async () => {
    const user = userEvent.setup()
    await renderForm()
    await goToNameStep(user)

    await user.type(screen.getByRole('textbox'), '   ')
    await user.click(screen.getByRole('button', { name: /보드 만들기/i }))

    expect(mockMutate).not.toHaveBeenCalled()
  })
})
