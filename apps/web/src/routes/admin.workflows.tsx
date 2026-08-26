// 워크플로우 관리 목록 라우트 — /admin/workflows (FR-WF-04 D6)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { PlusIcon, CopyIcon, Trash2Icon, PencilIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { useWorkflows } from '@/hooks/use-workflows'
import { useDeleteWorkflow, useDuplicateWorkflow } from '@/hooks/use-workflows-admin'
import type { WorkflowView } from '@/api/workflows'

/**
 * 워크플로우 전체를 표로 보여주고 편집기로 보낸다.
 *
 * 상태·전환 **개수**를 함께 보여준다 — 이름만으로는 어느 것이 실제로 쓰이는 워크플로우인지
 * 구별할 수 없어서 Jira 목록도 같은 정보를 준다.
 */
export function AdminWorkflowsPage(): JSX.Element {
  const navigate = useNavigate()
  const { data: workflows, isPending } = useWorkflows()
  const deleteWorkflow = useDeleteWorkflow()
  const [toDelete, setToDelete] = useState<WorkflowView | null>(null)
  const duplicate = useDuplicateWorkflow()

  if (isPending) {
    return <Skeleton className="h-64 w-full" />
  }

  const list = workflows ?? []

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">{labels.list.heading}</h1>
        <Button onClick={() => void navigate({ to: '/admin/workflows/new' })}>
          <PlusIcon aria-hidden="true" className="size-4" />
          {labels.list.create}
        </Button>
      </div>

      {list.length === 0 ? (
        <EmptyState title={labels.list.emptyTitle} description={labels.list.emptyDescription} />
      ) : (
        <Table aria-label={labels.list.table}>
          <TableHeader>
            <TableRow>
              <TableHead>{labels.list.columnName}</TableHead>
              <TableHead>{labels.list.columnKey}</TableHead>
              <TableHead>{labels.list.columnStatusCount}</TableHead>
              <TableHead>{labels.list.columnTransitionCount}</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {list.map((workflow) => (
              <TableRow key={workflow.key}>
                <TableCell className="font-medium">{workflow.name}</TableCell>
                <TableCell className="text-(--text-subtle)">{workflow.key}</TableCell>
                <TableCell>{workflow.states.length}</TableCell>
                <TableCell>{workflow.transitions.length}</TableCell>
                <TableCell className="flex justify-end gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label={`${labels.list.edit} ${workflow.name}`}
                    onClick={() => void navigate({ to: `/admin/workflows/${workflow.key}` })}
                  >
                    <PencilIcon aria-hidden="true" className="size-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label={`${labels.list.duplicate} ${workflow.name}`}
                    disabled={duplicate.isPending}
                    onClick={() =>
                      duplicate.mutate({
                        sourceKey: workflow.key,
                        key: `${workflow.key}-copy`,
                        name: `${workflow.name} (사본)`,
                      })
                    }
                  >
                    <CopyIcon aria-hidden="true" className="size-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    aria-label={`${labels.list.remove} ${workflow.name}`}
                    onClick={() => setToDelete(workflow)}
                  >
                    <Trash2Icon aria-hidden="true" className="size-4" />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <ConfirmDialog
        open={toDelete !== null}
        onOpenChange={(open) => {
          if (!open) {
            setToDelete(null)
          }
        }}
        title={`${labels.list.remove} ${toDelete?.name ?? ''}`}
        confirmLabel={labels.list.remove}
        cancelLabel={labels.dialog.cancel}
        confirming={deleteWorkflow.isPending}
        destructive
        onConfirm={() => {
          if (toDelete !== null) {
            // ★닫는 책임은 소비자에게 있다(`confirm-dialog.tsx`). 성공했을 때만 닫아야
            //   진행 중에는 `confirming` 이 보이고, 실패하면 확인 맥락이 남는다.
            deleteWorkflow.mutate(toDelete.key, { onSuccess: () => setToDelete(null) })
          }
        }}
      />
    </div>
  )
}

/** TanStack Router code-based 어댑터 */
export function AdminWorkflowsRouteAdapter(): JSX.Element {
  return <AdminWorkflowsPage />
}
