// FavoriteButton 컴포넌트 단위 테스트 — FR-UX-02 D6/D7 Task-4 (TDD RED)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { FavoriteButton } from './FavoriteButton'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** ISSUE 즐겨찾기 픽스처 */
const ISSUE_FAVORITE = {
  id: 'fav-001',
  targetType: 'ISSUE' as const,
  targetId: 'PROJ-1',
  createdAt: '2024-01-01T00:00:00Z',
}

/** DASHBOARD 즐겨찾기 픽스처 — 같은 targetId이지만 타입이 다름 */
const DASHBOARD_FAVORITE = {
  id: 'fav-002',
  targetType: 'DASHBOARD' as const,
  targetId: 'PROJ-1',
  createdAt: '2024-01-01T00:00:00Z',
}

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
 * GET /api/v1/favorites MSW 핸들러를 등록한다.
 *
 * @param items 즐겨찾기 목록 픽스처
 */
function useFavoritesGetHandler(
  items: ReadonlyArray<{
    id: string
    targetType: 'ISSUE' | 'DASHBOARD' | 'PROJECT'
    targetId: string
    createdAt: string
  }>,
) {
  server.use(
    http.get('/api/v1/favorites', () =>
      HttpResponse.json({
        data: { items },
      }),
    ),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup / Teardown
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. 초기 렌더 — 즐겨찾기됨/아님 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton — S1 초기 렌더 상태', () => {
  it('S1a: 즐겨찾기 목록에 해당 타입+id가 있으면 aria-pressed=true (채운 별)', async () => {
    useFavoritesGetHandler([ISSUE_FAVORITE])
    const Wrapper = createWrapper()
    render(<FavoriteButton targetType="ISSUE" targetId="PROJ-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '즐겨찾기 해제' })
    expect(btn).toHaveAttribute('aria-pressed', 'true')
  })

  it('S1b: 즐겨찾기 목록에 해당 id가 없으면 aria-pressed=false (빈 별)', async () => {
    useFavoritesGetHandler([])
    const Wrapper = createWrapper()
    render(<FavoriteButton targetType="ISSUE" targetId="PROJ-2" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
    expect(btn).toHaveAttribute('aria-pressed', 'false')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 교차 타입 오판 가드 — targetId만 일치해도 타입 다르면 false
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton — S2 교차 타입 오판 가드 (필수)', () => {
  it('S2a: DASHBOARD 즐겨찾기만 있을 때 ISSUE 타입 버튼은 isFavorited=false (빈 별)여야 한다', async () => {
    // targetId='PROJ-1'이지만 targetType='DASHBOARD'만 목록에 있다.
    // ISSUE 타입 버튼은 반드시 즐겨찾기 안 된 상태여야 한다 — targetId만 비교하는 버그 차단.
    useFavoritesGetHandler([DASHBOARD_FAVORITE])
    const Wrapper = createWrapper()
    render(<FavoriteButton targetType="ISSUE" targetId="PROJ-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
    expect(btn).toHaveAttribute('aria-pressed', 'false')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 추가 클릭 — POST body 단언
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton — S3 추가 클릭 (POST)', () => {
  it('S3a: 빈 별 클릭 시 addFavorite이 올바른 targetType+targetId로 호출된다', async () => {
    const user = userEvent.setup()

    let capturedBody: unknown = undefined
    let getCallCount = 0

    server.use(
      http.get('/api/v1/favorites', () => {
        getCallCount++
        if (getCallCount === 1) {
          return HttpResponse.json({ data: { items: [] } })
        }
        // POST 후 refetch — 즐겨찾기 추가된 상태
        return HttpResponse.json({ data: { items: [ISSUE_FAVORITE] } })
      }),
      http.post('/api/v1/favorites', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(
          { data: ISSUE_FAVORITE },
          { status: 201 },
        )
      }),
    )

    const Wrapper = createWrapper()
    render(<FavoriteButton targetType="ISSUE" targetId="PROJ-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
    await user.click(btn)

    await waitFor(() => {
      expect(capturedBody).toEqual({ targetType: 'ISSUE', targetId: 'PROJ-1' })
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 제거 클릭 — DELETE 쿼리 단언
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton — S4 제거 클릭 (DELETE)', () => {
  it('S4a: 채운 별 클릭 시 removeFavorite이 올바른 targetType+targetId 쿼리파라미터로 호출된다', async () => {
    const user = userEvent.setup()

    let capturedUrl: string | undefined = undefined
    let getCallCount = 0

    server.use(
      http.get('/api/v1/favorites', () => {
        getCallCount++
        if (getCallCount === 1) {
          return HttpResponse.json({ data: { items: [ISSUE_FAVORITE] } })
        }
        // DELETE 후 refetch — 즐겨찾기 제거된 상태
        return HttpResponse.json({ data: { items: [] } })
      }),
      http.delete('/api/v1/favorites', ({ request }) => {
        capturedUrl = request.url
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const Wrapper = createWrapper()
    render(<FavoriteButton targetType="ISSUE" targetId="PROJ-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '즐겨찾기 해제' })
    await user.click(btn)

    await waitFor(() => {
      expect(capturedUrl).toBeDefined()
      const url = new URL(capturedUrl!)
      expect(url.searchParams.get('targetType')).toBe('ISSUE')
      expect(url.searchParams.get('targetId')).toBe('PROJ-1')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. isPending 동안 버튼 disabled (중복 클릭 가드)
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton — S5 isPending disabled 가드', () => {
  it('S5a: mutation in-flight 동안 버튼이 disabled다', async () => {
    const user = userEvent.setup()

    // POST가 절대 응답하지 않아 isPending 상태를 유지한다
    server.use(
      http.get('/api/v1/favorites', () =>
        HttpResponse.json({ data: { items: [] } }),
      ),
      http.post('/api/v1/favorites', () =>
        new Promise<never>(() => {
          // 영원히 pending — isPending=true 상태 고정
        }),
      ),
    )

    const Wrapper = createWrapper()
    render(<FavoriteButton targetType="ISSUE" targetId="PROJ-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
    expect(btn).not.toBeDisabled()

    await user.click(btn)

    await waitFor(() => {
      expect(screen.getByRole('button')).toBeDisabled()
    })
  })
})
