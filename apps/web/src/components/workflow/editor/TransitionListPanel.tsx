// 전환 목록 — 이름·경로·종류 표시 + 드래그로 순서 변경 + 편집·삭제
import * as React from 'react'
import { DndContext, closestCenter, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { GripVerticalIcon, PencilIcon, Trash2Icon, PlusIcon, ArrowRightIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { EmptyState } from '@/components/ui/empty-state'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { reorderIds } from '@/lib/status-order'
import type { WorkflowView } from '@/api/workflows'

type Transition = WorkflowView['transitions'][number]

interface TransitionListPanelProps {
  transitions: Transition[]
  /** 상태 key → 표시 이름. 키를 그대로 그리면 사용자가 `in_progress` 를 읽는다 */
  stateNames: Record<string, string>
  onAdd: () => void
  onEdit: (transition: Transition) => void
  onRemove: (transition: Transition) => void
  /**
   * 끌어서 순서를 바꿨을 때 — **전체** id 목록을 새 순서로 넘긴다 (FR-WF-01 D9).
   *
   * 이 순서가 발행 때 `display_order` 가 되고 이슈 화면 상태 메뉴가 그대로 따른다.
   */
  onReorder: (orderedIds: string[]) => void
  disabled?: boolean
}

/** 종류 배지 문구. NORMAL 은 배지가 없다 — 출발 상태가 그 자체로 설명이다. */
function kindBadge(kind: Transition['kind']): string | null {
  if (kind === 'GLOBAL') {
    return labels.transitionPanel.kindGlobal
  }
  if (kind === 'INITIAL') {
    return labels.transitionPanel.kindInitial
  }
  return null
}

/**
 * 한 줄.
 *
 * 버튼 접근성 이름에 전환 이름을 붙여 strict mode 충돌을 피한다(§2 즉사 계약).
 */
function SortableTransitionRow({
  transition,
  stateNames,
  onEdit,
  onRemove,
  disabled,
}: {
  transition: Transition
  stateNames: Record<string, string>
  onEdit: (transition: Transition) => void
  onRemove: (transition: Transition) => void
  disabled: boolean
}): React.JSX.Element {
  const { attributes, listeners, setNodeRef, transform, transition: dndTransition, isDragging } = useSortable({
    id: transition.id,
  })
  const badge = kindBadge(transition.kind)

  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition: dndTransition }}
      className={`flex items-center gap-3 rounded-md border border-border px-3 py-2 ${isDragging ? 'opacity-60' : ''}`}
    >
      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-label={`${labels.transitionPanel.dragHandle} ${transition.name}`}
        className="cursor-grab text-(--text-subtle)"
        disabled={disabled}
        {...attributes}
        {...listeners}
      >
        <GripVerticalIcon aria-hidden="true" className="size-4" />
      </Button>
      <span className="flex-1 text-sm font-medium">{transition.name}</span>
      <span className="flex items-center gap-1 text-xs text-(--text-subtle)">
        {transition.fromStateKey !== null ? (
          <>
            {stateNames[transition.fromStateKey] ?? transition.fromStateKey}
            <ArrowRightIcon aria-hidden="true" className="size-3" />
          </>
        ) : null}
        {stateNames[transition.toStateKey] ?? transition.toStateKey}
      </span>
      {badge !== null ? <Badge variant="neutral">{badge}</Badge> : null}
      <Button
        variant="ghost"
        size="sm"
        aria-label={`${labels.transitionPanel.edit} ${transition.name}`}
        disabled={disabled}
        onClick={() => onEdit(transition)}
      >
        <PencilIcon aria-hidden="true" className="size-4" />
      </Button>
      <Button
        variant="ghost"
        size="sm"
        aria-label={`${labels.transitionPanel.remove} ${transition.name}`}
        disabled={disabled || transition.kind === 'INITIAL'}
        onClick={() => onRemove(transition)}
      >
        <Trash2Icon aria-hidden="true" className="size-4" />
      </Button>
    </li>
  )
}

/**
 * 전환을 이름으로 구분해 순서대로 보여준다.
 *
 * 같은 상태쌍에 이름이 다른 전환이 여럿 있을 수 있으므로(FR-WF-05 F2) **이름이 1급**이고
 * 경로는 보조다. 목록 순서가 이슈 화면 상태 메뉴의 순서다 — Jira `opsbar-sequence` 대응.
 */
function TransitionListPanel({
  transitions,
  stateNames,
  onAdd,
  onEdit,
  onRemove,
  onReorder,
  disabled = false,
}: TransitionListPanelProps): React.JSX.Element {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const handleDragEnd = (event: DragEndEvent): void => {
    const { active, over } = event
    if (over === null || active.id === over.id) {
      return
    }
    const ids = transitions.map((t) => t.id)
    const next = reorderIds(ids, String(active.id), String(over.id))
    if (next === ids) {
      return
    }
    onReorder(next)
  }

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <p className="text-xs text-(--text-subtle)">{labels.transitionPanel.orderHint}</p>
        <Button size="sm" onClick={onAdd} disabled={disabled}>
          <PlusIcon aria-hidden="true" className="size-4" />
          {labels.transitionPanel.add}
        </Button>
      </div>
      {transitions.length === 0 ? (
        <EmptyState title={labels.transitionPanel.empty} />
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={transitions.map((t) => t.id)} strategy={verticalListSortingStrategy}>
            <ul aria-label={labels.transitionPanel.list} className="flex flex-col gap-2">
              {transitions.map((transition) => (
                <SortableTransitionRow
                  key={transition.id}
                  transition={transition}
                  stateNames={stateNames}
                  onEdit={onEdit}
                  onRemove={onRemove}
                  disabled={disabled}
                />
              ))}
            </ul>
          </SortableContext>
        </DndContext>
      )}
    </section>
  )
}

export { TransitionListPanel }
export type { TransitionListPanelProps }
