// 편성된 상태 목록 — 드래그로 순서 변경 + 제거
import * as React from 'react'
import { DndContext, closestCenter, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { GripVerticalIcon, Trash2Icon, PlusIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { EmptyState } from '@/components/ui/empty-state'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import type { WorkflowView } from '@/api/workflows'

/** 목록 한 줄이 다루는 상태 — 카탈로그 id 를 붙여 둔다(편성 API 가 id 로 받는다) */
interface PanelStatus {
  id: string
  key: string
  name: string
  category: WorkflowView['states'][number]['category']
}

interface StatusListPanelProps {
  statuses: PanelStatus[]
  onAdd: () => void
  onRemove: (status: PanelStatus) => void
  onReorder: (orderedIds: string[]) => void
  disabled?: boolean
}

/**
 * 한 줄.
 *
 * 제거 버튼과 드래그 핸들의 접근성 이름에 **상태 이름을 붙인다**. 이름이 같으면
 * `getByRole('button', { name })` 이 여러 개를 잡아 strict mode 로 죽는다(§2 즉사 계약).
 */
function SortableStatusRow({
  status,
  onRemove,
  disabled,
}: {
  status: PanelStatus
  onRemove: (status: PanelStatus) => void
  disabled: boolean
}): React.JSX.Element {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: status.id })

  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={`flex items-center gap-3 rounded-md border border-border px-3 py-2 ${isDragging ? 'opacity-60' : ''}`}
    >
      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-label={`${labels.statusPanel.dragHandle} ${status.name}`}
        className="cursor-grab text-(--text-subtle)"
        disabled={disabled}
        {...attributes}
        {...listeners}
      >
        <GripVerticalIcon aria-hidden="true" className="size-4" />
      </Button>
      <span className="flex-1 text-sm">{status.name}</span>
      <Badge variant="secondary">{status.category}</Badge>
      <Button
        variant="ghost"
        size="sm"
        aria-label={`${labels.statusPanel.remove} ${status.name}`}
        disabled={disabled}
        onClick={() => onRemove(status)}
      >
        <Trash2Icon aria-hidden="true" className="size-4" />
      </Button>
    </li>
  )
}

/**
 * 편성된 상태를 순서대로 보여주고, 드래그로 순서를 바꾸고, 뺀다.
 *
 * 순서 변경은 **전체 id 목록**을 올려 보낸다 — 백엔드가 부분 목록을 받으면 빠진 상태의
 * 순서가 어긋나 400 이다.
 */
function StatusListPanel({
  statuses,
  onAdd,
  onRemove,
  onReorder,
  disabled = false,
}: StatusListPanelProps): React.JSX.Element {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const handleDragEnd = (event: DragEndEvent): void => {
    const { active, over } = event
    if (over === null || active.id === over.id) {
      return
    }
    const ids = statuses.map((s) => s.id)
    const from = ids.indexOf(String(active.id))
    const to = ids.indexOf(String(over.id))
    if (from < 0 || to < 0) {
      return
    }
    const next = [...ids]
    next.splice(to, 0, next.splice(from, 1)[0]!)
    onReorder(next)
  }

  return (
    <section className="flex flex-col gap-3">
      <div className="flex justify-end">
        <Button size="sm" onClick={onAdd} disabled={disabled}>
          <PlusIcon aria-hidden="true" className="size-4" />
          {labels.statusPanel.add}
        </Button>
      </div>
      {statuses.length === 0 ? (
        <EmptyState title={labels.statusPanel.empty} />
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <SortableContext items={statuses.map((s) => s.id)} strategy={verticalListSortingStrategy}>
            <ul aria-label={labels.statusPanel.list} className="flex flex-col gap-2">
              {statuses.map((status) => (
                <SortableStatusRow key={status.id} status={status} onRemove={onRemove} disabled={disabled} />
              ))}
            </ul>
          </SortableContext>
        </DndContext>
      )}
    </section>
  )
}

export { StatusListPanel }
export type { StatusListPanelProps, PanelStatus }
