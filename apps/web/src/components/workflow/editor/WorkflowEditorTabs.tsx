// 편집기 탭 두 개(상태·전환) — 목록 패널 배치만 한다. 편집 규칙은 리듀서가 갖는다
import * as React from 'react'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { EmptyState } from '@/components/ui/empty-state'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import type { WorkflowView } from '@/api/workflows'
import { StatusListPanel } from './StatusListPanel'
import type { PanelStatus } from './StatusListPanel'
import { TransitionListPanel } from './TransitionListPanel'
import type { TargetTransition } from './WorkflowEditorDialogs'

interface WorkflowEditorTabsProps {
  statuses: PanelStatus[]
  transitions: WorkflowView['transitions']
  stateNames: Record<string, string>
  /** 카탈로그 조회가 실패했는가 — 「상태가 없다」와 구별해 알린다 */
  catalogFailed: boolean
  onAddStatus: () => void
  onRemoveStatus: (status: PanelStatus) => void
  onReorderStates: (orderedKeys: string[]) => void
  onAddTransition: () => void
  onEditTransition: (target: TargetTransition) => void
  onRemoveTransition: (target: TargetTransition) => void
}

/**
 * 상태·전환 탭.
 *
 * 초안 기반이라 **카탈로그 UUID 조인이 없다.** 정규 테이블을 직접 고치던 시절에는 편성 API 가
 * id 를 받아서 조인이 필요했고, 카탈로그에 없는 상태가 섞이면 순서 변경이 통째로 막혔다.
 * 초안은 상태를 키로 다루므로 그 자리가 사라졌다.
 *
 * 다이어그램 모드는 로드맵 PR 9 몫이라 탭은 둘뿐이다.
 */
function WorkflowEditorTabs({
  statuses,
  transitions,
  stateNames,
  catalogFailed,
  onAddStatus,
  onRemoveStatus,
  onReorderStates,
  onAddTransition,
  onEditTransition,
  onRemoveTransition,
}: WorkflowEditorTabsProps): React.JSX.Element {
  return (
    <Tabs defaultValue="statuses">
      <TabsList aria-label={labels.editor.tabs}>
        <TabsTrigger value="statuses">{labels.editor.statusTab}</TabsTrigger>
        <TabsTrigger value="transitions">{labels.editor.transitionTab}</TabsTrigger>
      </TabsList>

      <TabsContent value="statuses">
        {/* 못 그리는 것과 없는 것은 다른 사실이다 — 공지를 먼저 낸다. */}
        {catalogFailed ? <EmptyState title={labels.editor.catalogFailed} /> : null}
        <StatusListPanel
          statuses={statuses}
          onAdd={onAddStatus}
          onRemove={onRemoveStatus}
          onReorder={onReorderStates}
        />
      </TabsContent>

      <TabsContent value="transitions">
        <TransitionListPanel
          transitions={transitions}
          stateNames={stateNames}
          onAdd={onAddTransition}
          // 패널이 `id` 자리에 로컬 id 를 돌려준다 — 그 값이 곧 리듀서가 쓰는 식별자다.
          onEdit={(transition) => {
            onEditTransition({ localId: transition.id, name: transition.name })
          }}
          onRemove={(transition) => {
            onRemoveTransition({ localId: transition.id, name: transition.name })
          }}
        />
      </TabsContent>
    </Tabs>
  )
}

export { WorkflowEditorTabs }
export type { WorkflowEditorTabsProps }
