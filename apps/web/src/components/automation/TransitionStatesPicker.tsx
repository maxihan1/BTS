// 「상태 전환됨」 트리거의 From/To 상태 필터 — 프로젝트 워크플로 상태를 체크박스로 고른다 (R9 · JAU-5)
import type { JSX } from 'react'
import { useMemo } from 'react'
import { extractStatusOptions, useProjectWorkflows } from '@/hooks/use-workflows'
import type { StatusOption } from '@/hooks/use-workflows'

const labels = {
  description: '고르지 않으면 모든 상태가 해당합니다. 둘 다 비우면 모든 전환에 발화합니다.',
  from: '이전 상태 (From)',
  to: '이후 상태 (To)',
  loading: '상태를 불러오는 중…',
  loadFailed: '워크플로 상태를 불러오지 못했습니다.',
} as const

/** triggerConfig `fromStates`/`toStates`(상태 키 배열) — 비면 「모두」. */
export interface TransitionStates {
  readonly fromStates: string[]
  readonly toStates: string[]
}

interface TransitionStatesPickerProps {
  readonly projectKey: string
  readonly value: TransitionStates
  readonly onChange: (next: TransitionStates) => void
}

export function TransitionStatesPicker({ projectKey, value, onChange }: TransitionStatesPickerProps): JSX.Element {
  const workflowsQuery = useProjectWorkflows(projectKey)
  const options = useMemo(() => extractStatusOptions(workflowsQuery.data ?? []), [workflowsQuery.data])

  if (workflowsQuery.isPending) return <p className="mb-4 text-xs text-muted-foreground">{labels.loading}</p>
  if (workflowsQuery.isError) return <p className="mb-4 text-xs text-destructive">{labels.loadFailed}</p>

  return (
    <div className="mb-4">
      <p className="mb-2 text-xs text-muted-foreground">{labels.description}</p>
      <div className="grid grid-cols-2 gap-4">
        <StateCheckboxGroup
          legend={labels.from}
          options={options}
          selected={value.fromStates}
          onChange={(fromStates) => { onChange({ ...value, fromStates }) }}
        />
        <StateCheckboxGroup
          legend={labels.to}
          options={options}
          selected={value.toStates}
          onChange={(toStates) => { onChange({ ...value, toStates }) }}
        />
      </div>
    </div>
  )
}

interface StateCheckboxGroupProps {
  readonly legend: string
  readonly options: StatusOption[]
  readonly selected: string[]
  readonly onChange: (next: string[]) => void
}

function StateCheckboxGroup({ legend, options, selected, onChange }: StateCheckboxGroupProps): JSX.Element {
  return (
    <fieldset>
      <legend className="mb-1 text-sm font-medium">{legend}</legend>
      <ul className="flex flex-col gap-1">
        {options.map((option) => (
          <li key={option.key}>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={selected.includes(option.key)}
                onChange={(event) => {
                  onChange(
                    event.target.checked
                      ? [...selected, option.key]
                      : selected.filter((key) => key !== option.key),
                  )
                }}
              />
              {option.name}
            </label>
          </li>
        ))}
      </ul>
    </fieldset>
  )
}
