// useDebounce 훅 단위 테스트 — 값 지연 반환 동작 검증
import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useDebounce } from '../use-debounce'

afterEach(() => {
  vi.useRealTimers()
})

describe('useDebounce', () => {
  it('T-DB-1: 초기 렌더 시 입력값이 즉시 반환된다', () => {
    const { result } = renderHook(() => useDebounce('initial', 300))
    expect(result.current).toBe('initial')
  })

  it('T-DB-2: delay 전에는 이전 값이 유지된다', () => {
    vi.useFakeTimers()
    const { result, rerender } = renderHook(
      ({ value }: { value: string }) => useDebounce(value, 300),
      { initialProps: { value: 'initial' } },
    )

    rerender({ value: 'changed' })

    // delay 전 — 여전히 'initial'
    expect(result.current).toBe('initial')
    vi.useRealTimers()
  })

  it('T-DB-3: delay 경과 후 새 값으로 업데이트된다', async () => {
    vi.useFakeTimers()
    const { result, rerender } = renderHook(
      ({ value }: { value: string }) => useDebounce(value, 300),
      { initialProps: { value: 'initial' } },
    )

    rerender({ value: 'changed' })
    expect(result.current).toBe('initial')

    act(() => {
      vi.advanceTimersByTime(300)
    })

    expect(result.current).toBe('changed')
    vi.useRealTimers()
  })

  it('T-DB-4: delay 내에 값이 여러 번 바뀌면 마지막 값만 반영된다 (debounce 효과)', () => {
    vi.useFakeTimers()
    const { result, rerender } = renderHook(
      ({ value }: { value: string }) => useDebounce(value, 300),
      { initialProps: { value: 'a' } },
    )

    rerender({ value: 'ab' })
    rerender({ value: 'abc' })
    rerender({ value: 'abcd' })

    // delay 전 — 초기값 유지
    expect(result.current).toBe('a')

    act(() => {
      vi.advanceTimersByTime(300)
    })

    // delay 경과 후 — 마지막 값
    expect(result.current).toBe('abcd')
    vi.useRealTimers()
  })
})
