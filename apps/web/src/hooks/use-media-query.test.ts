// useMediaQuery 훅 단위 테스트 — matchMedia 구독/해제/기본값 (FR-UX-06 Phase 5 PR20 Task-2 RED)
import { describe, it, expect, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useMediaQuery } from './use-media-query'

/** 지정한 초기 matches 값으로 matchMedia mock을 설치하고, 리스너 등록/해제 spy와
 * change 이벤트를 수동으로 발화시키는 트리거 함수를 함께 반환한다. */
function mockMatchMedia(initialMatches: boolean): {
  addEventListener: ReturnType<typeof vi.fn>
  removeEventListener: ReturnType<typeof vi.fn>
  fireChange: (matches: boolean) => void
} {
  let currentMatches = initialMatches
  let changeHandler: ((event: MediaQueryListEvent) => void) | null = null

  const addEventListener = vi.fn((type: string, handler: (event: MediaQueryListEvent) => void) => {
    if (type === 'change') changeHandler = handler
  })
  const removeEventListener = vi.fn((type: string) => {
    if (type === 'change') changeHandler = null
  })

  const mql = {
    get matches() {
      return currentMatches
    },
    media: '(min-width: 1024px)',
    addEventListener,
    removeEventListener,
  }

  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue(mql),
  )

  return {
    addEventListener,
    removeEventListener,
    fireChange: (matches: boolean) => {
      currentMatches = matches
      changeHandler?.({ matches } as MediaQueryListEvent)
    },
  }
}

describe('useMediaQuery', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('T-MQ-1: 초기 렌더에 matchMedia(query).matches 값을 반환한다 (matches=true)', () => {
    mockMatchMedia(true)

    const { result } = renderHook(() => useMediaQuery('(min-width: 1024px)'))

    expect(result.current).toBe(true)
  })

  it('T-MQ-2: 초기 렌더에 matchMedia(query).matches 값을 반환한다 (matches=false)', () => {
    mockMatchMedia(false)

    const { result } = renderHook(() => useMediaQuery('(min-width: 1024px)'))

    expect(result.current).toBe(false)
  })

  it('T-MQ-3: change 이벤트가 발생하면 반환값이 갱신되어 리렌더된다', () => {
    const { fireChange } = mockMatchMedia(false)

    const { result } = renderHook(() => useMediaQuery('(min-width: 1024px)'))
    expect(result.current).toBe(false)

    act(() => {
      fireChange(true)
    })

    expect(result.current).toBe(true)
  })

  it('T-MQ-4: 언마운트 시 removeEventListener("change", ...)로 리스너를 해제한다', () => {
    const { addEventListener, removeEventListener } = mockMatchMedia(true)

    const { unmount } = renderHook(() => useMediaQuery('(min-width: 1024px)'))

    expect(addEventListener).toHaveBeenCalledWith('change', expect.any(Function))

    unmount()

    expect(removeEventListener).toHaveBeenCalledWith('change', expect.any(Function))
  })

  it('T-MQ-5: window.matchMedia가 없는 환경에서 throw하지 않고 false를 반환한다', () => {
    vi.stubGlobal('matchMedia', undefined)

    const { result } = renderHook(() => useMediaQuery('(min-width: 1024px)'))

    expect(result.current).toBe(false)
  })
})
