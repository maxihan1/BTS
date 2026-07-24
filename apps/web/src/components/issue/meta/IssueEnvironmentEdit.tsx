// 이슈 환경(environment) 편집 컴포넌트 (IssueMetaPanel 분해 A, FR-IS-04)
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import { Button } from '@/components/ui/button'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueEnvironmentEdit props */
export interface IssueEnvironmentEditProps {
  /** 현재 환경 값 — null이면 빈 문자열로 표시 */
  value: string | null
  /** 저장 콜백 — 편집된 문자열 전달 */
  onSave: (environment: string) => void
  /** 수정 권한 — false이면 저장 버튼 disabled (fail-closed) */
  canEdit: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 환경 편집 컴포넌트.
 *
 * - 로컬 상태로 편집, 저장 버튼 클릭 시 onSave 호출
 * - issue.environment props가 바뀌면(refetch) 로컬 상태도 동기화 (stale 방지)
 * - WCAG AA: aria-label
 */
export function IssueEnvironmentEdit({ value, onSave, canEdit }: IssueEnvironmentEditProps): JSX.Element {
  const [draft, setDraft] = useState(value ?? '')

  // props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
  const prevValueRef = useRef(value)
  useEffect(() => {
    if (prevValueRef.current !== value) {
      prevValueRef.current = value
      setDraft(value ?? '')
    }
  }, [value])

  return (
    <div className="flex flex-col gap-1.5">
      <textarea
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-ring"
        rows={3}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder={issueDetailStrings.environmentPlaceholder}
        aria-label={issueDetailStrings.environmentLabel}
        maxLength={1000}
      />
      <Button
        variant="outline"
        size="sm"
        className="self-end min-h-[44px]"
        onClick={() => onSave(draft)}
        disabled={!canEdit}
        aria-label={issueDetailStrings.environmentSaveButton}
        data-testid="environment-save"
      >
        {issueDetailStrings.environmentSaveButton}
      </Button>
    </div>
  )
}
