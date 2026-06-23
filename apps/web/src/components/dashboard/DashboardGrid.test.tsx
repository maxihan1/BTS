// DashboardGrid 컴포넌트 단위 테스트 — react-grid-layout WidthProvider stub + onLayoutChange 순수 로직 (FR-DB-01 Task 8)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DashboardTile } from '@/lib/dashboard-layout'

// ─────────────────────────────────────────────────────────────────────────────
// react-grid-layout stub — jsdom은 컨테이너 width 0이라 WidthProvider가 정상 동작 안 함.
// WidthProvider/GridLayout을 stub해서 가짜그린 방지.
// ─────────────────────────────────────────────────────────────────────────────

/** 캡처된 GridLayout props — onLayoutChange 단언에 활용 */
let capturedGridLayoutProps: {
  layout?: unknown[]
  onLayoutChange?: (layout: unknown[]) => void
  isDraggable?: boolean
  isResizable?: boolean
} | null = null

vi.mock('react-grid-layout', () => {
  /**
   * WidthProvider stub — 그리드 컨테이너 너비를 1200px로 고정 주입.
   * jsdom에서 clientWidth=0 문제를 우회한다.
   */
  function WidthProvider(Component: React.ComponentType<Record<string, unknown>>) {
    return function WidthInjected(props: Record<string, unknown>) {
      return <Component {...props} width={1200} />
    }
  }

  /**
   * GridLayout stub — 자식 렌더 + props 캡처.
   * layout 배열 구조, isDraggable/isResizable, onLayoutChange 콜백을 캡처한다.
   */
  function GridLayout(props: {
    layout?: unknown[]
    onLayoutChange?: (layout: unknown[]) => void
    isDraggable?: boolean
    isResizable?: boolean
    children?: React.ReactNode
    [key: string]: unknown
  }) {
    capturedGridLayoutProps = {
      layout: props.layout,
      onLayoutChange: props.onLayoutChange,
      isDraggable: props.isDraggable,
      isResizable: props.isResizable,
    }
    return (
      <div data-testid="grid-layout" data-is-draggable={String(props.isDraggable ?? false)}>
        {props.children}
      </div>
    )
  }

  return { WidthProvider, default: GridLayout }
})

// ─────────────────────────────────────────────────────────────────────────────
// fixtures
// ─────────────────────────────────────────────────────────────────────────────

