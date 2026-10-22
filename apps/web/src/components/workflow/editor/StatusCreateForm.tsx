// 상태 선택 다이얼로그 안에서 전역 카탈로그에 새 상태를 만드는 폼 (WF-04-3 · 결함 B6)
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { useCreateStatus } from '@/hooks/use-workflows-admin'
import type { StateCategory } from '@/api/workflows-admin'

/** 초안에 편성할 때 필요한 값 — 리듀서 `addState` 의 entry 와 같다 */
export interface PickedStatus {
  key: string
  name: string
  category: StateCategory
}

interface StatusCreateFormProps {
  /** 카탈로그에 만들어진 뒤 부른다. 부른 쪽이 곧바로 초안에 편성한다 */
  onCreated: (status: PickedStatus) => void
  /** 고르기로 돌아간다 */
  onBack: () => void
}

const CATEGORIES: { value: StateCategory; label: string }[] = [
  { value: 'TODO', label: labels.statusCreate.categoryTodo },
  { value: 'IN_PROGRESS', label: labels.statusCreate.categoryInProgress },
  { value: 'DONE', label: labels.statusCreate.categoryDone },
]

/**
 * 키 · 이름 · 범주를 받아 `POST /api/v1/statuses` 로 만든다.
 *
 * ★ **화면이 권한을 판정하지 않는다** (WF-08-1 과 같은 결정). 쓰기는 전역 `CREATE` 라 프로젝트
 * 관리자는 403 을 받고 `notifyWorkflowAdminError` 토스트로 안다. 미리 알 수 있게 안내문이 말한다.
 */
function StatusCreateForm({ onCreated, onBack }: StatusCreateFormProps): React.JSX.Element {
  const createStatus = useCreateStatus()
  const [key, setKey] = React.useState('')
  const [name, setName] = React.useState('')
  const [category, setCategory] = React.useState<StateCategory>('TODO')
  const canSubmit = key.trim().length > 0 && name.trim().length > 0 && !createStatus.isPending

  const submit = (): void => {
    const input = { key: key.trim(), name: name.trim(), category }
    createStatus.mutate(
      { ...input, description: null },
      {
        // 서버가 정한 key 를 쓴다 — 보낸 값과 같아야 하지만 정본은 응답이다.
        onSuccess: (created) => onCreated({ ...input, key: created.key }),
      },
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-(--text-subtle)">{labels.statusCreate.hint}</p>
      <div className="flex flex-col gap-2">
        <Label htmlFor="status-create-key">{labels.statusCreate.key}</Label>
        <Input
          id="status-create-key"
          placeholder={labels.statusCreate.keyPlaceholder}
          maxLength={50}
          value={key}
          onChange={(e) => setKey(e.target.value)}
        />
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor="status-create-name">{labels.statusCreate.name}</Label>
        <Input id="status-create-name" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <fieldset className="flex flex-col gap-2">
        <legend className="text-sm font-medium">{labels.statusCreate.category}</legend>
        <RadioGroup
          value={category}
          onValueChange={(next) => setCategory(next as StateCategory)}
          aria-label={labels.statusCreate.category}
          className="flex gap-4"
        >
          {CATEGORIES.map((option) => {
            const itemId = `status-create-category-${option.value.toLowerCase()}`
            return (
              <div key={option.value} className="flex items-center gap-2">
                {/* 이름을 aria-labelledby 로 건다 — 원만 있는 라디오는 이름이 없다(TransitionFormDialog 와 같다) */}
                <RadioGroupItem id={itemId} value={option.value} aria-labelledby={`${itemId}-label`} />
                <Label id={`${itemId}-label`} htmlFor={itemId}>
                  {option.label}
                </Label>
              </div>
            )
          })}
        </RadioGroup>
      </fieldset>
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={onBack}>
          {labels.statusCreate.back}
        </Button>
        <Button disabled={!canSubmit} onClick={submit}>
          {labels.statusCreate.open}
        </Button>
      </div>
    </div>
  )
}

export { StatusCreateForm }
