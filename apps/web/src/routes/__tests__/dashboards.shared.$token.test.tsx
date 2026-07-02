// 대시보드 익명 공유 뷰 라우트 단위 테스트 — 로딩/성공/404/embed 크롬 최소화 (FR-DB-03 D6/D7 Task 9)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { PublicDashboard } from '@/api/dashboards'

// ─────────────────────────────────────────────────────────────────────────────
// mock — api/dashboards(getPublicDashboard raw fetch), TanStack Router, DashboardGrid
// ─────────────────────────────────────────────────────────────────────────────

const mockGetPublicDashboard = vi.fn()

vi.mock('@/api/dashboards', () => ({
  getPublicDashboard: (token: string) => mockGetPublicDashboard(token),
}))

const mockUseParams = vi.fn()
const mockUseSearch = vi.fn()

// ★ useNavigate를 의도적으로 export하지 않는다 — 구현이 실수로 로그인 리다이렉트를 추가하면
//   "useNavigate is not a function"으로 즉시 실패해 EC-11(리다이렉트 금지) 회귀를 잡아낸다.
vi.mock('@tanstack/react-router', () => ({
  useParams: () => mockUseParams(),
  useSearch: () => mockUseSearch(),
}))

// DashboardGrid mock — tiles/publicMode props를 캡처해 익명 렌더러 경유 여부를 단언한다
let capturedTiles: unknown[] | null = null
let capturedPublicMode: boolean | null = null

