// 워크플로우 초안 편집기 셸 — 초안을 고치고 발행해야 운영에 반영된다 (FR-WF-07 D6)
import * as React from 'react'
import { ApiError } from '@/api/client'
import { WorkflowAdminApiError } from '@/api/workflows-admin.http'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { useStatusCatalog } from '@/hooks/use-workflows-admin'
import { useWorkflowDraft } from '@/hooks/use-workflow-draft'
import { useWorkflowPublish } from '@/hooks/use-workflow-publish'
import { usePublishFlow } from '@/hooks/use-publish-flow'
import { publishBlockReason } from '@/lib/workflow-draft'
import type { TransitionInput } from '@/lib/workflow-draft'
import type { TransitionDefinitionInput } from '@/api/workflows-admin'
import { WorkflowMetaForm } from './WorkflowMetaForm'
import type { PanelStatus } from './StatusListPanel'
import { DraftStatusBar } from './DraftStatusBar'
import { DraftConflictBanner } from './DraftConflictBanner'
import { WorkflowEditorTabs } from './WorkflowEditorTabs'
import { WorkflowEditorDialogs } from './WorkflowEditorDialogs'
import type { TargetTransition } from './WorkflowEditorDialogs'
import { toPanelStatuses, toPanelTransition, toStateNames } from './draft-adapters'

interface WorkflowEditorPageProps {
  /** 편집 대상 워크플로우 키 */
  workflowKey: string
}

/**
 * 아직 못 그리는 상태를 가른다. 그릴 수 있으면 null.
 *
 * ★ **에러 클래스가 둘이고 상속 관계가 없다.** 초안 조회는 `workflows-draft.ts` 를 지나
 * `WorkflowAdminApiError` 를 던지고, 다른 조회 경로는 `ApiError` 다. 초안 전환으로 조회
 * 경로가 바뀌면서 `ApiError` 만 보던 분기가 404 를 조용히 「불러오지 못했습니다」로
 * 떨어뜨렸다 — 그 자리를 여기서 한 번에 본다.
 *
 * 예외 메시지를 그대로 그리지 않는다 — `ApiError` 는 `API 503`, Zod 실패는 JSON 덤프다.
 */
function renderLoadGate(loading: boolean, error: unknown): React.JSX.Element | null {
  if (loading) {
    return <Skeleton className="h-64 w-full" />
  }
  if (error === null || error === undefined) {
    return null
  }
  const status = error instanceof ApiError || error instanceof WorkflowAdminApiError ? error.status : null
  return <EmptyState title={status === 404 ? labels.editor.notFound : labels.editor.loadFailed} />
}

/**
 * 워크플로우 하나를 초안으로 편집한다.
 *
 * ### ★ 편집은 배포가 아니다
 * 모든 조작이 **로컬 초안**에 쌓이고 자동저장으로 `workflow_drafts` 에 담긴다. 운영에 나가는
 * 것은 발행뿐이라, 편집 중간 상태가 다른 사용자에게 새지 않는다 — FR-WF-07 이 존재하는
 * 이유가 이것이다.
 *
 * 다이어그램 모드는 로드맵 PR 9 몫이라 여기서는 탭 두 개(상태·전환)만 둔다.
 */
