// 이슈의 다중 컴포넌트 할당을 선택하는 순수 presentational 셀렉터 컴포넌트 — FR-CM-02
import type { JSX } from 'react'
import { useState } from 'react'
import type { Component } from '@/api/components'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ComponentMultiSelect props */
export interface ComponentMultiSelectProps {
  /** 현재 선택된 컴포넌트 UUID 목록 — props 파생, useState 초기화 금지 (stale key prop 회귀 방지) */
  value: string[]
  /** 선택 가능한 컴포넌트 목록 — 프로젝트 컴포넌트 전체 */
  options: Component[]
  /** 선택 변경 콜백 — 새 컴포넌트 UUID 배열 전달 */
  onChange: (ids: string[]) => void
  /** 비활성화 여부 — true이면 모든 체크박스 disabled (fail-closed, canEdit=false 게이트) */
  disabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// ComponentMultiSelect
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 컴포넌트 다중 선택 컴포넌트.
 *
 * - 검색 input으로 컴포넌트 이름 필터링 (대소문자 무시)
 * - 체크박스로 다중 선택/해제
 * - value에 있는 컴포넌트는 checked 상태로 표시
 * - disabled=true이면 모든 체크박스 비활성 (fail-closed)
 * - 선택된 컴포넌트는 칩으로 상단에 표시
 * - WCAG AA: aria-label, label 연결
 *
 * mutation은 route(issues.$key.tsx)가 소유한다.
 * 이 컴포넌트는 value/options/onChange/disabled만 받는 순수 presentational이다.
 */
export function ComponentMultiSelect({
  value,
  options,
  onChange,
  disabled = false,
}: ComponentMultiSelectProps): JSX.Element {
  const [searchQuery, setSearchQuery] = useState('')

  const filteredOptions = options.filter((comp) =>
    comp.name.toLowerCase().includes(searchQuery.toLowerCase()),
  )

  /**
   * 체크박스 토글 핸들러.
   * 선택 중이면 제거, 미선택이면 추가.
   */
  function handleToggle(id: string) {
    if (value.includes(id)) {
      onChange(value.filter((v) => v !== id))
    } else {
      onChange([...value, id])
    }
  }

  // 현재 선택된 컴포넌트 칩 목록 — options에 있는 항목만 표시
  const selectedComponents = value
    .map((id) => options.find((comp) => comp.id === id))
    .filter((comp): comp is Component => comp !== undefined)

  return (
    <div className="flex flex-col gap-2">
      {/* 선택된 컴포넌트 칩 목록 */}
      <ComponentChipList components={selectedComponents} />

      {/* 검색 input */}
      <input
        type="text"
        role="textbox"
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
        placeholder={`${issueDetailStrings.componentsLabel} 검색`}
        aria-label={`${issueDetailStrings.componentsLabel} 검색`}
        value={searchQuery}
        onChange={(e) => setSearchQuery(e.target.value)}
        disabled={disabled}
      />

      {/* 컴포넌트 체크박스 목록 */}
      <ComponentOptionList
        options={filteredOptions}
        selectedIds={value}
        onToggle={handleToggle}
        disabled={disabled}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ComponentChipList — 선택된 컴포넌트 칩 목록 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** ComponentChipList props */
interface ComponentChipListProps {
  /** 선택된 컴포넌트 목록 */
  components: Component[]
}

/**
 * 선택된 컴포넌트를 칩으로 렌더하는 서브컴포넌트.
 * 선택이 없으면 렌더하지 않는다.
 */
function ComponentChipList({ components }: ComponentChipListProps): JSX.Element | null {
  if (components.length === 0) return null

  return (
    <div className="flex flex-wrap gap-1" data-testid="component-chip-list">
      {components.map((comp) => (
        <span
          key={comp.id}
          data-testid="component-chip"
          className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium"
        >
          {comp.name}
        </span>
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// ComponentOptionList — 체크박스 목록 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** ComponentOptionList props */
interface ComponentOptionListProps {
  /** 표시할 컴포넌트 옵션 목록 (필터 적용 후) */
  options: Component[]
  /** 현재 선택된 UUID 목록 */
  selectedIds: string[]
  /** 체크박스 토글 콜백 */
  onToggle: (id: string) => void
  /** 전체 비활성화 여부 */
  disabled: boolean
}

/**
 * 컴포넌트 체크박스 목록 서브컴포넌트.
 * - 각 컴포넌트를 label + checkbox 쌍으로 렌더
 * - WCAG AA: label 연결로 체크박스가 getByRole('checkbox', {name}) 으로 탐색 가능
 * - max-h + overflow-y-auto로 스크롤 지원
 */
function ComponentOptionList({
  options,
  selectedIds,
  onToggle,
  disabled,
}: ComponentOptionListProps): JSX.Element {
  return (
    <ul className="flex flex-col gap-0.5 max-h-48 overflow-y-auto">
      {options.map((comp) => {
        const checkboxId = `component-checkbox-${comp.id}`
        const isChecked = selectedIds.includes(comp.id)

        return (
          <li key={comp.id}>
            <label
              htmlFor={checkboxId}
              className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted cursor-pointer has-[:disabled]:opacity-40 has-[:disabled]:cursor-not-allowed"
            >
              <input
                type="checkbox"
                id={checkboxId}
                aria-label={comp.name}
                checked={isChecked}
                onChange={() => onToggle(comp.id)}
                disabled={disabled}
                className="rounded border-input focus:ring-2 focus:ring-ring"
              />
              <span>{comp.name}</span>
            </label>
          </li>
        )
      })}
    </ul>
  )
}
