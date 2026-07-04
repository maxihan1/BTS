// useKeyboardShortcuts 훅 테스트 — leader 시퀀스/도움말 토글/enabled 가드/입력창 가드/cleanup 검증 (FR-UX-05 Task-2)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useKeyboardShortcuts } from './useKeyboardShortcuts'
import { LEADER_TIMEOUT_MS } from './shortcuts'

// TanStack Router useNavigate 모킹 — 라우터 컨텍스트 없이 단위 테스트 (CommandPalette.test.tsx 패턴 미러)
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

/** keydown 이벤트를 지정한 대상에 디스패치하고 act로 감싸 상태 갱신을 반영한다 */
function dispatchKey(key: string, target: EventTarget = document): void {
  act(() => {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))
  })
}

describe('useKeyboardShortcuts', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('enabled=false면 keydown이 들어와도 navigate를 호출하지 않는다 (E8, FR7)', () => {
    renderHook(() => useKeyboardShortcuts(false))

    dispatchKey('c')

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('enabled=true에서 c 입력 시 새 이슈 생성 경로로 navigate한다', () => {
    renderHook(() => useKeyboardShortcuts(true))

    dispatchKey('c')

    expect(mockNavigate).toHaveBeenCalledTimes(1)
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/new' })
  })

  it('g 다음 i를 타임아웃 이내에 입력하면 내 이슈로 navigate한다', () => {
    renderHook(() => useKeyboardShortcuts(true))

    dispatchKey('g')
    dispatchKey('i')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues' })
  })

  it('g 입력 후 타임아웃이 지나면 leader 시퀀스가 리셋되어 i가 navigate하지 않는다 (E1)', () => {
    vi.useFakeTimers()
    renderHook(() => useKeyboardShortcuts(true))

    dispatchKey('g')
    act(() => {
      vi.advanceTimersByTime(LEADER_TIMEOUT_MS)
    })
    dispatchKey('i')

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('?는 도움말을 열고, 다시 입력하면 닫는다 — 토글 (E9)', () => {
    const { result } = renderHook(() => useKeyboardShortcuts(true))

    dispatchKey('?')
    expect(result.current.helpOpen).toBe(true)

    dispatchKey('?')
    expect(result.current.helpOpen).toBe(false)
  })

  it('도움말 열림 중에는 c가 navigate하지 않고, ?로 닫을 수 있다 (E7)', () => {
    const { result } = renderHook(() => useKeyboardShortcuts(true))

    dispatchKey('?')
    expect(result.current.helpOpen).toBe(true)

    dispatchKey('c')
    expect(mockNavigate).not.toHaveBeenCalled()

    dispatchKey('?')
    expect(result.current.helpOpen).toBe(false)
  })

  it('입력창(input)에 포커스된 상태에서 c는 단축키로 처리되지 않는다 (E4)', () => {
    renderHook(() => useKeyboardShortcuts(true))
    const input = document.createElement('input')
    document.body.appendChild(input)

    dispatchKey('c', input)

    expect(mockNavigate).not.toHaveBeenCalled()
    document.body.removeChild(input)
  })

  it('언마운트 후에는 리스너가 해제되어 keydown이 navigate를 호출하지 않는다 (NFR4)', () => {
    const { unmount } = renderHook(() => useKeyboardShortcuts(true))
    unmount()

    dispatchKey('c')

    expect(mockNavigate).not.toHaveBeenCalled()
  })
})
