// useKeyboardShortcuts 훅 테스트 — leader 시퀀스/도움말 토글/enabled 가드/입력창 가드/cleanup 검증 (FR-UX-05 Task-2)
// + useKeymap 배선(서버 override 반영/비로그인 GET 가드/로딩 중 기본 키맵) 검증 (FR-PF-03 Task-8)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { server } from '@/test/server'
import { KEYMAP_QUERY_KEY } from '@/api/keymap'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { useKeyboardShortcuts } from './useKeyboardShortcuts'
import { LEADER_TIMEOUT_MS } from './shortcuts'
import { useContextShortcuts, useContextShortcutsStore } from './useContextShortcuts'

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

// ─────────────────────────────────────────────────────────────────────────────
// 컨텍스트 단축키 폴백 (FR-UX-10 F10 Task-3)
//
// 전역 SHORTCUTS 가 먼저 판별하고, 그 결과가 none 일 때만 컨텍스트로 폴백한다.
// leader 대기(E6)·도움말 열림(E7)에서는 폴백하지 않는다 — 이 두 가드를 한
// 파이프라인에 두는 것이 ADR D-2 의 이유다.
// ─────────────────────────────────────────────────────────────────────────────

/** keydown 을 cancelable 로 디스패치하고 이벤트를 돌려준다 — preventDefault 검증용 */
function dispatchCancelableKey(key: string): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
  act(() => {
    document.dispatchEvent(event)
  })
  return event
}

describe('useKeyboardShortcuts — 컨텍스트 단축키 폴백 (FR-UX-10 F10)', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    useContextShortcutsStore.setState({ handlers: {} })
    server.use(http.get('/api/v1/users/me/keymap', () => HttpResponse.json(DEFAULT_KEYMAP_FIXTURE)))
  })

  it('전역 미매칭 키 j 가 활성 컨텍스트로 폴백해 커서를 움직인다 (FR2)', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))
    renderShortcuts(true)

    dispatchKey('j')

    expect(onCursorMove).toHaveBeenCalledWith(1)
  })

  it('k 는 반대 방향으로 폴백한다', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))
    renderShortcuts(true)

    dispatchKey('k')

    expect(onCursorMove).toHaveBeenCalledWith(-1)
  })

  it('★E6 — g(leader) 직후 j 는 무동작이다. 시퀀스만 리셋되고 커서는 움직이지 않는다', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))
    renderShortcuts(true)

    dispatchKey('g')
    dispatchKey('j')

    expect(onCursorMove).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('★E6 후속 — 리셋된 뒤 다시 누른 j 는 정상 발화한다', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))
    renderShortcuts(true)

    dispatchKey('g')
    dispatchKey('j')
    dispatchKey('j')

    expect(onCursorMove).toHaveBeenCalledOnce()
    expect(onCursorMove).toHaveBeenCalledWith(1)
  })

  it('★E7 — 도움말 열림 중에는 j 도 [ 도 무동작이다 (배후 목록이 움직이면 안 된다)', () => {
    const onCursorMove = vi.fn()
    const onToggleSidebar = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))
    renderHook(() => useContextShortcuts('app-shell', { onToggleSidebar }))
    renderShortcuts(true)

    dispatchKey('?')
    dispatchKey('j')
    dispatchKey('[')

    expect(onCursorMove).not.toHaveBeenCalled()
    expect(onToggleSidebar).not.toHaveBeenCalled()
  })

  it('★C2-b — 컨텍스트 키가 발화하면 preventDefault 를 호출한다 (후행 bubble 리스너 차단)', () => {
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove: vi.fn() }))
    renderShortcuts(true)

    const event = dispatchCancelableKey('j')

    expect(event.defaultPrevented).toBe(true)
  })

  it('미등록 키는 preventDefault 하지 않는다 (브라우저 기본 동작 보존)', () => {
    renderHook(() => useContextShortcuts('issue-list', {}))
    renderShortcuts(true)

    const event = dispatchCancelableKey('z')

    expect(event.defaultPrevented).toBe(false)
  })

  it('E12 — 컨텍스트가 app-shell 뿐이면 j 는 죽고 [ 만 산다', () => {
    const onCursorMove = vi.fn()
    const onToggleSidebar = vi.fn()
    renderHook(() => useContextShortcuts('app-shell', { onToggleSidebar, onCursorMove }))
    renderShortcuts(true)

    dispatchKey('j')
    dispatchKey('[')

    expect(onCursorMove).not.toHaveBeenCalled()
    expect(onToggleSidebar).toHaveBeenCalledOnce()
  })

  it('E10 — 입력창 포커스 중에는 컨텍스트 키도 무동작이다', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))
    renderShortcuts(true)

    const input = document.createElement('input')
    document.body.appendChild(input)
    dispatchKey('j', input)
    input.remove()

    expect(onCursorMove).not.toHaveBeenCalled()
  })

  it('E11 — 비로그인(enabled=false)이면 컨텍스트 키도 발화하지 않는다', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))
    renderShortcuts(false)

    dispatchKey('j')

    expect(onCursorMove).not.toHaveBeenCalled()
  })

  it('전역 키는 컨텍스트보다 우선한다 — c 는 여전히 새 이슈로 간다', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))
    renderShortcuts(true)

    dispatchKey('c')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/new' })
    expect(onCursorMove).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 판별식 — ADR D-2 를 기계로 강제한다.
//
// "리스너 스파이 카운트" 로는 못 잡는다. 테스트 환경에 다른 훅이 함께 마운트되면
// 카운트가 1이 아니고, 다른 파일이 리스너를 추가해도 자기 파일만 보는 단언은
// 침묵한다. 그래서 디렉토리 소스를 직접 센다.
// ─────────────────────────────────────────────────────────────────────────────

describe('판별식 — keyboard-shortcuts 디렉토리의 keydown 리스너는 정확히 1개', () => {
  it('document/window 에 keydown 을 거는 곳이 useKeyboardShortcuts.ts 한 곳뿐이다 (ADR D-2)', () => {
    // jsdom 환경의 `import.meta.url` 은 file 스킴이 아니라 fileURLToPath 가 던진다.
    // vitest 는 apps/web 을 cwd 로 돌므로 거기서 상대 경로로 짚는다.
    const dir = join(process.cwd(), 'src/components/keyboard-shortcuts')
    const sources = readdirSync(dir).filter(
      (name) => /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name),
    )

    const owners = sources.filter((name) =>
      /(?:document|window)\.addEventListener\(\s*['"]keydown['"]/.test(
        readFileSync(join(dir, name), 'utf8'),
      ),
    )

    expect(owners).toEqual(['useKeyboardShortcuts.ts'])
  })
})