vi.mock('@/components/dashboard/DashboardGrid', () => ({
  DashboardGrid: (props: { tiles: unknown[]; publicMode?: boolean }) => {
    capturedTiles = props.tiles
    capturedPublicMode = props.publicMode ?? null
    return <div data-testid="dashboard-grid" data-tile-count={props.tiles.length} />
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixtures
// ─────────────────────────────────────────────────────────────────────────────

const SHARE_TOKEN = 'test-share-token-abc123'

const PUBLIC_DASHBOARD: PublicDashboard = {
  name: '분기 리포트',
  description: '팀 공유용 요약',
  layout: JSON.stringify([
    { i: 'tile-1', x: 0, y: 0, w: 6, h: 4, gadgetType: 'text_widget', config: { markdown: '안내' } },
    { i: 'tile-2', x: 6, y: 0, w: 6, h: 4, gadgetType: 'assigned_to_me' },
  ]),
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function renderPage(props: { token?: string; embed?: boolean } = {}) {
  const { SharedDashboardPage } = await import('@/routes/dashboards.shared.$token')
  return render(<SharedDashboardPage token={props.token ?? SHARE_TOKEN} embed={props.embed} />)
}

async function renderAdapter() {
  const { SharedDashboardRouteAdapter } = await import('@/routes/dashboards.shared.$token')
  return render(<SharedDashboardRouteAdapter />)
}

beforeEach(() => {
  capturedTiles = null
  capturedPublicMode = null
  vi.clearAllMocks()
  mockUseParams.mockReturnValue({ token: SHARE_TOKEN })
  mockUseSearch.mockReturnValue({ embed: undefined })
})

// ─────────────────────────────────────────────────────────────────────────────
// 로딩
// ─────────────────────────────────────────────────────────────────────────────

describe('로딩', () => {
  /**
   * T-DB3-SH-L1. fetch가 완료되기 전에는 로딩 스켈레톤이 렌더된다.
   */
  it('T-DB3-SH-L1: fetch 완료 전에는 로딩 스켈레톤이 렌더된다', async () => {
    let resolvePromise: (value: PublicDashboard) => void = () => {}
    mockGetPublicDashboard.mockReturnValue(
      new Promise<PublicDashboard>((resolve) => {
        resolvePromise = resolve
      }),
    )
    await renderPage()
    expect(screen.getByRole('status')).toBeInTheDocument()
    resolvePromise(PUBLIC_DASHBOARD)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 성공 (S2)
// ─────────────────────────────────────────────────────────────────────────────

describe('성공', () => {
  /**
   * T-DB3-SH-S1. 성공 시 대시보드 이름(h1)과 DashboardGrid가 렌더된다.
   */
  it('T-DB3-SH-S1: 성공 시 대시보드 이름과 DashboardGrid가 렌더된다', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderPage()
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: '분기 리포트' })).toBeInTheDocument(),
    )
    expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument()
  })

  /**
   * T-DB3-SH-S2. description이 있으면 표시된다.
   */
  it('T-DB3-SH-S2: description이 있으면 표시된다', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderPage()
    await waitFor(() => expect(screen.getByText('팀 공유용 요약')).toBeInTheDocument())
  })

  /**
   * T-DB3-SH-S3. DashboardGrid에 publicMode=true + parseLayout된 tiles(데이터 가젯 포함)가 전달된다.
   * 데이터 가젯 플레이스홀더 자체의 렌더는 PublicGadgetRenderer(Task 7)/DashboardTile(Task 8) 책임이며,
   * 이 라우트는 DashboardGrid에 publicMode를 올바르게 넘기는 배선만 검증한다.
   */
  it('T-DB3-SH-S3: DashboardGrid에 publicMode=true와 tiles가 전달된다 (데이터 가젯 포함)', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(capturedPublicMode).toBe(true)
    expect(capturedTiles?.length).toBe(2)
    const tiles = capturedTiles as Array<{ gadgetType?: string }>
    expect(tiles.some((t) => t.gadgetType === 'assigned_to_me')).toBe(true)
  })

  /**
   * T-DB3-SH-S4. 편집/헤더/소유자 정보 UI가 노출되지 않는다(설정·삭제·저장 버튼 부재).
   */
  it('T-DB3-SH-S4: 편집 UI(설정/삭제/저장 버튼)가 노출되지 않는다', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /설정|삭제|저장/i })).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 404 (S5/S6, EC-11)
// ─────────────────────────────────────────────────────────────────────────────

describe('404', () => {
  /**
   * T-DB3-SH-404-1. 무효/만료/삭제 토큰이면 notFound 문구가 role="alert"로 렌더된다.
   */
  it('T-DB3-SH-404-1: 무효 토큰이면 notFound 문구가 role=alert로 렌더된다', async () => {
    mockGetPublicDashboard.mockRejectedValue(new Error('404'))
    await renderPage()
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.getByText('공유된 대시보드를 찾을 수 없습니다')).toBeInTheDocument()
  })

  /**
   * T-DB3-SH-404-2. 404여도 로그인 리다이렉트나 그리드가 렌더되지 않는다 (raw fetch, EC-11).
   * useNavigate가 mock 모듈에 없으므로, 구현이 리다이렉트를 시도하면 이 테스트가 즉시 실패한다.
   */
  it('T-DB3-SH-404-2: 404여도 로그인 리다이렉트 없이 notFound만 렌더된다', async () => {
    mockGetPublicDashboard.mockRejectedValue(new Error('404'))
    await renderPage()
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(screen.queryByRole('heading')).toBeNull()
    expect(screen.queryByTestId('dashboard-grid')).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// embed 크롬 최소화 (S3, EC-7)
// ─────────────────────────────────────────────────────────────────────────────

describe('embed 크롬 최소화', () => {
  /**
   * T-DB3-SH-EMB1. embed=true이면 이름(h1)이 렌더되지 않고 그리드만 남는다.
   */
  it('T-DB3-SH-EMB1: embed=true면 이름(h1)이 렌더되지 않는다', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderPage({ embed: true })
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(screen.queryByRole('heading')).toBeNull()
  })

  /**
   * T-DB3-SH-EMB2. embed=false(기본)면 이름이 렌더된다 — embed 미전달 회귀 방지.
   */
  it('T-DB3-SH-EMB2: embed 미전달(기본값)이면 이름이 렌더된다', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderPage()
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
  })

  /**
   * T-DB3-SH-EMB3. embed 파라미터 없이도 링크 직접 열람이 정상 동작한다 (EC-7).
   */
  it('T-DB3-SH-EMB3: embed 없이 직접 열람해도 정상 동작한다 (EC-7)', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderPage({ token: 'another-token' })
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(mockGetPublicDashboard).toHaveBeenCalledWith('another-token')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// SharedDashboardRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

describe('SharedDashboardRouteAdapter', () => {
  /**
   * T-DB3-SH-RA1. useParams의 token으로 getPublicDashboard가 호출된다.
   */
  it('T-DB3-SH-RA1: useParams token으로 getPublicDashboard가 호출된다', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderAdapter()
    await waitFor(() => expect(mockGetPublicDashboard).toHaveBeenCalledWith(SHARE_TOKEN))
  })

  /**
   * T-DB3-SH-RA2. useSearch의 embed=1이면 임베드 모드로 전달돼 h1이 렌더되지 않는다.
   */
  it('T-DB3-SH-RA2: useSearch embed=1이면 임베드 모드로 전달된다', async () => {
    mockUseSearch.mockReturnValue({ embed: '1' })
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderAdapter()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(screen.queryByRole('heading')).toBeNull()
  })

  /**
   * T-DB3-SH-RA3. useSearch의 embed이 undefined(쿼리 미지정)이면 일반 뷰로 렌더된다.
   */
  it('T-DB3-SH-RA3: useSearch embed 미지정이면 일반 뷰(h1 노출)로 렌더된다', async () => {
    mockGetPublicDashboard.mockResolvedValue(PUBLIC_DASHBOARD)
    await renderAdapter()
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
  })
})
