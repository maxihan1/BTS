// 보드 설정 — 컬럼 한 개의 카드 (이름 · 담은 상태 · WIP · 카드 수) (부채 177 R4)
import type { JSX } from 'react'
import { useState } from 'react'
import { useDroppable, useDraggable } from '@dnd-kit/core'
import { GripVertical, Trash2 } from 'lucide-react'
import type { BoardColumn } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { DraggableState } from './DraggableState'

/** ColumnSettingsCard props */
export interface ColumnSettingsCardProps {
  /** 그릴 컬럼. 보드 상세 응답의 `columns[n]` 을 그대로 받는다. */
  column: BoardColumn
  /**
   * 이 보드의 카드 목록이 조회 상한에 잘렸는지 (`BoardDetail.truncated`).
   *
   * ★잘렸으면 **카드 수를 주장하지 않는다.** `column.cards.length` 는 잘린 목록의 길이라
   * 1000 이 넘는 보드에서 거짓이 된다(eng 리뷰 BLOCKER-1). 서버의 `removedCardCount` 도
   * 같은 잘린 목록에서 세므로 두 곳이 같은 한계를 공유한다.
   */
  truncated: boolean
  /** 상태를 끌 수 있는가 — CREATE 권한. 없으면 읽기만 된다. */
  draggable: boolean
  /**
   * 삭제를 누를 수 있는가.
   *
   * 권한(CREATE)과 **마지막 컬럼 여부**를 부모가 미리 합쳐서 준다. 여기서 다시 판정하면
   * 「몇 개 남았나」를 카드가 알아야 하고, 그것은 카드가 알 일이 아니다.
   */
  canDelete: boolean
  /** 삭제가 잠긴 사유. `canDelete=false` 일 때만 의미가 있다. */
  deleteDisabledReason?: string
  /** 삭제 버튼을 눌렀을 때 — 확인 창은 부모가 연다. */
  onRequestDelete: () => void
  /** 이름을 확정했을 때(Enter 또는 blur). 값이 그대로면 부르지 않는다. */
  onRenameCommit: (name: string) => void
  /**
   * WIP 제한을 확정했을 때.
   *
   * `null` 은 **해제**다(J29 "clear the existing value"). 빈 입력이 그것이다.
   */
  onWipLimitCommit: (wipLimit: number | null) => void
}

/**
 * 컬럼 한 개를 설정 화면에 그린다.
 *
 * 이 PR 의 이 컴포넌트는 **읽기 전용**이다 — 이름 편집 · WIP 편집 · 상태 드래그는 후속 task 가
 * 여기에 붙인다. 읽기가 먼저 서야 조작이 붙을 자리가 생긴다.
 *
 * ### 상태 0개 컬럼을 명시한다
 * 백엔드가 상태 0개 컬럼을 허용한다(#444 E1) — 지라의 「컬럼 먼저, 상태는 드래그로」 흐름이
 * 그것을 요구한다(J23→J27). 그 컬럼은 보드 화면에서 **항상 비어 있는데**, 설정 화면이 그냥
 * 비워 두면 사용자가 미완성임을 모른다. 「상태 없음」을 글자로 적는다.
 */
