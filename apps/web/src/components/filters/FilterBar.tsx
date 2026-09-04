// 이슈/보드 필터 바 공유 코어 — 담당자 typeahead·라벨 자동완성·컴포넌트 멀티셀렉트·활성 칩·초기화 (FR-UX-06 PR17 Task 2)
import type { JSX, ReactNode } from 'react'
import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { LabelAutocompleteInput } from '@/components/labels/LabelAutocompleteInput'
import { ComponentMultiSelect } from '@/components/issue/ComponentMultiSelect'
import { FilterDropdown } from './FilterDropdown'
import { useUsers, useUsersByIds } from '@/hooks/use-users'
import { useComponents } from '@/hooks/use-components'
import { useDebounce } from '@/hooks/use-debounce'
import type { BoardCardFilterParams } from '@/api/boards'
import type { UserSummary } from '@/api/users'
import { filterBarLabels } from '@/i18n/filter-bar-labels'

// ─────────────────────────────────────────────────────────────────────────────
// FilterBar (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/** FilterBar 활성 칩 하나를 표현하는 데이터 — 이슈 전용 status 칩 등 leadingChips에 사용 */
export interface FilterChipData {
  /** React key 및 내부 식별에 사용하는 고유 id */
  readonly id: string
  /** 칩에 표시할 텍스트 */
  readonly label: string
  /** 칩 제거(✕) 클릭 콜백 */
  readonly onRemove: () => void
}

/** FilterBar props — T는 BoardCardFilterParams(공통 필드)를 확장한 필터 파라미터 타입 */
export interface FilterBarProps<T extends BoardCardFilterParams> {
  /** 프로젝트 키 — useComponents 쿼리에 사용 */
  projectKey: string
  /** 현재 필터 파라미터 — 제어형, 상태는 부모 소유 */
  value: T
  /** 필터 변경 콜백 — 새 필터 전체를 전달 */
  onChange: (next: T) => void
  /** element id 접두사. 예: 'issue-filter' | 'board-filter' */
  idPrefix: string
  /** 담당자 섹션 앞에 렌더할 슬롯(이슈 전용 StatusMultiSelect 등). 미전달 시 렌더하지 않는다. */
  leadingSection?: ReactNode
  /** 활성 필터 칩 목록 맨 앞에 렌더할 칩(이슈 전용 상태 칩 등). 미전달 시 렌더하지 않는다. */
  leadingChips?: readonly FilterChipData[]
  /** activeCount에 가산할 값(이슈 전용 statusKeys.length 등). 기본값 0. */
  extraActiveCount?: number
  /**
   * 초기화 버튼 클릭 시 값 초기화를 위임할 콜백. 전달 시 이 콜백이 값 초기화를
   * 대신하고 기본 공통-clear onChange는 호출되지 않는다 — 이슈 전용 status 등
   * T가 추가로 가진 필드까지 함께 비워야 하는 상위 래퍼가 사용.
   * 미전달 시 기존 공통-clear onChange 동작을 유지한다.
   * 로컬 검색 입력(담당자/라벨) clear는 onReset 전달 여부와 무관하게 항상 수행된다.
   */
  onReset?: () => void
  /**
   * 렌더하지 않을 섹션. 미전달이면 **전부 표시**(기존 소비처 동작 불변).
   *
   * 백로그(FR-UX-13 F16)는 서버 필터가 없어 클라이언트가 직접 걸러야 하는데
   * `backlogIssueSchema` 에 `labels`·`componentIds` 필드가 **아예 없다**. 두 섹션을 그대로
   * 렌더하면 눌러도 아무 일이 일어나지 않는 **장식 필터**가 된다. 그래서 값 계산이 아니라
   * **표시만** 끈다 — `activeCount` 계산식과 `handleReset` 동작은 건드리지 않는다.
   */
  hiddenSections?: {
    readonly labels?: boolean
    readonly components?: boolean
  }
}

/**
 * 이슈/보드 필터 바 공유 코어 — 제어형.
 *
 * 담당자 = typeahead(useUsers, useDebounce 250ms), 라벨 = LabelAutocompleteInput,
 * 컴포넌트 = ComponentMultiSelect. 활성 필터 칩(개별 ✕ 제거) + 초기화 버튼 + 활성 개수 표시.
 * 상태(status) 섹션은 이슈 전용이라 이 컴포넌트가 소유하지 않고 leadingSection/leadingChips
 * 슬롯으로 상위(이슈 래퍼)가 주입한다.
 *
 * handleReset은 로컬 검색 입력(assigneeQuery/labelInput)을 항상 비우고,
 * 값 초기화는 onReset이 있으면 위임(이슈 전용 statusKeys 등 T 확장 필드 포함), 없으면
 * 공통 필드(assigneeIds/includeUnassigned/labels/componentIds)만 onChange로 비운다.
 *
 * WCAG AA: label 연결, 칩 aria-label, 터치 타깃 44px.
 */
