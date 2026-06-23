// 이슈 목록 필터 바 — status/담당자/라벨/컴포넌트 다중 선택, 활성 필터 칩, 초기화 (FR-SR-01 Task 4)
import type { JSX } from 'react'
import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { LabelAutocompleteInput } from '@/components/labels/LabelAutocompleteInput'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { useUsers, useUsersByIds } from '@/hooks/use-users'
import { useComponents } from '@/hooks/use-components'
import { useDebounce } from '@/hooks/use-debounce'
import { useWorkflows, extractStatusOptions } from '@/hooks/use-workflows'
import type { StatusOption } from '@/hooks/use-workflows'
import type { IssueFilterParams } from '@/api/issues'
import type { UserSummary } from '@/api/users'
import { issueFilterLabels } from '@/i18n/issue-filter-labels'

// ─────────────────────────────────────────────────────────────────────────────
// IssueFilterBar (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/** IssueFilterBar props */
export interface IssueFilterBarProps {
  /** 프로젝트 키 — useComponents 쿼리에 사용 */
  projectKey: string
  /** 현재 필터 파라미터 — 제어형, 상태는 부모 소유 */
  value: IssueFilterParams
  /** 필터 변경 콜백 — 새 필터 전체를 전달 */
  onChange: (next: IssueFilterParams) => void
}

/**
 * 이슈 목록 필터 바 — 제어형.
 *
 * status = StatusMultiSelect(useWorkflows), 담당자 = typeahead(useUsers, useDebounce 250ms),
 * 라벨 = LabelAutocompleteInput, 컴포넌트 = ComponentMultiSelect.
 * 활성 필터 칩(개별 ✕ 제거) + 초기화 버튼 + 활성 개수 표시.
 *
 * EC7 fail-safe: useWorkflows 로딩/에러 시 status 섹션은 빈 목록으로 렌더(throw 금지).
 * WCAG AA: label 연결, 칩 aria-label, 터치 타깃 44px.
 */
export function IssueFilterBar({ projectKey, value, onChange }: IssueFilterBarProps): JSX.Element {
  const [assigneeQuery, setAssigneeQuery] = useState('')
  const [labelInput, setLabelInput] = useState('')

  const { data: workflows, isLoading: workflowsLoading, isError: workflowsError } = useWorkflows()
  const { data: users = [], isLoading: usersLoading } = useUsers(useDebounce(assigneeQuery, 250))
  const { data: components = [] } = useComponents(projectKey)

  // EC7 fail-safe: 워크플로우 로딩/에러 시 빈 목록
  const statusOptions: StatusOption[] = useMemo(() => {
    if (workflowsLoading || workflowsError || workflows === undefined) return []
    return extractStatusOptions(workflows)
  }, [workflows, workflowsLoading, workflowsError])

  // 선택된 담당자 이름 안정 표시 — 검색어가 비워져도 이름 유지
  const { data: selectedUsers = [] } = useUsersByIds(value.assigneeIds)
  const assigneeNameMap = useMemo<Map<string, string>>(() => {
    const map = new Map<string, string>()
    for (const u of selectedUsers) {
      map.set(u.id, u.displayName ?? u.username)
    }
    return map
  }, [selectedUsers])

  // 선택된 status 키 → name 맵
  const statusNameMap = useMemo<Map<string, string>>(() => {
    const map = new Map<string, string>()
    for (const opt of statusOptions) {
      map.set(opt.key, opt.name)
    }
    return map
  }, [statusOptions])

  const activeCount =
    value.statusKeys.length +
    value.assigneeIds.length +
    value.labels.length +
    value.componentIds.length +
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
    onChange({ statusKeys: [], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] })
    setAssigneeQuery('')
    setLabelInput('')
  }

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-muted/30 p-4">
      {activeCount > 0 && (
        <span className="w-full text-xs text-muted-foreground">
          {issueFilterLabels.count.applied(activeCount)}
        </span>
      )}

      {/* status 셀렉터 */}
      <StatusMultiSelect
        options={statusOptions}
        selectedKeys={value.statusKeys}
        onChange={(keys) => onChange({ ...value, statusKeys: keys })}
        disabled={workflowsLoading || workflowsError}
      />

      {/* 담당자 typeahead + 미배정 체크박스 */}
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

      {/* 라벨 자동완성 */}
      <div className="flex min-w-[180px] flex-col gap-1">
        <label className="text-xs font-medium text-muted-foreground" htmlFor="issue-filter-label-input">
          {issueFilterLabels.filter.labelLabel}
        </label>
        <LabelAutocompleteInput
          value={labelInput}
          onChange={setLabelInput}
          onCommit={handleLabelCommit}
          existingLabels={value.labels}
          placeholder={issueFilterLabels.filter.labelPlaceholder}
        />
      </div>

      {/* 컴포넌트 멀티셀렉트 */}
      <div className="flex min-w-[180px] flex-col gap-1">
        <span className="text-xs font-medium text-muted-foreground">
          {issueFilterLabels.filter.componentLabel}
        </span>
        <ComponentMultiSelect
          value={value.componentIds}
          options={components}
          onChange={(ids) => onChange({ ...value, componentIds: ids })}
        />
      </div>

      {/* 활성 필터 칩 + 초기화 버튼 */}
      <div className="flex w-full flex-wrap items-center gap-2">
        <ActiveFilterChips
          statusKeys={value.statusKeys}
          statusNameMap={statusNameMap}
          assigneeIds={value.assigneeIds}
          assigneeNameMap={assigneeNameMap}
          labels={value.labels}
          onStatusRemove={(key) =>
            onChange({ ...value, statusKeys: value.statusKeys.filter((k) => k !== key) })
          }
          onAssigneeRemove={(id) =>
            onChange({ ...value, assigneeIds: value.assigneeIds.filter((a) => a !== id) })
          }
          onLabelRemove={(label) =>
            onChange({ ...value, labels: value.labels.filter((l) => l !== label) })
          }
        />
        <Button type="button" variant="outline" size="sm" onClick={handleReset}>
          {issueFilterLabels.filter.reset}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// StatusMultiSelect — 상태 전용 비공개 서브컴포넌트
