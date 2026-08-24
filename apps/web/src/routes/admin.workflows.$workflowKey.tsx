// 워크플로우 편집기 라우트 — /admin/workflows/$workflowKey (FR-WF-04 D6)
import type { JSX } from 'react'
import { useParams, useNavigate } from '@tanstack/react-router'
import { ArrowLeftIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { WorkflowEditorPage } from '@/components/workflow/editor/WorkflowEditorPage'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'

/**
 * 편집기를 감싸고 목록으로 돌아가는 길만 얹는다.
 *
 * 편집 로직은 `WorkflowEditorPage` 가 진다 — 라우트는 파라미터 추출과 배치만 한다.
 */
export function WorkflowEditorRouteAdapter(): JSX.Element {
  const { workflowKey } = useParams({ strict: false }) as { workflowKey: string }
  const navigate = useNavigate()

  return (
    <div className="flex flex-col gap-4">
      <Button
        variant="ghost"
        size="sm"
        className="self-start"
        onClick={() => void navigate({ to: '/admin/workflows' })}
      >
        <ArrowLeftIcon aria-hidden="true" className="size-4" />
        {labels.editor.backToList}
      </Button>
      <WorkflowEditorPage workflowKey={workflowKey} />
    </div>
  )
}
