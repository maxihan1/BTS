// useSprintBurndownByBoard 훅 단위 테스트 — 2단 조회와 「스프린트 없음」 판정
//
// ★가젯 컴포넌트 테스트는 이 훅을 목한다. 그래서 아래 판정은 거기서 **안 잡힌다** —
//   특히 `hasNoActiveSprint` 는 「보드는 읽혔는데 activeSprint 가 null」이라는 두 조건의
//   곱이라 한쪽만 봐도 그럴듯하게 통과한다. 여기가 그것을 재는 유일한 자리다.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

vi.mock('@/api/burndown')
vi.mock('./use-boards')

import { fetchSprintBurndown } from '@/api/burndown'
import type { BurndownResponse } from '@/api/burndown'
import { useBoard } from './use-boards'
import { useSprintBurndownByBoard, sprintBurndownKeys } from './use-sprint-burndown'

const BOARD_ID = 'b0000000-0000-4000-8000-000000000001'
const SPRINT_ID = 'a0000000-0000-4000-8000-000000000002'

const burndownFixture = { sprintId: SPRINT_ID } as unknown as BurndownResponse

type BoardHookResult = ReturnType<typeof useBoard>

/** `useBoard` 목 — 필요한 세 필드만 준다. */
function mockBoard(over: { data?: unknown; isLoading?: boolean; isError?: boolean }): void {
  vi.mocked(useBoard).mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
    ...over,
  } as unknown as BoardHookResult)
}

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

function freshClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('sprintBurndownKeys', () => {
  it('번다운 상세 라우트와 같은 키를 만든다 — 캐시를 공유한다', () => {
    expect(sprintBurndownKeys.detail(SPRINT_ID)).toEqual(['sprint-burndown', SPRINT_ID])
  })
})

describe('useSprintBurndownByBoard', () => {
  it('활성 스프린트가 있으면 그 UUID 로 번다운을 부른다 (2단 조회)', async () => {
    mockBoard({ data: { activeSprint: { sprintId: SPRINT_ID } } })
    vi.mocked(fetchSprintBurndown).mockResolvedValue(burndownFixture)

    const { result } = renderHook(() => useSprintBurndownByBoard(BOARD_ID), {
      wrapper: createWrapper(freshClient()),
    })

    await waitFor(() => {
      expect(result.current.data).toBe(burndownFixture)
    })
    // ★보드에서 꺼낸 UUID 가 실제로 넘어갔는지 — 여기가 2단의 이음매다.
    expect(fetchSprintBurndown).toHaveBeenCalledWith(SPRINT_ID)
    expect(result.current.hasNoActiveSprint).toBe(false)
  })

  it('★칸반 보드(activeSprint=null)면 번다운을 아예 부르지 않는다', async () => {
    mockBoard({ data: { activeSprint: null } })

    const { result } = renderHook(() => useSprintBurndownByBoard(BOARD_ID), {
      wrapper: createWrapper(freshClient()),
    })

    await waitFor(() => {
      expect(result.current.hasNoActiveSprint).toBe(true)
    })
    // 스프린트가 없는데 부르면 400/404 가 나고 화면이 「불러오지 못했습니다」로 떨어진다.
    expect(fetchSprintBurndown).not.toHaveBeenCalled()
    expect(result.current.isError).toBe(false)
  })

  it('★보드를 아직 못 읽은 동안에는 「스프린트 없음」이라고 말하지 않는다', () => {
    // data 가 undefined 인 것은 「없다」가 아니라 「모른다」다. 둘을 뭉치면 로딩 중에
    // 「활성 스프린트가 없습니다」가 깜빡였다가 차트로 바뀐다.
    mockBoard({ isLoading: true })

    const { result } = renderHook(() => useSprintBurndownByBoard(BOARD_ID), {
      wrapper: createWrapper(freshClient()),
    })

    expect(result.current.hasNoActiveSprint).toBe(false)
    expect(result.current.isLoading).toBe(true)
  })

  it('★보드 조회가 실패한 동안에도 「스프린트 없음」이라고 말하지 않는다', () => {
    mockBoard({ isError: true })

    const { result } = renderHook(() => useSprintBurndownByBoard(BOARD_ID), {
      wrapper: createWrapper(freshClient()),
    })

    expect(result.current.hasNoActiveSprint).toBe(false)
    expect(result.current.isError).toBe(true)
  })

  it('보드 조회 실패는 isError 로 올린다', () => {
    mockBoard({ isError: true })

    const { result } = renderHook(() => useSprintBurndownByBoard(BOARD_ID), {
      wrapper: createWrapper(freshClient()),
    })

    expect(result.current.isError).toBe(true)
    expect(result.current.data).toBeUndefined()
  })

  it('boardId 가 undefined 면 번다운을 부르지 않는다', () => {
    mockBoard({})

    renderHook(() => useSprintBurndownByBoard(undefined), {
      wrapper: createWrapper(freshClient()),
    })

    expect(fetchSprintBurndown).not.toHaveBeenCalled()
  })
})
