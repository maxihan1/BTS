// 팔레트 라이브 검색 훅 테스트 — 디바운스·이슈키·프로젝트 게이트 (FR-UX-12 F4 T3)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { issueHandlers } from '@/mocks/issue-handlers'
import { searchHandlers } from '@/mocks/search-handlers'
import { DEFAULT_SEARCH_PAGE, makeSearchPage } from '@/mocks/search-fixtures'
import type { PaletteInput } from './palette-input'
import { usePaletteSearch } from './use-palette-search'

// 활성 프로젝트 해소를 훅 경계에서 갈아끼운다 — 프로젝트 상태별 분기(FR7)를
// 네트워크 없이 검증하기 위함. 컴포넌트 단위 mock 관례(vi.mock 광범위 금지 메모리).
const mockResolved = vi.fn()
vi.mock('@/hooks/use-resolved-active-project', () => ({
  useResolvedActiveProject: () => mockResolved(),
}))

// ★라우터는 `useSearch` 한 개만 갈아끼운다 — 모듈 전체 교체가 아니다.
// `useSearch({ strict: false })` 는 RouterProvider 밖에서 그냥 undefined 를 주는 게 아니라
// **던진다** (실측: TypeError "Cannot read properties of null (reading 'isServer')" —
// useMatch 가 router 컨텍스트를 null 로 받고 `router.isServer` 를 읽는다).
// 실 memory router 로 감쌀 수도 없다 — `RouterProvider` 는 children 을 렌더하지 않고
// 매칭된 라우트 component 만 렌더하므로 renderHook 의 wrapper 계약과 맞지 않는다
// (ProjectSwitcher.test.tsx 가 라우트마다 `component:` 를 지정하는 이유가 그것이다).
// 이 훅이 라우터에서 읽는 것은 URL 프로젝트 키 하나뿐이고 그 소비자(useResolvedActiveProject)는
// 이미 위에서 갈아끼웠으므로, 최소 표면만 대체하고 나머지 라우터 API 는 실물로 둔다.
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useSearch: () => ({}),
}))

/** 전역 `retry: false` 를 재현한 테스트 전용 클라이언트 (main.tsx 기본값과 동일) */
const makeClient = (): QueryClient =>
  new QueryClient({ defaultOptions: { queries: { retry: false } } })

// ★클라이언트를 wrapper **바깥**에 둔다. wrapper 본문에서 new 하면 rerender 마다 새
// 클라이언트가 만들어져 캐시와 진행 중 조회가 통째로 리셋되고, 디바운스 단언이 "요청을
// 안 했다" 가 아니라 "요청이 지워졌다" 를 보게 된다.
let queryClient = makeClient()

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

/**
 * 훅을 렌더하고 입력을 바꿔 끼울 수 있는 핸들을 돌려준다.
 *
 * ★디바운스를 보려면 **타이핑을 재현해야 한다.** 처음부터 질의를 실은 채 마운트하면
 * `useDebounce` 가 `useState(value)` 로 초기값을 즉시 채택해 디바운스 구간이 아예
 * 존재하지 않는다 — 실제 팔레트도 빈 입력으로 열린 뒤 글자가 들어온다.
 *
 * @param initial 최초 마운트 시점의 판별 결과
 */
function renderPalette(initial: PaletteInput) {
  return renderHook((input: PaletteInput) => usePaletteSearch(input), {
    wrapper,
    initialProps: initial,
  })
}

