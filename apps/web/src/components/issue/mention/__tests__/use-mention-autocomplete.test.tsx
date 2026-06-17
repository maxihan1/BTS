// useMentionAutocomplete 훅 + MentionDropdown 컴포넌트 테스트 — FR-MN-02 Task 2 RED
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, useRef, useState, type ReactNode } from 'react'
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

/**
 * 제어 컴포넌트 하네스.
 * - value는 외부에서 주입된 초기값으로 시작, onChange spy가 불리면 내부 상태 갱신.
 * - selectionStart 시뮬레이션: textarea에 직접 접근해 property를 설정 후 onSelect 이벤트 발행.
 */
function ControlledHarness({
  initialValue,
  onChangeSpy,
}: {
  initialValue: string
  onChangeSpy: (v: string) => void
}) {
  const [value, setValue] = useState(initialValue)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  function handleChange(next: string) {
    setValue(next)
    onChangeSpy(next)
  }

  const handlers = useMentionAutocomplete({ value, onChange: handleChange, textareaRef })

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

/**
 * textarea의 value와 selectionStart를 동시에 시뮬레이션한다.
 *
 * @testing-library/dom의 fireEvent.change는 target 객체 assign 시 기존
 * Object.defineProperty로 고정된 속성을 readonly로 처리해 에러가 난다.
 * 대신:
 * 1) textarea.value를 DOM native setter로 직접 설정
 * 2) fireEvent.change 발행 (React onChange 핸들러 호출)
 * 3) selectionStart를 defineProperty로 설정 후 onSelect 발행 (caret 전달)
 *
 * 훅은 onChange(e.currentTarget.value + selectionStart)와
 * onSelect(e.currentTarget.value + selectionStart)를 모두 읽으므로 두 경로 커버.
 *
 * 모든 이벤트를 act()로 감싸서 React 상태 업데이트가 즉시 flush되도록 한다.
 * act() 없이 이벤트를 발행하면 비동기 업데이트가 다음 테스트로 전파될 수 있다.
 */
async function triggerMentionInput(
  textarea: HTMLTextAreaElement,
  text: string,
  caret: number,
): Promise<void> {
  // 1) DOM value를 네이티브 setter로 직접 설정 (fireEvent.change의 target assign과 충돌 방지)
  const nativeValueSetter = Object.getOwnPropertyDescriptor(
    HTMLTextAreaElement.prototype,
    'value',
  )?.set

  // 2+3) value 설정과 change 이벤트를 같은 act 블록에서 실행
  await act(() => {
    if (nativeValueSetter !== undefined) {
      nativeValueSetter.call(textarea, text)
    } else {
      // 폴백: 직접 할당
      textarea.value = text
    }
    fireEvent.change(textarea)
  })

  // 4) selectionStart 설정 후 onSelect 발행 (caret 전달)
  await act(() => {
    Object.defineProperty(textarea, 'selectionStart', {
      value: caret,
      configurable: true,
      writable: true,
    })
    fireEvent.select(textarea)
  })
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

  it('displayName이 있으면 displayName과 @username을 함께 표시한다', () => {
    const onSelect = vi.fn()
    render(
      <MentionDropdown candidates={candidates} activeIndex={-1} onSelect={onSelect} />,
    )

    expect(screen.getByText('김앨리스')).toBeInTheDocument()
    expect(screen.getByText('@alice')).toBeInTheDocument()
  })

  it('displayName이 null이면 username과 @username을 함께 표시한다', () => {
    const onSelect = vi.fn()
    render(
      <MentionDropdown candidates={candidates} activeIndex={-1} onSelect={onSelect} />,
    )

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

  /**
   * NOTE: 이 테스트는 MentionDropdown describe 블록의 맨 마지막에 위치해야 한다.
   * fireEvent.mouseDown이 jsdom 내부 이벤트 처리 상태를 변경해,
   * 이후 useMentionAutocomplete의 composition/select 이벤트 핸들러에 영향을 줄 수 있다.
   * 블록 마지막 배치로 useMentionAutocomplete 테스트가 영향을 받지 않도록 한다.
   */
  it('onMouseDown으로 후보를 선택하면 onSelect가 해당 UserSummary와 함께 호출된다', () => {
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
  it('S1: @jo 입력(onChange+onSelect, selectionStart=3) 후 debounce 경과 → 후보 드롭다운 표시', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <ControlledHarness initialValue="" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement
    await triggerMentionInput(textarea, '@jo', 3)

    // MSW를 통한 실제 fetch를 기다린다.
    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })
  })

  it('FR3: @ 만 입력(query="" 길이 0) → fetch/open 안 함', () => {
    /**
     * fake timer로 debounce를 즉시 통과해도 open=false임을 확인한다.
     * query='', MIN_QUERY_LENGTH=1 조건으로 useUsers가 enabled=false.
     */
    vi.useFakeTimers()
    try {
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const onChangeSpy = vi.fn()

      render(
        <ControlledHarness initialValue="" onChangeSpy={onChangeSpy} />,
        { wrapper: createWrapper(queryClient) },
      )

      const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

      // triggerMentionInput은 async이지만 fake timer 환경에서는 await 없이 호출
      // (async 내부의 act()는 fake timer와 맞지 않으므로 직접 동기 방식으로)
      const nativeValueSetter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set
      act(() => {
        if (nativeValueSetter !== undefined) nativeValueSetter.call(textarea, '@')
        fireEvent.change(textarea)
      })
      act(() => {
        Object.defineProperty(textarea, 'selectionStart', { value: 1, configurable: true, writable: true })
        fireEvent.select(textarea)
      })

      // debounce 시간 넉넉히 경과 후에도 listbox 없음
      act(() => { vi.advanceTimersByTime(500) })
      expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('S3/FR6: 드롭다운 열린 상태에서 ArrowDown → Enter → onChange splice 결과 호출 + preventDefault', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <ControlledHarness initialValue="" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement
    await triggerMentionInput(textarea, '@jo', 3)

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })

    // ArrowDown — 첫 번째 후보(index 0)가 active
    const downEvent = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(downEvent) })
    expect(downEvent.defaultPrevented).toBe(true)

    // Enter — splice 결과 onChange 호출 + preventDefault
    const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(enterEvent) })

    expect(enterEvent.defaultPrevented).toBe(true)
    // onChange가 '@<username> ' 형태의 splice 결과로 호출됨
    expect(onChangeSpy).toHaveBeenCalled()
    const calls = onChangeSpy.mock.calls
    const lastArg = calls[calls.length - 1]?.[0] as string | undefined
    expect(lastArg).toMatch(/^@\w+ ?/)
  })

  it('주의2: open=false 상태 Enter → preventDefault 호출 안 됨', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <ControlledHarness initialValue="hello world" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

    // 드롭다운 없는 상태 → Enter
    const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(enterEvent) })

    expect(enterEvent.defaultPrevented).toBe(false)
  })

  it('S4: Escape → 드롭다운 닫힘, splice onChange 미호출', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    render(
      <ControlledHarness initialValue="" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement
    await triggerMentionInput(textarea, '@jo', 3)

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })

    // onChange 호출 카운트 기록 (멘션 입력 시 호출됨)
    const callCountBeforeEsc = onChangeSpy.mock.calls.length

    const escEvent = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(escEvent) })

    // 드롭다운 닫힘
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    // splice onChange 추가 호출 없음
    expect(onChangeSpy.mock.calls.length).toBe(callCountBeforeEsc)
  })

  it('FR8: compositionStart 후 입력 → 감지 보류(open=false), compositionEnd 후 재개(open=true)', async () => {
    /**
     * IME 감지 보류/재개를 검증한다.
     * - 감지 보류: isComposingRef=true 중 detectAndUpdate early return → open 미변경
     * - 감지 재개: compositionEnd 후 isComposingRef=false → detectAndUpdate 정상 실행
     *
     * 캐시 seed + waitFor로 검증한다. MentionDropdown onMouseDown 테스트와의 간섭을
     * 막기 위해 별도 describe + 자체 cleanup 전략 없이, beforeEach를 재활용한다.
     *
     * waitFor가 실패하는 근본 원인: fireEvent.mouseDown(li)이 jsdom 내부 state를 변경해
     * 이후 waitFor polling이 block되는 현상. 이를 우회하기 위해 테스트 시작 시
     * document.activeElement 초기화(body.blur())를 적용한다.
     */
    // jsdom 내부 포커스 상태 초기화 (이전 테스트의 mousedown 잔재 해소)
    act(() => { (document.activeElement as HTMLElement | null)?.blur?.() })

    // staleTime: Infinity로 캐시 seed 데이터가 revalidation fetch 없이 즉시 반환되도록 설정
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity } },
    })
    const joResults: UserSummary[] = [
      { id: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5', username: 'joanna', displayName: '조안나', email: null },
    ]
    const onChangeSpy = vi.fn()
    const nativeSetter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set

    render(
      <ControlledHarness initialValue="" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    // 캐시 seed — useUsers('jo') 활성화 즉시 반환 (staleTime: Infinity로 재요청 없음)
    act(() => { queryClient.setQueryData(['users', 'jo'], joResults) })

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

    // Step 1: compositionStart → 감지 보류
    await act(() => { fireEvent.compositionStart(textarea) })

    // Step 2: IME 중 '@jo' 입력 → 감지 보류 확인
    await act(() => {
      if (nativeSetter !== undefined) nativeSetter.call(textarea, '@jo')
      fireEvent.change(textarea)
    })
    await act(() => {
      Object.defineProperty(textarea, 'selectionStart', {
        value: 3,
        configurable: true,
        writable: true,
      })
      fireEvent.select(textarea)
    })
    // IME 중 — open=false 확인
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()

    // Step 3: compositionEnd — isComposingRef=false + detectAndUpdate 즉시 실행
    // handleCompositionEnd가 compositionEnd 시점의 value/selectionStart로 재감지하므로
    // 별도 select 이벤트 없이 드롭다운이 열려야 한다.
    await act(() => {
      Object.defineProperty(textarea, 'selectionStart', {
        value: 3,
        configurable: true,
        writable: true,
      })
      fireEvent.compositionEnd(textarea)
    })

    // 드롭다운 노출 확인 (staleTime:Infinity 캐시 hit → debounce 250ms 경과 후 즉시 표시)
    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })
  })

  // ── FR-MN-02 코드리뷰 수정 2: reset() 반환 + 드롭다운 닫힘 ─────────────────

  it('CR2: reset() 호출 시 드롭다운이 닫힌다', async () => {
    /**
     * @al 입력으로 드롭다운을 연 뒤 reset()을 호출해 listbox가 사라지는지 검증.
     * ControlledHarness에 reset을 노출하기 위해 inline 하네스를 사용한다.
     */
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    let capturedReset: (() => void) | undefined

    function ResetHarness() {
      const [value, setValue] = useState('')
      const textareaRef = useRef<HTMLTextAreaElement | null>(null)
      const handlers = useMentionAutocomplete({ value, onChange: setValue, textareaRef })
      capturedReset = handlers.reset
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

    render(<ResetHarness />, { wrapper: createWrapper(queryClient) })

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement
    await triggerMentionInput(textarea, '@al', 3)

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })

    // reset() 호출 → 드롭다운 닫힘
    act(() => { capturedReset?.() })

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  // ── FR-MN-02 코드리뷰 수정 3: activeIndex clamp ──────────────────────────

  it('CR3: candidates 축소 시 activeIndex가 유효 범위로 clamp된다', async () => {
    /**
     * activeIndex=2인 상태에서 candidates가 2개로 줄면(인덱스 0~1)
     * activeIndex가 1(마지막 유효)로 clamp되어 Enter가 먹통이 되지 않음을 검증.
     *
     * 구조: queryClient.setQueryData 로 candidates를 직접 교체해 길이 변경을 시뮬레이션.
     * 훅은 candidates.length dep useEffect로 clamp를 수행해야 한다.
     */
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity } },
    })

    const manyUsers: UserSummary[] = [
      { id: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5', username: 'alice', displayName: '앨리스', email: null },
      { id: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a', username: 'bob', displayName: '밥', email: null },
      { id: 'c3d4e5f6-a7b8-4c9d-0e1f-2a3b4c5d6e7f', username: 'carol', displayName: '캐롤', email: null },
    ]
    const fewUsers: UserSummary[] = [
      { id: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5', username: 'alice', displayName: '앨리스', email: null },
      { id: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a', username: 'bob', displayName: '밥', email: null },
    ]

    const onChangeSpy = vi.fn()

    render(
      <ControlledHarness initialValue="" onChangeSpy={onChangeSpy} />,
      { wrapper: createWrapper(queryClient) },
    )

    // 캐시를 3명으로 시드 후 @al 입력
    act(() => { queryClient.setQueryData(['users', 'al'], manyUsers) })

    const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement
    await triggerMentionInput(textarea, '@al', 3)

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).toBeInTheDocument()
    }, { timeout: 1000 })

    // ArrowDown 2번 → activeIndex=2 (carol, 인덱스 2)
    act(() => { textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true })) })
    act(() => { textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true })) })
    act(() => { textarea.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true })) })

    // candidates를 2명으로 축소 → activeIndex=2가 범위 초과(0~1)
    act(() => { queryClient.setQueryData(['users', 'al'], fewUsers) })
    // TanStack Query 구독 알림이 React에 전달되어 re-render가 완료되기를 기다린다
    await waitFor(() => {
      // listbox options가 2개로 줄어들었을 때 candidates 교체 완료
      expect(screen.getAllByRole('option')).toHaveLength(2)
    }, { timeout: 500 })

    // Enter — clamp 후 유효 후보(alice|bob)가 선택되어야 한다
    const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    act(() => { textarea.dispatchEvent(enterEvent) })

    expect(enterEvent.defaultPrevented).toBe(true)
    // splice 결과(유효 username)가 onChange에 전달됨 — undefined 먹통 아님
    const calls = onChangeSpy.mock.calls
    const lastArg = calls[calls.length - 1]?.[0] as string | undefined
    expect(lastArg).toMatch(/^@(alice|bob) /)
  })

  it('E1: selectionStart가 null(jsdom) → ?? 0 으로 안전 처리(오류 없음)', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const onChangeSpy = vi.fn()

    expect(() => {
      render(
        <ControlledHarness initialValue="" onChangeSpy={onChangeSpy} />,
        { wrapper: createWrapper(queryClient) },
      )

      const textarea = screen.getByTestId('textarea') as HTMLTextAreaElement

      // selectionStart를 null로 강제 (jsdom 미포커스 시나리오)
      Object.defineProperty(textarea, 'selectionStart', {
        value: null,
        configurable: true,
        writable: true,
      })
      // onSelect 발행 — ?? 0 fallback 동작 확인
      fireEvent.select(textarea)
    }).not.toThrow()
  })
})
