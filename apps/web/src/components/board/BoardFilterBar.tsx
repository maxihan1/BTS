// 보드 카드 필터 바 — 담당자/라벨/컴포넌트 다중 선택, 활성 필터 칩, 초기화 (FR-BD-02 Task-5)
import type { JSX } from 'react'
import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { LabelAutocompleteInput } from '@/components/labels/LabelAutocompleteInput'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { useUsers, useUsersByIds } from '@/hooks/use-users'
import { useComponents } from '@/hooks/use-components'
import { useDebounce } from '@/hooks/use-debounce'
import type { BoardCardFilterParams } from '@/api/boards'
import type { UserSummary } from '@/api/users'
import { boardFilterLabels } from '@/i18n/board-filter-labels'

/** BoardFilterBar props */
export interface BoardFilterBarProps {
  /** 프로젝트 키 — useComponents 쿼리에 사용 */
  projectKey: string
  /** 현재 필터 파라미터 — 제어형, 상태는 부모 소유 */
  value: BoardCardFilterParams
  /** 필터 변경 콜백 — 새 필터 전체를 전달 */
  onChange: (next: BoardCardFilterParams) => void
}

/** 보드 카드 필터 바 — 제어형. 담당자 typeahead / 라벨 자동완성 / 컴포넌트 체크박스 / 칩 / 초기화. */
export function BoardFilterBar({ projectKey, value, onChange }: BoardFilterBarProps): JSX.Element {
  const [assigneeQuery, setAssigneeQuery] = useState('')
  const [labelInput, setLabelInput] = useState('')

  const { data: users = [], isLoading: usersLoading } = useUsers(useDebounce(assigneeQuery, 250))
  const { data: components = [] } = useComponents(projectKey)

  // 선택된 담당자 이름을 안정적으로 표시하기 위해 id 다건 조회 — 검색어가 비워져도 이름 유지
  const { data: selectedUsers = [] } = useUsersByIds(value.assigneeIds)
  const assigneeNameMap = useMemo<Map<string, string>>(() => {
    const map = new Map<string, string>()
    for (const u of selectedUsers) {
      map.set(u.id, u.displayName ?? u.username)
    }
    return map
  }, [selectedUsers])

  const activeCount =
    value.assigneeIds.length + value.labels.length + value.componentIds.length +
    (value.includeUnassigned ? 1 : 0)

  function handleAssigneeSelect(user: UserSummary) {
    if (value.assigneeIds.includes(user.id)) return
    onChange({ ...value, assigneeIds: [...value.assigneeIds, user.id] })
    setAssigneeQuery('')
  }

  function handleLabelCommit(label: string) {
    if (value.labels.includes(label)) return
    onChange({ ...value, labels: [...value.labels, label] })
    setLabelInput('')
  }

  function handleReset() {
    onChange({ assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] })
    setAssigneeQuery('')
    setLabelInput('')
  }

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-muted/30 p-4">
      {activeCount > 0 && (
        <span className="w-full text-xs text-muted-foreground">
          {boardFilterLabels.count.applied(activeCount)}
        </span>
      )}
      <AssigneeSection
        query={assigneeQuery}
        onQueryChange={setAssigneeQuery}
        users={users}
        isLoading={usersLoading}
        selectedIds={value.assigneeIds}
        includeUnassigned={value.includeUnassigned}
        onSelect={handleAssigneeSelect}
        onUnassignedToggle={(e) => onChange({ ...value, includeUnassigned: e.target.checked })}
      />
      <div className="flex min-w-[180px] flex-col gap-1">
        <label className="text-xs font-medium text-muted-foreground" htmlFor="board-filter-label-input">
          {boardFilterLabels.filter.labelLabel}
        </label>
        <LabelAutocompleteInput
          value={labelInput}
          onChange={setLabelInput}
          onCommit={handleLabelCommit}
          existingLabels={value.labels}
          placeholder={boardFilterLabels.filter.labelPlaceholder}
        />
      </div>
      <div className="flex min-w-[180px] flex-col gap-1">
        <span className="text-xs font-medium text-muted-foreground">
          {boardFilterLabels.filter.componentLabel}
        </span>
        <ComponentMultiSelect
          value={value.componentIds}
          options={components}
          onChange={(ids) => onChange({ ...value, componentIds: ids })}
        />
      </div>
      <div className="flex w-full flex-wrap items-center gap-2">
        <ActiveFilterChips
          assigneeIds={value.assigneeIds}
          assigneeNameMap={assigneeNameMap}
          labels={value.labels}
          onAssigneeRemove={(id) =>
            onChange({ ...value, assigneeIds: value.assigneeIds.filter((a) => a !== id) })
          }
          onLabelRemove={(label) =>
            onChange({ ...value, labels: value.labels.filter((l) => l !== label) })
          }
        />
        <Button type="button" variant="outline" size="sm" onClick={handleReset}>
          {boardFilterLabels.filter.reset}
        </Button>
      </div>
    </div>
  )
}

