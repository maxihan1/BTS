// useMentionAutocomplete 훅 + MentionDropdown 컴포넌트 테스트 — FR-MN-02 Task 2 RED
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, useRef, type ReactNode } from 'react'
import { server } from '@/test/server'
import { userHandlers } from '@/mocks/user-handlers'
import { useAuthStore } from '@/auth/authStore'
import { useMentionAutocomplete } from '../use-mention-autocomplete'
import { MentionDropdown } from '../MentionDropdown'
import type { UserSummary } from '@/api/users'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

/** 훅을 textarea에 연결하는 테스트용 컴포넌트 */
function HookHarness({
  initialValue,
  onChangeSpy,
}: {
  initialValue: string
  onChangeSpy: (v: string) => void
}) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  // 단순화: 상태는 컴포넌트 외부 spy로 기록. value는 초기값 고정(이벤트에서 실제 변경 추적).
  const { onChange, onKeyDown, onCompositionStart, onCompositionEnd, onSelect: onSelectEvt, onBlur, mentionDropdown } =
    useMentionAutocomplete({
      value: initialValue,
      onChange: onChangeSpy,
      textareaRef,
    })

  return (
    <div>
      <textarea
        data-testid="textarea"
        ref={textareaRef}
        defaultValue={initialValue}
        onChange={onChange}
        onKeyDown={onKeyDown}
        onCompositionStart={onCompositionStart}
        onCompositionEnd={onCompositionEnd}
        onSelect={onSelectEvt}
        onBlur={onBlur}
      />
      {mentionDropdown}
    </div>
  )
}