beforeEach(() => {
  // `src/test/handlers.ts` 기본 목록에는 auth refresh 하나뿐이라 필요한 BC 핸들러를
  // 파일마다 등록한다(저장소 관례 — CreateIssueDialog.test.tsx 등). 등록을 빼먹으면
  // 요청이 미핸들 에러로 떨어져 "검색이 실패했다"는 **거짓 신호**가 된다.
  server.use(...issueHandlers, ...searchHandlers)
  queryClient = makeClient()
  vi.useFakeTimers({ shouldAdvanceTime: true })
  mockResolved.mockReturnValue({ status: 'ready', projectKey: 'ATLAS', source: 'stored' })
})
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('usePaletteSearch — 자유 텍스트', () => {
  it('디바운스 250ms 전에는 검색하지 않는다 (NFR1)', () => {
    const { result, rerender } = renderPalette({ kind: 'empty' })
    rerender({ kind: 'free-text', query: '로그인' })
    act(() => {
      vi.advanceTimersByTime(200)
    })
    expect(result.current.results).toEqual([])
    expect(result.current.isSearching).toBe(false)
  })

  it('250ms 후 검색 결과가 채워진다 (S4)', async () => {
    const { result, rerender } = renderPalette({ kind: 'empty' })
    rerender({ kind: 'free-text', query: '로그인' })
    act(() => {
      vi.advanceTimersByTime(250)
    })
    await waitFor(() => {
      expect(result.current.results.length).toBeGreaterThan(0)
    })
  })

  it('결과 상한 7건을 서버에 요청한다 (NFR3)', async () => {
    // ★응답 길이가 아니라 **요청** 을 증인으로 삼는다.
    // `results.length <= 7` 은 절대 깨지지 않는 공허한 단언이었다 — 기본 목이 요청 body 의
    // `size` 를 읽지 않고 3건짜리 픽스처를 고정 반환하므로 좌변이 늘 3이다. 목을 slice 하게
    // 고쳐도 `min(3, 상한) = 3` 이라 그대로고, 픽스처를 8건으로 늘리면 정확히 3건을 기대하는
    // search.spec.ts 가 깨진다. NFR3 이 실제로 요구하는 것은 "팔레트가 7건만 요청한다" 이므로
    // 요청 body 를 잰다 — 이 축은 픽스처 총량과 무관해 항상 비-공허하다.
    // 기대값 7 은 상수를 import 하지 않고 **하드코딩** 한다. import 하면 상한을 50 으로 바꿔도
    // 단언이 따라 움직여 다시 공허해진다.
    const requestedSizes: unknown[] = []
    server.use(
      http.post('/api/v1/search/aql', async ({ request }) => {
        const body: unknown = await request.json()
        requestedSizes.push(
          body !== null && typeof body === 'object'
            ? (body as Record<string, unknown>)['size']
            : undefined,
        )
        return HttpResponse.json(DEFAULT_SEARCH_PAGE)
      }),
    )
    const { rerender } = renderPalette({ kind: 'empty' })
    rerender({ kind: 'free-text', query: '로그인' })
    act(() => {
      vi.advanceTimersByTime(250)
    })
    await waitFor(() => {
      expect(requestedSizes).toHaveLength(1)
    })
    expect(requestedSizes[0]).toBe(7)
  })

  it('totalCount 는 서버 전체 건수다 — results.length 로 대체되지 않는다 (design 리뷰 2-2)', async () => {
    // 표시분(3건)과 총계(42건)를 어긋나게 만들어야 `results.length` 대체를 잡아낸다.
    // 기본 픽스처는 totalElements 가 hits.length 와 같아 둘을 구분하지 못한다.
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(makeSearchPage(DEFAULT_SEARCH_PAGE.data, { totalElements: 42 })),
      ),
    )
    const { result, rerender } = renderPalette({ kind: 'empty' })
    rerender({ kind: 'free-text', query: '로그인' })
    act(() => {
      vi.advanceTimersByTime(250)
    })
    await waitFor(() => {
      expect(result.current.totalCount).toBe(42)
    })
    expect(result.current.results).toHaveLength(3)
  })

  it('검색이 실패하면 errorMessage 가 남는다 (FR13)', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json({ errorCode: 'SEARCH_INTERNAL_ERROR' }, { status: 500 }),
      ),
    )
    const { result, rerender } = renderPalette({ kind: 'empty' })
    rerender({ kind: 'free-text', query: '로그인' })
    act(() => {
      vi.advanceTimersByTime(250)
    })
    await waitFor(() => {
      expect(result.current.errorMessage).not.toBeNull()
    })
    expect(result.current.results).toEqual([])
  })
})