function WorkflowEditorPage({ workflowKey }: WorkflowEditorPageProps): React.JSX.Element {
  const draft = useWorkflowDraft(workflowKey)
  const catalog = useStatusCatalog()
  const publish = useWorkflowPublish(workflowKey)
  const flow = usePublishFlow(draft, publish)

  const [pickerOpen, setPickerOpen] = React.useState(false)
  const [statusToRemove, setStatusToRemove] = React.useState<PanelStatus | null>(null)
  const [transitionFormOpen, setTransitionFormOpen] = React.useState(false)
  const [editingTransition, setEditingTransition] = React.useState<TargetTransition | null>(null)
  const [transitionToRemove, setTransitionToRemove] = React.useState<TargetTransition | null>(null)

  const gate = renderLoadGate(draft.isLoading || catalog.isPending, draft.loadError)
  if (gate !== null) {
    return gate
  }

  const { draft: editable, canResetToDefault, exists } = draft.state
  const catalogEntries = catalog.data ?? []
  const panelStatuses = toPanelStatuses(editable)
  const panelTransitions = editable.transitions.map(toPanelTransition)
  const stateNames = toStateNames(editable)
  const blockReason = publishBlockReason(editable)

  /** 폼이 돌려준 값을 리듀서 입력으로 옮긴다. 두 타입의 필드 이름이 다르다. */
  const toTransitionInput = (input: TransitionDefinitionInput): TransitionInput => ({
    from: input.fromStatusKey,
    to: input.toStatusKey,
    name: input.name,
    kind: input.kind,
  })

  /** 고른 상태를 초안에 편성한다. 이름·카테고리는 **카탈로그 값 그대로** — 임의 값은 400 이다. */
  const handleAddStatus = (statusId: string): void => {
    const entry = catalogEntries.find((s) => s.id === statusId)
    if (entry !== undefined) {
      draft.dispatch({
        type: 'addState',
        entry: { key: entry.key, name: entry.name, category: entry.category },
      })
    }
  }

  /** 상태를 뺀다. 그 상태를 가리키는 전환도 리듀서가 함께 뺀다 — 남기면 저장이 400 이다. */
  const handleRemoveStatus = (): void => {
    if (statusToRemove !== null) {
      draft.dispatch({ type: 'removeState', key: statusToRemove.key })
      setStatusToRemove(null)
    }
  }

  const handleRemoveTransition = (): void => {
    if (transitionToRemove !== null) {
      draft.dispatch({ type: 'deleteTransition', localId: transitionToRemove.localId })
      setTransitionToRemove(null)
    }
  }

  const handleSubmitTransition = (input: TransitionDefinitionInput): void => {
    if (editingTransition === null) {
      draft.dispatch({ type: 'createTransition', input: toTransitionInput(input) })
    } else {
      draft.dispatch({
        type: 'updateTransition',
        localId: editingTransition.localId,
        input: toTransitionInput(input),
      })
    }
  }

  return (
    <div className="flex flex-col gap-6">
      {flow.conflicted ? (
        <DraftConflictBanner onDiscard={flow.confirmDiscard} discarding={flow.busy} />
      ) : null}

      <WorkflowMetaForm
        // ★ h1 은 **초안 이름이 아니라** 발행된 이름이어야 한다 — 아직 반영되지 않은 값을
        //   제목으로 쓰면 화면이 「이미 그렇게 됐다」고 말하는 셈이다. 초안 이름은 입력에만 있다.
        heading={workflowKey}
        name={editable.name}
        description={editable.description ?? ''}
        onNameChange={(value) => {
          draft.dispatch({ type: 'setName', value })
        }}
        onDescriptionChange={(value) => {
          draft.dispatch({ type: 'setDescription', value })
        }}
        saving={draft.saveState === 'saving'}
        // 저장은 자동이다. 버튼은 「지금 저장」의 뜻으로만 남긴다.
        onSave={() => {
          void draft.flush()
        }}
      />

      <DraftStatusBar
        saveState={draft.saveState}
        saveError={draft.saveError}
        hasDraft={exists}
        canResetToDefault={canResetToDefault}
        onPublish={flow.startPublish}
        onReset={flow.openReset}
        onDiscard={flow.openDiscard}
        busy={flow.busy}
      />

      {draft.state.lastRejection !== null ? (
        <p role="alert" className="text-destructive text-sm">
          {draft.state.lastRejection}
        </p>
      ) : null}

      <WorkflowEditorTabs
        statuses={panelStatuses}
        transitions={panelTransitions}
        stateNames={stateNames}
        catalogFailed={catalog.isError}
        onAddStatus={() => {
          setPickerOpen(true)
        }}
        onRemoveStatus={setStatusToRemove}
        onReorderStates={(orderedKeys) => {
          draft.dispatch({ type: 'reorderStates', orderedKeys })
        }}
        onAddTransition={() => {
          setEditingTransition(null)
          setTransitionFormOpen(true)
        }}
        onEditTransition={(target) => {
          setEditingTransition(target)
          setTransitionFormOpen(true)
        }}
        onRemoveTransition={setTransitionToRemove}
      />

      <WorkflowEditorDialogs
        draft={editable}
        catalog={catalogEntries}
        stateNames={stateNames}
        blockReason={blockReason}
        pickerOpen={pickerOpen}
        onPickerOpenChange={setPickerOpen}
        onAddStatus={handleAddStatus}
        statusToRemove={statusToRemove}
        onStatusRemoveChange={setStatusToRemove}
        onConfirmRemoveStatus={handleRemoveStatus}
        transitionFormOpen={transitionFormOpen}
        onTransitionFormOpenChange={setTransitionFormOpen}
        panelTransitions={panelTransitions}
        editingTransition={editingTransition}
        onSubmitTransition={handleSubmitTransition}
        transitionToRemove={transitionToRemove}
        onTransitionRemoveChange={setTransitionToRemove}
        onConfirmRemoveTransition={handleRemoveTransition}
        flowDialog={flow.dialog}
        onFlowClose={flow.close}
        preview={flow.preview}
        onConfirmPublish={flow.confirmPublish}
        onConfirmReset={flow.confirmReset}
        onConfirmDiscard={flow.confirmDiscard}
        busy={flow.busy}
      />
    </div>
  )
}

export { WorkflowEditorPage }
export type { WorkflowEditorPageProps }
