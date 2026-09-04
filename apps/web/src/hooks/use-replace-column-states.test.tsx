// 상태 매핑 교체 훅 테스트 — 직렬 전송(X1)과 모든 실패에서 되돌림(G2) (부채 177 Task 6)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { JSX, ReactNode } from 'react'
import { ApiError } from '@/api/client'
import { useReplaceColumnStates, isStateConflict } from './use-replace-column-states'

const mockReplace = vi.fn()
vi.mock('@/api/board-columns', () => ({
  replaceColumnStates: (...args: unknown[]) => mockReplace(...args),
}))

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
const COL_A = 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891'
const COL_B = 'c3d4e5f6-a7b8-4012-9cde-f01234567892'

function wrapper(qc: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }): JSX.Element {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('useReplaceColumnStates — 순서 계약 (X1 · E8)', () => {
  it('T-RC-1: 계획의 요청을 순서대로, 앞이 끝난 뒤에 보낸다', async () => {
    const order: string[] = []
    mockReplace.mockImplementation(async (_board: string, columnId: string) => {
      order.push(`start:${columnId}`)
      await new Promise((r) => setTimeout(r, 10))
      order.push(`end:${columnId}`)
    })

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useReplaceColumnStates(BOARD_ID), { wrapper: wrapper(qc) })

    result.current.mutate({
      changes: [
        { columnId: COL_A, stateKeys: [] },
        { columnId: COL_B, stateKeys: ['triage'] },
      ],
    })

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    // ★겹치면 안 된다. Promise.all 로 바꾸면 start 두 개가 연달아 찍히고 이 단언이 red 다.
    //   겹치는 순간 넣기가 먼저 도착해 서버가 409 를 낸다.
    expect(order).toEqual([`start:${COL_A}`, `end:${COL_A}`, `start:${COL_B}`, `end:${COL_B}`])
  })

  it('T-RC-2: 첫 요청이 실패하면 두 번째를 보내지 않는다', async () => {
    mockReplace.mockRejectedValueOnce(new ApiError(500, {}))

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const { result } = renderHook(() => useReplaceColumnStates(BOARD_ID), { wrapper: wrapper(qc) })

    result.current.mutate({
      changes: [
        { columnId: COL_A, stateKeys: [] },
        { columnId: COL_B, stateKeys: ['triage'] },
      ],
    })

    await waitFor(() => {
      expect(result.current.isError).toBe(true)
    })
    expect(mockReplace).toHaveBeenCalledTimes(1)
  })
})

describe('useReplaceColumnStates — 모든 실패에서 되돌린다 (G2)', () => {
  it.each([
    ['409 충돌', new ApiError(409, {})],
    ['500 서버 오류', new ApiError(500, {})],
    ['네트워크 단절', new TypeError('Failed to fetch')],
  ])('T-RC-3 (%s): 실패해도 보드 상세를 무효화한다', async (_label, error) => {
    mockReplace.mockRejectedValue(error)

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const invalidate = vi.spyOn(qc, 'invalidateQueries')
    const { result } = renderHook(() => useReplaceColumnStates(BOARD_ID), { wrapper: wrapper(qc) })

    result.current.mutate({ changes: [{ columnId: COL_A, stateKeys: [] }] })

    await waitFor(() => {
      expect(result.current.isError).toBe(true)
    })

    // ★무효화가 곧 되돌리기다. onSuccess 에만 두면 실패한 드롭이 화면에 그대로 남아
    //   서버와 다른 것을 보여 준다. 409 만 처리하고 나머지를 삼기면 뒤의 두 건이 red 다.
    expect(invalidate).toHaveBeenCalled()
  })
})

describe('isStateConflict — 409 만 갈라낸다 (E3)', () => {
  it('T-RC-4: 409 는 참이다', () => {
    expect(isStateConflict(new ApiError(409, {}))).toBe(true)
  })

  it('T-RC-5: 다른 상태 코드와 비-ApiError 는 거짓이다', () => {
    expect(isStateConflict(new ApiError(500, {}))).toBe(false)
    expect(isStateConflict(new TypeError('Failed to fetch'))).toBe(false)
    expect(isStateConflict(undefined)).toBe(false)
  })
})