describe('usePaletteSearch — 이슈키 (S1·S3·E6)', () => {
  it('이슈키는 디바운스 없이 즉시 조회한다', async () => {
    const { result } = renderPalette({ kind: 'issue-key', issueKey: 'ATLAS-1' })
    await waitFor(() => {
      expect(result.current.issueHit?.key).toBe('ATLAS-1')
    })
  })

  it('없는 키는 issueHit 이 null 이고 자유 텍스트 결과가 대신 온다 (S3)', async () => {
    const { result } = renderPalette({ kind: 'issue-key', issueKey: 'ATLAS-99999' })
    await waitFor(() => {
      expect(result.current.issueHit).toBeNull()
    })
    // 404 는 "없음"으로 흡수한다 — 에러 안내로 새면 존재 여부가 노출된다(E6)
    expect(result.current.errorMessage).toBeNull()
    await waitFor(() => {
      expect(result.current.results.length).toBeGreaterThan(0)
    })
  })
})

describe('usePaletteSearch — 활성 프로젝트 게이트 (FR7·S8·E8·E9)', () => {
  it('empty 면 자유 텍스트 검색을 호출하지 않고 needsProject 를 세운다', () => {
    mockResolved.mockReturnValue({ status: 'empty' })
    const { result, rerender } = renderPalette({ kind: 'empty' })
    rerender({ kind: 'free-text', query: '로그인' })
    act(() => {
      vi.advanceTimersByTime(250)
    })
    expect(result.current.needsProject).toBe(true)
    expect(result.current.results).toEqual([])
    // '0개'는 해소가 끝난 사실이다 — 진행/실패 갈래와 섞이지 않는다
    expect(result.current.isResolvingProject).toBe(false)
    expect(result.current.projectError).toBeNull()
  })

  it('★error 는 needsProject 로 뭉개지지 않고 projectError 로 갈린다 (E9)', () => {
    // ★'0개'와 '못 불러왔다'는 서로 다른 사실이고, 후자의 유일한 탈출구가 재시도다
    // (`use-resolved-active-project.ts:69-70`). `retry` 를 멤버 안에 실어 소비처가
    // 배선을 빠뜨릴 수 없게 한다 — optional prop 이면 잊어도 tsc 가 못 잡는다(CR-A 교훈).
    const retry = vi.fn()
    mockResolved.mockReturnValue({ status: 'error', retry })
    const { result, rerender } = renderPalette({ kind: 'empty' })
    rerender({ kind: 'free-text', query: '로그인' })
    act(() => {
      vi.advanceTimersByTime(250)
    })

    expect(result.current.needsProject).toBe(false)
    expect(result.current.projectError).not.toBeNull()
    expect(result.current.results).toEqual([])

    result.current.projectError?.retry()
    expect(retry).toHaveBeenCalledTimes(1)
  })

  it('★empty 여도 이슈키 조회는 계속 동작한다 (ADR D-4 — 프로젝트 무관)', async () => {
    mockResolved.mockReturnValue({ status: 'empty' })
    const { result } = renderPalette({ kind: 'issue-key', issueKey: 'ATLAS-1' })
    await waitFor(() => {
      expect(result.current.issueHit?.key).toBe('ATLAS-1')
    })
    expect(result.current.needsProject).toBe(false)
  })

  it('loading 이면 검색을 보류한다 (E8)', () => {
    mockResolved.mockReturnValue({ status: 'loading' })
    const { result, rerender } = renderPalette({ kind: 'empty' })
    rerender({ kind: 'free-text', query: '로그인' })
    act(() => {
      vi.advanceTimersByTime(250)
    })
    expect(result.current.results).toEqual([])
    // 아직 해소 중일 뿐이라 "프로젝트가 없다"고 단정하지 않는다(E8)
    expect(result.current.needsProject).toBe(false)
    // ★"단정하지 않는다"를 렌더 층이 알아들으려면 **진행 중이라는 사실 자체**가 필요하다.
    // needsProject:false 만 내보내면 소비처는 그것을 "검색 가능"으로 읽고 0건 안내를 켠다.
    expect(result.current.isResolvingProject).toBe(true)
    expect(result.current.projectError).toBeNull()
  })
})

describe('usePaletteSearch — empty 입력 (E12)', () => {
  it('empty 는 어떤 조회도 하지 않는다', () => {
    const { result } = renderPalette({ kind: 'empty' })
    act(() => {
      vi.advanceTimersByTime(250)
    })
    expect(result.current.results).toEqual([])
    expect(result.current.issueHit).toBeNull()
    expect(result.current.needsProject).toBe(false)
    expect(result.current.isResolvingProject).toBe(false)
    expect(result.current.projectError).toBeNull()
  })
})
