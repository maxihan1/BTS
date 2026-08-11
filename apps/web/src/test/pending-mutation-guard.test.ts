// pending mutation 가드 계약 테스트 — 가드가 실제 위반을 잡는지와 전역 배선이 살아 있는지를 증명한다
//
// 이 짝이 없으면 가드는 장식이 된다. 추적이 꺼지거나 `setup.ts` 배선이 빠져도
// 「pending 0 건」이 조용히 통과하기 때문이다.
//
// ## 런타임 계약 (①~⑤)
//   ① 배선 플래그 — 추적이 켜졌다 (★단독으로는 증명이 약하다. 아래 ⑥ 참조)
//   ② 비-공허 — 새로 만든 QueryClient 의 mutation 이 레지스트리에 잡힌다
//   ③ 양성 — 정착하지 않은 mutation 을 실제로 검출한다
//   ④ 음성 — 정착한 mutation 은 검출하지 않는다 (항진명제가 아니다)
//   ⑤ 리셋 — 앞 테스트가 캐시를 등록했어도 다음 테스트는 0 에서 시작한다
//
// ★⑤가 ②·③·④ **뒤에** 있어야 하는 이유. 이 단언을 스위트 앞쪽에 두면 앞 테스트가 mutation 을
// 아예 만들지 않아 0 이 항진명제가 된다(`resetTrackedCaches()` 를 지워도 초록). 실제로 캐시를
// 등록한 테스트 뒤라야 0 이 「전역 리셋이 돌았다」의 증거가 된다.
//
// ## 소스 훑기 계약 (⑥~⑧)
// ①~⑤는 전부 라이브러리 함수를 **직접** 부르고 `setup.ts` 의 전역 `afterEach` 를 거치지 않는다.
// 그래서 그 `afterEach` 를 통째로 지워도 ①~⑤는 초록이다(코드리뷰·테스트 리뷰가 각각 실측).
// 배선 자체는 소스를 훑어 봉인한다 — `msw-single-setupserver.test.ts` 와 같은 관례다.
//
// ★이 파일 자신은 검사 대상이 아니다(`setup.ts` 와 가드 모듈만 읽는다). 그래서 자기탐지 회피를
// 위한 문자열 조립이 필요 없다.

import { QueryClient, QueryClientProvider, useMutation } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
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
  const gate = new Promise<void>((resolve_) => {
    release = resolve_
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

describe('pending mutation 가드 — 런타임 계약', () => {
  it('① 배선 플래그 — 추적이 켜져 있다', () => {
    expect(
      isPendingMutationTrackerInstalled(),
      'setup.ts 에서 installPendingMutationTracker() 배선이 빠지면 전역 가드가 통째로 공허해진다.',
    ).toBe(true)
  })

  it('② 비-공허 — 새로 만든 QueryClient 의 mutation 이 레지스트리에 잡힌다', async () => {
    const { gate, release } = createGate()
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

  it('⑤ 리셋 — 앞 테스트가 캐시를 등록했어도 다음 테스트는 0 에서 시작한다', () => {
    expect(
      trackedCacheCount(),
      'setup.ts 의 resetTrackedCaches() 가 빠지면 앞 테스트(②③④)의 캐시가 그대로 남는다.',
    ).toBe(0)
  })

  it('⑥ 노출값 — pending 정보에 variables 의 **값**이 담기지 않는다', async () => {
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    const { gate, release } = createGate()
    const mutation = client.getMutationCache().build(client, {
      mutationKey: ['secret-shape-probe'],
      mutationFn: async (): Promise<string> => {
        await gate
        return 'ok'
      },
    })
    void mutation.execute({ currentPassword: 'NEVER-LOG-ME', newPassword: 'NEVER-LOG-ME-2' })

    await waitFor(() => {
      expect(collectPendingMutations().length).toBe(1)
    })

    const serialized = JSON.stringify(collectPendingMutations())
    expect(serialized, '자격증명 값이 단언 페이로드에 실리면 CI 로그에 그대로 찍힌다.').not.toContain(
      'NEVER-LOG-ME',
    )
    expect(serialized, '어느 mutation 인지 식별할 키 이름은 남아 있어야 한다.').toContain(
      'currentPassword',
    )

    release()
    await settlePendingMutations()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 소스 훑기 계약 — 전역 배선이 살아 있는가
// ─────────────────────────────────────────────────────────────────────────────

const HERE = dirname(fileURLToPath(import.meta.url))
const SETUP_SRC = readFileSync(resolve(HERE, 'setup.ts'), 'utf8')
const GUARD_SRC = readFileSync(resolve(HERE, 'pending-mutation-guard.ts'), 'utf8')

describe('pending mutation 가드 — 전역 배선 봉인', () => {
  it('⑦ setup.ts 가 전역 afterEach 에서 pending 을 수집해 단언한다', () => {
    // 비-공허 확인 — 훑은 소스가 실제로 setup.ts 다 (빈 문자열이면 아래 단언이 전부 공허해진다).
    expect(SETUP_SRC.length, 'setup.ts 를 읽지 못했다면 이 계약 전체가 공허하다.').toBeGreaterThan(500)
    expect(SETUP_SRC).toContain('installPendingMutationTracker()')

    expect(
      SETUP_SRC,
      'setup.ts 가 collectPendingMutations() 를 부르지 않으면 가드는 아무것도 재지 않는다.',
    ).toContain('collectPendingMutations()')

    expect(
      SETUP_SRC,
      '수집만 하고 단언하지 않으면 위반이 조용히 통과한다. expect.soft(...).toEqual([]) 가 있어야 한다.',
    ).toMatch(/expect\.soft\([\s\S]*?\)\.toEqual\(\[\]\)/)
  })

  it('⑧ 수집이 레지스트리 정리보다 앞에 있다', () => {
    const collectAt = SETUP_SRC.indexOf('collectPendingMutations()')
    const resetAt = SETUP_SRC.indexOf('resetTrackedCaches()')

    expect(collectAt).toBeGreaterThan(-1)
    expect(resetAt).toBeGreaterThan(-1)
    expect(
      collectAt,
      '정리가 수집보다 먼저 돌면 레지스트리가 항상 비어 가드가 100% 공허해진다.',
    ).toBeLessThan(resetAt)
  })

  it('⑨ setup.ts 와 가드 모듈이 RTL 을 static import 하지 않는다', () => {
    const staticRtlImport = /^\s*import\s[\s\S]*?from\s+['"]@testing-library\/react['"]/m

    expect(
      staticRtlImport.test(SETUP_SRC),
      'setup.ts 가 RTL 을 static import 하면 RTL 을 쓰지 않는 순수 유닛 테스트 전량이 로드 비용을 문다.',
    ).toBe(false)

    expect(
      staticRtlImport.test(GUARD_SRC),
      '가드 모듈은 setup.ts 가 로드하므로 같은 비용이 전 스위트에 전파된다. 동적 import 를 유지할 것.',
    ).toBe(false)
  })
})
