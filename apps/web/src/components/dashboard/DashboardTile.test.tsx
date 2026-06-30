// DashboardTile 컴포넌트 단위 테스트 — 가젯 통합 + legacy 무회귀 (FR-DB-02 Task 7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DashboardTile as DashboardTileData } from '@/lib/dashboard-layout'

// ─────────────────────────────────────────────────────────────────────────────
// GadgetRenderer mock — gadgetType 분기 단언용
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/dashboard/gadgets/GadgetRenderer', () => ({
  GadgetRenderer: ({ tile }: { tile: DashboardTileData }) => (
    <div data-testid="gadget-renderer" data-gadget-type={tile.gadgetType} />
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixtures
// ─────────────────────────────────────────────────────────────────────────────

/** legacy 타일 — gadgetType 없음 */
const LEGACY_TILE: DashboardTileData = {
  i: 'tile-legacy',
  x: 0,
  y: 0,
  w: 6,
  h: 4,
  title: '일반 위젯',
}

/** 가젯 타일 — gadgetType 있음 */
const GADGET_TILE: DashboardTileData = {
  i: 'tile-gadget',
  x: 0,
  y: 0,
  w: 4,
  h: 3,
  title: '',
  gadgetType: 'text_widget',
  config: { markdown: '## 안녕하세요' },
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function renderTile(opts: {
  tile?: DashboardTileData
  canEdit?: boolean
  onDelete?: (id: string) => void
  onEditTitle?: (id: string, title: string) => void
}) {
  const { DashboardTile } = await import('@/components/dashboard/DashboardTile')
  const { tile = LEGACY_TILE, canEdit = false, onDelete = vi.fn(), onEditTitle = vi.fn() } = opts
  return render(
    <DashboardTile
      tile={tile}
      canEdit={canEdit}
      onDelete={onDelete}
      onEditTitle={onEditTitle}
    />,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// legacy 타일 무회귀 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardTile — legacy 타일 무회귀', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /**
   * T7-T1: gadgetType이 없으면 placeholder 텍스트가 렌더된다.
   */
  it('T7-T1: gadgetType이 없으면 기존 placeholder가 렌더된다', async () => {
    await renderTile({ tile: LEGACY_TILE })
    expect(screen.getByText('이 자리에 위젯을 추가할 수 있습니다')).toBeInTheDocument()
  })

  /**
   * T7-T2: gadgetType이 없으면 GadgetRenderer가 렌더되지 않는다.
   */
  it('T7-T2: gadgetType이 없으면 GadgetRenderer가 렌더되지 않는다', async () => {
    await renderTile({ tile: LEGACY_TILE })
    expect(screen.queryByTestId('gadget-renderer')).toBeNull()
  })

  /**
   * T7-T3: gadgetType이 없으면 tile.title이 헤더에 표시된다.
   */
  it('T7-T3: legacy 타일 헤더에 tile.title이 표시된다', async () => {
    await renderTile({ tile: LEGACY_TILE })
    expect(screen.getByText('일반 위젯')).toBeInTheDocument()
  })

  /**
   * T7-T4: canEdit=true이면 삭제 버튼이 렌더된다.
   */
  it('T7-T4: legacy 타일 canEdit=true이면 삭제 버튼이 렌더된다', async () => {
    await renderTile({ tile: LEGACY_TILE, canEdit: true })
    expect(screen.getByRole('button', { name: /삭제/i })).toBeInTheDocument()
  })

  /**
   * T7-T5: canEdit=false이면 삭제 버튼이 없다.
   */
  it('T7-T5: legacy 타일 canEdit=false이면 삭제 버튼이 없다', async () => {
    await renderTile({ tile: LEGACY_TILE, canEdit: false })
    expect(screen.queryByRole('button', { name: /삭제/i })).toBeNull()
  })

  /**
   * T7-T6: canEdit=true이면 제목 클릭 시 편집 input이 활성화된다.
   */
  it('T7-T6: legacy 타일 canEdit=true이면 제목 클릭 시 인라인 편집 input이 활성화된다', async () => {
    const user = userEvent.setup()
    await renderTile({ tile: LEGACY_TILE, canEdit: true })
    await user.click(screen.getByText('일반 위젯'))
    expect(screen.getByRole('textbox')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 가젯 타일 통합 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardTile — 가젯 타일 통합', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /**
   * T7-G1: gadgetType이 있으면 GadgetRenderer가 렌더된다.
   * RED: DashboardTile이 아직 GadgetRenderer를 렌더하지 않아 실패.
   */
  it('T7-G1: gadgetType이 있으면 GadgetRenderer가 렌더된다', async () => {
    await renderTile({ tile: GADGET_TILE })
    expect(screen.getByTestId('gadget-renderer')).toBeInTheDocument()
  })

  /**
   * T7-G2: gadgetType이 있으면 placeholder 텍스트가 없다.
   * RED: DashboardTile이 항상 placeholder를 렌더해 실패.
   */
  it('T7-G2: gadgetType이 있으면 placeholder 텍스트가 없다', async () => {
    await renderTile({ tile: GADGET_TILE })
    expect(screen.queryByText('이 자리에 위젯을 추가할 수 있습니다')).toBeNull()
  })

  /**
   * T7-G3: gadgetType이 있으면 헤더에 한국어 가젯 라벨이 표시된다 (C6).
   * RED: gadgetLabels 매핑 미적용 상태에서 raw 'text_widget'이 나와 실패.
   */
  it('T7-G3: gadgetType이 있으면 헤더에 한국어 가젯 라벨이 표시된다 (C6)', async () => {
    await renderTile({ tile: GADGET_TILE })
    // text_widget → gadgetLabels['text_widget'] = '텍스트'
    expect(screen.getByText('텍스트')).toBeInTheDocument()
  })

  /**
   * T7-G7: 미지 gadgetType은 raw gadgetType을 fallback으로 표시한다 (C6).
   * RED: gadgetLabels 매핑 미적용 상태에서도 raw 타입이 나오므로 GREEN일 수 있지만,
   * 매핑 적용 후 fallback 경로를 명시적으로 검증한다.
   */
  it('T7-G7: 미지 gadgetType은 raw gadgetType을 fallback으로 표시한다 (C6)', async () => {
    await renderTile({ tile: { ...GADGET_TILE, gadgetType: 'unknown_type_xyz' } })
    expect(screen.getByText('unknown_type_xyz')).toBeInTheDocument()
  })

  /**
   * T7-G4: 가젯 타일에서도 canEdit=true이면 삭제 버튼이 렌더된다.
   */
  it('T7-G4: 가젯 타일 canEdit=true이면 삭제 버튼이 렌더된다', async () => {
    await renderTile({ tile: GADGET_TILE, canEdit: true })
    expect(screen.getByRole('button', { name: /삭제/i })).toBeInTheDocument()
  })

  /**
   * T7-G5: 가젯 타일에서도 canEdit=false이면 삭제 버튼이 없다.
   */
  it('T7-G5: 가젯 타일 canEdit=false이면 삭제 버튼이 없다', async () => {
    await renderTile({ tile: GADGET_TILE, canEdit: false })
    expect(screen.queryByRole('button', { name: /삭제/i })).toBeNull()
  })

  /**
   * T7-G6: GadgetRenderer에 tile이 올바르게 전달된다 (gadgetType 포함).
   * RED: GadgetRenderer가 렌더되지 않아 실패.
   */
  it('T7-G6: GadgetRenderer에 tile의 gadgetType이 전달된다', async () => {
    await renderTile({ tile: GADGET_TILE })
    const renderer = screen.getByTestId('gadget-renderer')
    expect(renderer).toHaveAttribute('data-gadget-type', 'text_widget')
  })
})