// ─── AssigneeSection ──────────────────────────────────────────────────────────

interface AssigneeSectionProps {
  readonly query: string
  readonly onQueryChange: (q: string) => void
  readonly users: UserSummary[]
  readonly isLoading: boolean
  readonly selectedIds: string[]
  readonly includeUnassigned: boolean
  readonly onSelect: (user: UserSummary) => void
  readonly onUnassignedToggle: (e: React.ChangeEvent<HTMLInputElement>) => void
}

/** 담당자 typeahead + 미배정 체크박스 서브컴포넌트. */
function AssigneeSection({
  query, onQueryChange, users, isLoading, selectedIds, includeUnassigned, onSelect, onUnassignedToggle,
}: AssigneeSectionProps): JSX.Element {
  const show = query.length >= 1
  return (
    <div className="relative flex min-w-[200px] flex-col gap-1">
      <label htmlFor="board-filter-assignee-input" className="text-xs font-medium text-muted-foreground">
        {boardFilterLabels.filter.assigneeLabel}
      </label>
      <input
        id="board-filter-assignee-input"
        type="text"
        aria-label={boardFilterLabels.filter.assigneeLabel}
        className="min-h-[44px] rounded-md border bg-background px-3 py-2 text-sm"
        placeholder={boardFilterLabels.filter.assigneePlaceholder}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
      />
      {show && (
        <ul className="absolute top-full z-50 mt-1 w-full max-h-40 overflow-y-auto rounded-md border bg-popover shadow-md">
          {isLoading && <li className="px-3 py-1.5 text-sm text-muted-foreground">{boardFilterLabels.search.loading}</li>}
          {!isLoading && users.length === 0 && <li className="px-3 py-1.5 text-sm text-muted-foreground">{boardFilterLabels.search.noResults}</li>}
          {!isLoading && users.filter((u) => !selectedIds.includes(u.id)).map((user) => (
            <li key={user.id}>
              <button type="button" className="w-full min-h-[44px] text-left px-3 py-2 text-sm hover:bg-accent" onClick={() => onSelect(user)}>
                {user.displayName ?? user.username}
              </button>
            </li>
          ))}
        </ul>
      )}
      <label className="flex min-h-[44px] items-center gap-2 text-sm">
        <input type="checkbox" aria-label={boardFilterLabels.filter.unassigned} checked={includeUnassigned} onChange={onUnassignedToggle} className="rounded border-input" />
        <span>{boardFilterLabels.filter.unassigned}</span>
      </label>
    </div>
  )
}

// ─── ActiveFilterChips ────────────────────────────────────────────────────────

interface ActiveFilterChipsProps {
  readonly assigneeIds: string[]
  /** 담당자 id → displayName|username 맵 — useUsersByIds 기반으로 안정 표시 */
  readonly assigneeNameMap: Map<string, string>
  readonly labels: string[]
  readonly onAssigneeRemove: (id: string) => void
  readonly onLabelRemove: (label: string) => void
}

/** 활성 필터 칩 목록 — 칩이 없으면 null 반환. */
function ActiveFilterChips({ assigneeIds, assigneeNameMap, labels, onAssigneeRemove, onLabelRemove }: ActiveFilterChipsProps): JSX.Element | null {
  if (assigneeIds.length === 0 && labels.length === 0) return null
  return (
    <div className="flex flex-wrap gap-1.5" role="list" aria-label="적용된 필터">
      {assigneeIds.map((id) => {
        const label = assigneeNameMap.get(id) ?? id
        return <Chip key={`assignee-${id}`} label={label} onRemove={() => onAssigneeRemove(id)} />
      })}
      {labels.map((label) => <Chip key={`label-${label}`} label={label} onRemove={() => onLabelRemove(label)} />)}
    </div>
  )
}

/** 단일 필터 칩. */
function Chip({ label, onRemove }: { readonly label: string; readonly onRemove: () => void }): JSX.Element {
  return (
    <span role="listitem" className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs">
      {label}
      <button type="button" aria-label={boardFilterLabels.chip.removeAriaLabel(label)} className="ml-1 flex min-h-[44px] min-w-[44px] items-center justify-center rounded-full leading-none hover:bg-muted-foreground/20" onClick={onRemove}>
        ✕
      </button>
    </span>
  )
}
