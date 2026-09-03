// 도메인 조회 훅 테스트 — 디바운스 합류 · 순번 가드 · dedupe 오염 방지 · 언마운트 정리
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useDomainRouteLookup, ROUTE_LOOKUP_DEBOUNCE_MS } from './useDomainRouteLookup'

// fetchRoute 를 직접 대체한다. 디바운스/순번은 **호출 횟수와 해소 순서**로만 검증할 수 있는데,
// MSW 는 응답 순서를 테스트가 원하는 대로 뒤집기 어렵다.
const routeMocks = vi.hoisted(() => ({ fetchRoute: vi.fn() }))
vi.mock('@/api/route', () => ({ fetchRoute: routeMocks.fetchRoute }))

const OKTA = {
  matched: true as const,
  type: 'SAML' as const,
  registrationId: 'okta',
  displayName: 'Okta SSO',
}
const ACME = {
  matched: true as const,
  type: 'OIDC' as const,
  registrationId: 'acme',
  displayName: 'Acme SSO',
}

beforeEach(() => {
  routeMocks.fetchRoute.mockReset()
})

describe('useDomainRouteLookup — 디바운스', () => {
  // 🛑 fake timers 는 이 describe 안으로만 좁힌다. `waitFor` 가 내부적으로 타이머를 쓰기 때문에
  //    전역으로 걸면 순번·dedupe 테스트가 15초 timeout 으로 죽는다(실측).
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('한 글자씩 치는 동안 요청이 1회로 합류한다', async () => {
    routeMocks.fetchRoute.mockResolvedValue({ matched: false })
    const { result } = renderHook(() => useDomainRouteLookup())

    // 부분 도메인마다 schedule 이 불리지만 타이머는 매번 재설정된다
    act(() => {
      result.current.scheduleLookup('alice@o')
      result.current.scheduleLookup('alice@ok')
      result.current.scheduleLookup('alice@okt')
      result.current.scheduleLookup('alice@okta.com')
    })
    expect(routeMocks.fetchRoute).not.toHaveBeenCalled()

    await act(async () => {
      vi.advanceTimersByTime(ROUTE_LOOKUP_DEBOUNCE_MS)
    })

    expect(routeMocks.fetchRoute).toHaveBeenCalledTimes(1)
    expect(routeMocks.fetchRoute).toHaveBeenCalledWith('okta.com')
  })

  it('flush 는 대기 중인 디바운스를 취소하고 즉시 조회한다 (중복 발사 없음)', async () => {
    routeMocks.fetchRoute.mockResolvedValue(OKTA)
    const { result } = renderHook(() => useDomainRouteLookup())

    act(() => {
      result.current.scheduleLookup('alice@okta.com')
      result.current.flushLookup('alice@okta.com')
    })

    expect(routeMocks.fetchRoute).toHaveBeenCalledTimes(1)

    // 취소된 타이머가 뒤늦게 발사되지 않아야 한다
    await act(async () => {
      vi.advanceTimersByTime(ROUTE_LOOKUP_DEBOUNCE_MS * 2)
    })
    expect(routeMocks.fetchRoute).toHaveBeenCalledTimes(1)
  })
})

describe('useDomainRouteLookup — 순번 가드', () => {
  it('늦게 도착한 옛 응답이 최신 결과를 덮어쓰지 않는다', async () => {
    let resolveFirst: (v: unknown) => void = () => {}
    const first = new Promise((r) => {
      resolveFirst = r
    })
    routeMocks.fetchRoute
      .mockReturnValueOnce(first) // okta.com — 느리게 도착
      .mockResolvedValueOnce(ACME) // acme.com — 먼저 도착

    const { result } = renderHook(() => useDomainRouteLookup())

    act(() => {
      result.current.flushLookup('alice@okta.com')
    })
    await act(async () => {
      result.current.flushLookup('alice@acme.com')
    })

    await waitFor(() => {
      expect(result.current.matchedRoute?.registrationId).toBe('acme')
    })

    // 이제 첫 요청이 뒤늦게 도착한다 — 최신(acme)을 덮으면 안 된다
    await act(async () => {
      resolveFirst(OKTA)
      await Promise.resolve()
    })

    expect(result.current.matchedRoute?.registrationId).toBe('acme')
  })
})

describe('useDomainRouteLookup — dedupe', () => {
  it('같은 도메인을 연속 조회하면 요청이 1회다', async () => {
    routeMocks.fetchRoute.mockResolvedValue(OKTA)
    const { result } = renderHook(() => useDomainRouteLookup())

    await act(async () => {
      result.current.flushLookup('alice@okta.com')
    })
    await act(async () => {
      result.current.flushLookup('bob@okta.com')
    })

    expect(routeMocks.fetchRoute).toHaveBeenCalledTimes(1)
  })

  it('조회가 실패하면 dedupe 키를 되돌려 재조회가 가능하다', async () => {
    routeMocks.fetchRoute
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(OKTA)
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const { result } = renderHook(() => useDomainRouteLookup())

    await act(async () => {
      result.current.flushLookup('alice@okta.com')
    })
    await act(async () => {
      result.current.flushLookup('alice@okta.com')
    })

    expect(routeMocks.fetchRoute).toHaveBeenCalledTimes(2)
    await waitFor(() => {
      expect(result.current.matchedRoute?.registrationId).toBe('okta')
    })

    warn.mockRestore()
  })
})

describe('useDomainRouteLookup — 언마운트 정리', () => {
  // 타이머를 직접 진행시키는 테스트라 fake timers 가 필요하다. `waitFor` 를 쓰지 않으므로 안전하다.
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('언마운트 후에는 대기 중이던 디바운스가 발사되지 않는다', async () => {
    routeMocks.fetchRoute.mockResolvedValue({ matched: false })
    const { result, unmount } = renderHook(() => useDomainRouteLookup())

    act(() => {
      result.current.scheduleLookup('alice@okta.com')
    })
    unmount()

    await act(async () => {
      vi.advanceTimersByTime(ROUTE_LOOKUP_DEBOUNCE_MS * 2)
    })

    expect(routeMocks.fetchRoute).not.toHaveBeenCalled()
  })
})