export function FilterBar<T extends BoardCardFilterParams>({
  projectKey,
  value,
  onChange,
  idPrefix,
  leadingSection,
  leadingChips = [],
  extraActiveCount = 0,
  onReset,
  hiddenSections,
}: FilterBarProps<T>): JSX.Element {
  const [assigneeQuery, setAssigneeQuery] = useState('')
  const [labelInput, setLabelInput] = useState('')

  const { data: users = [], isLoading: usersLoading } = useUsers(useDebounce(assigneeQuery, 250))
  const { data: components = [] } = useComponents(projectKey)

  // 선택된 담당자 이름 안정 표시 — 검색어가 비워져도 이름 유지
  const { data: selectedUsers = [] } = useUsersByIds(value.assigneeIds)
  const assigneeNameMap = useMemo<Map<string, string>>(() => {
    const map = new Map<string, string>()
    for (const u of selectedUsers) {
      map.set(u.id, u.displayName ?? u.username)
    }
    return map
  }, [selectedUsers])

  const activeCount =
    value.assigneeIds.length +
    value.labels.length +
    value.componentIds.length +
    (value.includeUnassigned ? 1 : 0) +
    extraActiveCount

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
    // 로컬 검색 입력(담당자/라벨)은 onReset 위임 여부와 무관하게 항상 비운다 —
    // 값 초기화만 onReset(이슈 전용 status 포함)에 위임하거나 공통-clear로 폴백한다.
    setAssigneeQuery('')
    setLabelInput('')
    if (onReset) {
      onReset()
    } else {
      onChange({ ...value, assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] })
    }
  }

  const assigneeCount = value.assigneeIds.length + (value.includeUnassigned ? 1 : 0)

  // 지라 기본 검색과 같은 **가로 한 줄** 배치. 종전에는 네 섹션을 전부 펼쳐 세로로 쌓았고
  // (상태만 체크박스 6줄) 카드 하나가 화면 절반을 먹어, 목록이 좁아지는 사이드패널 표시
  // 방식에서는 이슈 테이블이 접힘선 아래로 밀려났다.
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/30 p-2">
      {leadingSection}

      {/* 담당자 typeahead + 미배정 체크박스 */}
      <FilterDropdown
        label={filterBarLabels.filter.assigneeLabel}
        selectedCount={assigneeCount}
        contentClassName="w-72"
      >
        <AssigneeSection
          idPrefix={idPrefix}
          query={assigneeQuery}
          onQueryChange={setAssigneeQuery}
          users={users}
          isLoading={usersLoading}
          selectedIds={value.assigneeIds}
          includeUnassigned={value.includeUnassigned}
          onSelect={handleAssigneeSelect}
          onUnassignedToggle={(e) => onChange({ ...value, includeUnassigned: e.target.checked })}
        />
      </FilterDropdown>

      {/* 라벨 자동완성 — 소비처가 감출 수 있다(백로그는 응답에 labels가 없다) */}
      {hiddenSections?.labels !== true && (
        <FilterDropdown
          label={filterBarLabels.filter.labelLabel}
          selectedCount={value.labels.length}
          contentClassName="w-72"
        >
          {/* 섹션 제목은 드롭다운 트리거가 들고 있다 — 여기 또 적으면 같은 텍스트가 둘이 되어
              `getByText('라벨')` 이 strict 위반으로 죽는다. 입력 자신은 `aria-label="라벨
              자동완성"` 을 이미 갖고 있어 접근성 이름을 잃지 않는다. */}
          <div className="flex flex-col gap-1">
            <LabelAutocompleteInput
              value={labelInput}
              onChange={setLabelInput}
              onCommit={handleLabelCommit}
              existingLabels={value.labels}
              placeholder={filterBarLabels.filter.labelPlaceholder}
            />
          </div>
        </FilterDropdown>
      )}

      {/* 컴포넌트 멀티셀렉트 — 소비처가 감출 수 있다(백로그는 응답에 componentIds가 없다) */}
      {hiddenSections?.components !== true && (
        <FilterDropdown
          label={filterBarLabels.filter.componentLabel}
          selectedCount={value.componentIds.length}
          contentClassName="w-72"
        >
          {/* 위와 같은 이유로 섹션 제목을 두지 않는다 — 검색 입력이 `컴포넌트 검색` 이라는
              제 이름을 갖는다. */}
          <div className="flex flex-col gap-1">
            <ComponentMultiSelect
              value={value.componentIds}
              options={components}
              onChange={(ids) => onChange({ ...value, componentIds: ids })}
            />
          </div>
        </FilterDropdown>
      )}

      <Button type="button" variant="outline" size="sm" className="min-h-[44px]" onClick={handleReset}>
        {filterBarLabels.filter.reset}
      </Button>

      {activeCount > 0 && (
        <span className="text-xs text-muted-foreground">
          {filterBarLabels.count.applied(activeCount)}
        </span>
      )}

      {/* 활성 필터 칩 — 무엇이 걸려 있는지 접힌 드롭다운 밖에서도 보이게 한다 */}
      <div className="flex w-full flex-wrap items-center gap-2 empty:hidden">
        <ActiveFilterChips
          leadingChips={leadingChips}
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
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// AssigneeSection
// ─────────────────────────────────────────────────────────────────────────────

