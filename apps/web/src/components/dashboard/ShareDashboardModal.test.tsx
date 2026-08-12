// 대시보드 공유 모달 단위 테스트 — 링크 생성/복사·PRIVATE 경고·임베드·목록 인라인 취소·빈 상태 (FR-DB-03 D6/D7 Task 5)
import { server } from '@/test/server'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { dashboardHandlers } from '@/mocks/dashboard-handlers'
import {
  DEFAULT_DASHBOARD,
  resetDashboardStore,
  seedDashboard,
  resetShareTokenStore,
  seedShareToken,
} from '@/mocks/dashboard-fixtures'
import { ShareDashboardModal } from './ShareDashboardModal'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 — dashboardHandlers 전용 (stateful 공유 토큰 store 포함)
// use-dashboards.test.tsx와 동일 패턴 — 파일 전체에서 서버 1개를 재사용한다.
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...dashboardHandlers)
})

beforeEach(() => {
  resetDashboardStore()
  seedDashboard(DEFAULT_DASHBOARD)
  resetShareTokenStore()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// ─────────────────────────────────────────────────────────────────────────────
// clipboard mock — vi.stubGlobal 패턴 (ReleaseNotesDialog.test.tsx 동일)
// ─────────────────────────────────────────────────────────────────────────────

const clipboardWriteText = vi.fn()

function stubClipboard(): void {
  clipboardWriteText.mockResolvedValue(undefined)
  vi.stubGlobal('navigator', {
    ...navigator,
    clipboard: { writeText: clipboardWriteText },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

interface RenderOptions {
  visibility?: string
}

function renderModal(options: RenderOptions = {}) {
  const Wrapper = createWrapper()
  const onClose = vi.fn()
  render(
    <ShareDashboardModal
      dashboardId={DEFAULT_DASHBOARD.id}
      visibility={options.visibility ?? 'PRIVATE'}
      open={true}
      onClose={onClose}
    />,
    { wrapper: Wrapper },
  )
  return { onClose }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트: 모달 스캐폴드
// ─────────────────────────────────────────────────────────────────────────────

describe('ShareDashboardModal — 스캐폴드', () => {
  it('open=false이면 아무것도 렌더하지 않는다', () => {
    const Wrapper = createWrapper()
    const onClose = vi.fn()
    render(
      <ShareDashboardModal
        dashboardId={DEFAULT_DASHBOARD.id}
        visibility="PRIVATE"
        open={false}
        onClose={onClose}
      />,
      { wrapper: Wrapper },
    )
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('open=true이면 role=dialog aria-modal=true로 렌더된다', () => {
    renderModal()
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAttribute('aria-label')
  })

  it('닫기 버튼 클릭 시 onClose가 호출된다', async () => {
    const user = userEvent.setup()
    const { onClose } = renderModal()
    await user.click(screen.getByRole('button', { name: /닫기/i }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('Escape 키를 누르면 onClose가 호출된다', async () => {
    const user = userEvent.setup()
    const { onClose } = renderModal()
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트: PRIVATE/TEAM 경고 배너
// ─────────────────────────────────────────────────────────────────────────────

describe('ShareDashboardModal — 공개 범위 경고 배너', () => {
  it('visibility=PRIVATE이면 경고 배너가 표시된다', () => {
    renderModal({ visibility: 'PRIVATE' })
    expect(screen.getByText('링크가 있는 누구나 읽을 수 있습니다')).toBeInTheDocument()
  })

  it('visibility=TEAM이면 경고 배너가 표시된다', () => {
    renderModal({ visibility: 'TEAM' })
    expect(screen.getByText('링크가 있는 누구나 읽을 수 있습니다')).toBeInTheDocument()
  })

  it('visibility=ORG이면 경고 배너가 표시되지 않는다', () => {
    renderModal({ visibility: 'ORG' })
    expect(screen.queryByText('링크가 있는 누구나 읽을 수 있습니다')).toBeNull()
  })

  it('경고 배너는 destructive(빨강) 스타일을 사용하지 않는다', () => {
    renderModal({ visibility: 'PRIVATE' })
    const banner = screen.getByRole('note')
    expect(banner.className).not.toMatch(/destructive/)
    expect(banner.className).toMatch(/warning/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트: 링크 생성 + 복사
// ─────────────────────────────────────────────────────────────────────────────

describe('ShareDashboardModal — 링크 생성 및 복사', () => {
  it('링크 생성 버튼 클릭 시 발급된 토큰으로 공개 URL이 표시된다', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByRole('button', { name: '링크 생성' }))

    await waitFor(() => {
      const urlBox = screen.getByDisplayValue(new RegExp(`${window.location.origin}/dashboards/shared/`))
      expect(urlBox).toBeInTheDocument()
    })
  })

  it('링크 생성 직후 "지금만 복사할 수 있습니다" 안내가 표시된다', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByRole('button', { name: '링크 생성' }))

    expect(await screen.findByText('이 링크는 지금만 복사할 수 있습니다')).toBeInTheDocument()
  })

  it('복사 버튼 클릭 시 클립보드에 공개 URL이 복사되고 라벨이 "복사됨"으로 전환된다', async () => {
    // userEvent.setup()이 navigator를 재구성하므로 clipboard 스텁은 setup() 이후에 적용한다
    // (memory: userEvent.setup()이 vi.stubGlobal('navigator', ...)를 무효화)
    const user = userEvent.setup()
    stubClipboard()
    renderModal()

    await user.click(screen.getByRole('button', { name: '링크 생성' }))
    await screen.findByText('이 링크는 지금만 복사할 수 있습니다')

    const copyButtons = screen.getAllByRole('button', { name: '복사' })
    const urlCopyButton = copyButtons[0]
    if (urlCopyButton === undefined) throw new Error('복사 버튼을 찾을 수 없습니다')
    await user.click(urlCopyButton)

    await waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith(
        expect.stringContaining(`${window.location.origin}/dashboards/shared/`),
      )
    })
    expect(await screen.findByText('복사됨')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트: 임베드 코드
// ─────────────────────────────────────────────────────────────────────────────

describe('ShareDashboardModal — 임베드 코드', () => {
  it('링크 생성 전에는 임베드 코드 섹션이 표시되지 않는다', () => {
    renderModal()
    expect(screen.queryByText('임베드 코드')).toBeNull()
  })

  it('링크 생성 후 iframe 임베드 스니펫이 방금 발급한 토큰으로 표시된다', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByRole('button', { name: '링크 생성' }))

    await waitFor(() => {
      expect(screen.getByText('임베드 코드')).toBeInTheDocument()
    })
    const snippet = screen.getByText(/<iframe/)
    expect(snippet.textContent).toContain(`${window.location.origin}/dashboards/shared/`)
    expect(snippet.textContent).toContain('?embed=1')
  })

  it('임베드 복사 버튼 클릭 시 클립보드에 iframe 스니펫이 복사된다', async () => {
    const user = userEvent.setup()
    stubClipboard()
    renderModal()

    await user.click(screen.getByRole('button', { name: '링크 생성' }))
    await waitFor(() => {
      expect(screen.getByText('임베드 코드')).toBeInTheDocument()
    })

    const copyButtons = screen.getAllByRole('button', { name: '복사' })
    const embedCopyButton = copyButtons[1]
    if (embedCopyButton === undefined) throw new Error('임베드 복사 버튼을 찾을 수 없습니다')
    await user.click(embedCopyButton)

    await waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith(expect.stringContaining('<iframe'))
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트: 발급된 링크 목록 + 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('ShareDashboardModal — 발급된 링크 목록', () => {
  it('발급된 토큰이 없으면 빈 상태 문구가 표시된다', async () => {
    renderModal()
    expect(await screen.findByText('아직 발급된 공유 링크가 없습니다')).toBeInTheDocument()
  })

  it('발급된 토큰이 있으면 목록에 만료 없음 라벨과 함께 표시된다', async () => {
    seedShareToken({
      id: 'd0000000-0000-4000-8000-000000000001',
      dashboardId: DEFAULT_DASHBOARD.id,
      token: 'raw-token-should-not-leak',
      createdAt: '2026-07-01T00:00:00.000Z',
      expiresAt: null,
    })
    renderModal()

    expect(await screen.findByText('만료 없음')).toBeInTheDocument()
    // 빈 상태 문구는 사라져야 한다
    expect(screen.queryByText('아직 발급된 공유 링크가 없습니다')).toBeNull()
  })

  it('목록 항목에는 복사/임베드 버튼이 없다 (원문 토큰 재조회 불가)', async () => {
    seedShareToken({
      id: 'd0000000-0000-4000-8000-000000000001',
      dashboardId: DEFAULT_DASHBOARD.id,
      token: 'raw-token-should-not-leak',
      createdAt: '2026-07-01T00:00:00.000Z',
      expiresAt: null,
    })
    renderModal()

    await screen.findByText('만료 없음')
    // 링크를 아직 생성하지 않은 상태이므로 복사 버튼은 화면에 전혀 없어야 한다
    expect(screen.queryByRole('button', { name: '복사' })).toBeNull()
  })

  it('목록 항목에는 lastAccessedAt(마지막 접근일)이 표시되지 않는다', async () => {
    seedShareToken({
      id: 'd0000000-0000-4000-8000-000000000001',
      dashboardId: DEFAULT_DASHBOARD.id,
      token: 'raw-token-should-not-leak',
      createdAt: '2026-07-01T00:00:00.000Z',
      expiresAt: null,
    })
    renderModal()

    await screen.findByText('만료 없음')
    expect(screen.queryByText(/마지막 접근/)).toBeNull()
  })

  it('취소 버튼 클릭 시 인라인 확인 문구와 확인/취소 버튼이 표시된다', async () => {
    const user = userEvent.setup()
    seedShareToken({
      id: 'd0000000-0000-4000-8000-000000000002',
      dashboardId: DEFAULT_DASHBOARD.id,
      token: 'raw-token-to-revoke',
      createdAt: '2026-07-01T00:00:00.000Z',
      expiresAt: null,
    })
    renderModal()

    const revokeBtn = await screen.findByRole('button', { name: '취소' })
    await user.click(revokeBtn)

    expect(await screen.findByText('정말 이 링크를 취소하시겠습니까?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '확인' })).toBeInTheDocument()
  })

  it('인라인 확인에서 확인 클릭 시 취소 API가 호출되고 목록에서 항목이 제거된다', async () => {
    const user = userEvent.setup()
    seedShareToken({
      id: 'd0000000-0000-4000-8000-000000000003',
      dashboardId: DEFAULT_DASHBOARD.id,
      token: 'raw-token-to-revoke-2',
      createdAt: '2026-07-01T00:00:00.000Z',
      expiresAt: null,
    })
    renderModal()

    const revokeBtn = await screen.findByRole('button', { name: '취소' })
    await user.click(revokeBtn)
    await screen.findByText('정말 이 링크를 취소하시겠습니까?')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(screen.getByText('아직 발급된 공유 링크가 없습니다')).toBeInTheDocument()
    })
  })

  it('발급 직후 그 토큰을 목록에서 취소하면 상단 공개 URL/임베드 영역이 사라진다 (데드엔드 방지, C1)', async () => {
    const user = userEvent.setup()
    renderModal()

    // 링크 생성 — 상단에 공개 URL/임베드 영역이 뜬다
    await user.click(screen.getByRole('button', { name: '링크 생성' }))
    await screen.findByText('이 링크는 지금만 복사할 수 있습니다')
    expect(screen.getByText('임베드 코드')).toBeInTheDocument()

    // 목록이 refetch되어 방금 발급한 항목의 취소 버튼이 나타날 때까지 대기
    const revokeBtn = await screen.findByRole('button', { name: '취소' })
    await user.click(revokeBtn)
    await screen.findByText('정말 이 링크를 취소하시겠습니까?')
    await user.click(screen.getByRole('button', { name: '확인' }))

    // 상단 영역(복사 안내/임베드 코드)이 죽은 링크를 계속 보여주지 않아야 한다
    await waitFor(() => {
      expect(screen.queryByText('이 링크는 지금만 복사할 수 있습니다')).toBeNull()
    })
    expect(screen.queryByText('임베드 코드')).toBeNull()
    expect(screen.getByRole('button', { name: '링크 생성' })).toBeInTheDocument()
    expect(screen.getByText('아직 발급된 공유 링크가 없습니다')).toBeInTheDocument()
  })

  it('인라인 확인에서 취소(아니오) 클릭 시 확인 문구가 사라지고 항목은 유지된다', async () => {
    const user = userEvent.setup()
    seedShareToken({
      id: 'd0000000-0000-4000-8000-000000000004',
      dashboardId: DEFAULT_DASHBOARD.id,
      token: 'raw-token-keep',
      createdAt: '2026-07-01T00:00:00.000Z',
      expiresAt: null,
    })
    renderModal()

    const revokeBtn = await screen.findByRole('button', { name: '취소' })
    await user.click(revokeBtn)
    await screen.findByText('정말 이 링크를 취소하시겠습니까?')

    const dialog = screen.getByRole('dialog')
    const cancelBtn = within(dialog).getAllByRole('button', { name: '취소' }).slice(-1)[0]
    if (cancelBtn === undefined) throw new Error('인라인 취소(아니오) 버튼을 찾을 수 없습니다')
    await user.click(cancelBtn)

    await waitFor(() => {
      expect(screen.queryByText('정말 이 링크를 취소하시겠습니까?')).toBeNull()
    })
    expect(screen.getByText('만료 없음')).toBeInTheDocument()
  })
})
