// 워크플로우 목록 모드 편집기 셸 — 이름·설명 폼 + 상태/전환 탭 (FR-WF-04 D6)
import * as React from 'react'
import { toast } from 'sonner'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import {
  useWorkflowDetail,
  useStatusCatalog,
  useUpdateWorkflow,
  useAddWorkflowStatus,
  useRemoveWorkflowStatus,
  useReorderWorkflowStatuses,
  useCreateTransition,
  useUpdateTransition,
  useDeleteTransition,
} from '@/hooks/use-workflows-admin'
import type { WorkflowView } from '@/api/workflows'
import type { TransitionDefinitionInput } from '@/api/workflows-admin'
import { StatusListPanel } from './StatusListPanel'
import type { PanelStatus } from './StatusListPanel'
import { StatusPickerDialog } from './StatusPickerDialog'
import { TransitionListPanel } from './TransitionListPanel'
import { TransitionFormDialog } from './TransitionFormDialog'

type Transition = WorkflowView['transitions'][number]

interface WorkflowEditorPageProps {
  /** 편집 대상 워크플로우 키 */
  workflowKey: string
}

/**
 * 편성된 상태에 **카탈로그 id 를 붙인다**.
 *
 * 조회 응답(`states[]`)에는 `key` 만 있고 편성 API 는 `statusId` 를 받는다. 이 조인을
 * 빠뜨리면 화면에는 상태가 보이는데 빼거나 순서를 바꿀 수 없다 — 읽기와 쓰기가 서로 다른
 * 식별자를 쓰는 자리다. 카탈로그에 없는 상태는 조작 대상에서 제외한다.
 */
function joinStatusIds(
  states: WorkflowView['states'],
  catalog: { id: string; key: string }[],
): { joined: PanelStatus[]; droppedKeys: string[] } {
  const joined: PanelStatus[] = []
  const droppedKeys: string[] = []
  for (const state of [...states].sort((a, b) => a.displayOrder - b.displayOrder)) {
    const entry = catalog.find((c) => c.key === state.key)
    if (entry === undefined) {
      // ★ 조용히 버리면 안 된다. 버린 상태는 화면에서 사라지는데, 순서 변경은 그 워크플로우의
      //   상태 **전부**를 보내야 하므로 부분 목록이 나가 백엔드가 400 을 던진다. 그 400 은
      //   사유 넷이 공유하는 코드라 사용자에게 엉뚱한 안내가 뜬다. 드러내서 막는다.
      droppedKeys.push(state.key)
      continue
    }
    joined.push({ id: entry.id, key: state.key, name: state.name, category: state.category })
  }
  return { joined, droppedKeys }
}

/**
 * 워크플로우 하나를 목록 모드로 편집한다.
 *
 * 다이어그램 모드는 로드맵 PR 9(`@xyflow/react`) 몫이라 여기서는 탭 두 개(상태·전환)만 둔다.
 */