interface AssigneeSectionProps {
  /** element id 접두사 — `${idPrefix}-assignee-input` */
  readonly idPrefix: string
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
  idPrefix,
  query,
  onQueryChange,
  users,
  isLoading,
  selectedIds,
  includeUnassigned,
  onSelect,
  onUnassignedToggle,
}: AssigneeSectionProps): JSX.Element {
  const inputId = `${idPrefix}-assignee-input`
  const show = query.length >= 1
  return (
    <div className="relative flex min-w-[200px] flex-col gap-1">
      <label htmlFor={inputId} className="text-xs font-medium text-muted-foreground">
        {filterBarLabels.filter.assigneeLabel}
      </label>
      <input
        id={inputId}
        type="text"
        aria-label={filterBarLabels.filter.assigneeLabel}
        className="min-h-[44px] rounded-md border bg-background px-3 py-2 text-sm"
        placeholder={filterBarLabels.filter.assigneePlaceholder}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
      />
      {show && (
        <ul className="absolute top-full z-50 mt-1 w-full max-h-40 overflow-y-auto rounded-md border bg-popover shadow-md">
          {isLoading && (
            <li className="px-3 py-1.5 text-sm text-muted-foreground">
              {filterBarLabels.search.loading}
            </li>
          )}
          {!isLoading && users.length === 0 && (
            <li className="px-3 py-1.5 text-sm text-muted-foreground">
              {filterBarLabels.search.noResults}
            </li>
          )}
          {!isLoading && users.filter((u) => !selectedIds.includes(u.id)).map((user) => (
            <li key={user.id}>
              {/* PR22 OUT — P5 옵션 행: 콤보박스 후보라 w-full text-left 가 필요하고, Button의 inline-flex/justify-center와 충돌한다 */}
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
          aria-label={filterBarLabels.filter.unassigned}
          checked={includeUnassigned}
          onChange={onUnassignedToggle}
          className="rounded border-input"
        />
        <span>{filterBarLabels.filter.unassigned}</span>
      </label>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ActiveFilterChips
// ─────────────────────────────────────────────────────────────────────────────

interface ActiveFilterChipsProps {
  /** 이슈 전용 status 칩 등 — 담당자 칩보다 먼저 렌더 */
  readonly leadingChips: readonly FilterChipData[]
  readonly assigneeIds: string[]
  /** 담당자 id → displayName|username 맵 — useUsersByIds 기반으로 안정 표시 */
  readonly assigneeNameMap: Map<string, string>
  readonly labels: string[]
  readonly onAssigneeRemove: (id: string) => void
  readonly onLabelRemove: (label: string) => void
}

/** 활성 필터 칩 목록 — leadingChips/담당자/라벨이 모두 없으면 null 반환. */
function ActiveFilterChips({
  leadingChips,
  assigneeIds,
  assigneeNameMap,
  labels,
  onAssigneeRemove,
  onLabelRemove,
}: ActiveFilterChipsProps): JSX.Element | null {
  if (leadingChips.length === 0 && assigneeIds.length === 0 && labels.length === 0) return null
  return (
    <div className="flex flex-wrap gap-1.5" role="list" aria-label="적용된 필터">
      {leadingChips.map((chip) => (
        <Chip key={chip.id} label={chip.label} onRemove={chip.onRemove} />
      ))}
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
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label={filterBarLabels.chip.removeAriaLabel(label)}
        className="ml-1 min-h-[44px] min-w-[44px] rounded-full leading-none hover:bg-muted-foreground/20"
        onClick={onRemove}
      >
        ✕
      </Button>
    </span>
  )
}
