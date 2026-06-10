// 이슈의 다중 버전 연결을 선택하는 순수 presentational 셀렉터 컴포넌트 — FR-VR-03
import type { JSX } from 'react'
import { useState } from 'react'
import type { Version } from '@/api/versions.types'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** VersionMultiSelect props */
export interface VersionMultiSelectProps {
  /**
   * 셀렉터 종류.
   * - 'affects': 영향 버전 (Affects Versions)
   * - 'fix': 수정 버전 (Fix Versions)
   */
  variant: 'affects' | 'fix'
  /** 현재 선택된 버전 UUID 목록 — props 파생, useState 초기화 금지 (stale key prop 회귀 방지) */
  value: string[]
  /** 선택 가능한 버전 목록 — 프로젝트 버전 전체 */
  options: Version[]
  /** 선택 변경 콜백 — 새 버전 UUID 배열 전달 */
  onChange: (ids: string[]) => void
  /** 비활성화 여부 — true이면 모든 체크박스 disabled (fail-closed, canEdit=false 게이트) */
  disabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// VersionMultiSelect
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 버전 다중 선택 컴포넌트.
 *
 * - variant='affects': 영향 버전, variant='fix': 수정 버전
 * - ARCHIVED 버전은 드롭다운에서 기본 숨김
 *   단, value(이미 연결된)에 포함된 ARCHIVED 버전은 표시 (Jira 정석)
 * - 검색 input으로 버전 이름 필터링 (대소문자 무시)
 * - 체크박스로 다중 선택/해제
 * - value에 있는 버전은 checked 상태로 표시
 * - disabled=true이면 모든 체크박스 비활성 (fail-closed)
 * - 선택된 버전은 칩으로 상단에 표시
 * - WCAG AA: aria-label, label 연결
 *
 * mutation은 route(issues.$key.tsx)가 소유한다.
 * 이 컴포넌트는 value/options/onChange/disabled만 받는 순수 presentational이다.
 */
export function VersionMultiSelect({
  variant,
  value,
  options,
  onChange,
  disabled = false,
}: VersionMultiSelectProps): JSX.Element {
  const [searchQuery, setSearchQuery] = useState('')

  const searchLabel =
    variant === 'affects'
      ? issueDetailStrings.affectsVersionsSearchPlaceholder
      : issueDetailStrings.fixVersionsSearchPlaceholder

  /**
   * 드롭다운에 표시할 버전 목록을 결정한다.
   *
   * 규칙.
   * - ARCHIVED 버전은 기본 숨김
   * - 단, value(현재 연결된)에 포함된 ARCHIVED 버전은 표시 (Jira 정석)
   * - 검색어로 필터링
   */
  const visibleOptions = options.filter((ver) => {
    if (ver.status === 'ARCHIVED' && !value.includes(ver.id)) return false
    return ver.name.toLowerCase().includes(searchQuery.toLowerCase())
  })

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

  // 현재 선택된 버전 칩 목록 — options에 있는 항목만 표시
  const selectedVersions = value
    .map((id) => options.find((ver) => ver.id === id))
    .filter((ver): ver is Version => ver !== undefined)

  return (
    <div className="flex flex-col gap-2">
      {/* 선택된 버전 칩 목록 */}
      <VersionChipList versions={selectedVersions} />

      {/* 검색 input */}
      <input
        type="text"
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
        placeholder={searchLabel}
        aria-label={searchLabel}
        value={searchQuery}
        onChange={(e) => setSearchQuery(e.target.value)}
        disabled={disabled}
      />

      {/* 버전 체크박스 목록 */}
      <VersionOptionList
        options={visibleOptions}
        selectedIds={value}
        onToggle={handleToggle}
        disabled={disabled}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// VersionChipList — 선택된 버전 칩 목록 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** VersionChipList props */
interface VersionChipListProps {
  /** 선택된 버전 목록 */
  versions: Version[]
}

/**
 * 선택된 버전을 칩으로 렌더하는 서브컴포넌트.
 * 선택이 없으면 렌더하지 않는다.
 */
function VersionChipList({ versions }: VersionChipListProps): JSX.Element | null {
  if (versions.length === 0) return null

  return (
    <div className="flex flex-wrap gap-1" data-testid="version-chip-list">
      {versions.map((ver) => (
        <span
          key={ver.id}
          data-testid="version-chip"
          className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium"
        >
          {ver.name}
        </span>
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// VersionOptionList — 체크박스 목록 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** VersionOptionList props */
interface VersionOptionListProps {
  /** 표시할 버전 옵션 목록 (필터 적용 후) */
  options: Version[]
  /** 현재 선택된 UUID 목록 */
  selectedIds: string[]
  /** 체크박스 토글 콜백 */
  onToggle: (id: string) => void
  /** 전체 비활성화 여부 */
  disabled: boolean
}

/**
 * 버전 체크박스 목록 서브컴포넌트.
 * - 각 버전을 label + checkbox 쌍으로 렌더
 * - WCAG AA: label 연결로 체크박스가 getByRole('checkbox', {name}) 으로 탐색 가능
 * - max-h + overflow-y-auto로 스크롤 지원
 */
function VersionOptionList({
  options,
  selectedIds,
  onToggle,
  disabled,
}: VersionOptionListProps): JSX.Element {
  return (
    <ul className="flex flex-col gap-0.5 max-h-48 overflow-y-auto">
      {options.map((ver) => {
        const checkboxId = `version-checkbox-${ver.id}`
        const isChecked = selectedIds.includes(ver.id)

        return (
          <li key={ver.id}>
            <label
              htmlFor={checkboxId}
              className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted cursor-pointer has-[:disabled]:opacity-40 has-[:disabled]:cursor-not-allowed"
            >
              <input
                type="checkbox"
                id={checkboxId}
                aria-label={ver.name}
                checked={isChecked}
                onChange={() => onToggle(ver.id)}
                disabled={disabled}
                className="rounded border-input focus:ring-2 focus:ring-ring"
              />
              <span>{ver.name}</span>
            </label>
          </li>
        )
      })}
    </ul>
  )
}
