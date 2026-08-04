// FavoriteButton 컴포넌트 단위 테스트 — FR-UX-02 D6/D7 Task-4 (TDD RED)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { ReactNode } from 'react'
import { createRef } from 'react'
import { render, screen, waitFor, act } from '@testing-library/react'
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
 * 실브라우저 규칙을 jsdom 에 재현한다 — `disabled` 로 전환된 요소는 포커스를 잃고,
 * 다시 enabled 가 돼도 포커스는 돌아오지 않는다 (activeElement 는 `<body>` 로 떨어진다).
 *
 * jsdom 29 는 이 규칙을 구현하지 않는다(실측: `disabled=true` 이후에도 activeElement 유지).
 * 그래서 "유닛 25/25 초록 · 실브라우저에서는 스크린리더 무음" 이 그대로 통과했다.
 * 관찰자를 심어 브라우저와 같은 규칙을 재현해야 이 회귀를 잡을 수 있다.
 *
 * body 에 `tabindex=-1` 을 잠시 붙이는 이유는 jsdom 이 focusable 하지 않은 요소의
 * `focus()` 를 무시하기 때문이다 — 실브라우저의 "activeElement=BODY" 를 만들기 위한 최소 장치다.
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
// S5. isPending 동안 버튼 비활성 (중복 클릭 가드)
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton — S5 isPending 비활성 가드', () => {
  it('S5a: mutation in-flight 동안 버튼이 aria-disabled 로 비활성을 알린다 (네이티브 disabled 금지)', async () => {
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
    expect(btn).toHaveAttribute('aria-disabled', 'false')

    await user.click(btn)

    await waitFor(() => {
      expect(screen.getByRole('button')).toHaveAttribute('aria-disabled', 'true')
    })
    // 네이티브 disabled 를 쓰면 브라우저가 포커스를 <body> 로 떨어뜨려
    // aria-pressed 변화를 스크린리더가 읽지 못한다 (FR-UX-10 F11 Task-5b).
    expect(screen.getByRole('button')).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. FR-UX-10 F11 — 단축키 `s` 손잡이 (focusRef + aria-keyshortcuts)
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton — S6 FR-UX-10 F11 단축키 `s` 손잡이', () => {
  it('S6a: focusRef 로 버튼에 포커스를 줄 수 있다 (Task 5 가 focus 후 click 한다)', async () => {
    useFavoritesGetHandler([])
    const focusRef = createRef<HTMLButtonElement>()
    const Wrapper = createWrapper()
    render(
      <FavoriteButton targetType="ISSUE" targetId="PROJ-1" focusRef={focusRef} />,
      { wrapper: Wrapper },
    )

    const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
    // ref 가 실제 DOM 노드를 잡았는지 먼저 본다 — `?.` 가 null 을 삼켜 공허 통과하는 것을 막는다
    expect(focusRef.current).not.toBeNull()
    act(() => {
      focusRef.current?.focus()
    })
    expect(btn).toHaveFocus()
  })

  it('S6b: focusRef 가 연결되면 버튼이 aria-keyshortcuts="s" 를 알린다', async () => {
    useFavoritesGetHandler([])
    const focusRef = createRef<HTMLButtonElement>()
    const Wrapper = createWrapper()
    render(
      <FavoriteButton targetType="ISSUE" targetId="PROJ-1" focusRef={focusRef} />,
      { wrapper: Wrapper },
    )

    const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
    expect(btn).toHaveAttribute('aria-keyshortcuts', 's')
  })

  it('S6c: focusRef 가 없으면 aria-keyshortcuts 를 붙이지 않는다 — 대시보드·보드·필터 목록엔 `s` 가 없다', async () => {
    // 이 버튼은 이슈 상세 외에 SavedFilterMenu·대시보드·보드 화면도 쓴다.
    // 무조건 붙이면 단축키가 없는 화면에서 스크린리더가 없는 기능을 안내한다.
    useFavoritesGetHandler([])
    const Wrapper = createWrapper()
    render(<FavoriteButton targetType="ISSUE" targetId="PROJ-1" />, { wrapper: Wrapper })

    const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
    expect(btn).not.toHaveAttribute('aria-keyshortcuts')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. FR-UX-10 F11 Task-5b — 뮤테이션 중/후 포커스 보존 (F-2 봉합)
//
// Task 5 는 `s` 핸들러를 focus() → click() 순서로 고쳤고 유닛 25/25 가 초록이었다.
// 그런데 실브라우저 추적은 그다음을 보여줬다.
//   뮤테이션 시작 → disabled=true → activeElement=BODY
//   뮤테이션 완료 → disabled=false → activeElement=BODY (복귀 없음)
//   800ms 뒤      → aria-pressed=true 인데 포커스가 없다 → 스크린리더 무음
// 기존 유닛은 "누른 순간"만 재서 이 구간을 통째로 놓쳤다. 여기서 그 구간을 잰다.
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton — S7 뮤테이션 중/후 포커스 보존 (Task-5b)', () => {
  it('S7a: 뮤테이션 진행 중에도 포커스가 토글 버튼에 남는다', async () => {
    const user = userEvent.setup()
    const { gate, release } = createGate()
    let postStarted = false

    server.use(
      http.get('/api/v1/favorites', () => HttpResponse.json({ data: { items: [] } })),
      http.post('/api/v1/favorites', async () => {
        postStarted = true
        await gate
        return HttpResponse.json({ data: ISSUE_FAVORITE }, { status: 201 })
      }),
    )

    const stopEmulation = emulateDisabledFocusLoss()
    try {
      const focusRef = createRef<HTMLButtonElement>()
      const Wrapper = createWrapper()
      render(
        <FavoriteButton targetType="ISSUE" targetId="PROJ-1" focusRef={focusRef} />,
        { wrapper: Wrapper },
      )

      const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
      // `s` 단축키 경로와 동일하게 focus() 후 click() 한다
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
    }
  })

  it('S7b: 뮤테이션 완료 후 aria-pressed 가 바뀌는 시점에도 포커스가 버튼에 남는다', async () => {
    const user = userEvent.setup()
    const { gate, release } = createGate()
    let getCallCount = 0

    server.use(
      http.get('/api/v1/favorites', () => {
        getCallCount++
        return getCallCount === 1
          ? HttpResponse.json({ data: { items: [] } })
          : HttpResponse.json({ data: { items: [ISSUE_FAVORITE] } })
      }),
      http.post('/api/v1/favorites', async () => {
        await gate
        return HttpResponse.json({ data: ISSUE_FAVORITE }, { status: 201 })
      }),
    )

    const stopEmulation = emulateDisabledFocusLoss()
    try {
      const focusRef = createRef<HTMLButtonElement>()
      const Wrapper = createWrapper()
      render(
        <FavoriteButton targetType="ISSUE" targetId="PROJ-1" focusRef={focusRef} />,
        { wrapper: Wrapper },
      )

      const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
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

  it('S7c: 뮤테이션 진행 중 재클릭해도 POST 는 한 번만 나간다 — 증인은 컴포넌트 자체 가드다', async () => {
    // aria-disabled 는 브라우저가 클릭을 막아주지 않는다. 네이티브 disabled 를 벗기면
    // 중복 발행을 막는 책임이 전적으로 handleClick 첫 줄 `if (isMutating) return` 으로 옮겨간다.
    // 그 가드를 지우면 이 테스트가 red 여야 한다 (E8 짝 테스트).
    const user = userEvent.setup()
    const { gate, release } = createGate()
    let postCount = 0

    server.use(
      http.get('/api/v1/favorites', () => HttpResponse.json({ data: { items: [] } })),
      http.post('/api/v1/favorites', async () => {
        postCount++
        await gate
        return HttpResponse.json({ data: ISSUE_FAVORITE }, { status: 201 })
      }),
    )

    try {
      const Wrapper = createWrapper()
      render(<FavoriteButton targetType="ISSUE" targetId="PROJ-1" />, { wrapper: Wrapper })

      const btn = await screen.findByRole('button', { name: '즐겨찾기에 추가' })
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
    }
  })
})
