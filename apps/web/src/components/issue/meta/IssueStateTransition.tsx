// 이슈 상태 전이 셀렉터 (IssueMetaPanel 분해 B, FR-IS-01)
import type { JSX } from 'react'
import type { IssueTransition } from '@/api/issues'
// IssueMetaPanel.tsx가 정본으로 export하는 타입을 재사용 — type-only import라 컴파일 시
// 소거되므로 IssueMetaPanel.tsx가 이 파일을 값으로 import해도 런타임 순환 문제는 없다
// (meta/IssueCustomFieldsEdit.tsx의 isFieldHidden/isFieldDisabled 재사용 선례 동형).
import type { TransitionUnavailableReason } from '@/components/issue/IssueMetaPanel'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueStateTransition props */
export interface IssueStateTransitionProps {
  /** 현재 상태에서 가용한 전이 목록 */
  transitions: IssueTransition[]
  /** 전이 실행 콜백 — toStateKey 전달 */
  onTransition: (toStateKey: string) => void
  /** 전이 진행 중 여부 — true 시 셀렉터 disabled (NFR3) */
  isTransitioning: boolean
  /** 전이 컨트롤을 노출할 수 없는 사유 (스펙 E5) */
  unavailableReason: TransitionUnavailableReason
  /** 제어값 — 전이 시도 후 리셋에 사용 (C1 회귀 방지) */
  selectedValue: string
  /** 제어값 변경 콜백 */
  onSelectedValueChange: (value: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상태 전이 셀렉터 컴포넌트.
 *
 * - 가용전이 0건 + unavailableReason='no-workflow' → 미설정 안내 (스펙 E5 S5)
 * - 가용전이 0건 + unavailableReason='terminal'|null → "더 진행할 전이 없음" 안내 (스펙 E5 S6)
 * - 가용전이 있으면 네이티브 select — IssueTypeSelect 동일 패턴
 * - 첫 옵션은 placeholder(비선택 상태), 전이 선택 시 onTransition(toStateKey) 호출
 * - isTransitioning=true → disabled (중복클릭 방지, NFR3)
 * - WCAG AA: min-h-[44px] 터치 타깃, aria-label
 */
export function IssueStateTransition({
  transitions,
  onTransition,
  isTransitioning,
  unavailableReason,
  selectedValue,
  onSelectedValueChange,
}: IssueStateTransitionProps): JSX.Element {
  // 가용전이 0건 → 사유에 따라 안내문구 분기 (스펙 E5)
  if (transitions.length === 0) {
    if (unavailableReason === 'no-workflow') {
      return (
        <p className="text-xs text-muted-foreground mt-1">
          {issueDetailStrings.transitionWorkflowNotConfiguredError}
        </p>
      )
    }
    return (
      <p className="text-xs text-muted-foreground mt-1">
        {issueDetailStrings.noTransitionsAvailable}
      </p>
    )
  }

  function handleChange(e: React.ChangeEvent<HTMLSelectElement>) {
    const toStateKey = e.target.value
    // placeholder 옵션 선택 무시
    if (toStateKey === '') return
    onSelectedValueChange(toStateKey)
    onTransition(toStateKey)
  }

  return (
    <select
      className="w-full rounded-md border border-input bg-background px-2 text-sm min-h-[44px] focus:outline-none focus:ring-2 focus:ring-ring"
      value={selectedValue}
      onChange={handleChange}
      disabled={isTransitioning}
      aria-label={issueDetailStrings.transitionSelectLabel}
    >
      <option value="" disabled>
        {issueDetailStrings.transitionSelectLabel}
      </option>
      {transitions.map((t) => (
        <option key={t.key} value={t.toStateKey}>
          {t.name}
        </option>
      ))}
    </select>
  )
}
