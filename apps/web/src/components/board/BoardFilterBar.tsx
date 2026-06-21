// 보드 카드 필터 바 — 담당자/라벨/컴포넌트 다중 선택, 활성 필터 칩, 초기화 (FR-BD-02 Task-5)
import type { JSX } from 'react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { LabelAutocompleteInput } from '@/components/labels/LabelAutocompleteInput'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { useUsers } from '@/hooks/use-users'
import { useComponents } from '@/hooks/use-components'
import { useDebounce } from '@/hooks/use-debounce'
import type { BoardCardFilterParams } from '@/api/boards'
import type { UserSummary } from '@/api/users'
import { boardFilterLabels } from '@/i18n/board-filter-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BoardFilterBar props */
export interface BoardFilterBarProps {
  /** 프로젝트 키 — useComponents 쿼리에 사용 */
  projectKey: string
  /** 현재 필터 파라미터 — 제어형, 상태는 부모 소유 */
  value: BoardCardFilterParams
  /** 필터 변경 콜백 — 새 필터 전체를 전달 */
  onChange: (next: BoardCardFilterParams) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 활성 필터 카운트 계산 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 활성 필터 개수를 계산한다. */
function countActiveFilters(value: BoardCardFilterParams): number {
  return (
    value.assigneeIds.length +
    value.labels.length +
    value.componentIds.length +
    (value.includeUnassigned ? 1 : 0)
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// BoardFilterBar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 카드 필터 바 컴포넌트.
 *
 * - 제어형: value/onChange props로 상태를 부모가 소유한다.
 * - 담당자: useUsers typeahead + "미배정" 체크박스
 * - 라벨: LabelAutocompleteInput 재사용, 다중 선택 칩 누적
 * - 컴포넌트: ComponentMultiSelect 재사용
 * - 활성 필터가 1개 이상이면 개수 텍스트 표시
 * - 초기화 버튼으로 전체 필터 리셋
 *
 * @param projectKey 프로젝트 키 (컴포넌트 목록 조회에 사용)
 * @param value 현재 필터 파라미터
 * @param onChange 필터 변경 콜백
 */
export function BoardFilterBar({
  projectKey,
  value,
  onChange,
}: BoardFilterBarProps): JSX.Element {
  const [assigneeQuery, setAssigneeQuery] = useState('')
  const [labelInput, setLabelInput] = useState('')

  const debouncedAssigneeQuery = useDebounce(assigneeQuery, 250)
  const { data: users = [], isLoading: usersLoading } = useUsers(debouncedAssigneeQuery)
  const { data: components = [] } = useComponents(projectKey)

  const activeCount = countActiveFilters(value)

  /** 담당자 선택 핸들러 */
  function handleAssigneeSelect(user: UserSummary) {
    if (value.assigneeIds.includes(user.id)) return
    onChange({ ...value, assigneeIds: [...value.assigneeIds, user.id] })
    setAssigneeQuery('')
  }

  /** 담당자 칩 제거 핸들러 */
  function handleAssigneeRemove(id: string) {
    onChange({ ...value, assigneeIds: value.assigneeIds.filter((a) => a !== id) })
  }

  /** 미배정 토글 핸들러 */
  function handleUnassignedToggle(e: React.ChangeEvent<HTMLInputElement>) {
    onChange({ ...value, includeUnassigned: e.target.checked })
  }

  /** 라벨 확정 핸들러 */
  function handleLabelCommit(label: string) {
    if (value.labels.includes(label)) return
    onChange({ ...value, labels: [...value.labels, label] })
    setLabelInput('')
  }

  /** 라벨 칩 제거 핸들러 */
  function handleLabelRemove(label: string) {
    onChange({ ...value, labels: value.labels.filter((l) => l !== label) })
  }

  /** 컴포넌트 변경 핸들러 */
  function handleComponentChange(ids: string[]) {
    onChange({ ...value, componentIds: ids })
  }

  /** 초기화 핸들러 */
  function handleReset() {
    onChange({ assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] })
    setAssigneeQuery('')
    setLabelInput('')
  }

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-muted/30 p-4">
      {/* 활성 필터 카운트 */}
      {activeCount > 0 && (
        <span className="w-full text-xs text-muted-foreground">
          {boardFilterLabels.count.applied(activeCount)}
        </span>
      )}

      {/* 담당자 typeahead */}
      <AssigneeTypeahead
        query={assigneeQuery}
        onQueryChange={setAssigneeQuery}
        users={users}
        isLoading={usersLoading}
        selectedIds={value.assigneeIds}
        includeUnassigned={value.includeUnassigned}
        onSelect={handleAssigneeSelect}
        onUnassignedToggle={handleUnassignedToggle}
      />

      {/* 라벨 자동완성 */}
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

      {/* 컴포넌트 체크박스 */}
      <div className="flex min-w-[180px] flex-col gap-1">
        <span className="text-xs font-medium text-muted-foreground">
          {boardFilterLabels.filter.componentLabel}
        </span>
        <ComponentMultiSelect
          value={value.componentIds}
          options={components}
          onChange={handleComponentChange}
        />
      </div>

      {/* 활성 필터 칩 + 초기화 */}
      <div className="flex w-full flex-wrap items-center gap-2">
        <FilterChipList
          assigneeIds={value.assigneeIds}
          users={users}
          labels={value.labels}
          onAssigneeRemove={handleAssigneeRemove}
          onLabelRemove={handleLabelRemove}
        />
        <Button type="button" variant="outline" size="sm" onClick={handleReset}>
          {boardFilterLabels.filter.reset}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AssigneeTypeahead — 담당자 typeahead 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface AssigneeTypeaheadProps {
  readonly query: string
  readonly onQueryChange: (q: string) => void
  readonly users: UserSummary[]
  readonly isLoading: boolean
  readonly selectedIds: string[]
  readonly includeUnassigned: boolean
  readonly onSelect: (user: UserSummary) => void
  readonly onUnassignedToggle: (e: React.ChangeEvent<HTMLInputElement>) => void
}

/** 담당자 검색 typeahead + 미배정 체크박스 서브컴포넌트. */
function AssigneeTypeahead({
  query,
  onQueryChange,
  users,
  isLoading,
  selectedIds,
  includeUnassigned,
  onSelect,
  onUnassignedToggle,
}: AssigneeTypeaheadProps): JSX.Element {
  const showResults = query.length >= 1

  return (
    <div className="relative flex min-w-[200px] flex-col gap-1">
      <label
        htmlFor="board-filter-assignee-input"
        className="text-xs font-medium text-muted-foreground"
      >
        {boardFilterLabels.filter.assigneeLabel}
      </label>
      <input
        id="board-filter-assignee-input"
        type="text"
        role="textbox"
        aria-label={boardFilterLabels.filter.assigneeLabel}
        className="min-h-[44px] rounded-md border bg-background px-3 py-2 text-sm"
        placeholder={boardFilterLabels.filter.assigneePlaceholder}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
      />

      {/* 검색 결과 드롭다운 */}
      {showResults && (
        <ul className="absolute top-full z-50 mt-1 w-full max-h-40 overflow-y-auto rounded-md border bg-popover shadow-md">
          {isLoading && (
            <li className="px-3 py-1.5 text-sm text-muted-foreground">
              {boardFilterLabels.search.loading}
            </li>
          )}
          {!isLoading && users.length === 0 && (
            <li className="px-3 py-1.5 text-sm text-muted-foreground">
              {boardFilterLabels.search.noResults}
            </li>
          )}
          {!isLoading &&
            users
              .filter((u) => !selectedIds.includes(u.id))
              .map((user) => {
                const label = user.displayName ?? user.username
                return (
                  <li key={user.id}>
                    <button
                      type="button"
                      className="w-full min-h-[44px] text-left px-3 py-2 text-sm hover:bg-accent"
                      onClick={() => onSelect(user)}
                    >
                      {label}
                    </button>
                  </li>
                )
              })}
        </ul>
      )}

      {/* 미배정 체크박스 */}
      <label className="flex items-center gap-2 text-sm min-h-[44px]">
        <input
          type="checkbox"
          role="checkbox"
          aria-label={boardFilterLabels.filter.unassigned}
          checked={includeUnassigned}
          onChange={onUnassignedToggle}
          className="rounded border-input"
        />
        <span>{boardFilterLabels.filter.unassigned}</span>
      </label>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// FilterChipList — 활성 필터 칩 목록 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface FilterChipListProps {
  readonly assigneeIds: string[]
  readonly users: UserSummary[]
  readonly labels: string[]
  readonly onAssigneeRemove: (id: string) => void
  readonly onLabelRemove: (label: string) => void
}

/** 활성 필터 칩 목록. 제거 버튼 포함. */
function FilterChipList({
  assigneeIds,
  users,
  labels,
  onAssigneeRemove,
  onLabelRemove,
}: FilterChipListProps): JSX.Element | null {
  const hasChips = assigneeIds.length > 0 || labels.length > 0
  if (!hasChips) return null

  return (
    <div className="flex flex-wrap gap-1.5" role="list" aria-label="적용된 필터">
      {assigneeIds.map((id) => {
        const user = users.find((u) => u.id === id)
        const name = user?.displayName ?? user?.username ?? id
        return (
          <FilterChip
            key={`assignee-${id}`}
            label={name}
            onRemove={() => onAssigneeRemove(id)}
          />
        )
      })}
      {labels.map((label) => (
        <FilterChip
          key={`label-${label}`}
          label={label}
          onRemove={() => onLabelRemove(label)}
        />
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// FilterChip — 단일 필터 칩
// ─────────────────────────────────────────────────────────────────────────────

interface FilterChipProps {
  readonly label: string
  readonly onRemove: () => void
}

/** 단일 필터 칩 — 표시 이름 + ✕ 제거 버튼. */
function FilterChip({ label, onRemove }: FilterChipProps): JSX.Element {
  return (
    <span
      role="listitem"
      className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs"
    >
      {label}
      <button
        type="button"
        aria-label={boardFilterLabels.chip.removeAriaLabel(label)}
        className="ml-1 rounded-full hover:bg-muted-foreground/20 min-h-[44px] min-w-[44px] flex items-center justify-center leading-none"
        onClick={onRemove}
      >
        ✕
      </button>
    </span>
  )
}
