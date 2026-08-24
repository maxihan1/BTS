// 전환 목록 — 이름·경로·종류 표시 + 편집·삭제
import * as React from 'react'
import { PencilIcon, Trash2Icon, PlusIcon, ArrowRightIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { EmptyState } from '@/components/ui/empty-state'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import type { WorkflowView } from '@/api/workflows'

type Transition = WorkflowView['transitions'][number]

interface TransitionListPanelProps {
  transitions: Transition[]
  /** 상태 key → 표시 이름. 키를 그대로 그리면 사용자가 `in_progress` 를 읽는다 */
  stateNames: Record<string, string>
  onAdd: () => void
  onEdit: (transition: Transition) => void
  onRemove: (transition: Transition) => void
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
 * 전환을 이름으로 구분해 보여준다.
 *
 * 같은 상태쌍에 이름이 다른 전환이 여럿 있을 수 있으므로(FR-WF-05 F2) **이름이 1급**이고
 * 경로는 보조다. 편집·삭제 버튼 이름에 전환 이름을 붙여 strict mode 충돌을 피한다.
 */
function TransitionListPanel({
  transitions,
  stateNames,
  onAdd,
  onEdit,
  onRemove,
  disabled = false,
}: TransitionListPanelProps): React.JSX.Element {
  return (
    <section className="flex flex-col gap-3">
      <div className="flex justify-end">
        <Button size="sm" onClick={onAdd} disabled={disabled}>
          <PlusIcon aria-hidden="true" className="size-4" />
          {labels.transitionPanel.add}
        </Button>
      </div>
      {transitions.length === 0 ? (
        <EmptyState title={labels.transitionPanel.empty} />
      ) : (
        <ul aria-label={labels.transitionPanel.list} className="flex flex-col gap-2">
          {transitions.map((transition) => {
            const badge = kindBadge(transition.kind)
            return (
              <li
                key={transition.id}
                className="flex items-center gap-3 rounded-md border border-border px-3 py-2"
              >
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
                {badge !== null ? <Badge variant="secondary">{badge}</Badge> : null}
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
          })}
        </ul>
      )}
    </section>
  )
}

export { TransitionListPanel }
export type { TransitionListPanelProps }
