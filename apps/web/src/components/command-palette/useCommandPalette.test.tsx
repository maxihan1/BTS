// useCommandPalette 훅 테스트 — Cmd+K/Ctrl+K 토글 및 enabled(인증) 가드 검증
import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook, fireEvent, act } from '@testing-library/react'
import { useCommandPalette, useCommandPaletteStore } from './useCommandPalette'

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

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-10 F11 — 열림 상태를 zustand 스토어가 소유한다
  //
  // `.` 의 등록 지점은 ShellLayout(`app-shell` 레이어)인데, 팔레트를 렌더하는 것은
  // 그 조상인 RootLayout 이다. 컴포넌트-로컬 useState 로는 ShellLayout 이 그 상태에
  // 닿지 못해 키를 삼키고도 아무 일이 안 일어난다(ADR D-5-a).
  // ───────────────────────────────────────────────────────────────────────────

  it('스토어로 연 팔레트가 훅 반환값에도 반영된다 (F11 `.` — 트리 밖에서 여는 경로)', () => {
    const { result } = renderHook(() => useCommandPalette(true))

    expect(result.current.open).toBe(false)

    act(() => {
      useCommandPaletteStore.getState().setOpen(true)
    })

    expect(result.current.open).toBe(true)
  })

  it('훅의 setOpen 이 스토어에 반영된다 — CommandPalette 의 Esc 닫기가 `.` 을 되살린다', () => {
    const { result } = renderHook(() => useCommandPalette(true))

    act(() => {
      result.current.setOpen(true)
    })

    expect(useCommandPaletteStore.getState().open).toBe(true)
  })

  it('로그아웃 후 재로그인해도 팔레트가 열린 채로 뜨지 않는다 (FR2, E5 — 모듈 전역 스토어 세션 누수 가드)', () => {
    const { result, rerender } = renderHook(({ enabled }) => useCommandPalette(enabled), {
      initialProps: { enabled: true },
    })

    fireEvent.keyDown(document, { key: 'k', metaKey: true })
    expect(result.current.open).toBe(true)

    // 로그아웃 — 스토어는 모듈 전역이라 여기서 비우지 않으면 다음 세션으로 샌다
    rerender({ enabled: false })
    expect(result.current.open).toBe(false)
    expect(useCommandPaletteStore.getState().open).toBe(false)

    // 재로그인 — 이전 세션의 열림이 되살아나면 안 된다
    rerender({ enabled: true })
    expect(result.current.open).toBe(false)
    expect(useCommandPaletteStore.getState().open).toBe(false)
  })
})