function WorkflowEditorPage({ workflowKey }: WorkflowEditorPageProps): React.JSX.Element {
  const detail = useWorkflowDetail(workflowKey)
  const catalog = useStatusCatalog()

  const updateWorkflow = useUpdateWorkflow(workflowKey)
  const addStatus = useAddWorkflowStatus(workflowKey)
  const removeStatus = useRemoveWorkflowStatus(workflowKey)
  const reorderStatuses = useReorderWorkflowStatuses(workflowKey)
  const createTransition = useCreateTransition(workflowKey)
  const updateTransition = useUpdateTransition(workflowKey)
  const deleteTransition = useDeleteTransition(workflowKey)

  const [name, setName] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [pickerOpen, setPickerOpen] = React.useState(false)
  const [statusToRemove, setStatusToRemove] = React.useState<PanelStatus | null>(null)
  const [transitionFormOpen, setTransitionFormOpen] = React.useState(false)
  const [editingTransition, setEditingTransition] = React.useState<Transition | null>(null)
  const [transitionToRemove, setTransitionToRemove] = React.useState<Transition | null>(null)

  const workflow = detail.data
  const serverKey = workflow?.key
  const serverName = workflow?.name
  const serverDescription = workflow?.description

  // 서버 값이 오거나 바뀌면 폼을 맞춘다.
  //
  // 의존성을 **원시값으로 좁힌다.** `workflow` 객체를 넣으면 refetch 마다 동일성이 바뀌어
  // 사용자가 입력 중이던 값을 매번 덮어쓴다 — 저장 직후 무효화가 도는 이 화면에서는
  // 곧바로 드러나는 자리다.
  React.useEffect(() => {
    if (serverName !== undefined && serverDescription !== undefined) {
      setName(serverName)
      setDescription(serverDescription)
    }
  }, [serverKey, serverName, serverDescription])

  if (detail.isPending || catalog.isPending) {
    return <Skeleton className="h-64 w-full" />
  }
  // ★ 실패와 「없음」을 가른다. 종전에는 둘 다 목록 화면의 빈 상태 문구
  //   (「워크플로우가 없습니다 / 첫 워크플로우를 만들어…」)를 상세 화면에 띄웠다.
  if (detail.isError) {
    return <EmptyState title={labels.editor.loadFailed} description={detail.error.message} />
  }
  if (workflow === undefined) {
    return <EmptyState title={labels.editor.notFound} />
  }

  const catalogEntries = catalog.data ?? []
  const { joined: panelStatuses, droppedKeys } = joinStatusIds(workflow.states, catalogEntries)
  const stateNames = Object.fromEntries(workflow.states.map((s) => [s.key, s.name]))
  // 카탈로그가 실패하면 조인이 전부 비어 「편성된 상태가 없습니다」라는 **거짓말**이 뜬다.
  // 상태가 있는데 못 그리는 것과 상태가 없는 것은 다른 사실이다.
  const statusPanelBlocked = catalog.isError || droppedKeys.length > 0
  const statusPanelNotice = catalog.isError ? labels.editor.catalogFailed : labels.editor.unknownStatuses

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold">{workflow.name}</h1>

        <div className="flex flex-col gap-2">
          <Label htmlFor="workflow-name">{labels.editor.nameField}</Label>
          <Input
            id="workflow-name"
            aria-label={labels.editor.nameField}
            placeholder={labels.namePlaceholder}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div className="flex flex-col gap-2">
          <Label htmlFor="workflow-description">{labels.editor.descriptionField}</Label>
          <Textarea
            id="workflow-description"
            aria-label={labels.editor.descriptionField}
            placeholder={labels.descriptionPlaceholder}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>

        <div className="flex justify-end">
          <Button
            disabled={name.trim().length === 0 || updateWorkflow.isPending}
            onClick={() => {
              updateWorkflow.mutate(
                { name: name.trim(), description: description.trim().length > 0 ? description.trim() : null },
                { onSuccess: () => toast.success(labels.editor.saved) },
              )
            }}
          >
            {labels.editor.save}
          </Button>
        </div>
      </header>

      <Tabs defaultValue="statuses">
        <TabsList aria-label={labels.editor.tabs}>
          <TabsTrigger value="statuses">{labels.editor.statusTab}</TabsTrigger>
          <TabsTrigger value="transitions">{labels.editor.transitionTab}</TabsTrigger>
        </TabsList>

        <TabsContent value="statuses">
          {statusPanelBlocked ? (
            <EmptyState
              title={statusPanelNotice}
              description={droppedKeys.length > 0 ? droppedKeys.join(', ') : undefined}
            />
          ) : null}
          <StatusListPanel
            statuses={panelStatuses}
            onAdd={() => setPickerOpen(true)}
            onRemove={setStatusToRemove}
            onReorder={(orderedIds) => reorderStatuses.mutate(orderedIds)}
            disabled={
              statusPanelBlocked || addStatus.isPending || removeStatus.isPending || reorderStatuses.isPending
            }
          />
        </TabsContent>

        <TabsContent value="transitions">
          <TransitionListPanel
            transitions={workflow.transitions}
            stateNames={stateNames}
            onAdd={() => {
              setEditingTransition(null)
              setTransitionFormOpen(true)
            }}
            onEdit={(transition) => {
              setEditingTransition(transition)
              setTransitionFormOpen(true)
            }}
            onRemove={setTransitionToRemove}
            disabled={createTransition.isPending || updateTransition.isPending || deleteTransition.isPending}
          />
        </TabsContent>
      </Tabs>

      <StatusPickerDialog
        open={pickerOpen}
        onOpenChange={setPickerOpen}
        catalog={catalogEntries}
        usedKeys={workflow.states.map((s) => s.key)}
        adding={addStatus.isPending}
        onAdd={(statusId) => addStatus.mutate({ statusId, displayOrder: workflow.states.length + 1 })}
      />

      <ConfirmDialog
        open={statusToRemove !== null}
        onOpenChange={(open) => {
          if (!open) {
            setStatusToRemove(null)
          }
        }}
        title={`${labels.statusPanel.remove} ${statusToRemove?.name ?? ''}`}
        confirmLabel={labels.statusPanel.remove}
        cancelLabel={labels.dialog.cancel}
        confirming={removeStatus.isPending}
        destructive
        onConfirm={() => {
          if (statusToRemove !== null) {
            removeStatus.mutate(statusToRemove.id)
          }
        }}
      />

      <ConfirmDialog
        open={transitionToRemove !== null}
        onOpenChange={(open) => {
          if (!open) {
            setTransitionToRemove(null)
          }
        }}
        title={`${labels.transitionPanel.remove} ${transitionToRemove?.name ?? ''}`}
        confirmLabel={labels.transitionPanel.remove}
        cancelLabel={labels.dialog.cancel}
        confirming={deleteTransition.isPending}
        destructive
        onConfirm={() => {
          if (transitionToRemove !== null) {
            deleteTransition.mutate(transitionToRemove.id)
          }
        }}
      />

      <TransitionFormDialog
        open={transitionFormOpen}
        onOpenChange={setTransitionFormOpen}
        states={workflow.states}
        editing={editingTransition}
        submitting={createTransition.isPending || updateTransition.isPending}
        onSubmit={(input: TransitionDefinitionInput) => {
          if (editingTransition === null) {
            createTransition.mutate(input)
          } else {
            updateTransition.mutate({ transitionId: editingTransition.id, input })
          }
        }}
      />
    </div>
  )
}

export { WorkflowEditorPage }
export type { WorkflowEditorPageProps }
