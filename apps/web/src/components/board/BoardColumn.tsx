// 칸반 보드 컬럼 컴포넌트 — 드롭 영역 + 카드 목록 + 빈 컬럼 placeholder + WIP 경고 + 스윔레인 그룹 + 셀 단위 SortableContext
import { memo } from 'react'
import { useDroppable } from '@dnd-kit/core'
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { cn } from '@/lib/utils'
import type { BoardColumn as BoardColumnType, SwimlaneField } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { groupCardsBySwimlane } from '@/lib/swimlane-group'
import type { SwimlaneGroup } from '@/lib/swimlane-group'
import { BoardCard } from './BoardCard'
import type { CardAssigneeDisplay } from './BoardCard'
import { WipCountBadge } from './WipCountBadge'

/**
 * swimlaneField=NONE일 때의 셀(컬럼 × 스윔레인 그룹) key.
 * swimlane-group.ts groupByNone·board-drop.ts findGroupKey와 동일 상수 —
 * 이 파일과 BoardCard.tsx의 기본값이 서로 다른 값을 쓰지 않도록 여기서 단일 정의한다.
 */
const NONE_CELL_KEY = 'none'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BoardColumn 컴포넌트 Props */
export interface BoardColumnProps {
  /** 컬럼 데이터 (카드 목록 포함) */
  column: BoardColumnType
  /**
   * 이슈 키 → 담당자 표시 상태 맵.
   * 페이지가 3-상태(unassigned/named/unknown)로 해석해 주입한다.
   * 맵에 없는 키는 unassigned로 fallback한다.
   */
  assigneeNames: Map<string, CardAssigneeDisplay>
  /** 드래그 카드가 이 컬럼 위에 있는지 여부. 하이라이트에 사용 */
  isOver?: boolean
  /**
   * 스윔레인 그룹화 기준.
   * NONE=단일 목록, ASSIGNEE=담당자별 그룹, PRIORITY=우선순위별 그룹.
   */
  swimlaneField: SwimlaneField
  /**
   * 보드 필터 활성 여부 (FR-BD-03 hotfix-p2).
   * true이면 WIP 초과 경고 약화 + "(필터됨)" 표시.
   * WipCountBadge로 전달되며 이동/판정 로직에는 영향 없음.
   */
  isFilterActive?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 — 스윔레인 서브헤더 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** 스윔레인 그룹 한 개(서브헤더 + 카드 목록)를 렌더한다. */
function SwimlaneSection({
  group,
  assigneeNames,
  columnId,
}: {
  group: SwimlaneGroup
  assigneeNames: Map<string, CardAssigneeDisplay>
  columnId: string
}): React.ReactElement {
  const UNASSIGNED: CardAssigneeDisplay = { state: 'unassigned' }
  // 셀(컬럼 × 이 그룹) 안에서의 정렬 순서 — rank 순서(원본 배열 순서) 그대로
  const cellItems = group.cards.map((card) => card.issueKey)

  return (
    <div
      role="group"
      aria-label={group.label}
      className="flex flex-col gap-1"
    >
      {/* 서브헤더 — 컬럼 헤더보다 약한 위계 */}
      <div className="flex items-center gap-1 px-1 pt-1">
        <hr className="flex-1 border-border" aria-hidden="true" />
        <span className="text-xs text-muted-foreground">{group.label}</span>
        <hr className="flex-1 border-border" aria-hidden="true" />
      </div>

      {/* 카드 목록 — 셀(컬럼 × 그룹) 단위 SortableContext */}
      <div className="flex flex-col gap-2">
        <SortableContext items={cellItems} strategy={verticalListSortingStrategy}>
          {group.cards.map((card) => (
            <BoardCard
              key={card.issueKey}
              card={card}
              columnId={columnId}
              assignee={assigneeNames.get(card.issueKey) ?? UNASSIGNED}
              swimlaneGroupKey={group.key}
            />
          ))}
        </SortableContext>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

const UNASSIGNED: CardAssigneeDisplay = { state: 'unassigned' }

function BoardColumnInner({ column, assigneeNames, isOver = false, swimlaneField, isFilterActive = false }: BoardColumnProps) {
  const { setNodeRef } = useDroppable({
    id: column.columnId,
    data: { category: column.category },
  })

  const cardCount = column.cards.length

  // NONE 이외이면 그룹화
  const groups = swimlaneField !== 'NONE'
    ? groupCardsBySwimlane(column.cards, swimlaneField, assigneeNames)
    : null

  return (
    <div
      className="flex min-w-72 w-72 flex-col gap-2"
      role="group"
      aria-label={boardLabels.column.ariaLabel(column.name, cardCount)}
    >
      {/* sticky 헤더 — 이름 + 카테고리 배지 + 카드 수(WIP 포함) */}
      <div className="sticky top-0 z-10 flex items-center gap-2 rounded-t-lg bg-(--bg-neutral-solid) px-3 py-2">
        <span className="flex-1 text-sm font-semibold text-foreground">{column.name}</span>
        <span
          className="rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground"
          aria-label={boardLabels.column.categoryAriaLabel(column.category)}
        >
          {column.category}
        </span>
        <WipCountBadge
          count={cardCount}
          wipLimit={column.wipLimit}
          wipExceeded={column.wipExceeded}
          isFilterActive={isFilterActive}
        />
      </div>

      {/* 드롭 영역 + 카드 목록 — 빈 컬럼에도 드롭 가능하도록 ref 유지 */}
      <div
        ref={setNodeRef}
        data-col-id={column.columnId}
        className={cn(
          'flex flex-1 flex-col gap-2 rounded-b-lg border border-border p-2 transition-colors',
          isOver && 'bg-accent ring-2 ring-primary',
        )}
      >
        {column.cards.length === 0 ? (
          <div
            className="flex flex-1 items-center justify-center rounded-md py-8 text-xs text-muted-foreground"
            aria-label="카드 없음"
          >
            카드 없음
          </div>
        ) : groups !== null ? (
          // 스윔레인 그룹화 렌더
          groups.map((group) => (
            <SwimlaneSection
              key={group.key}
              group={group}
              assigneeNames={assigneeNames}
              columnId={column.columnId}
            />
          ))
        ) : (
          // NONE — 단일 목록. 컬럼 전체가 하나의 셀(SortableContext)
          <SortableContext
            items={column.cards.map((card) => card.issueKey)}
            strategy={verticalListSortingStrategy}
          >
            {column.cards.map((card) => (
              <BoardCard
                key={card.issueKey}
                card={card}
                columnId={column.columnId}
                assignee={assigneeNames.get(card.issueKey) ?? UNASSIGNED}
                swimlaneGroupKey={NONE_CELL_KEY}
              />
            ))}
          </SortableContext>
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑 — Map 참조가 안정적일 때 재렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드의 개별 컬럼.
 *
 * - sticky 헤더에 이름·카테고리 배지·카드 수(WIP 제한 포함)를 표시한다.
 * - wipLimit이 있으면 "{count}/{limit}" 형식으로 표기한다.
 * - wipExceeded=true·isFilterActive=false이면 amber 경고 톤 + aria-label "WIP 초과"를 적용한다.
 * - wipExceeded=true·isFilterActive=true이면 경고를 약화하고 "(필터됨)" 라벨을 표시한다.
 * - `useDroppable`로 드롭 영역을 제공한다. 빈 컬럼에도 드롭 가능.
 * - 카드가 없으면 흐린 "카드 없음" placeholder를 표시한다.
 * - `isOver=true`이면 ring-2 하이라이트를 적용한다.
 * - `swimlaneField`가 NONE이 아니면 그룹화된 서브헤더+카드 목록을 렌더한다.
 *   드롭 영역(data-col-id/useDroppable id)은 항상 columnId로 불변.
 * - 셀(컬럼 × 스윔레인 그룹) 단위로 `SortableContext`를 감싼다 —
 *   NONE이면 컬럼 카드 전체가 하나의 셀, 그 외엔 각 SwimlaneGroup이 하나의 셀.
 *   각 BoardCard에는 그 셀의 key(`'none'` 또는 SwimlaneGroup.key)를 `swimlaneGroupKey`로 전달해
 *   board-drop.ts의 셀 판정과 정합시킨다.
 * - `memo`로 래핑되어 column·assigneeNames·isOver·swimlaneField가 변하지 않으면 재렌더하지 않는다.
 */
export const BoardColumn = memo(BoardColumnInner)