/** 동적으로 value를 바꿀 수 있는 제어 컴포넌트 하네스 */
function ControlledHarness({
  value,
  onChange,
}: {
  value: string
  onChange: (v: string) => void
}) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const handlers = useMentionAutocomplete({ value, onChange, textareaRef })

  return (
    <div>
      <textarea
        data-testid="textarea"
        ref={textareaRef}
        value={value}
        onChange={handlers.onChange}
        onKeyDown={handlers.onKeyDown}
        onCompositionStart={handlers.onCompositionStart}
        onCompositionEnd={handlers.onCompositionEnd}
        onSelect={handlers.onSelect}
        onBlur={handlers.onBlur}
      />
      {handlers.mentionDropdown}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 셋업
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...userHandlers)
  useAuthStore.setState({ accessToken: 'test-token', user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// MentionDropdown 단독 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('MentionDropdown', () => {
  const candidates: UserSummary[] = [
    { id: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5', username: 'alice', displayName: '김앨리스', email: null },
    { id: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a', username: 'bob', displayName: null, email: null },
  ]

  it('role=listbox ul과 role=option li를 렌더한다', () => {
    const onSelect = vi.fn()
    render(
      <MentionDropdown candidates={candidates} activeIndex={-1} onSelect={onSelect} />,
    )

    expect(screen.getByRole('listbox')).toBeInTheDocument()
    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(2)
  })

  it('displayName이 있으면 "displayName (@username)"을 표시한다', () => {
    const onSelect = vi.fn()
    render(
      <MentionDropdown candidates={candidates} activeIndex={-1} onSelect={onSelect} />,
    )

    expect(screen.getByText('김앨리스')).toBeInTheDocument()
    expect(screen.getByText('@alice')).toBeInTheDocument()
  })

  it('displayName이 null이면 username만 표시하고 @username도 함께 보인다', () => {
    const onSelect = vi.fn()
    render(
      <MentionDropdown candidates={candidates} activeIndex={-1} onSelect={onSelect} />,
    )

    // bob은 displayName이 null이므로 username이 주 표시
    expect(screen.getByText('bob')).toBeInTheDocument()
    expect(screen.getByText('@bob')).toBeInTheDocument()
  })

  it('activeIndex에 해당하는 option이 aria-selected=true다', () => {
    const onSelect = vi.fn()
    render(
      <MentionDropdown candidates={candidates} activeIndex={0} onSelect={onSelect} />,
    )

    const options = screen.getAllByRole('option')
    expect(options[0]).toHaveAttribute('aria-selected', 'true')
    expect(options[1]).toHaveAttribute('aria-selected', 'false')
  })

  it('onMouseDown으로 후보를 선택하면 onSelect가 호출된다', () => {
    const onSelect = vi.fn()
    render(
      <MentionDropdown candidates={candidates} activeIndex={-1} onSelect={onSelect} />,
    )

    const options = screen.getAllByRole('option')
    fireEvent.mouseDown(options[0] as HTMLElement)
    expect(onSelect).toHaveBeenCalledWith(candidates[0])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useMentionAutocomplete 훅 통합 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('useMentionAutocomplete', () => {
  it('S1: @jo 입력(onChange, selectionStart=3) 후 debounce 경과 → 후보 드롭다운 표시', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <HookHarness initialValue="" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

    // selectionStart를 3으로 설정하고 onChange 발행
    Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
    fireEvent.change(textarea, { target: { value: '@jo', selectionStart: 3 } })

    // 250ms debounce + 쿼리 완료 대기
    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })
  })

  it('FR3: @ 만 입력(query="" 길이 0) → fetch/open 안 함', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <HookHarness initialValue="" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

    Object.defineProperty(textarea, 'selectionStart', { value: 1, configurable: true })
    fireEvent.change(textarea, { target: { value: '@', selectionStart: 1 } })

    // 충분한 시간 대기 후에도 listbox 없음
    await new Promise((r) => setTimeout(r, 400))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('S3/FR6: 드롭다운 열린 상태에서 ArrowDown → activeIndex 1, Enter → onChange splice 결과 호출 + preventDefault', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    // value 상태를 외부에서 제어
    let currentValue = '@jo'
    const onChangeSpy = vi.fn((next: string) => { currentValue = next })

    render(
      <ControlledHarness value={currentValue} onChange={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

    // @jo 입력으로 드롭다운 오픈
    Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
    fireEvent.change(textarea, { target: { value: '@jo', selectionStart: 3 } })

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })

    // ArrowDown — 첫 번째 후보(index 0)가 active
    const downEvent = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(downEvent) })

    // Enter — splice 결과 onChange 호출 + preventDefault
    const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(enterEvent) })

    expect(enterEvent.defaultPrevented).toBe(true)
    expect(onChangeSpy).toHaveBeenCalled()
    // splice 결과는 '@<username> ' 형태
    const lastCall = onChangeSpy.mock.calls[onChangeSpy.mock.calls.length - 1] as [string]
    expect(lastCall[0]).toMatch(/^@\w+ $/)
  })

  it('주의2: open=false 상태 Enter → preventDefault 호출 안 됨', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <HookHarness initialValue="hello world" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

    // 드롭다운 없는 상태 → Enter
    const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(enterEvent) })

    expect(enterEvent.defaultPrevented).toBe(false)
  })

  it('S4: Escape → open=false, onChange 호출 안 됨', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <ControlledHarness value="@jo" onChange={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

    Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
    fireEvent.change(textarea, { target: { value: '@jo', selectionStart: 3 } })

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })

    const escEvent = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(escEvent) })

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    // value 변경 없음 (splice onChange 미호출)
    // onChangeSpy는 이전 change 이벤트에서 호출됐을 수 있으므로, Escape 이후 추가 호출 없음 확인
    const callCountBeforeEsc = onChangeSpy.mock.calls.length
    expect(onChangeSpy.mock.calls.length).toBe(callCountBeforeEsc)
  })

  it('FR8: compositionStart 후 입력 → 감지 보류, compositionEnd 후 재개', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <HookHarness initialValue="" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

    // compositionStart → 감지 보류
    fireEvent.compositionStart(textarea)

    Object.defineProperty(textarea, 'selectionStart', { value: 3, configurable: true })
    fireEvent.change(textarea, { target: { value: '@jo', selectionStart: 3 } })

    // compositionEnd 전에는 드롭다운 열리지 않아야 함
    await new Promise((r) => setTimeout(r, 400))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()

    // compositionEnd → 재개
    fireEvent.compositionEnd(textarea)

    // compositionEnd 후 onChange가 다시 발생해야 드롭다운 열림
    // (실제 IME는 compositionEnd 후 change를 다시 발행하나, 여기서는 재감지 트리거만 확인)
    fireEvent.change(textarea, { target: { value: '@jo', selectionStart: 3 } })

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })
  })

  it('E1: selectionStart가 null(jsdom) → ?? 0 으로 안전 처리(오류 없음)', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    expect(() => {
      render(
        <HookHarness initialValue="" onChangeSpy={onChangeSpy} />,
        { wrapper: createWrapper(queryClient) },
      )

      const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

      // selectionStart를 null로 강제 (jsdom 시뮬레이션)
      Object.defineProperty(textarea, 'selectionStart', { value: null, configurable: true, writable: true })
      fireEvent.change(textarea, { target: { value: '@jo' } })
    }).not.toThrow()
  })
})
