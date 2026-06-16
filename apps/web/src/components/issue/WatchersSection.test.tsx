// WatchersSection 컴포넌트 단위 테스트 — FR-WT-01 D6 Task-2 (TDD RED)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { aliceUser } from '@/mocks/auth-fixtures'
import { WatchersSection } from './WatchersSection'

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
