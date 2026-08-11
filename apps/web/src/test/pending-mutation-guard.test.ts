// pending mutation 가드 계약 테스트 — 가드가 실제 위반을 잡는지와 전역 배선이 살아 있는지를 증명한다
//
// 이 짝이 없으면 가드는 장식이 된다. 추적이 꺼지거나 `setup.ts` 배선이 빠져도
// 「pending 0 건」이 조용히 통과하기 때문이다. 그래서 아래 네 가지를 각각 단언한다.
//   ① 배선 — `setup.ts` 가 추적을 실제로 켰다
//   ② 비-공허 — 새로 만든 QueryClient 의 mutation 이 레지스트리에 잡힌다
//   ③ 양성 — 정착하지 않은 mutation 을 실제로 검출한다
//   ④ 음성 — 정착한 mutation 은 검출하지 않는다 (항진명제가 아니다)
//
// ★이 파일 자신이 전역 가드에 걸리면 안 되므로, 붙잡은 mutation 은 테스트 안에서 반드시 푼다.

import { QueryClient, QueryClientProvider, useMutation } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { act, createElement, type ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import {
  collectPendingMutations,
  isPendingMutationTrackerInstalled,
  settlePendingMutations,
  trackedCacheCount,
} from './pending-mutation-guard'

/** 수동으로 열어줄 때까지 응답하지 않는 게이트. */
function createGate(): { gate: Promise<void>; release: () => void } {
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  return { gate, release }
}

/** mutationFn 이 게이트를 기다리는 훅을 QueryClientProvider 안에서 렌더한다. */
function renderGatedMutation(gate: Promise<void>): { mutate: () => void } {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }): ReactNode =>
    createElement(QueryClientProvider, { client }, children)

  const { result } = renderHook(
    () =>
      useMutation({
        mutationFn: async (): Promise<string> => {
          await gate
          return 'ok'
        },
      }),
    { wrapper },
  )

  return {
    mutate: () => {
      act(() => {
        result.current.mutate()
      })
    },
  }
}

describe('pending mutation 가드', () => {
  it('① 배선 — setup.ts 가 추적을 실제로 켰다', () => {
    expect(
      isPendingMutationTrackerInstalled(),
      'setup.ts 에서 installPendingMutationTracker() 배선이 빠지면 전역 가드가 통째로 공허해진다.',
    ).toBe(true)
  })

  it('② 비-공허 — 새로 만든 QueryClient 의 mutation 이 레지스트리에 잡힌다', async () => {
    const { gate, release } = createGate()
    // 전역 afterEach 가 레지스트리를 비우므로 이 테스트는 0 에서 시작한다.
    expect(trackedCacheCount()).toBe(0)

    const { mutate } = renderGatedMutation(gate)
    mutate()

    await waitFor(() => {
      expect(trackedCacheCount()).toBeGreaterThan(0)
    })

    release()
    await settlePendingMutations()
  })

  it('③ 양성 — 정착하지 않은 mutation 을 실제로 검출한다', async () => {
    const { gate, release } = createGate()
    const { mutate } = renderGatedMutation(gate)
    mutate()

    await waitFor(() => {
      expect(collectPendingMutations().length).toBe(1)
    })

    // 여기서 풀지 않으면 이 테스트 자신이 전역 가드에 걸린다 — 그것이 가드가 살아 있다는 뜻이기도 하다.
    release()
    await settlePendingMutations()
  })

  it('④ 음성 — 정착한 mutation 은 검출하지 않는다', async () => {
    const { gate, release } = createGate()
    const { mutate } = renderGatedMutation(gate)
    mutate()

    await waitFor(() => {
      expect(collectPendingMutations().length).toBe(1)
    })

    release()
    await settlePendingMutations()

    // 캐시는 여전히 추적 중이지만(②) pending 은 사라졌다 — 「레지스트리가 비어서 통과」가 아니다.
    expect(trackedCacheCount()).toBeGreaterThan(0)
    expect(collectPendingMutations()).toEqual([])
  })
})
