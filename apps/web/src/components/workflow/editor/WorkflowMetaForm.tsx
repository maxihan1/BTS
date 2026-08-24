// 워크플로우 이름·설명 편집 폼 — 편집기 상단 (FR-WF-04 D6)
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'

interface WorkflowMetaFormProps {
  /** 화면 제목 — 서버가 준 현재 이름이다(입력 중인 값이 아니다) */
  heading: string
  name: string
  description: string
  onNameChange: (value: string) => void
  onDescriptionChange: (value: string) => void
  onSave: () => void
  saving?: boolean
}

/**
 * 이름·설명을 고치고 저장한다.
 *
 * `heading` 을 따로 받는 이유. `<h1>` 은 **서버가 준 이름**이어야 한다 — 입력값을 그대로
 * 쓰면 타이핑하는 동안 제목이 따라 움직여, 저장이 이미 된 것처럼 보인다.
 */
function WorkflowMetaForm({
  heading,
  name,
  description,
  onNameChange,
  onDescriptionChange,
  onSave,
  saving = false,
}: WorkflowMetaFormProps): React.JSX.Element {
  return (
    <header className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">{heading}</h1>

      <div className="flex flex-col gap-2">
        <Label htmlFor="workflow-name">{labels.editor.nameField}</Label>
        <Input
          id="workflow-name"
          aria-label={labels.editor.nameField}
          placeholder={labels.namePlaceholder}
          value={name}
          onChange={(e) => onNameChange(e.target.value)}
        />
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor="workflow-description">{labels.editor.descriptionField}</Label>
        <Textarea
          id="workflow-description"
          aria-label={labels.editor.descriptionField}
          placeholder={labels.descriptionPlaceholder}
          value={description}
          onChange={(e) => onDescriptionChange(e.target.value)}
        />
      </div>

      <div className="flex justify-end">
        <Button disabled={name.trim().length === 0 || saving} onClick={onSave}>
          {labels.editor.save}
        </Button>
      </div>
    </header>
  )
}

export { WorkflowMetaForm }
export type { WorkflowMetaFormProps }
