// WatchersSection 컴포넌트 단위 테스트 — FR-WT-01 D6 Task-2 (TDD RED)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactNode } from 'react'
import { createRef } from 'react'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { aliceUser } from '@/mocks/auth-fixtures'
import { WatchersSection } from './WatchersSection'

// sonner toast mock — 실제 DOM 없이 호출 여부만 검증
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// toast mock 참조 — P2 테스트에서 호출 검증
import { toast } from 'sonner'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처 — Zod v4 UUID 검증을 통과하는 RFC4122 v4 형식 UUID
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 테스트 userId — RFC4122 v4 형식 (Zod v4 UUID 검증 통과).
 * auth store를 이 값으로 시드하고 MSW 응답 watcher.userId도 이 값을 사용한다.
 */
const ALICE_UUID = 'a0000000-0000-4000-a000-000000000001'
/**
 * bob 테스트 userId — RFC4122 v4 형식 (Zod v4 UUID 검증 통과).
 */
const BOB_UUID = 'b0000000-0000-4000-a000-000000000002'

/** alice 워처 응답 픽스처 */
const aliceWatcher = { userId: ALICE_UUID, displayName: 'User alice' }
/** bob 워처 응답 픽스처 */
const bobWatcher = { userId: BOB_UUID, displayName: 'User bob' }

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

/**
 * 실브라우저 규칙을 jsdom 에 재현한다 — `disabled` 로 전환된 요소는 포커스를 잃고,
 * 다시 enabled 가 돼도 포커스는 돌아오지 않는다 (activeElement 는 `<body>` 로 떨어진다).
 *
 * jsdom 29 는 이 규칙을 구현하지 않는다(실측: `disabled=true` 이후에도 activeElement 유지).
 * 그래서 "유닛 초록 · 실브라우저에서는 스크린리더 무음" 이 그대로 통과했다.
 *
 * body 에 `tabindex=-1` 을 잠시 붙이는 이유는 jsdom 이 focusable 하지 않은 요소의
 * `focus()` 를 무시하기 때문이다 — 실브라우저의 "activeElement=BODY" 를 만들기 위한 최소 장치다.
 *
 * (FavoriteButton.test.tsx 에도 같은 헬퍼가 있다. 테스트 전용 장치를 공유 모듈로 올리면
 *  프로덕션 번들 경계가 흐려져 각 파일에 국소 보관한다.)
 *
 * @returns 관찰을 해제하고 body 를 원상 복구하는 함수
 */
function emulateDisabledFocusLoss(): () => void {
  const observer = new MutationObserver((records) => {
    for (const record of records) {
      const el = record.target
      if (el instanceof HTMLButtonElement && el.disabled && document.activeElement === el) {
        document.body.tabIndex = -1
        document.body.focus()
      }
    }
  })
  observer.observe(document.body, {
    attributes: true,
    attributeFilter: ['disabled'],
    subtree: true,
  })
  return () => {
    observer.disconnect()
    document.body.removeAttribute('tabindex')
  }
}

/**
 * 수동으로 열어줄 때까지 응답하지 않는 게이트를 만든다 — mutation in-flight 구간을 고정한다.
 *
 * @returns 대기용 promise 와 이를 여는 release 함수
 */
function createGate(): { gate: Promise<void>; release: () => void } {
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  return { gate, release }
}

/**
 * GET /api/v1/issues/:key/watchers 핸들러를 server.use()로 등록한다.
 *
 * @param issueKey 이슈 키
 * @param opts.watchers 워처 목록
 * @param opts.isWatching 현재 사용자 워칭 여부
 */
