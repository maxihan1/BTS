// 이슈 유형 선택 셀렉터 (IssueMetaPanel 분해 A)
import type { JSX } from 'react'
import type { IssueTypeResponse } from '@/api/issue-types'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueTypeSelect props */
export interface IssueTypeSelectProps {
  /** 현재 선택된 typeId — props 파생, useState 초기화 금지 */
  value: number
  /** 셀렉터에 표시할 타입 목록 */
  availableTypes: IssueTypeResponse[]
  /** 현재 typeId — 변경 여부 비교용 */
  currentTypeId: number
  /** 타입 변경 콜백 — number typeId 전달 */
  onTypeChange: (typeId: number) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 유형 셀렉터 컴포넌트.
 *
 * - value는 부모 props에서 파생(issue.typeId) — stale key prop 회귀 방지
 * - 현재 값과 동일한 선택은 onTypeChange를 호출하지 않는다.
 * - WCAG AA: min-h-[44px] 터치 타깃, aria-label
 */
export function IssueTypeSelect({
  value,
  availableTypes,
  currentTypeId,
  onTypeChange,
}: IssueTypeSelectProps): JSX.Element {
  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const selectedId = Number(e.target.value)
    if (selectedId !== currentTypeId) {
      onTypeChange(selectedId)
    }
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={value}
      onChange={handleChange}
      aria-label={issueDetailStrings.typeSelectLabel}
    >
      {availableTypes.map((type) => (
        <option key={type.id} value={type.id}>
          {type.name}
        </option>
      ))}
    </select>
  )
}
