// 이슈 목록 필터 바 — 공유 FilterBar에 이슈 전용 status 섹션을 얹은 얇은 래퍼 (FR-UX-06 PR17)
import type { JSX } from 'react'
import { useMemo } from 'react'
import { FilterBar } from '@/components/filters/FilterBar'
import { FilterDropdown } from '@/components/filters/FilterDropdown'
import type { FilterChipData } from '@/components/filters/FilterBar'
import { useWorkflows, extractStatusOptions } from '@/hooks/use-workflows'
import type { StatusOption } from '@/hooks/use-workflows'
import type { IssueFilterParams } from '@/api/issues'
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
 * 담당자/라벨/컴포넌트/칩/초기화/카운트는 공유 {@link FilterBar}(공통 코어)에 위임하고,
 * status 섹션(useWorkflows 기반 StatusMultiSelect)만 이 래퍼가 소유해 leadingSection/
 * leadingChips 슬롯으로 주입한다.
 *
 * EC7 fail-safe: useWorkflows 로딩/에러 시 status 섹션은 빈 목록으로 렌더(throw 금지).
 * WCAG AA: label 연결, 칩 aria-label, 터치 타깃 44px 등은 FilterBar가 보장한다.
 */
export function IssueFilterBar({ projectKey, value, onChange }: IssueFilterBarProps): JSX.Element {
  const { data: workflows, isLoading: workflowsLoading, isError: workflowsError } = useWorkflows()

  // EC7 fail-safe: 워크플로우 로딩/에러 시 빈 목록
  const statusOptions: StatusOption[] = useMemo(() => {
    if (workflowsLoading || workflowsError || workflows === undefined) return []
    return extractStatusOptions(workflows)
  }, [workflows, workflowsLoading, workflowsError])

  // 선택된 status 키 → name 맵
  const statusNameMap = useMemo<Map<string, string>>(() => {
    const map = new Map<string, string>()
    for (const opt of statusOptions) {
      map.set(opt.key, opt.name)
    }
    return map
  }, [statusOptions])

  const statusChips: FilterChipData[] = value.statusKeys.map((key) => ({
    id: key,
    label: statusNameMap.get(key) ?? key,
    onRemove: () => onChange({ ...value, statusKeys: value.statusKeys.filter((k) => k !== key) }),
  }))

  return (
    <FilterBar
      projectKey={projectKey}
      value={value}
      onChange={onChange}
      idPrefix="issue-filter"
      leadingSection={
        statusOptions.length > 0 ? (
          <FilterDropdown
            label={issueFilterLabels.filter.statusLabel}
            selectedCount={value.statusKeys.length}
          >
            <StatusMultiSelect
              options={statusOptions}
              selectedKeys={value.statusKeys}
              onChange={(keys) => onChange({ ...value, statusKeys: keys })}
              disabled={workflowsLoading || workflowsError}
            />
          </FilterDropdown>
        ) : undefined
      }
      leadingChips={statusChips}
      extraActiveCount={value.statusKeys.length}
      onReset={() =>
        onChange({ statusKeys: [], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] })
      }
    />
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
      {/* 섹션 제목은 드롭다운 트리거가 이미 들고 있다 — 여기 또 적으면 같은 텍스트가 둘이 된다 */}
      <ul className="flex max-h-60 flex-col gap-0.5 overflow-y-auto">
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
