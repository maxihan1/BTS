// 편집기가 띄우는 다이얼로그 조립 — 셸이 배선만 남게 한다
import * as React from 'react'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { workflowPublishLabels as publishLabels } from '@/i18n/workflow-publish-labels'
import type { StatusCatalogEntry } from '@/api/workflows-admin'
import type { TransitionDefinitionInput } from '@/api/workflows-admin'
import type { WorkflowView } from '@/api/workflows'
import type { PublishPreview } from '@/api/workflows-draft.types'
import type { EditableDraft } from '@/lib/workflow-draft'
import type { PublishFlowDialog, UseMigrationWizardResult } from '@/hooks/use-publish-flow'
import { StatusPickerDialog } from './StatusPickerDialog'
import { TransitionFormDialog } from './TransitionFormDialog'
import { PublishDialog } from './PublishDialog'
import { ResetToDefaultDialog } from './ResetToDefaultDialog'
import type { PanelStatus } from './StatusListPanel'

/** 로컬 id 로 지목된 전환. 목록 패널이 `id` 자리에 로컬 id 를 돌려준다. */
export interface TargetTransition {
  localId: string
  name: string
}

interface WorkflowEditorDialogsProps {
  draft: EditableDraft
  catalog: StatusCatalogEntry[]
  stateNames: Record<string, string>
  /** 로컬에서 아는 발행 차단 사유 */
  blockReason: string | null

  // 상태 편성
  pickerOpen: boolean
  onPickerOpenChange: (open: boolean) => void
  onAddStatus: (statusId: string) => void
  statusToRemove: PanelStatus | null
  onStatusRemoveChange: (status: PanelStatus | null) => void
  onConfirmRemoveStatus: () => void

  // 전환
  transitionFormOpen: boolean
  onTransitionFormOpenChange: (open: boolean) => void
  /** 목록 패널 형태로 옮긴 전환들 — 폼이 수정 대상을 이 목록에서 찾는다 */
  panelTransitions: WorkflowView['transitions']
  editingTransition: TargetTransition | null
  onSubmitTransition: (input: TransitionDefinitionInput) => void
  transitionToRemove: TargetTransition | null
  onTransitionRemoveChange: (target: TargetTransition | null) => void
  onConfirmRemoveTransition: () => void

  // 발행 흐름
  flowDialog: PublishFlowDialog
  onFlowClose: () => void
  preview: PublishPreview | null
  onConfirmPublish: () => void
  onConfirmReset: () => void
  onConfirmDiscard: () => void
  busy: boolean
  /**
   * 이관 마법사의 살아 있는 배선(`useMigrationWizard`).
   *
   * ★ **여기서 부르지 않고 위에서 받는다.** `WorkflowEditorPage` 가 이 훅을 쥐고 있어야
   * `discardDisabled`(G-3)를 `DraftStatusBar` 의 초안 폐기 버튼까지 끌어올릴 수 있다 — 이
   * 컴포넌트는 조립만 한다는 위 주석의 계약을 그대로 따른다(concern 1 정리 겸용).
   */
  migration: UseMigrationWizardResult
}

/**
 * 다이얼로그 여섯을 한 곳에 모은다.
 *
 * 셸에 두면 배선(어떤 상태가 어떤 다이얼로그를 여는가)과 조립(각 다이얼로그의 props)이 한
 * 파일에서 뒤섞여 컴포넌트 200줄 상한을 넘는다. 여기는 조립만 한다 — 상태는 전부 위에서 온다.
 */
function WorkflowEditorDialogs({
  draft,
  catalog,
  stateNames,
  blockReason,
  pickerOpen,
  onPickerOpenChange,
  onAddStatus,
  statusToRemove,
  onStatusRemoveChange,
  onConfirmRemoveStatus,
  transitionFormOpen,
  onTransitionFormOpenChange,
  panelTransitions,
  editingTransition,
  onSubmitTransition,
  transitionToRemove,
  onTransitionRemoveChange,
  onConfirmRemoveTransition,
  flowDialog,
  onFlowClose,
  preview,
  onConfirmPublish,
  onConfirmReset,
  onConfirmDiscard,
  busy,
  migration,
}: WorkflowEditorDialogsProps): React.JSX.Element {
  return (
    <>
      <StatusPickerDialog
        open={pickerOpen}
        onOpenChange={onPickerOpenChange}
        catalog={catalog}
        usedKeys={draft.states.map((s) => s.key)}
        onAdd={onAddStatus}
      />

      <ConfirmDialog
        open={statusToRemove !== null}
        onOpenChange={(open) => {
          if (!open) {
            onStatusRemoveChange(null)
          }
        }}
        title={`${labels.statusPanel.remove} ${statusToRemove?.name ?? ''}`}
        confirmLabel={labels.statusPanel.remove}
        cancelLabel={labels.dialog.cancel}
        destructive
        onConfirm={onConfirmRemoveStatus}
      />

      <ConfirmDialog
        open={transitionToRemove !== null}
        onOpenChange={(open) => {
          if (!open) {
            onTransitionRemoveChange(null)
          }
        }}
        title={`${labels.transitionPanel.remove} ${transitionToRemove?.name ?? ''}`}
        confirmLabel={labels.transitionPanel.remove}
        cancelLabel={labels.dialog.cancel}
        destructive
        onConfirm={onConfirmRemoveTransition}
      />

      <TransitionFormDialog
        open={transitionFormOpen}
        onOpenChange={onTransitionFormOpenChange}
        states={draft.states}
        editing={
          editingTransition === null
            ? null
            : (panelTransitions.find((t) => t.id === editingTransition.localId) ?? null)
        }
        onSubmit={onSubmitTransition}
      />

      {/* 미리보기가 오기 전에는 발행 다이얼로그를 그리지 않는다 — 빈 값으로 그리면
          「사라지는 상태 없음」이 잠깐 보였다가 바뀐다. */}
      {preview !== null ? (
        <PublishDialog
          open={flowDialog === 'publish'}
          onOpenChange={onFlowClose}
          preview={preview}
          stateNames={stateNames}
          blockReason={blockReason}
          onPublish={onConfirmPublish}
          publishing={busy}
          draft={draft}
          migration={migration}
        />
      ) : null}

      <ResetToDefaultDialog
        open={flowDialog === 'reset'}
        onOpenChange={onFlowClose}
        onConfirm={onConfirmReset}
        confirming={busy}
      />

      <ConfirmDialog
        open={flowDialog === 'discard'}
        onOpenChange={onFlowClose}
        title={publishLabels.discard.dialogTitle}
        description={publishLabels.discard.dialogDescription}
        confirmLabel={publishLabels.discard.confirm}
        cancelLabel={publishLabels.common.cancel}
        confirming={busy}
        destructive
        onConfirm={onConfirmDiscard}
      />
    </>
  )
}

export { WorkflowEditorDialogs }
export type { WorkflowEditorDialogsProps }
