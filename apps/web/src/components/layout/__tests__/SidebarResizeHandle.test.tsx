// 사이드바 폭 조절 핸들 단위 테스트 — ARIA splitter 계약 + 키보드 조작 (Jira 패리티 J6)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
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

    expect(
      screen.queryByRole('separator', { name: navLabels.resizeSidebar }),
    ).not.toBeInTheDocument()
  })

  it('T-RH-4: 모바일 드로어에서는 렌더하지 않는다', () => {
    // 드로어 폭은 264px 고정 계약이다 — 저장 폭이 실릴 자리가 아니다.
    matchMediaMock.mockReturnValue(true)
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    expect(
      screen.queryByRole('separator', { name: navLabels.resizeSidebar }),
    ).not.toBeInTheDocument()
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

  it('T-RH-10: 처리하지 않는 키는 preventDefault 하지 않는다 (전역 단축키 보호)', () => {
    // ★「폭이 안 바뀐다」만 재면 공허하다 — `[` 는 어떤 구현에서도 폭을 안 바꾸므로
    //   그 단언은 아무것도 배제하지 못한다. 실제로 `event.preventDefault()` 를 조기 반환
    //   **위로** 올려도(= 핸들 위 모든 키가 죽는다) 초록이었다(코드 리뷰 C2 뮤테이션).
    //   전역 `[` 가 사는 근거는 defaultPrevented 가 false 라는 것이므로 그것을 직접 잰다.
    render(<SidebarResizeHandle controlsId="app-sidebar" />)

    const el = handle()
    el.focus()
    const passthrough = new KeyboardEvent('keydown', {
      key: '[',
      bubbles: true,
      cancelable: true,
    })
    el.dispatchEvent(passthrough)

    expect(passthrough.defaultPrevented, '전역 `[` 가 핸들 위에서 죽는다').toBe(false)
    expect(useSidebarWidth.getState().width).toBe(DEFAULT_SIDEBAR_WIDTH)

    // 반대편 — 처리하는 키는 반드시 막아야 가로 스크롤·캐럿 브라우징이 끼어들지 않는다.
    const handled = new KeyboardEvent('keydown', {
      key: 'ArrowRight',
      bubbles: true,
      cancelable: true,
    })
    el.dispatchEvent(handled)
    expect(handled.defaultPrevented, '처리한 키를 막지 않는다').toBe(true)
  })

  it('T-RH-11: 드래그 중에는 영속하지 않고 pointerup 에 한 번 확정한다', () => {
    // ★이 PR 이 가장 크게 내세우는 결정(60fps localStorage 쓰기 방지)인데, 스토어 API 단언
    //   (T-SW-4)만으로는 **배선**이 봉인되지 않는다. `pointermove` 의 setWidth 를 commitWidth 로
    //   바꿔도 유닛·E2E 가 전부 초록이었다(코드 리뷰 C3) — E2E 는 폭만 재고 쓰기 횟수를 안 센다.
    render(<SidebarResizeHandle controlsId="app-sidebar" />)
    const el = handle()

    fireEvent.pointerDown(el, { clientX: DEFAULT_SIDEBAR_WIDTH, pointerId: 1 })
    fireEvent.pointerMove(el, { clientX: DEFAULT_SIDEBAR_WIDTH + 36, pointerId: 1 })

    expect(useSidebarWidth.getState().width).toBe(DEFAULT_SIDEBAR_WIDTH + 36)
    expect(
      localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY),
      '드래그 중에 영속했다 — 포인터 이동마다 쓰기가 일어난다',
    ).toBeNull()

    fireEvent.pointerUp(el, { clientX: DEFAULT_SIDEBAR_WIDTH + 36, pointerId: 1 })

    expect(localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY)).toBe(String(DEFAULT_SIDEBAR_WIDTH + 36))
    expect(document.body.style.userSelect, '드래그가 끝났는데 선택 차단이 남아 있다').toBe('')
  })

  it('T-RH-12: 드래그 중 언마운트돼도 body 의 텍스트 선택 차단이 남지 않는다', () => {
    // 🛑 실제 재현 경로 — 핸들을 누른 채 `[`(전역 토글)를 누르면 레일이 되어 핸들이 언마운트된다.
    //    `endDrag` 는 요소 자신의 pointerup/pointercancel 에만 물려 있어 복구가 안 되고,
    //    `user-select: none` 이 body 에 남아 **앱 전체에서 텍스트 선택·복사가 죽는다**
    //    (코드 리뷰 C1, 프로브로 실측 확인). 새로고침 말고는 복구 경로가 없다.
    const { unmount } = render(<SidebarResizeHandle controlsId="app-sidebar" />)

    fireEvent.pointerDown(handle(), { clientX: DEFAULT_SIDEBAR_WIDTH, pointerId: 1 })
    expect(document.body.style.userSelect).toBe('none')

    unmount()

    expect(document.body.style.userSelect, '언마운트 뒤에도 선택 차단이 남았다').toBe('')
  })

  it('T-RH-13: aria-valuetext 가 단위를 읽어 준다', () => {
    // `aria-valuenow` 만 있으면 스크린리더가 「264」라고만 읽는다 — 무엇의 264인지 알 수 없다.
    render(<SidebarResizeHandle controlsId="app-sidebar" />)
    expect(handle()).toHaveAttribute('aria-valuetext', `${DEFAULT_SIDEBAR_WIDTH}픽셀`)
  })
})
