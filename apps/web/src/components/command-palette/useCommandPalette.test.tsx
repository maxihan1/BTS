// useCommandPalette 훅 테스트 — Cmd+K/Ctrl+K 토글 및 enabled(인증) 가드 검증
import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, fireEvent } from '@testing-library/react'
import { useCommandPalette } from './useCommandPalette'

describe('useCommandPalette', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('Cmd+K(metaKey) 입력 시 팔레트를 열고 브라우저 기본 동작을 막는다 (FR1, E6)', () => {
    const { result } = renderHook(() => useCommandPalette(true))

    // fireEvent.keyDown은 preventDefault가 호출되면 false를 반환한다 (cancelable 이벤트)
    const notCancelled = fireEvent.keyDown(document, { key: 'k', metaKey: true })

    expect(result.current.open).toBe(true)
    expect(notCancelled).toBe(false)
  })

  it('Ctrl+K(ctrlKey) 입력 시에도 팔레트를 연다 — win/linux', () => {
    const { result } = renderHook(() => useCommandPalette(true))

    fireEvent.keyDown(document, { key: 'k', ctrlKey: true })

    expect(result.current.open).toBe(true)
  })

  it('열린 상태에서 다시 Cmd+K를 누르면 닫힌다 — 토글 (E7)', () => {
    const { result } = renderHook(() => useCommandPalette(true))

    fireEvent.keyDown(document, { key: 'k', metaKey: true })
    expect(result.current.open).toBe(true)

    fireEvent.keyDown(document, { key: 'k', metaKey: true })
    expect(result.current.open).toBe(false)
  })

  it('enabled가 false면 keydown 리스너를 등록하지 않고 open을 강제로 false로 유지한다 (FR2, E5)', () => {
    const addEventListenerSpy = vi.spyOn(document, 'addEventListener')

    const { result } = renderHook(() => useCommandPalette(false))

    fireEvent.keyDown(document, { key: 'k', metaKey: true })

    expect(result.current.open).toBe(false)
    expect(addEventListenerSpy).not.toHaveBeenCalledWith('keydown', expect.any(Function))
  })
})
