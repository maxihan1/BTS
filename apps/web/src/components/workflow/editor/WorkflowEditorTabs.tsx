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
import type { CanvasState } from './WorkflowEditorCanvas'
import type { LayoutInputTransition } from '@/lib/workflow-layout'

/**
 * 캔버스는 **지연 로드**한다 (NFR N3).
 *
 * `@xyflow/react` 와 그 스타일시트가 편집기 첫 화면 번들에 실리면, 다이어그램 탭을 한 번도
 * 안 여는 사용자까지 그 비용을 낸다. 기본 탭이 「상태」라 대다수가 그렇다.
 */
const WorkflowEditorCanvas = React.lazy(async () => {
  const mod = await import('./WorkflowEditorCanvas')
  return { default: mod.WorkflowEditorCanvas }
})

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

  // ── 다이어그램 탭 (FR-WF-07 D8) ────────────────────────────────────────────
  /** 초안 상태 — 좌표를 포함한다. 목록 탭의 `statuses` 와 달리 캔버스 계산용이다 */
  canvasStates: readonly CanvasState[]
  /** 초안 전환 — 배열 위치가 곧 identity 다(초안 전환에는 id 가 없다) */
  canvasTransitions: readonly LayoutInputTransition[]
  /** 잠긴 워크플로우면 배치·연결이 막힌다 (엣지 E12) */
  locked: boolean
  onMoveState: (key: string, x: number, y: number) => void
  onCreateTransitionFromCanvas: (from: string, to: string) => void
  onEditTransitionByIndex: (transitionIndex: number) => void
}

/**
 * 상태·전환 탭.
 *
 * 초안 기반이라 **카탈로그 UUID 조인이 없다.** 정규 테이블을 직접 고치던 시절에는 편성 API 가
 * id 를 받아서 조인이 필요했고, 카탈로그에 없는 상태가 섞이면 순서 변경이 통째로 막혔다.
 * 초안은 상태를 키로 다루므로 그 자리가 사라졌다.
 *
 * 다이어그램 탭이 셋째다 (FR-WF-07 D8 · 로드맵 PR 9). 기본 탭은 지금대로 「상태」다 —
 * 바꾸면 `e2e/workflow-editor.spec.ts` 가 진입 직후 상태 목록을 기대하는 단언에서 깨진다.
 *
 * ★ 탭 이름 「다이어그램」은 `'상태'`·`'전환'` 을 substring 으로 품지 않는다.
 * `getByRole('tab', { name })` 이 부분 일치라 품는 순간 기존 E2E 가 두 탭을 잡아 즉사한다.
 * `i18n/__tests__/workflow-editor-labels.test.ts` 가 양방향으로 대조한다.
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
  canvasStates,
  canvasTransitions,
  locked,
  onMoveState,
  onCreateTransitionFromCanvas,
  onEditTransitionByIndex,
}: WorkflowEditorTabsProps): React.JSX.Element {
  return (
    <Tabs defaultValue="statuses">
      <TabsList aria-label={labels.editor.tabs}>
        <TabsTrigger value="statuses">{labels.editor.statusTab}</TabsTrigger>
        <TabsTrigger value="transitions">{labels.editor.transitionTab}</TabsTrigger>
        <TabsTrigger value="diagram">{labels.editor.diagramTab}</TabsTrigger>
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

      <TabsContent value="diagram">
        <React.Suspense fallback={null}>
          <WorkflowEditorCanvas
            states={canvasStates}
            transitions={canvasTransitions}
            locked={locked}
            onMoveState={onMoveState}
            onCreateTransition={onCreateTransitionFromCanvas}
            onEditTransition={onEditTransitionByIndex}
          />
        </React.Suspense>
      </TabsContent>
    </Tabs>
  )
}

export { WorkflowEditorTabs }
export type { WorkflowEditorTabsProps }
