// 워크플로우 생성 라우트 — /admin/workflows/new (FR-WF-04 D6)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Combobox } from '@/components/ui/combobox'
import { Skeleton } from '@/components/ui/skeleton'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { useCreateWorkflow, useStatusCatalog } from '@/hooks/use-workflows-admin'

/**
 * 새 워크플로우를 만든다.
 *
 * ★ **최초 상태를 반드시 하나 고르게 한다.** 백엔드 `Workflow.of()` invariant 가 「상태 하나
 * 이상」을 요구해 빈 목록으로 만들면 400 이고, 통과시켜도 만들자마자 조회가 죽는다. 나머지
 * 상태는 편집기에서 붙인다.
 */
export function WorkflowNewPage(): JSX.Element {
  const navigate = useNavigate()
  const catalog = useStatusCatalog()
  const createWorkflow = useCreateWorkflow()

  const [key, setKey] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [initialStatusKey, setInitialStatusKey] = useState<string | null>(null)

  if (catalog.isPending) {
    return <Skeleton className="h-64 w-full" />
  }

  const entries = catalog.data ?? []
  const initial = entries.find((s) => s.key === initialStatusKey)
  const canSubmit =
    key.trim().length > 0 && name.trim().length > 0 && initial !== undefined && !createWorkflow.isPending

  return (
    <div className="flex max-w-xl flex-col gap-6">
      <h1 className="text-xl font-semibold">{labels.list.create}</h1>

      <div className="flex flex-col gap-2">
        <Label htmlFor="workflow-key">{labels.list.columnKey}</Label>
        <Input
          id="workflow-key"
          aria-label={labels.list.columnKey}
          placeholder={labels.keyPlaceholder}
          value={key}
          onChange={(e) => setKey(e.target.value)}
        />
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor="workflow-new-name">{labels.editor.nameField}</Label>
        <Input
          id="workflow-new-name"
          aria-label={labels.editor.nameField}
          placeholder={labels.namePlaceholder}
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor="workflow-new-description">{labels.editor.descriptionField}</Label>
        <Textarea
          id="workflow-new-description"
          aria-label={labels.editor.descriptionField}
          placeholder={labels.descriptionPlaceholder}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor="workflow-initial-status">{labels.editor.initialStatusField}</Label>
        <Combobox
          id="workflow-initial-status"
          options={entries.map((s) => ({ value: s.key, label: s.name }))}
          value={initialStatusKey}
          onChange={setInitialStatusKey}
          ariaLabel={labels.editor.initialStatusField}
          placeholder={labels.statusSearchPlaceholder}
          emptyText={labels.statusPanel.empty}
          triggerPlaceholder={labels.statusSearchPlaceholder}
        />
      </div>

      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={() => void navigate({ to: '/admin/workflows' })}>
          {labels.dialog.cancel}
        </Button>
        <Button
          disabled={!canSubmit}
          onClick={() => {
            if (initial === undefined) {
              return
            }
            createWorkflow.mutate(
              {
                key: key.trim(),
                name: name.trim(),
                description: description.trim().length > 0 ? description.trim() : null,
                statuses: [
                  { key: initial.key, name: initial.name, category: initial.category, displayOrder: 1 },
                ],
              },
              {
                onSuccess: (created) => {
                  toast.success(labels.editor.saved)
                  void navigate({ to: `/admin/workflows/${created.key}` })
                },
              },
            )
          }}
        >
          {labels.list.create}
        </Button>
      </div>
    </div>
  )
}

/** TanStack Router code-based 어댑터 */
export function WorkflowNewRouteAdapter(): JSX.Element {
  return <WorkflowNewPage />
}
