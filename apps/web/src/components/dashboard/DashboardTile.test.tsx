// DashboardTile 컴포넌트 단위 테스트 — 가젯 통합 + legacy 무회귀 (FR-DB-02 Task 7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DashboardTile as DashboardTileData } from '@/lib/dashboard-layout'
import { dashboardModeLabels } from '@/i18n/dashboard-labels'

// ─────────────────────────────────────────────────────────────────────────────
// GadgetRenderer mock — gadgetType 분기 단언용
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/dashboard/gadgets/GadgetRenderer', () => ({
  GadgetRenderer: ({ tile }: { tile: DashboardTileData }) => (
    <div data-testid="gadget-renderer" data-gadget-type={tile.gadgetType} />
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// PublicGadgetRenderer mock — publicMode 분기 단언용 (FR-DB-03 D6/D7 Task-8)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/dashboard/gadgets/PublicGadgetRenderer', () => ({
  PublicGadgetRenderer: ({ tile }: { tile: DashboardTileData }) => (
    <div data-testid="public-gadget-renderer" data-gadget-type={tile.gadgetType ?? ''} />
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
  /**
   * 편집 **모드**. 기본값이 `canEdit` 가 아니라 **false** 인 것이 중요하다 —
   * 기본을 권한에 맞추면 두 축이 다시 하나로 뭉쳐서, 「보기 모드에서 편집 UI 가 뜬다」는
   * 결함을 이 테스트 파일이 영영 못 잡는다.
   */
  isEditing?: boolean
  onDelete?: (id: string) => void
  onEditTitle?: (id: string, title: string) => void
  onDuplicate?: (id: string) => void
  publicMode?: boolean
}) {
  const { DashboardTile } = await import('@/components/dashboard/DashboardTile')
  const {
    tile = LEGACY_TILE,
    canEdit = false,
    isEditing = false,
    onDelete = vi.fn(),
    onEditTitle = vi.fn(),
    onDuplicate = vi.fn(),
    publicMode,
  } = opts
  return render(
    <DashboardTile
      tile={tile}
      canEdit={canEdit}
      isEditing={isEditing}
      onDelete={onDelete}
      onEditTitle={onEditTitle}
      onDuplicate={onDuplicate}
      publicMode={publicMode}
    />,
  )
}

