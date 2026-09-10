// 프로젝트 워크플로우 편집기 라우트 — 관리 편집기를 그대로 마운트한다 (FR-WF-08 Task 4-2)
import type { JSX } from 'react'
import { useNavigate, useParams } from '@tanstack/react-router'
import { ArrowLeftIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { WorkflowEditorPage } from '@/components/workflow/editor/WorkflowEditorPage'
import { workflowEditorLabels } from '@/i18n/workflow-editor-labels'

/**
 * 편집기를 감싸고 프로젝트 목록으로 돌아가는 길만 얹는다.
 *
 * ★**편집기 안에 프로젝트 분기를 넣지 않는다.** `WorkflowEditorPage` 는 `workflowKey` 하나로
 * 동작하고, 어느 화면에서 열렸는지 몰라야 한다. 알게 하는 순간 관리 화면과 프로젝트 화면의
 * 동작이 갈리기 시작하고, 두 경로가 서로를 검사하지 않는 두 목록이 된다 —
 * `admin.workflows.$workflowKey.tsx` 와 이 파일이 **같은 두께**인 것이 그 계약의 증거다.
 *
 * 편집 권한은 백엔드가 소유로 판정한다(FR-WF-08 PR ③). 전역 워크플로우를 이 경로로 열면
 * 저장이 403 이고, 그래서 목록이 전역 행에 편집 대신 「내 프로젝트로 복제」를 준다.
 */
export function ProjectWorkflowEditorRouteAdapter(): JSX.Element {
  const { projectKey, workflowKey } = useParams({ strict: false }) as {
    projectKey: string
    workflowKey: string
  }
  const navigate = useNavigate()

  return (
    <div className="flex flex-col gap-4 p-8">
      <Button
        variant="ghost"
        size="sm"
        className="self-start"
        onClick={() => void navigate({ to: `/projects/${projectKey}/settings/workflows` })}
      >
        <ArrowLeftIcon aria-hidden="true" className="size-4" />
        {workflowEditorLabels.projectSettings.backToList}
      </Button>
      <WorkflowEditorPage workflowKey={workflowKey} />
    </div>
  )
}
