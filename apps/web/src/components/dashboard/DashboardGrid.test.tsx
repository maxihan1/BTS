// DashboardGrid 컴포넌트 단위 테스트 — react-grid-layout WidthProvider stub + onLayoutChange 순수 로직 (FR-DB-01 Task 8)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DashboardTile } from '@/lib/dashboard-layout'
import { dashboardModeLabels } from '@/i18n/dashboard-labels'

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
  /**
   * 편집 **모드**. 기본값은 `canEdit` 를 따라간다 — 이 파일의 기존 테스트가 전부
   * 「권한이 있으면 드래그된다」를 재고 있어서, 기본을 false 로 두면 그 의미가 통째로 바뀐다.
   * 두 축이 갈리는 지점은 아래 「보기/편집 모드」 describe 가 따로 잰다.
   */
  isEditing?: boolean
  onLayoutChange?: (tiles: DashboardTile[]) => void
  onDeleteTile?: (id: string) => void
  onEditTitle?: (id: string, title: string) => void
  onDuplicate?: (id: string) => void
  onAddTile?: () => void
  publicMode?: boolean
}) {
  const { DashboardGrid } = await import('@/components/dashboard/DashboardGrid')
  const {
    tiles = [],
    canEdit = false,
    isEditing = canEdit,
    onLayoutChange,
    onDeleteTile,
    onEditTitle,
    onDuplicate,
    onAddTile,
    publicMode,
  } = props
  return render(
    <DashboardGrid
      tiles={tiles}
      canEdit={canEdit}
      isEditing={isEditing}
      onLayoutChange={onLayoutChange ?? vi.fn()}
      onDeleteTile={onDeleteTile ?? vi.fn()}
      onEditTitle={onEditTitle ?? vi.fn()}
      onDuplicate={onDuplicate ?? vi.fn()}
      onAddTile={onAddTile ?? (canEdit ? vi.fn() : undefined)}
      publicMode={publicMode}
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
  it('G-5: 타일이 없으면 빈 상태 안내 영역이 렌더된다 (C4 가젯 추가 1차 버튼)', async () => {
    await renderGrid({ tiles: [], canEdit: true })
    // 빈 상태 = 점선 테두리 영역 + "가젯 추가" 1차 버튼 (C4: 가젯 일원화)
    expect(screen.getByRole('button', { name: /가젯 추가/i })).toBeInTheDocument()
  })

  /**
   * G-6. canEdit=false이면 빈 그리드에서 "가젯 추가" 버튼이 없다 (읽기 전용 EC3, C4).
   */
  it('G-6: canEdit=false이면 빈 그리드에서 가젯 추가 버튼이 없다 (C4)', async () => {
    await renderGrid({ tiles: [], canEdit: false })
    expect(screen.queryByRole('button', { name: /가젯 추가/i })).toBeNull()
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
  it('G-8: canEdit=true이면 타일에 ⋯ 메뉴가 렌더된다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: true })
    expect(
      screen.getByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).toBeInTheDocument()
  })

  /**
   * G-9. canEdit=false이면 타일에 ⋯ 메뉴가 없다 (읽기 전용 EC3).
   *
   * ★종전 단언은 `queryByRole('button', { name: /삭제/i })` 였고 **항상 참**이었다.
   *   ⋯ 트리거의 접근명은 `가젯 메뉴` 이고 「삭제」는 **닫힌 드롭다운 안의 `menuitem`** 이라,
   *   어떤 canEdit/isEditing 조합에서도 그 이름의 `button` 은 존재하지 않는다.
   *   `canEdit: true, isEditing: true` 로 뒤집어도 20 passed 였다(실측).
   *   제목만 「⋯ 메뉴가 없다」로 바뀌고 재는 것은 그대로였던 것이다 —
   *   `invariant-satisfied-by-helptext-not-logic` 양식.
   */
  it('G-9: canEdit=false이면 타일에 ⋯ 메뉴가 없다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: false })
    expect(
      screen.queryByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).toBeNull()
  })

  /**
   * G-9b. 권한이 있고 편집 모드면 ⋯ 메뉴가 **있다**.
   *
   * G-9 의 짝이다. 부재만 재면 「어떤 조합에서도 없다」와 구분이 안 된다 —
   * 그 상태가 방금 고친 가짜 그린이었다.
   */
  it('G-9b: canEdit=true + 편집 모드면 타일에 ⋯ 메뉴가 있다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: true, isEditing: true })
    expect(
      screen.getByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).toBeInTheDocument()
  })

  /**
   * G-10. 삭제 버튼 클릭 시 onDeleteTile이 타일 id로 호출된다.
   */
  it('G-10: ⋯ 메뉴의 삭제를 누르면 onDeleteTile이 타일 id로 호출된다', async () => {
    const user = userEvent.setup()
    const onDeleteTile = vi.fn()
    await renderGrid({ tiles: [TILE_A], canEdit: true, onDeleteTile })
    await user.click(screen.getByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }))
    await user.click(screen.getByRole('menuitem', { name: /삭제/i }))
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

  // ───────────────────────────────────────────────────────────────────────────
  // publicMode — 익명 공유 뷰 강제 읽기전용 (FR-DB-03 D6/D7 Task-8)
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * G-13. publicMode=true이면 canEdit=true여도 GridLayout에 isDraggable=false/isResizable=false가
   * 전달된다 (canEdit과 무관하게 강제 읽기전용).
   */
  it('G-13: publicMode=true이면 canEdit=true여도 GridLayout이 강제로 드래그/리사이즈 비활성화된다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: true, publicMode: true })
    expect(capturedGridLayoutProps?.isDraggable).toBe(false)
    expect(capturedGridLayoutProps?.isResizable).toBe(false)
  })

  /**
   * G-14. publicMode=true이면 canEdit=true·빈 그리드여도 "가젯 추가" 버튼이 노출되지 않는다.
   */
  it('G-14: publicMode=true이면 canEdit=true·빈 그리드여도 가젯 추가 버튼이 없다', async () => {
    await renderGrid({ tiles: [], canEdit: true, publicMode: true })
    expect(screen.queryByRole('button', { name: /가젯 추가/i })).toBeNull()
  })

  /**
   * G-15. publicMode=true이면 canEdit=true여도 타일 삭제 버튼이 렌더되지 않는다
   * (DashboardGrid → DashboardTile 강제 읽기전용 스레딩 확인).
   */
  it('G-15: publicMode=true이면 canEdit=true여도 타일 삭제 버튼이 없다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: true, publicMode: true })
    expect(screen.queryByRole('button', { name: /삭제/i })).toBeNull()
  })

  /**
   * G-16. publicMode=true이면 타일 본문이 PublicGadgetRenderer로 렌더된다(종단간 확인).
   * TILE_A는 gadgetType이 없는 legacy 타일 — PublicGadgetRenderer는 이 경우도
   * fail-closed로 "로그인이 필요한 가젯입니다" 플레이스홀더를 렌더한다.
   */
  it('G-16: publicMode=true이면 타일 본문이 PublicGadgetRenderer로 렌더된다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: true, publicMode: true })
    expect(screen.getByText('로그인이 필요한 가젯입니다')).toBeInTheDocument()
  })

  /**
   * G-17. publicMode 미전달(기본 false)이면 기존 동작 그대로다(회귀 방지 — G-3과 동일 단언).
   */
  it('G-17: publicMode 미전달이면 canEdit=true일 때 기존처럼 드래그/리사이즈가 활성화된다', async () => {
    await renderGrid({ tiles: [TILE_A], canEdit: true })
    expect(capturedGridLayoutProps?.isDraggable).toBe(true)
    expect(capturedGridLayoutProps?.isResizable).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 보기/편집 모드 — 권한과 모드는 다른 축이다 (Jira 패리티 JD-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardGrid — 보기/편집 모드', () => {
  const TILE: DashboardTile = { i: 'a', x: 0, y: 0, w: 4, h: 3, title: '위젯' }

  // react-grid-layout 이 목이라 실제 클래스가 안 붙는다. 이 파일이 이미 쓰는
  // `capturedGridLayoutProps` 로 **무엇이 전달됐는가**를 잰다 (G-2·G-3 과 같은 방식).

  it('★권한이 있어도 보기 모드면 드래그·리사이즈가 꺼진다', async () => {
    await renderGrid({ tiles: [TILE], canEdit: true, isEditing: false })

    expect(capturedGridLayoutProps?.isDraggable).toBe(false)
    expect(capturedGridLayoutProps?.isResizable).toBe(false)
  })

  it('권한 + 편집 모드면 드래그·리사이즈가 켜진다', async () => {
    await renderGrid({ tiles: [TILE], canEdit: true, isEditing: true })

    expect(capturedGridLayoutProps?.isDraggable).toBe(true)
    expect(capturedGridLayoutProps?.isResizable).toBe(true)
  })

  it('권한이 없으면 편집 모드여도 꺼진다', async () => {
    await renderGrid({ tiles: [TILE], canEdit: false, isEditing: true })

    expect(capturedGridLayoutProps?.isDraggable).toBe(false)
  })
})
