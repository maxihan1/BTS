// useKeyboardShortcuts 훅 테스트 — leader 시퀀스/도움말 토글/enabled 가드/입력창 가드/cleanup 검증 (FR-UX-05 Task-2)
// + useKeymap 배선(서버 override 반영/비로그인 GET 가드/로딩 중 기본 키맵) 검증 (FR-PF-03 Task-8)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { server } from '@/test/server'
import { KEYMAP_QUERY_KEY } from '@/api/keymap'
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

/** GET /api/v1/users/me/keymap 기본 응답 — override 없는 5종 완비 기본 키맵 */
const DEFAULT_KEYMAP_FIXTURE = {
  bindings: [
    { action: 'help', keyCombo: '?', trigger: 'single', customized: false },
    { action: 'create-issue', keyCombo: 'c', trigger: 'single', customized: false },
    { action: 'search', keyCombo: '/', trigger: 'single', customized: false },
    { action: 'goto-my-issues', keyCombo: 'g i', trigger: 'leader', customized: false },
    { action: 'goto-dashboard', keyCombo: 'g d', trigger: 'leader', customized: false },
  ],
}

/**
 * useKeyboardShortcuts를 QueryClientProvider로 감싸 렌더한다.
 *
 * Task-8부터 훅 내부가 useKeymap(react-query)을 구독하므로 QueryClientProvider 없이는
 * "No QueryClient set" 에러가 난다(useSessionsQuery.test.tsx 등 기존 react-query 훅 테스트 wrapper 패턴 미러).
 *
 * @param enabled useKeyboardShortcuts에 전달할 활성화 플래그(로그인 상태)
 * @returns queryClient(쿼리 상태 직접 조회용) + renderHook 결과
 */
function renderShortcuts(enabled: boolean) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  return { queryClient, ...renderHook(() => useKeyboardShortcuts(enabled), { wrapper }) }
}

describe('useKeyboardShortcuts', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    // 기본 GET 핸들러 — 개별 테스트가 필요 시 server.use()로 덮어쓴다
    server.use(http.get('/api/v1/users/me/keymap', () => HttpResponse.json(DEFAULT_KEYMAP_FIXTURE)))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('enabled=false면 keydown이 들어와도 navigate를 호출하지 않는다 (E8, FR7)', () => {
    renderShortcuts(false)

    dispatchKey('c')

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('enabled=true에서 c 입력 시 새 이슈 생성 경로로 navigate한다', () => {
    renderShortcuts(true)

    dispatchKey('c')

    expect(mockNavigate).toHaveBeenCalledTimes(1)
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/new' })
  })

  it('g 다음 i를 타임아웃 이내에 입력하면 내 이슈로 navigate한다', () => {
    renderShortcuts(true)

    dispatchKey('g')
    dispatchKey('i')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues' })
  })

  it('g 입력 후 타임아웃이 지나면 leader 시퀀스가 리셋되어 i가 navigate하지 않는다 (E1)', () => {
    vi.useFakeTimers()
    renderShortcuts(true)

    dispatchKey('g')
    act(() => {
      vi.advanceTimersByTime(LEADER_TIMEOUT_MS)
    })
    dispatchKey('i')

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('?는 도움말을 열고, 다시 입력하면 닫는다 — 토글 (E9)', () => {
    const { result } = renderShortcuts(true)

    dispatchKey('?')
    expect(result.current.helpOpen).toBe(true)

    dispatchKey('?')
    expect(result.current.helpOpen).toBe(false)
  })

  it('도움말 열림 중에는 c가 navigate하지 않고, ?로 닫을 수 있다 (E7)', () => {
    const { result } = renderShortcuts(true)

    dispatchKey('?')
    expect(result.current.helpOpen).toBe(true)

    dispatchKey('c')
    expect(mockNavigate).not.toHaveBeenCalled()

    dispatchKey('?')
    expect(result.current.helpOpen).toBe(false)
  })

  it('입력창(input)에 포커스된 상태에서 c는 단축키로 처리되지 않는다 (E4)', () => {
    renderShortcuts(true)
    const input = document.createElement('input')
    document.body.appendChild(input)

    dispatchKey('c', input)

    expect(mockNavigate).not.toHaveBeenCalled()
    document.body.removeChild(input)
  })

  it('언마운트 후에는 리스너가 해제되어 keydown이 navigate를 호출하지 않는다 (NFR4)', () => {
    const { unmount } = renderShortcuts(true)
    unmount()

    dispatchKey('c')

    expect(mockNavigate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-PF-03 Task-8 — useKeymap 배선(서버 override 반영 + 비로그인 GET 가드 + 로딩 중 기본 키맵)
// ─────────────────────────────────────────────────────────────────────────────
describe('useKeyboardShortcuts + useKeymap 배선 (FR-PF-03 Task-8)', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('로그인 상태에서 서버 override(create-issue→n)가 로드되면 n이 발화하고 기본 c는 더는 발화하지 않는다 (FR5-b)', async () => {
    server.use(
      http.get('/api/v1/users/me/keymap', () =>
        HttpResponse.json({
          bindings: [
            { action: 'help', keyCombo: '?', trigger: 'single', customized: false },
            { action: 'create-issue', keyCombo: 'n', trigger: 'single', customized: true },
            { action: 'search', keyCombo: '/', trigger: 'single', customized: false },
            { action: 'goto-my-issues', keyCombo: 'g i', trigger: 'leader', customized: false },
            { action: 'goto-dashboard', keyCombo: 'g d', trigger: 'leader', customized: false },
          ],
        }),
      ),
    )
    const { queryClient } = renderShortcuts(true)

    await waitFor(() => expect(queryClient.getQueryState(KEYMAP_QUERY_KEY)?.status).toBe('success'))

    dispatchKey('c')
    expect(mockNavigate).not.toHaveBeenCalled()

    dispatchKey('n')
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/new' })
  })

  it('enabled=false(비로그인)이면 useKeymap이 GET을 호출하지 않고 기본 키맵으로 동작한다 (FR7)', () => {
    let requested = false
    server.use(
      http.get('/api/v1/users/me/keymap', () => {
        requested = true
        return HttpResponse.json(DEFAULT_KEYMAP_FIXTURE)
      }),
    )

    renderShortcuts(false)
    dispatchKey('c')

    expect(requested).toBe(false)
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('쿼리 응답이 도착하기 전(로딩 중)에는 기본 키맵으로 즉시 동작한다 (무회귀)', () => {
    server.use(http.get('/api/v1/users/me/keymap', () => HttpResponse.json(DEFAULT_KEYMAP_FIXTURE)))

    // renderHook 직후 await 없이 곧바로 dispatch — fetch가 아직 resolve되지 않은 시점(로딩 중)을 보장한다
    renderShortcuts(true)
    dispatchKey('c')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/new' })
  })
})
