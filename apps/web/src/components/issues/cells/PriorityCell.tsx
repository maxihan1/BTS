// 이슈 목록 우선순위 셀 — 클릭해 그 자리에서 바꾼다 (FR-UX-11 F9)
import type { JSX } from 'react'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { CELL_OPTION_CLASS } from './EditableCell'

/** 선택 가능한 우선순위 (1=가장 높음 ~ 5=가장 낮음) — IssuePrioritySelect 와 동일 집합 */
const PRIORITIES = [1, 2, 3, 4, 5] as const

/** PriorityCellEditor props */
export interface PriorityCellEditorProps {
  /** 현재 우선순위 — props 파생, useState 초기화 금지 (stale state 회귀 방지) */
  value: number
  /** 수정 권한 여부 — false 면 전 선택지 disabled (fail-closed) */
  canEdit: boolean
  /** 저장 진행 중 — true 면 중복 제출을 막기 위해 disabled (NFR3) */
  isSaving: boolean
  /** 우선순위 변경 콜백 — number 전달 */
  onChange: (priority: number) => void
}

/**
 * popover 안에 뜨는 우선순위 선택 목록.
 *
 * 상세 화면의 `IssuePrioritySelect` 는 `min-h-[44px]`·`w-full` 네이티브 `<select>` 라
 * 목록 셀 popover 안에서는 과하다. 값 집합(1~5)과 라벨(`priorityNames`)은 **같은 정본**을
 * 쓰되 표현만 목록에 맞춘다.
 *
 * 조립(`EditableCell` 로 감싸기)은 `IssueColumnRenderContext` 가 확정된 뒤 붙인다 —
 * 이 컴포넌트는 순수 프레젠테이션이라 조회 훅을 갖지 않는다.
 *
 * @param props 현재 값 · 권한 · 저장 상태 · 변경 콜백
 * @returns 우선순위 선택 버튼 목록
 */
export function PriorityCellEditor({
  value,
  canEdit,
  isSaving,
  onChange,
}: PriorityCellEditorProps): JSX.Element {
  return (
    <div className="flex flex-col gap-0.5">
      {!canEdit && <p className="px-2 py-1 text-xs text-(--text-subtle)">편집 권한이 없습니다.</p>}
      {PRIORITIES.map((p) => (
        <Button
          key={p}
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canEdit || isSaving}
          aria-current={p === value ? 'true' : undefined}
          onClick={() => onChange(p)}
          className={CELL_OPTION_CLASS}
        >
          {issueDetailStrings.priorityNames[p]}
        </Button>
      ))}
    </div>
  )
}