// ComponentMultiSelect는 Component{id,name} props라 StatusOption{key,name}과 형태가 달라
// 그대로 재사용 불가 (C4). 보드 코드 0 변경 제약상 ComponentMultiSelect 제네릭화 금지.
// ─────────────────────────────────────────────────────────────────────────────

interface StatusMultiSelectProps {
  /** 선택 가능한 상태 옵션 목록 */
  readonly options: StatusOption[]
  /** 현재 선택된 상태 키 목록 */
  readonly selectedKeys: string[]
  /** 선택 변경 콜백 — 새 상태 키 배열 전달 */
  readonly onChange: (keys: string[]) => void
  /** 비활성화 여부 — useWorkflows 로딩/에러 시 true (EC7 fail-safe) */
  readonly disabled?: boolean
}

/**
 * 상태 멀티셀렉트 서브컴포넌트.
 *
 * - 옵션·칩에 name 표시 (key 아님)
 * - value엔 key 저장/전송
 * - disabled=true이면 체크박스 비활성 (EC7 fail-safe)
 * - 옵션이 없으면 섹션 자체를 렌더하지 않음
 */
function StatusMultiSelect({
  options,
  selectedKeys,
  onChange,
  disabled = false,
}: StatusMultiSelectProps): JSX.Element | null {
  if (options.length === 0) return null

  function handleToggle(key: string) {
    if (selectedKeys.includes(key)) {
      onChange(selectedKeys.filter((k) => k !== key))
    } else {
      onChange([...selectedKeys, key])
    }
  }

  return (
    <div className="flex min-w-[160px] flex-col gap-1">
      <span className="text-xs font-medium text-muted-foreground">
        {issueFilterLabels.filter.statusLabel}
      </span>
      <ul className="flex flex-col gap-0.5 max-h-48 overflow-y-auto">
        {options.map((opt) => {
          const checkboxId = `issue-filter-status-${opt.key}`
          const isChecked = selectedKeys.includes(opt.key)
          return (
            <li key={opt.key}>
              <label
                htmlFor={checkboxId}
                className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted cursor-pointer has-[:disabled]:opacity-40 has-[:disabled]:cursor-not-allowed"
              >
                <input
                  type="checkbox"
                  id={checkboxId}
                  aria-label={opt.name}
                  checked={isChecked}
                  onChange={() => handleToggle(opt.key)}
                  disabled={disabled}
                  className="rounded border-input focus:ring-2 focus:ring-ring"
                />
                <span>{opt.name}</span>
              </label>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AssigneeSection
// ─────────────────────────────────────────────────────────────────────────────

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
      <label htmlFor="issue-filter-assignee-input" className="text-xs font-medium text-muted-foreground">
        {issueFilterLabels.filter.assigneeLabel}
      </label>
      <input
        id="issue-filter-assignee-input"
        type="text"
        aria-label={issueFilterLabels.filter.assigneeLabel}
        className="min-h-[44px] rounded-md border bg-background px-3 py-2 text-sm"
        placeholder={issueFilterLabels.filter.assigneePlaceholder}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
      />
      {show && (
        <ul className="absolute top-full z-50 mt-1 w-full max-h-40 overflow-y-auto rounded-md border bg-popover shadow-md">
          {isLoading && (
            <li className="px-3 py-1.5 text-sm text-muted-foreground">
              {issueFilterLabels.search.loading}
            </li>
          )}
          {!isLoading && users.length === 0 && (
            <li className="px-3 py-1.5 text-sm text-muted-foreground">
              {issueFilterLabels.search.noResults}
            </li>
          )}
          {!isLoading && users.filter((u) => !selectedIds.includes(u.id)).map((user) => (
            <li key={user.id}>
              <button
                type="button"
                className="w-full min-h-[44px] text-left px-3 py-2 text-sm hover:bg-accent"
                onClick={() => onSelect(user)}
              >
                {user.displayName ?? user.username}
              </button>
            </li>
          ))}
        </ul>
      )}
      <label className="flex min-h-[44px] items-center gap-2 text-sm">
        <input
          type="checkbox"
          aria-label={issueFilterLabels.filter.unassigned}
          checked={includeUnassigned}
          onChange={onUnassignedToggle}
          className="rounded border-input"
        />
        <span>{issueFilterLabels.filter.unassigned}</span>
      </label>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ActiveFilterChips
// ─────────────────────────────────────────────────────────────────────────────

interface ActiveFilterChipsProps {
  readonly statusKeys: string[]
  /** 상태 키 → name 맵 — StatusMultiSelect 옵션 기반으로 name 표시 */
  readonly statusNameMap: Map<string, string>
  readonly assigneeIds: string[]
  /** 담당자 id → displayName|username 맵 — useUsersByIds 기반으로 안정 표시 */
  readonly assigneeNameMap: Map<string, string>
  readonly labels: string[]
  readonly onStatusRemove: (key: string) => void
  readonly onAssigneeRemove: (id: string) => void
  readonly onLabelRemove: (label: string) => void
}

/** 활성 필터 칩 목록 — 칩이 없으면 null 반환. */
function ActiveFilterChips({
  statusKeys, statusNameMap, assigneeIds, assigneeNameMap, labels,
  onStatusRemove, onAssigneeRemove, onLabelRemove,
}: ActiveFilterChipsProps): JSX.Element | null {
  if (statusKeys.length === 0 && assigneeIds.length === 0 && labels.length === 0) return null
  return (
    <div className="flex flex-wrap gap-1.5" role="list" aria-label="적용된 필터">
      {statusKeys.map((key) => {
        const name = statusNameMap.get(key) ?? key
        return (
          <Chip
            key={`status-${key}`}
            label={name}
            onRemove={() => onStatusRemove(key)}
          />
        )
      })}
      {assigneeIds.map((id) => {
        const label = assigneeNameMap.get(id) ?? id
        return <Chip key={`assignee-${id}`} label={label} onRemove={() => onAssigneeRemove(id)} />
      })}
      {labels.map((label) => (
        <Chip key={`label-${label}`} label={label} onRemove={() => onLabelRemove(label)} />
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Chip
// ─────────────────────────────────────────────────────────────────────────────

/** 단일 필터 칩. */
function Chip({ label, onRemove }: { readonly label: string; readonly onRemove: () => void }): JSX.Element {
  return (
    <span role="listitem" className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs">
      {label}
      <button
        type="button"
        aria-label={issueFilterLabels.chip.removeAriaLabel(label)}
        className="ml-1 flex min-h-[44px] min-w-[44px] items-center justify-center rounded-full leading-none hover:bg-muted-foreground/20"
        onClick={onRemove}
      >
        ✕
      </button>
    </span>
  )
}
