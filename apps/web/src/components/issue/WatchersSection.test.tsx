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
import { seedIssueWatchers, resetIssueWatcherStore } from '@/mocks/issue-watcher-handlers'
import { WatchersSection } from './WatchersSection'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** alice userId — auth-fixtures aliceUser와 동일 고정값 */
const ALICE_ID = '00000000-0000-0000-0000-000000000001'
/** bob userId — auth-fixtures bobUser와 동일 고정값 */
const BOB_ID = '00000000-0000-0000-0000-000000000002'

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

// ─────────────────────────────────────────────────────────────────────────────
// Setup / Teardown
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  // alice 로그인 상태 시드
  useAuthStore.getState().setSession({
    accessToken: 'test-token',
    user: aliceUser,
  })
  // 워처 저장소 초기화
  resetIssueWatcherStore()
})

afterEach(() => {
  useAuthStore.getState().clearSession()
  resetIssueWatcherStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. 목록 렌더 — 카운트 + displayName
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S1 목록 렌더', () => {
  it('S1a: 워처 카운트 "N명"과 displayName이 표시된다', async () => {
    seedIssueWatchers('ATLAS-1', [BOB_ID])
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    // 카운트
    expect(await screen.findByText('1명')).toBeInTheDocument()
    // displayName
    expect(screen.getByText('User bob')).toBeInTheDocument()
  })

  it('S1b: 감시자가 없으면 "0명"과 빈 상태 메시지가 표시된다', async () => {
    seedIssueWatchers('ATLAS-1', [])
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    expect(await screen.findByText('0명')).toBeInTheDocument()
    expect(screen.getByText('감시자가 없습니다.')).toBeInTheDocument()
  })

  it('S1c: 본인(alice)은 displayName 뒤에 "(나)"가 표기된다', async () => {
    seedIssueWatchers('ATLAS-1', [ALICE_ID])
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    // "User alice (나)" 또는 "(나)" 텍스트
    expect(await screen.findByText(/\(나\)/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 토글 버튼 초기 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('WatchersSection — S2 토글 버튼 초기 상태', () => {
  it('S2a: isWatching=false 이면 버튼 텍스트가 "지켜보기"이다', async () => {
    // alice는 워처 아님 (빈 저장소)
    seedIssueWatchers('ATLAS-1', [])
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '지켜보기' })
    expect(btn).toBeInTheDocument()
    expect(btn).toHaveAttribute('aria-pressed', 'false')
  })

  it('S2b: isWatching=true 이면 버튼 텍스트가 "지켜보는 중"이다', async () => {
    // alice가 이미 워처
    seedIssueWatchers('ATLAS-1', [ALICE_ID])
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
    // alice는 워처 아님
    seedIssueWatchers('ATLAS-1', [BOB_ID])
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    // 초기 렌더 대기 — 버튼 "지켜보기"
    const btn = await screen.findByRole('button', { name: '지켜보기' })
    expect(screen.getByText('1명')).toBeInTheDocument()

    await user.click(btn)

    // POST 후 invalidate → refetch → store에 alice 추가됨
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
    // alice가 이미 워처
    seedIssueWatchers('ATLAS-1', [ALICE_ID, BOB_ID])
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
    seedIssueWatchers('ATLAS-1', [])
    const Wrapper = createWrapper()
    render(<WatchersSection issueKey="ATLAS-1" />, { wrapper: Wrapper })

    // 로딩 완료 대기
    await screen.findByText('감시자가 없습니다.')

    expect(screen.getByTestId('watchers-section')).toBeInTheDocument()
    expect(screen.getByTestId('watch-toggle-button')).toBeInTheDocument()
  })
})
