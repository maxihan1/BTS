// 워크플로우 목록 모드 편집기 셸 — 이름·설명 폼 + 상태/전환 탭 (FR-WF-04 D6)
import * as React from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
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
import { WorkflowMetaForm } from './WorkflowMetaForm'
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
    // ★ `detail.error.message` 를 그대로 그리면 안 된다. `ApiError` 의 message 는
    //   `API ${status}` 라 화면에 「API 503」이 뜨고, Zod 실패면 이슈 JSON 덤프가 통째로
    //   렌더된다 — 내부 예외 문구를 사용자 표면으로 흘리지 않는다(FR-PM-04 교훈).
    // ★★ 404 를 여기서 가른다. 종전에는 `notFound` 라벨을 만들어 두고 그 아래 분기에
    //   뒀는데, TanStack v5 는 실패를 `isError` 로 보내므로 그 분기가 **도달 불가**였다.
    // ★★★ `ApiError` 로 좁히는 것이 맞는 이유. 이 BC 에는 에러 클래스가 **둘이고 상속
    //   관계가 없다** — 조회(`api/workflows.ts`)는 `ApiError`, 쓰기(`api/workflows-admin.ts`)는
    //   `WorkflowAdminApiError` 다. 이 분기가 보는 것은 `useWorkflowDetail` 의 조회 실패뿐이라
    //   `ApiError` 하나면 충분하다. 조회 경로가 admin 클라이언트로 바뀌면 여기도 함께 바꿔야
    //   한다 — 안 그러면 404 가 조용히 「불러오지 못했습니다」로 떨어진다.
    const notFound = detail.error instanceof ApiError && detail.error.status === 404
    return <EmptyState title={notFound ? labels.editor.notFound : labels.editor.loadFailed} />
  }
  if (workflow === undefined) {
    return <EmptyState title={labels.editor.loadFailed} />
  }

  const catalogEntries = catalog.data ?? []
  const { joined: panelStatuses, droppedKeys } = joinStatusIds(workflow.states, catalogEntries)
  const stateNames = Object.fromEntries(workflow.states.map((s) => [s.key, s.name]))
  // ★ 게이트를 **근거만큼만** 좁힌다.
  //
  // 없애려던 거짓말(「편성된 상태가 없습니다」)은 조인 결과가 **통째로 비었을 때만** 뜬다.
  // 종전에는 `droppedKeys` 가 하나만 있어도 패널을 안 그려, 카탈로그가 멀쩡한데도 나머지
  // 상태가 화면에서 사라지고 추가·제거 버튼까지 없어졌다 — 수정이 근거보다 넓었다.
  const hasUnknownStatuses = droppedKeys.length > 0
  const cannotRenderPanel = catalog.isError || (panelStatuses.length === 0 && workflow.states.length > 0)
  const statusPanelNotice = catalog.isError ? labels.editor.catalogFailed : labels.editor.unknownStatuses

  return (
    <div className="flex flex-col gap-6">
      <WorkflowMetaForm
        heading={workflow.name}
        name={name}
        description={description}
        onNameChange={setName}
        onDescriptionChange={setDescription}
        saving={updateWorkflow.isPending}
        onSave={() => {
          updateWorkflow.mutate(
            { name: name.trim(), description: description.trim().length > 0 ? description.trim() : null },
            { onSuccess: () => toast.success(labels.editor.saved) },
          )
        }}
      />

      <Tabs defaultValue="statuses">
        <TabsList aria-label={labels.editor.tabs}>
          <TabsTrigger value="statuses">{labels.editor.statusTab}</TabsTrigger>
          <TabsTrigger value="transitions">{labels.editor.transitionTab}</TabsTrigger>
        </TabsList>

        <TabsContent value="statuses">
          {catalog.isError || hasUnknownStatuses ? (
            // 못 그리는 것과 없는 것은 다른 사실이다 — 공지를 먼저 낸다.
            <EmptyState
              title={statusPanelNotice}
              description={hasUnknownStatuses ? droppedKeys.join(', ') : undefined}
            />
          ) : null}
          {cannotRenderPanel ? null : (
            <StatusListPanel
              statuses={panelStatuses}
              onAdd={() => setPickerOpen(true)}
              onRemove={setStatusToRemove}
              onReorder={(orderedIds) => reorderStatuses.mutate(orderedIds)}
              disabled={addStatus.isPending || removeStatus.isPending || reorderStatuses.isPending}
              reorderDisabled={hasUnknownStatuses}
            />
          )}
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
            // ★닫는 책임은 소비자에게 있다 — 근거는 `confirm-dialog.tsx` 의 `onConfirm` KDoc.
            removeStatus.mutate(statusToRemove.id, { onSuccess: () => setStatusToRemove(null) })
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
            deleteTransition.mutate(transitionToRemove.id, {
              onSuccess: () => setTransitionToRemove(null),
            })
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
