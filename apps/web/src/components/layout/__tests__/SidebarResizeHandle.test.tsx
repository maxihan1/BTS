// 사이드바 폭 조절 핸들 단위 테스트 — ARIA splitter 계약 + 키보드 조작 (Jira 패리티 J6)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SidebarResizeHandle } from '../SidebarResizeHandle'
import {
  useSidebarWidth,
  MIN_SIDEBAR_WIDTH,
  MAX_SIDEBAR_WIDTH,
  DEFAULT_SIDEBAR_WIDTH,
  SIDEBAR_WIDTH_STORAGE_KEY,
} from '@/hooks/use-sidebar-width'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { navLabels } from '@/i18n/nav-labels'

// `useMediaQuery` 는 jsdom 에서 항상 false(=데스크톱)를 낸다. 모바일 분기는 이 mock 으로 뒤집는다.
const matchMediaMock = vi.fn<(query: string) => boolean>(() => false)

vi.mock('@/hooks/use-media-query', () => ({
  useMediaQuery: (query: string): boolean => matchMediaMock(query),
}))

/** 핸들을 `aria-label` 로 집는다 — 토글 버튼(「사이드바 접기」)과 이름이 갈려 있어야 성립한다 */
function handle(): HTMLElement {
  return screen.getByRole('separator', { name: navLabels.resizeSidebar })
}

describe('SidebarResizeHandle', () => {
  beforeEach(() => {
    localStorage.clear()
    matchMediaMock.mockReturnValue(false)
    useSidebarWidth.setState({ width: DEFAULT_SIDEBAR_WIDTH })
    useSidebarCollapsed.setState({ collapsed: false })
  })

  it('T-RH-1: ARIA splitter 계약을 모두 싣는다', () => {
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    const el = handle()
    expect(el).toHaveAttribute('aria-orientation', 'vertical')
    expect(el).toHaveAttribute('aria-valuenow', String(DEFAULT_SIDEBAR_WIDTH))
    expect(el).toHaveAttribute('aria-valuemin', String(MIN_SIDEBAR_WIDTH))
    expect(el).toHaveAttribute('aria-valuemax', String(MAX_SIDEBAR_WIDTH))
    expect(el).toHaveAttribute('aria-controls', 'app-sidebar')
  })

  it('T-RH-2: 키보드 포커스를 받는다 (tabIndex=0)', () => {
    render(<SidebarResizeHandle controlsId="app-sidebar" />)
    expect(handle()).toHaveAttribute('tabindex', '0')
  })

  it('T-RH-3: 아이콘 레일(접힘)에서는 렌더하지 않는다', () => {
    // 🛑 64px 고정이라 aria-valuenow 가 거짓말이 되고, 끌어도 아무 일이 없는 죽은 컨트롤이 된다.
    useSidebarCollapsed.setState({ collapsed: true })
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    expect(screen.queryByRole('separator', { name: navLabels.resizeSidebar })).not.toBeInTheDocument()
  })

  it('T-RH-4: 모바일 드로어에서는 렌더하지 않는다', () => {
    // 드로어 폭은 264px 고정 계약이다 — 저장 폭이 실릴 자리가 아니다.
    matchMediaMock.mockReturnValue(true)
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    expect(screen.queryByRole('separator', { name: navLabels.resizeSidebar })).not.toBeInTheDocument()
  })

  it('T-RH-5: 화살표 키가 폭을 16px 씩 움직이고 영속한다', async () => {
    const user = userEvent.setup()
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    handle().focus()
    await user.keyboard('{ArrowRight}')

    expect(useSidebarWidth.getState().width).toBe(DEFAULT_SIDEBAR_WIDTH + 16)
    expect(localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY)).toBe(String(DEFAULT_SIDEBAR_WIDTH + 16))

    await user.keyboard('{ArrowLeft}{ArrowLeft}')
    expect(useSidebarWidth.getState().width).toBe(DEFAULT_SIDEBAR_WIDTH - 16)
  })

  it('T-RH-6: Shift + 화살표는 64px 씩 움직인다', async () => {
    const user = userEvent.setup()
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    handle().focus()
    await user.keyboard('{Shift>}{ArrowRight}{/Shift}')

    expect(useSidebarWidth.getState().width).toBe(DEFAULT_SIDEBAR_WIDTH + 64)
  })

  it('T-RH-7: Home·End 는 최소·최대로 보낸다', async () => {
    const user = userEvent.setup()
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    handle().focus()
    await user.keyboard('{Home}')
    expect(useSidebarWidth.getState().width).toBe(MIN_SIDEBAR_WIDTH)

    await user.keyboard('{End}')
    expect(useSidebarWidth.getState().width).toBe(MAX_SIDEBAR_WIDTH)
  })

  it('T-RH-8: 범위 끝에서 더 눌러도 넘어가지 않는다', async () => {
    const user = userEvent.setup()
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    handle().focus()
    await user.keyboard('{End}{ArrowRight}{ArrowRight}')
    expect(useSidebarWidth.getState().width).toBe(MAX_SIDEBAR_WIDTH)

    await user.keyboard('{Home}{ArrowLeft}{ArrowLeft}')
    expect(useSidebarWidth.getState().width).toBe(MIN_SIDEBAR_WIDTH)
  })

  it('T-RH-9: aria-valuenow 가 조작을 따라 갱신된다', async () => {
    // ★값이 바뀌는데 속성이 안 따라오면 스크린리더는 첫 폭을 계속 읽는다 — 보이는 것과
    //   읽히는 것이 갈리는 결함이라 시각 확인으로는 절대 안 잡힌다.
    const user = userEvent.setup()
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    handle().focus()
    await user.keyboard('{ArrowRight}')

    expect(handle()).toHaveAttribute('aria-valuenow', String(DEFAULT_SIDEBAR_WIDTH + 16))
  })

  it('T-RH-10: 처리하지 않는 키는 흘려보낸다', async () => {
    // 전역 `[` 단축키(사이드바 토글)가 핸들에 포커스가 있다고 죽으면 안 된다.
    const user = userEvent.setup()
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    handle().focus()
    // `[` 는 user-event 키보드 문법의 물리 키 표기 시작 문자라 `[BracketLeft]` 로 지정한다.
    await user.keyboard('[BracketLeft]')

    expect(useSidebarWidth.getState().width).toBe(DEFAULT_SIDEBAR_WIDTH)
  })
})