export function ColumnSettingsCard({
  column,
  truncated,
  draggable,
  canDelete,
  deleteDisabledReason,
  onRequestDelete,
  onRenameCommit,
  onWipLimitCommit,
}: ColumnSettingsCardProps): JSX.Element {
  const cardCountText = truncated
    ? boardLabels.settings.cardCountTruncated
    : boardLabels.settings.cardCount(column.cards.length)
  const { setNodeRef, isOver } = useDroppable({ id: column.columnId })

  // 순서 드래그는 **핸들에만** 건다. 카드 전체를 잡히게 하면 상태 배지 드래그와 겹쳐
  // 「무엇을 끌고 있나」가 모호해진다.
  const reorder = useDraggable({
    id: `column:${column.columnId}`,
    disabled: !draggable,
    data: { kind: 'column', columnId: column.columnId },
  })

  // 입력은 비제어로 두고 확정 시점(Enter · blur)에만 올린다 — 매 키 입력마다 서버를
  // 부르면 이름 한 번 바꾸는 데 요청이 열 번 간다.
  const [nameDraft, setNameDraft] = useState(column.name)
  const [wipDraft, setWipDraft] = useState(column.wipLimit === null ? '' : String(column.wipLimit))

  function commitName(): void {
    const next = nameDraft.trim()
    if (next === '' || next === column.name) {
      setNameDraft(column.name)
      return
    }
    onRenameCommit(next)
  }

  function commitWip(): void {
    const raw = wipDraft.trim()
    // 빈 입력 = 해제다(J29). 0 이하·비숫자는 서버가 400 이므로 여기서 되돌린다.
    if (raw === '') {
      if (column.wipLimit !== null) onWipLimitCommit(null)
      return
    }
    const parsed = Number(raw)
    if (!Number.isInteger(parsed) || parsed < 1) {
      setWipDraft(column.wipLimit === null ? '' : String(column.wipLimit))
      return
    }
    if (parsed !== column.wipLimit) onWipLimitCommit(parsed)
  }

  return (
    <article
      ref={setNodeRef}
      aria-label={column.name}
      className={cn(
        'ring-foreground/10 bg-card w-72 shrink-0 rounded-lg p-4 ring-1',
        isOver && 'ring-primary ring-2',
      )}
    >
      <div className="flex items-start justify-between gap-2">
        {/* 순서 드래그 핸들 — 지라는 "Hover over the top of a column, then drag"(J25) 다. */}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          ref={reorder.setNodeRef}
          disabled={!draggable}
          aria-label={boardLabels.settings.reorderHandleLabel(column.name)}
          className={cn('text-muted-foreground shrink-0', draggable && 'cursor-grab')}
          {...(draggable ? reorder.listeners : {})}
          {...reorder.attributes}
        >
          <GripVertical aria-hidden="true" />
        </Button>

        {/* 지라는 "Select a column's name to edit … then press Enter"(J24) 다 —
            별도 편집 모드 없이 입력 자체가 제목이다. */}
        <Input
          value={nameDraft}
          disabled={!draggable}
          aria-label={boardLabels.settings.renameColumnLabel(column.name)}
          className="h-8 border-transparent bg-transparent text-sm font-semibold shadow-none"
          onChange={(e) => {
            setNameDraft(e.target.value)
          }}
          onBlur={commitName}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              commitName()
            }
            if (e.key === 'Escape') setNameDraft(column.name)
          }}
        />
        {/* 지라는 컬럼 위쪽의 Delete 아이콘이다(J26). 사유는 title 로 붙인다 — 비활성 버튼은
            hover 로만 이유를 알 수 있으면 「왜 안 눌리지」로 끝난다. */}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          disabled={!canDelete}
          title={canDelete ? undefined : deleteDisabledReason}
          aria-label={boardLabels.settings.deleteColumnTitle(column.name)}
          onClick={onRequestDelete}
        >
          <Trash2 aria-hidden="true" />
        </Button>
      </div>

      <p className="text-muted-foreground mt-1 text-xs">{cardCountText}</p>

      {/* ★입력은 **하나뿐**이다 — 최대치. 지라는 minimum 도 받지만(J29) BTS 스키마에 그 칸이
          없다(편차 X2). 접근성 이름에 「최대」를 박아 두 번째 입력의 부재가 누락이 아니라
          결정임을 화면에서도 읽히게 한다. */}
      <div className="mt-2 flex items-center gap-2">
        <Input
          type="number"
          min={1}
          inputMode="numeric"
          value={wipDraft}
          disabled={!draggable}
          placeholder={boardLabels.settings.wipUnlimited}
          aria-label={boardLabels.settings.wipLimitInputLabel(column.name)}
          className="h-8 text-xs"
          onChange={(e) => {
            setWipDraft(e.target.value)
          }}
          onBlur={commitWip}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault()
              commitWip()
            }
          }}
        />
      </div>

      {column.states.length === 0 ? (
        // 미완 표시다 — 다음 행동(상태를 끌어다 놓기)이 남았음을 말한다.
        <p className="text-muted-foreground mt-4 text-sm italic">
          {boardLabels.settings.columnNoStates}
        </p>
      ) : (
        <ul className="mt-4 flex flex-col gap-2">
          {column.states.map((state) => (
            <li key={state.key}>
              <DraggableState
                state={state}
                fromColumnId={column.columnId}
                draggable={draggable}
              />
            </li>
          ))}
        </ul>
      )}
    </article>
  )
}
