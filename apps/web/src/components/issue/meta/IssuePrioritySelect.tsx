// 이슈 우선순위 선택 셀렉터 (IssueMetaPanel 분해 A, FR-IS-04)
import type { JSX } from 'react'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 셀렉터에 노출할 우선순위 값 목록 (1=가장 높음 ~ 5=가장 낮음) */
const PRIORITIES = [1, 2, 3, 4, 5] as const

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssuePrioritySelect props */
export interface IssuePrioritySelectProps {
  /** 현재 우선순위 — issue.priority props 파생, useState 초기화 금지 */
  value: number
  /** 우선순위 변경 콜백 — number 전달 */
  onPriorityChange: (priority: number) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 우선순위 셀렉터.
 *
 * - value는 부모 props에서 파생(issue.priority) — stale key prop 회귀 방지
 * - 1(가장 높음) ~ 5(가장 낮음) 옵션
 * - 변경 즉시 onPriorityChange 호출
 * - WCAG AA: min-h-[44px], aria-label
 */
export function IssuePrioritySelect({ value, onPriorityChange }: IssuePrioritySelectProps): JSX.Element {
  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    onPriorityChange(Number(e.target.value))
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={value}
      onChange={handleChange}
      aria-label={issueDetailStrings.prioritySelectLabel}
    >
      {PRIORITIES.map((p) => (
        <option key={p} value={p}>
          {issueDetailStrings.priorityNames[p]}
        </option>
      ))}
    </select>
  )
}