const TILE_A: DashboardTile = { i: 'tile-a', x: 0, y: 0, w: 6, h: 4, title: '위젯 A' }
const TILE_B: DashboardTile = { i: 'tile-b', x: 6, y: 0, w: 6, h: 4, title: '위젯 B' }

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function renderGrid(props: {
  tiles?: DashboardTile[]
  canEdit?: boolean
  onLayoutChange?: (tiles: DashboardTile[]) => void
  onDeleteTile?: (id: string) => void
  onEditTitle?: (id: string, title: string) => void
}) {
  const { DashboardGrid } = await import('@/components/dashboard/DashboardGrid')
  const { tiles = [], canEdit = false, onLayoutChange, onDeleteTile, onEditTitle } = props
  return render(
    <DashboardGrid
      tiles={tiles}
      canEdit={canEdit}
      onLayoutChange={onLayoutChange ?? vi.fn()}
      onDeleteTile={onDeleteTile ?? vi.fn()}
      onEditTitle={onEditTitle ?? vi.fn()}
    />,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardGrid', () => {
  beforeEach(() => {
    capturedGridLayoutProps = null
    vi.clearAllMocks()
  })

  /**
   * G-1. GridLayout stub이 렌더된다 — WidthProvider mock이 정상 동작함을 확인.
   */
  it('G-1: GridLayout stub이 data-testid="grid-layout"으로 렌더된다', async () => {
    await renderGrid({ tiles: [TILE_A] })
    expect(screen.getByTestId('grid-layout')).toBeInTheDocument()
  })

  /**
   * G-2. canEdit=false이면 isDraggable/isResizable=false가 GridLayout에 전달된다.
   */
  it('G-2: canEdit=false이면 GridLayout에 isDraggable=false/isResizable=false가 전달된다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: false })
    expect(capturedGridLayoutProps?.isDraggable).toBe(false)
    expect(capturedGridLayoutProps?.isResizable).toBe(false)
  })

  /**
   * G-3. canEdit=true이면 isDraggable/isResizable=true가 GridLayout에 전달된다.
   */
  it('G-3: canEdit=true이면 GridLayout에 isDraggable=true/isResizable=true가 전달된다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: true })
    expect(capturedGridLayoutProps?.isDraggable).toBe(true)
    expect(capturedGridLayoutProps?.isResizable).toBe(true)
  })

  /**
   * G-4. 타일이 있으면 타일 제목이 렌더된다.
   */
  it('G-4: 타일 제목이 화면에 렌더된다', async () => {
    await renderGrid({ tiles: [TILE_A, TILE_B] })
    expect(screen.getByText('위젯 A')).toBeInTheDocument()
    expect(screen.getByText('위젯 B')).toBeInTheDocument()
  })

  /**
   * G-5. 타일이 없으면(빈 그리드) 빈 상태 안내 문구가 렌더된다.
   */
  it('G-5: 타일이 없으면 빈 상태 안내 영역이 렌더된다', async () => {
    await renderGrid({ tiles: [], canEdit: true })
    // 빈 상태 = 점선 테두리 영역 + "위젯 추가" 1차 버튼
    expect(screen.getByRole('button', { name: /위젯 추가/i })).toBeInTheDocument()
  })

  /**
   * G-6. canEdit=false이면 빈 그리드에서 "위젯 추가" 버튼이 없다 (읽기 전용 EC3).
   */
  it('G-6: canEdit=false이면 빈 그리드에서 위젯 추가 버튼이 없다', async () => {
    await renderGrid({ tiles: [], canEdit: false })
    expect(screen.queryByRole('button', { name: /위젯 추가/i })).toBeNull()
  })

  /**
   * G-7. onLayoutChange 콜백이 호출될 때 DashboardTile[] 형태로 변환된다.
   * GridLayout의 onLayoutChange → DashboardTile 변환 순수 로직 단위 검증.
   */
  it('G-7: onLayoutChange 콜백이 DashboardTile[] 형태를 전달받는다', async () => {
    const onLayoutChange = vi.fn<(tiles: DashboardTile[]) => void>()
    await renderGrid({ tiles: [TILE_A], canEdit: true, onLayoutChange })

    // GridLayout stub의 onLayoutChange를 직접 호출 (드래그 시뮬레이션)
    const newLayout = [{ i: 'tile-a', x: 2, y: 1, w: 6, h: 4 }]
    capturedGridLayoutProps?.onLayoutChange?.(newLayout)

    expect(onLayoutChange).toHaveBeenCalledOnce()
    const result = onLayoutChange.mock.calls[0]?.[0]
    // 변환된 타일에 title이 보존돼야 한다 — mock.calls[0][0] 타입 안전 접근
    const firstTile = Array.isArray(result) ? result[0] : undefined
    expect((firstTile as DashboardTile | undefined)?.title).toBe('위젯 A')
    expect((firstTile as DashboardTile | undefined)?.x).toBe(2)
    expect((firstTile as DashboardTile | undefined)?.y).toBe(1)
  })

  /**
   * G-8. canEdit=true이면 타일에 삭제 버튼이 렌더된다.
   */
  it('G-8: canEdit=true이면 타일에 삭제 버튼이 렌더된다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: true })
    expect(screen.getByRole('button', { name: /삭제/i })).toBeInTheDocument()
  })

  /**
   * G-9. canEdit=false이면 타일에 삭제 버튼이 없다 (읽기 전용 EC3).
   */
  it('G-9: canEdit=false이면 타일에 삭제 버튼이 없다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: false })
    expect(screen.queryByRole('button', { name: /삭제/i })).toBeNull()
  })

  /**
   * G-10. 삭제 버튼 클릭 시 onDeleteTile이 타일 id로 호출된다.
   */
  it('G-10: 삭제 버튼 클릭 시 onDeleteTile이 타일 id로 호출된다', async () => {
    const user = userEvent.setup()
    const onDeleteTile = vi.fn()
    await renderGrid({ tiles: [TILE_A], canEdit: true, onDeleteTile })
    await user.click(screen.getByRole('button', { name: /삭제/i }))
    expect(onDeleteTile).toHaveBeenCalledWith('tile-a')
  })

  /**
   * G-11. 제목 인라인 편집: canEdit=true이면 제목 클릭 시 input이 나타난다.
   */
  it('G-11: canEdit=true이면 제목 클릭 시 인라인 편집 input이 활성화된다', async () => {
    const user = userEvent.setup()
    await renderGrid({ tiles: [TILE_A], canEdit: true })
    await user.click(screen.getByText('위젯 A'))
    expect(screen.getByRole('textbox')).toBeInTheDocument()
  })

  /**
   * G-12. 인라인 편집 완료(Enter) 시 onEditTitle이 호출된다.
   */
  it('G-12: 인라인 편집 Enter 시 onEditTitle이 (id, 새제목)으로 호출된다', async () => {
    const user = userEvent.setup()
    const onEditTitle = vi.fn()
    await renderGrid({ tiles: [TILE_A], canEdit: true, onEditTitle })
    await user.click(screen.getByText('위젯 A'))
    const input = screen.getByRole('textbox')
    await user.clear(input)
    await user.type(input, '수정된 제목')
    await user.keyboard('{Enter}')
    expect(onEditTitle).toHaveBeenCalledWith('tile-a', '수정된 제목')
  })
})