/** `⋯` 메뉴를 열고 항목이 뜰 때까지 기다린다. */
async function openTileMenu(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.click(screen.getByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }))
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
  it('T7-T4: legacy 타일 canEdit=true + 편집 모드면 ⋯ 메뉴에 삭제가 있다', async () => {
    const user = userEvent.setup()
    await renderTile({ tile: LEGACY_TILE, canEdit: true, isEditing: true })
    await openTileMenu(user)
    expect(screen.getByRole('menuitem', { name: /삭제/i })).toBeInTheDocument()
  })

  /**
   * T7-T5: canEdit=false이면 삭제 버튼이 없다.
   */
  it('T7-T5: legacy 타일 canEdit=false이면 삭제 버튼이 없다', async () => {
    await renderTile({ tile: LEGACY_TILE, canEdit: false })
    // ★`button[name=/삭제/]` 로 재지 않는다. ⋯ 트리거의 접근명은 `가젯 메뉴` 이고
    //   「삭제」는 **닫힌 드롭다운 안의 `menuitem`** 이라, 어떤 조합에서도 그 이름의
    //   button 은 없다 — 단언이 항상 참이 된다(G-9 에서 실측한 가짜 그린과 같은 형태).
    expect(
      screen.queryByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).toBeNull()
  })

  /**
   * T7-T6: 권한 + 편집 모드여야 제목 클릭이 편집으로 들어간다.
   */
  it('T7-T6: legacy 타일 canEdit=true + 편집 모드면 제목 클릭 시 인라인 편집이 활성화된다', async () => {
    const user = userEvent.setup()
    await renderTile({ tile: LEGACY_TILE, canEdit: true, isEditing: true })
    await user.click(screen.getByText('일반 위젯'))
    expect(screen.getByRole('textbox')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 보기/편집 모드 — 권한과 모드는 다른 축이다 (Jira 패리티 JD-1 · 리뷰 D-3)
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardTile — 보기/편집 모드 3분기', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('① 권한 없음 → ⋯ 메뉴가 없다', async () => {
    await renderTile({ tile: GADGET_TILE, canEdit: false, isEditing: true })
    expect(
      screen.queryByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).not.toBeInTheDocument()
  })

  it('② 권한 있음 · 보기 모드 → ⋯ 메뉴가 없다 (기본 상태다)', async () => {
    await renderTile({ tile: GADGET_TILE, canEdit: true, isEditing: false })
    expect(
      screen.queryByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).not.toBeInTheDocument()
  })

  it('③ 권한 있음 · 편집 모드 → ⋯ 메뉴가 있다', async () => {
    await renderTile({ tile: GADGET_TILE, canEdit: true, isEditing: true })
    expect(
      screen.getByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).toBeInTheDocument()
  })

  it('publicMode 는 권한·모드와 무관하게 ①과 같다', async () => {
    await renderTile({
      tile: GADGET_TILE,
      canEdit: true,
      isEditing: true,
      publicMode: true,
    })
    expect(
      screen.queryByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).not.toBeInTheDocument()
  })

  it('★보기 모드에서는 제목 클릭이 편집으로 들어가지 않는다 (리뷰 D-3)', async () => {
    // 드래그 핸들과 ⋯ 만 재고 제목을 빼면 「보기 모드인데 제목이 고쳐진다」로 모드 분리가 뚫린다.
    const user = userEvent.setup()
    await renderTile({ tile: LEGACY_TILE, canEdit: true, isEditing: false })
    await user.click(screen.getByText('일반 위젯'))
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })
})