function useWatcherGetHandler(
  issueKey: string,
  opts: {
    watchers: ReadonlyArray<{ userId: string; displayName: string }>
    isWatching: boolean
  },
) {
  server.use(
    http.get(`/api/v1/issues/${issueKey}/watchers`, () =>
      HttpResponse.json({
        data: {
          watchers: opts.watchers,
          count: opts.watchers.length,
          isWatching: opts.isWatching,
        },
      }),
    ),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup / Teardown
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  // alice 로그인 상태 시드 — userId는 RFC4122 v4 형식 UUID로 오버라이드
  useAuthStore.getState().setSession({
    accessToken: 'test-token',
    user: { ...aliceUser, userId: ALICE_UUID },
  })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. 목록 렌더 — 카운트 + displayName
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S1 목록 렌더', () => {
  it('S1a: 워처 카운트 "N명"과 displayName이 표시된다', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [bobWatcher], isWatching: false })
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    expect(await screen.findByText('1명')).toBeInTheDocument()
    expect(screen.getByText('User bob')).toBeInTheDocument()
  })

  it('S1b: 감시자가 없으면 "0명"과 빈 상태 메시지가 표시된다', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [], isWatching: false })
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    expect(await screen.findByText('0명')).toBeInTheDocument()
    expect(screen.getByText('감시자가 없습니다.')).toBeInTheDocument()
  })

  it('S1c: 본인(alice)은 displayName 뒤에 "(나)"가 표기된다', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [aliceWatcher], isWatching: true })
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    expect(await screen.findByText(/\(나\)/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 토글 버튼 초기 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S2 토글 버튼 초기 상태', () => {
  it('S2a: isWatching=false 이면 버튼 텍스트가 "지켜보기"이다', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [], isWatching: false })
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '지켜보기' })
    expect(btn).toBeInTheDocument()
    expect(btn).toHaveAttribute('aria-pressed', 'false')
  })

  it('S2b: isWatching=true 이면 버튼 텍스트가 "지켜보는 중"이다', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [aliceWatcher], isWatching: true })
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '지켜보는 중' })
    expect(btn).toBeInTheDocument()
    expect(btn).toHaveAttribute('aria-pressed', 'true')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. Watch 클릭 → POST self → 카운트+1 · 버튼 "지켜보는 중"
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S3 Watch 클릭 (POST self)', () => {
  it('S3a: "지켜보기" 클릭 시 POST가 호출되고 카운트가 +1 되며 버튼이 "지켜보는 중"으로 바뀐다', async () => {
    const user = userEvent.setup()

    let getCallCount = 0
    server.use(
      http.get('/api/v1/issues/ATLAS-1/watchers', () => {
        getCallCount++
        if (getCallCount === 1) {
          return HttpResponse.json({
            data: { watchers: [bobWatcher], count: 1, isWatching: false },
          })
        }
        // POST 후 refetch — alice 포함
        return HttpResponse.json({
          data: { watchers: [bobWatcher, aliceWatcher], count: 2, isWatching: true },
        })
      }),
      http.post('/api/v1/issues/ATLAS-1/watchers', () => new HttpResponse(null, { status: 201 })),
    )

    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    // 카운트 표시될 때까지 대기 (로딩 완료 후 버튼 클릭)
    expect(await screen.findByText('1명')).toBeInTheDocument()
    const btn = screen.getByRole('button', { name: '지켜보기' })

    await user.click(btn)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '지켜보는 중' })).toBeInTheDocument()
    })
    expect(screen.getByText('2명')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. Unwatch 클릭 → DELETE(내 userId) → 카운트-1
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S4 Unwatch 클릭 (DELETE)', () => {
  it('S4a: "지켜보는 중" 클릭 시 DELETE가 호출되고 카운트가 -1 된다', async () => {
    const user = userEvent.setup()

    let getCallCount = 0
    server.use(
      http.get('/api/v1/issues/ATLAS-1/watchers', () => {
        getCallCount++
        if (getCallCount === 1) {
          return HttpResponse.json({
            data: { watchers: [aliceWatcher, bobWatcher], count: 2, isWatching: true },
          })
        }
        return HttpResponse.json({
          data: { watchers: [bobWatcher], count: 1, isWatching: false },
        })
      }),
      http.delete(`/api/v1/issues/ATLAS-1/watchers/${ALICE_UUID}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '지켜보는 중' })
    expect(screen.getByText('2명')).toBeInTheDocument()

    await user.click(btn)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '지켜보기' })).toBeInTheDocument()
    })
    expect(screen.getByText('1명')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 에러 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S5 에러 상태', () => {
  it('S5a: GET 에러 시 에러 안내 문구가 표시된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-ERR/watchers', () =>
        HttpResponse.json({ errorCode: 'ISSUE_NOT_FOUND' }, { status: 404 }),
      ),
    )
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-ERR" />, { wrapper: Wrapper })

    expect(await screen.findByText('감시자를 불러오지 못했습니다.')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. data-testid 존재 확인 (E2E 셀렉터용)
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S6 data-testid', () => {
  it('S6a: 섹션 컨테이너와 토글 버튼에 data-testid가 존재한다', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [], isWatching: false })
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    await screen.findByText('감시자가 없습니다.')

    expect(screen.getByTestId('watchers-section')).toBeInTheDocument()
    expect(screen.getByTestId('watch-toggle-button')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. P1 — 로딩/에러 윈도우 토글 가드
// GET in-flight 또는 data===undefined 상태에서 버튼이 비활성으로 표시돼야 하고,
// 클릭해도 mutate가 발사되지 않아야 한다.
//
// FR-UX-10 F11 Task-5b 이후 비활성 표기는 네이티브 disabled 가 아니라 aria-disabled 다
// (네이티브 disabled 는 포커스를 <body> 로 떨어뜨려 스크린리더를 무음으로 만든다).
// 따라서 "클릭해도 안 나간다"의 증인이 브라우저에서 handleToggle 첫 줄 `if (!canToggle) return`
// 으로 옮겨왔다 — S7b 가 그 가드의 짝 테스트다.
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S7 로딩 윈도우 토글 가드 (P1)', () => {
  it('S7a: GET 로딩 중에 토글 버튼이 aria-disabled 로 비활성을 알린다 (네이티브 disabled 금지)', async () => {
    // GET이 절대 응답하지 않도록 하여 로딩 상태를 고정한다.
    server.use(
      http.get('/api/v1/issues/ATLAS-LOADING/watchers', () => {
        // 응답을 보내지 않으면 로딩 상태가 유지된다 — pending 상태 시뮬레이션
        return new Promise<never>(() => {
          // 영원히 pending
        })
      }),
    )
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-LOADING" />, { wrapper: Wrapper })

    // 버튼은 즉시 렌더되어야 하고, 로딩 중에는 비활성으로 표시돼야 한다.
    const btn = screen.getByTestId('watch-toggle-button')
    expect(btn).toHaveAttribute('aria-disabled', 'true')
    expect(btn).not.toBeDisabled()
  })

  it('S7b: GET 로딩 중 클릭해도 mutate가 발사되지 않는다', async () => {
    const user = userEvent.setup()
    let postCalled = false
    let deleteCalled = false

    server.use(
      http.get('/api/v1/issues/ATLAS-LOADING2/watchers', () => {
        return new Promise<never>(() => {
          // 영원히 pending
        })
      }),
      http.post('/api/v1/issues/ATLAS-LOADING2/watchers', () => {
        postCalled = true
        return new HttpResponse(null, { status: 201 })
      }),
      http.delete('/api/v1/issues/ATLAS-LOADING2/watchers/:userId', () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-LOADING2" />, { wrapper: Wrapper })

    const btn = screen.getByTestId('watch-toggle-button')
    // disabled 버튼에 강제 클릭 시도
    await user.click(btn)

    // 어떤 mutation도 발사되지 않아야 한다
    expect(postCalled).toBe(false)
    expect(deleteCalled).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. P2 — mutation 실패 시 toast.error 호출
// watch(POST) / unwatch(DELETE) 실패 시 사용자에게 에러 토스트를 보여야 한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S8 mutation 실패 시 toast.error (P2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('S8a: watch(POST) 실패 시 toast.error가 호출된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-FAIL/watchers', () =>
        HttpResponse.json({
          data: { watchers: [], count: 0, isWatching: false },
        }),
      ),
      http.post('/api/v1/issues/ATLAS-FAIL/watchers', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_ACCESS_DENIED', message: '권한이 없습니다' },
          { status: 403 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-FAIL" />, { wrapper: Wrapper })

    // 데이터 로드 완료 대기
    await screen.findByText('0명')
    const btn = screen.getByRole('button', { name: '지켜보기' })
    await user.click(btn)

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledTimes(1)
    })
  })

  it('S8b: unwatch(DELETE) 실패 시 toast.error가 호출된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.get('/api/v1/issues/ATLAS-FAIL2/watchers', () =>
        HttpResponse.json({
          data: {
            watchers: [aliceWatcher],
            count: 1,
            isWatching: true,
          },
        }),
      ),
      http.delete(`/api/v1/issues/ATLAS-FAIL2/watchers/${ALICE_UUID}`, () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_ACCESS_DENIED', message: '권한이 없습니다' },
          { status: 403 },
        ),
      ),
    )

    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-FAIL2" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '지켜보는 중' })
    await user.click(btn)

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledTimes(1)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. FR-UX-10 F11 — 단축키 `w` 손잡이 (focusRef + aria-keyshortcuts)
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S6 FR-UX-10 F11 단축키 `w` 손잡이', () => {
  it('S6a: focusRef 로 토글 버튼에 포커스를 줄 수 있다 (Task 5 가 focus 후 click 한다)', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [], isWatching: false })
    const focusRef = createRef<HTMLButtonElement>()
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" focusRef={focusRef} />, { wrapper: Wrapper })

    // ★GET 응답 도착까지 기다린다 — 로딩 윈도우에서는 토글이 차단(aria-disabled)이라
    //   포커스를 줘도 아무 일도 일어나지 않는다. 그 자체가 의도된 fail-safe(헛 POST 차단)이므로
    //   여기서 완화하지 않고 전제를 맞춘다.
    await screen.findByText('0명')
    const btn = screen.getByTestId('watch-toggle-button')
    expect(btn).toHaveAttribute('aria-disabled', 'false')

    // ref 가 실제 DOM 노드를 잡았는지 먼저 본다 — `?.` 가 null 을 삼켜 공허 통과하는 것을 막는다
    expect(focusRef.current).not.toBeNull()
    act(() => {
      focusRef.current?.focus()
    })
    expect(btn).toHaveFocus()
  })

  it('S6b: focusRef 가 연결되면 토글 버튼이 aria-keyshortcuts="w" 를 알린다', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [], isWatching: false })
    const focusRef = createRef<HTMLButtonElement>()
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" focusRef={focusRef} />, { wrapper: Wrapper })

    expect(await screen.findByTestId('watch-toggle-button')).toHaveAttribute(
      'aria-keyshortcuts',
      'w',
    )
  })

  it('S6c: focusRef 가 없으면 aria-keyshortcuts 를 붙이지 않는다', async () => {
    useWatcherGetHandler('ATLAS-1', { watchers: [], isWatching: false })
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    expect(await screen.findByTestId('watch-toggle-button')).not.toHaveAttribute(
      'aria-keyshortcuts',
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S9. FR-UX-10 F11 Task-5b — 뮤테이션 중/후 포커스 보존 (F-2 봉합)
//
// Task 5 는 `w` 핸들러를 focus() → click() 순서로 고쳤고 유닛이 초록이었다.
// 그런데 실브라우저 추적은 그다음을 보여줬다.
//   뮤테이션 시작 → disabled=true → activeElement=BODY
//   뮤테이션 완료 → disabled=false → activeElement=BODY (복귀 없음)
//   aria-pressed 가 바뀌는 시점에 포커스가 버튼에 없어 스크린리더가 읽지 않는다.
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S9 뮤테이션 중/후 포커스 보존 (Task-5b)', () => {
  it('S9a: 뮤테이션 진행 중에도 포커스가 토글 버튼에 남는다', async () => {
    const user = userEvent.setup()
    const { gate, release } = createGate()
    let postStarted = false

    server.use(
      http.get('/api/v1/issues/ATLAS-1/watchers', () =>
        HttpResponse.json({ data: { watchers: [], count: 0, isWatching: false } }),
      ),
      http.post('/api/v1/issues/ATLAS-1/watchers', async () => {
        postStarted = true
        await gate
        return new HttpResponse(null, { status: 201 })
      }),
    )

    const stopEmulation = emulateDisabledFocusLoss()
    try {
      const focusRef = createRef<HTMLButtonElement>()
      const Wrapper = createWrapper()
      render(<WatchersSection issueKey="ATLAS-1" focusRef={focusRef} />, { wrapper: Wrapper })

      await screen.findByText('0명')
      const btn = screen.getByTestId('watch-toggle-button')
      // `w` 단축키 경로와 동일하게 focus() 후 click() 한다
      act(() => {
        focusRef.current?.focus()
      })
      await user.click(btn)

      // in-flight 진입을 중립적으로 확인한다 (disabled/aria-disabled 어느 구현이든 참)
      await waitFor(() => {
        expect(postStarted).toBe(true)
      })

      expect(btn).toHaveFocus()
    } finally {
      release()
      stopEmulation()
      await settlePendingMutations()
    }
  })

  it('S9b: 뮤테이션 완료 후 aria-pressed 가 바뀌는 시점에도 포커스가 버튼에 남는다', async () => {
    const user = userEvent.setup()
    const { gate, release } = createGate()
    let getCallCount = 0

    server.use(
      http.get('/api/v1/issues/ATLAS-1/watchers', () => {
        getCallCount++
        return getCallCount === 1
          ? HttpResponse.json({ data: { watchers: [], count: 0, isWatching: false } })
          : HttpResponse.json({
              data: { watchers: [aliceWatcher], count: 1, isWatching: true },
            })
      }),
      http.post('/api/v1/issues/ATLAS-1/watchers', async () => {
        await gate
        return new HttpResponse(null, { status: 201 })
      }),
    )

    const stopEmulation = emulateDisabledFocusLoss()
    try {
      const focusRef = createRef<HTMLButtonElement>()
      const Wrapper = createWrapper()
      render(<WatchersSection issueKey="ATLAS-1" focusRef={focusRef} />, { wrapper: Wrapper })

      await screen.findByText('0명')
      const btn = screen.getByTestId('watch-toggle-button')
      act(() => {
        focusRef.current?.focus()
      })
      await user.click(btn)
      release()

      // 상태가 실제로 뒤집힌 시점 — 스크린리더가 aria-pressed 변화를 읽어야 하는 순간이다
      await waitFor(() => {
        expect(btn).toHaveAttribute('aria-pressed', 'true')
      })
      expect(btn).toHaveFocus()
    } finally {
      release()
      stopEmulation()
    }
  })

  it('S9c: 뮤테이션 진행 중 재클릭해도 POST 는 한 번만 나간다 — 증인은 컴포넌트 자체 가드다', async () => {
    // aria-disabled 는 브라우저가 클릭을 막아주지 않는다. 네이티브 disabled 를 벗기면
    // 중복 발행을 막는 책임이 전적으로 handleToggle 첫 줄 `if (!canToggle) return` 으로 옮겨간다.
    // 그 가드를 지우면 이 테스트가 red 여야 한다 (E8 짝 테스트).
    const user = userEvent.setup()
    const { gate, release } = createGate()
    let postCount = 0

    server.use(
      http.get('/api/v1/issues/ATLAS-1/watchers', () =>
        HttpResponse.json({ data: { watchers: [], count: 0, isWatching: false } }),
      ),
      http.post('/api/v1/issues/ATLAS-1/watchers', async () => {
        postCount++
        await gate
        return new HttpResponse(null, { status: 201 })
      }),
    )

    try {
      const Wrapper = createWrapper()
      render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

      await screen.findByText('0명')
      const btn = screen.getByTestId('watch-toggle-button')
      await user.click(btn)
      await waitFor(() => {
        expect(postCount).toBe(1)
      })

      // in-flight 상태에서 재클릭 — 가드가 없으면 여기서 POST 가 더 나간다
      await user.click(btn)
      await user.click(btn)

      expect(postCount).toBe(1)
    } finally {
      release()
      await settlePendingMutations()
    }
  })
})
