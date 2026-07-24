// 이슈 영향도 선택 셀렉터 (IssueMetaPanel 분해 A, FR-IS-04)
import type { JSX } from 'react'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 셀렉터에 노출할 영향도 값 목록 (1=높음 ~ 3=낮음) */
const IMPACTS = [1, 2, 3] as const

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueImpactSelect props */
export interface IssueImpactSelectProps {
  /** 현재 영향도 — null이면 미지정. issue.impact props 파생, useState 초기화 금지 */
  value: number | null
  /** 영향도 변경 콜백 — number 전달 */
  onImpactChange: (impact: number) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 영향도 셀렉터.
 *
 * - value=null → 미지정 옵션 활성, 셀렉터 value=''
 * - value 설정 후 → 미지정 옵션 disabled (클리어 불가 백엔드 제약)
 * - 1(높음) ~ 3(낮음) 옵션
 * - 변경 즉시 onImpactChange 호출
 * - WCAG AA: min-h-[44px], aria-label
 */
export function IssueImpactSelect({ value, onImpactChange }: IssueImpactSelectProps): JSX.Element {
  const isUnset = value === null

  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const selected = e.target.value
    if (selected === '') return
    onImpactChange(Number(selected))
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={isUnset ? '' : value}
      onChange={handleChange}
      aria-label={issueDetailStrings.impactSelectLabel}
    >
      <option value="" disabled={!isUnset}>
        {issueDetailStrings.impactUnset}
      </option>
      {IMPACTS.map((i) => (
        <option key={i} value={i}>
          {issueDetailStrings.impactNames[i]}
        </option>
      ))}
    </select>
  )
}