describe('DashboardTile — ⋯ 메뉴 (Jira 패리티 JD-3)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('메뉴에 복제와 삭제가 있다', async () => {
    const user = userEvent.setup()
    await renderTile({ tile: GADGET_TILE, canEdit: true, isEditing: true })
    await openTileMenu(user)

    expect(screen.getByRole('menuitem', { name: dashboardModeLabels.duplicate })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /삭제/i })).toBeInTheDocument()
  })

  it('복제를 누르면 그 타일 id 로 콜백이 온다', async () => {
    const user = userEvent.setup()
    const onDuplicate = vi.fn()
    await renderTile({ tile: GADGET_TILE, canEdit: true, isEditing: true, onDuplicate })
    await openTileMenu(user)
    await user.click(screen.getByRole('menuitem', { name: dashboardModeLabels.duplicate }))

    expect(onDuplicate).toHaveBeenCalledWith(GADGET_TILE.i)
  })

  it('삭제를 누르면 그 타일 id 로 콜백이 온다', async () => {
    const user = userEvent.setup()
    const onDelete = vi.fn()
    await renderTile({ tile: GADGET_TILE, canEdit: true, isEditing: true, onDelete })
    await openTileMenu(user)
    await user.click(screen.getByRole('menuitem', { name: /삭제/i }))

    expect(onDelete).toHaveBeenCalledWith(GADGET_TILE.i)
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
  it('T7-G4: 가젯 타일 canEdit=true + 편집 모드면 ⋯ 메뉴에 삭제가 있다', async () => {
    const user = userEvent.setup()
    await renderTile({ tile: GADGET_TILE, canEdit: true, isEditing: true })
    await openTileMenu(user)
    expect(screen.getByRole('menuitem', { name: /삭제/i })).toBeInTheDocument()
  })

  /**
   * T7-G5: 가젯 타일에서도 canEdit=false이면 삭제 버튼이 없다.
   */
  it('T7-G5: 가젯 타일 canEdit=false이면 삭제 버튼이 없다', async () => {
    await renderTile({ tile: GADGET_TILE, canEdit: false })
    // ★`button[name=/삭제/]` 로 재지 않는다. ⋯ 트리거의 접근명은 `가젯 메뉴` 이고
    //   「삭제」는 **닫힌 드롭다운 안의 `menuitem`** 이라, 어떤 조합에서도 그 이름의
    //   button 은 없다 — 단언이 항상 참이 된다(G-9 에서 실측한 가짜 그린과 같은 형태).
    expect(
      screen.queryByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).toBeNull()
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

// ─────────────────────────────────────────────────────────────────────────────
// publicMode — 익명 공유 뷰 강제 읽기전용 (FR-DB-03 D6/D7 Task-8)
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardTile — publicMode 익명 읽기전용', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /**
   * P-1: publicMode=true·가젯 타일이면 GadgetRenderer 대신 PublicGadgetRenderer가 렌더된다.
   * RED: DashboardTile이 아직 publicMode를 몰라 GadgetRenderer가 렌더되므로 실패.
   */
  it('P-1: publicMode=true이면 가젯 타일 본문이 PublicGadgetRenderer로 렌더된다', async () => {
    await renderTile({ tile: GADGET_TILE, publicMode: true })
    expect(screen.getByTestId('public-gadget-renderer')).toBeInTheDocument()
    expect(screen.queryByTestId('gadget-renderer')).toBeNull()
  })

  /**
   * P-2: publicMode=true·gadgetType 없는 legacy 타일도 PublicGadgetRenderer로 렌더된다
   * (PublicGadgetRenderer 자체가 fail-closed로 legacy 타일을 로그인 필요 플레이스홀더로 처리 — Task 7).
   * RED: DashboardTile이 gadgetType undefined일 때 기존 placeholder 분기를 타 실패.
   */
  it('P-2: publicMode=true이면 gadgetType 없는 legacy 타일도 PublicGadgetRenderer로 렌더된다', async () => {
    await renderTile({ tile: LEGACY_TILE, publicMode: true })
    expect(screen.getByTestId('public-gadget-renderer')).toBeInTheDocument()
    expect(screen.queryByText('이 자리에 위젯을 추가할 수 있습니다')).toBeNull()
  })

  /**
   * P-3: publicMode=true이면 canEdit=true여도 삭제 버튼이 렌더되지 않는다
   * (canEdit과 무관하게 publicMode면 강제 읽기전용).
   */
  it('P-3: publicMode=true이면 canEdit=true여도 삭제 버튼이 없다', async () => {
    await renderTile({ tile: GADGET_TILE, canEdit: true, publicMode: true })
    // ★`button[name=/삭제/]` 로 재지 않는다. ⋯ 트리거의 접근명은 `가젯 메뉴` 이고
    //   「삭제」는 **닫힌 드롭다운 안의 `menuitem`** 이라, 어떤 조합에서도 그 이름의
    //   button 은 없다 — 단언이 항상 참이 된다(G-9 에서 실측한 가짜 그린과 같은 형태).
    expect(
      screen.queryByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel }),
    ).toBeNull()
  })

  /**
   * P-4: publicMode=true이면 canEdit=true여도 제목 클릭 시 인라인 편집 input이 뜨지 않는다.
   */
  it('P-4: publicMode=true이면 canEdit=true여도 제목 클릭이 편집 input을 열지 않는다', async () => {
    const user = userEvent.setup()
    await renderTile({ tile: LEGACY_TILE, canEdit: true, publicMode: true })
    await user.click(screen.getByText('일반 위젯'))
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  /**
   * P-5: publicMode 미전달(기본 false)이면 gadgetType이 없는 legacy 타일은 기존처럼
   * placeholder를 렌더한다(회귀 방지 — T7-T1과 동일 단언).
   */
  it('P-5: publicMode 미전달이면 legacy 타일이 기존 placeholder를 렌더한다', async () => {
    await renderTile({ tile: LEGACY_TILE })
    expect(screen.getByText('이 자리에 위젯을 추가할 수 있습니다')).toBeInTheDocument()
    expect(screen.queryByTestId('public-gadget-renderer')).toBeNull()
  })
})
