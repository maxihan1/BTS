// 대시보드 react-grid-layout 12컬럼 그리드 컴포넌트 — WidthProvider + 타일 렌더 (FR-DB-01 Task 8)
import type { JSX } from 'react'
import GridLayout, { WidthProvider } from 'react-grid-layout'
import type { Layout } from 'react-grid-layout'
import 'react-grid-layout/css/styles.css'
import { Plus } from 'lucide-react'
import { COLS, ROW_HEIGHT, MARGIN, CONTAINER_PADDING } from '@/lib/dashboard-layout'
import type { DashboardTile as DashboardTileData } from '@/lib/dashboard-layout'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import { DashboardTile } from './DashboardTile'

// ─────────────────────────────────────────────────────────────────────────────
// WidthProvider 래핑 — 컨테이너 너비 자동 주입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * WidthProvider로 래핑된 GridLayout.
 * 반응형 단일 12컬럼 그리드 — ResponsiveGridLayout 사용 안 함(Maxi 결정).
 * 좁은 화면에서는 overflow-x-auto 가로스크롤.
 */
const WidthAwareGridLayout = WidthProvider(GridLayout)

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼 — Layout↔DashboardTile 변환
// ─────────────────────────────────────────────────────────────────────────────

/**
 * react-grid-layout의 Layout[] 를 DashboardTile[] 로 변환한다.
 * onLayoutChange 콜백으로 받은 위치/크기 정보를 기존 tiles와 머지해
 * title 등 BTS 전용 필드를 보존한다.
 *
 * @param nextLayout - react-grid-layout이 전달한 새 Layout[]
 * @param currentTiles - 현재 tiles (title 보존용)
 * @returns 머지된 DashboardTile[]
 */
function mergeLayoutToTiles(
  nextLayout: Layout[],
  currentTiles: DashboardTileData[],
): DashboardTileData[] {
  const tileMap = new Map<string, DashboardTileData>()
  for (const tile of currentTiles) {
    tileMap.set(tile.i, tile)
  }
  return nextLayout.map((item) => {
    const existing = tileMap.get(item.i)
    return {
      i: item.i,
      x: item.x,
      y: item.y,
      w: item.w,
      h: item.h,
      title: existing?.title ?? '새 위젯',
      // 가젯 필드 보존 — drag/resize 후에도 gadgetType/config 유실 방지 (FR-DB-02 Task 7)
      ...(existing?.gadgetType !== undefined && { gadgetType: existing.gadgetType }),
      ...(existing?.config !== undefined && { config: existing.config }),
    }
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** DashboardGrid Props */
export interface DashboardGridProps {
  /** 현재 타일 목록 */
  tiles: DashboardTileData[]
  /** 편집 권한 — true면 드래그/리사이즈·편집 UI 활성 */
  canEdit: boolean
  /** 레이아웃 변경 콜백 (드래그/리사이즈 완료 시) */
  onLayoutChange: (tiles: DashboardTileData[]) => void
  /** 타일 삭제 요청 콜백 */
  onDeleteTile: (id: string) => void
  /** 타일 제목 인라인 편집 완료 콜백 */
  onEditTitle: (id: string, title: string) => void
  /**
   * 위젯 추가 요청 콜백 — 빈 그리드 상태에서 1차 버튼 클릭 시 호출.
   * 미전달 시 버튼이 렌더되지 않는다(안전한 선택적 prop).
   */
  onAddTile?: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// DashboardGrid 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 react-grid-layout 그리드.
 *
 * - 단일 12컬럼, ROW_HEIGHT=60px (dashboard-layout.ts 상수 재사용).
 * - canEdit=true: isDraggable/isResizable=true, 타일 cursor-grab.
 * - canEdit=false: isDraggable/isResizable=false, 핸들/편집버튼 숨김.
 * - 빈 그리드: 점선 테두리 영역 + "위젯 추가" 1차 버튼(canEdit=true 일 때만).
 * - 좁은 화면: overflow-x-auto 가로스크롤.
 *
 * ★ jsdom mock 주의: react-grid-layout WidthProvider는 테스트에서 stub되어야 한다.
 *   컨테이너 width=0인 jsdom 환경에서 실 WidthProvider를 사용하면 가짜그린 발생.
 */
export function DashboardGrid({
  tiles,
  canEdit,
  onLayoutChange,
  onDeleteTile,
  onEditTitle,
  onAddTile,
}: DashboardGridProps): JSX.Element {
  /** react-grid-layout Layout[] 형태로 변환 */
  const layout: Layout[] = tiles.map(({ i, x, y, w, h }) => ({ i, x, y, w, h }))

  /** onLayoutChange → DashboardTile[] 변환 후 상위 콜백 호출 */
  function handleLayoutChange(nextLayout: Layout[]): void {
    onLayoutChange(mergeLayoutToTiles(nextLayout, tiles))
  }

  // 빈 그리드 상태
  if (tiles.length === 0) {
    return (
      <div className="overflow-x-auto">
        <div className="min-h-[240px] flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-muted-foreground/30 p-8 text-center">
          <p className="text-sm text-muted-foreground mb-4">
            {dashboardLabels.detail.emptyGrid}
          </p>
          {canEdit && onAddTile !== undefined && (
            <button
              type="button"
              className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors min-h-[44px]"
              aria-label={dashboardLabels.detail.addWidget}
              onClick={onAddTile}
            >
              <Plus className="h-4 w-4" aria-hidden="true" />
              {dashboardLabels.detail.addWidget}
            </button>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="overflow-x-auto">
      <WidthAwareGridLayout
        layout={layout}
        cols={COLS}
        rowHeight={ROW_HEIGHT}
        margin={MARGIN}
        containerPadding={CONTAINER_PADDING}
        isDraggable={canEdit}
        isResizable={canEdit}
        onLayoutChange={handleLayoutChange}
        draggableHandle=".drag-handle"
        draggableCancel="button,input"
      >
        {tiles.map((tile) => (
          <div key={tile.i} className="drag-handle">
            <DashboardTile
              tile={tile}
              canEdit={canEdit}
              onDelete={onDeleteTile}
              onEditTitle={onEditTitle}
            />
          </div>
        ))}
      </WidthAwareGridLayout>
    </div>
  )
}
