// 이슈 목록 우선순위 셀 — 클릭해 그 자리에서 바꾼다 (FR-UX-11 F9)
import type { JSX } from 'react'
import { useState } from 'react'
import type { QueryKey } from '@tanstack/react-query'
import type { IssueResponse } from '@/api/issues'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { useIssueListCellField } from '@/hooks/use-issue-list-cell-field'
import { useIssuePermissions } from '@/hooks/use-issue-permissions'
import { CELL_OPTION_CLASS, EditableCell } from './EditableCell'

/** 선택 가능한 우선순위 (1=가장 높음 ~ 5=가장 낮음) — IssuePrioritySelect 와 동일 집합 */
const PRIORITIES = [1, 2, 3, 4, 5] as const

/**
 * 닫힌 상태에서 보이는 우선순위 텍스트.
 *
 * ★`issue-columns.ts` 의 기존 `renderPriorityCell` 마크업을 **글자 단위로** 옮긴 것이다.
 * 편집 비활성(`ctx.edit` 부재) 경로도 이 컴포넌트를 소비하므로 두 경로가 같은 DOM 을
 * 낸다 — 이것이 회귀 0 의 장치다.
 *
 * @param props 표시할 우선순위 이름 (백엔드 `priorityName`)
 * @returns 우선순위 텍스트 span
 */
export function PriorityCellDisplay({ priorityName }: { priorityName: string }): JSX.Element {
  return <span className="text-(--text-default)">{priorityName}</span>
}

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

/** PriorityCell props */
export interface PriorityCellProps {
  /** 대상 이슈 — 표시값·`expectedVersion` 의 출처 */
  issue: IssueResponse
  /** 목록 queryKey — mutation 이 이 캐시를 낙관적으로 patch 한다 */
  listQueryKey: QueryKey
}

/** PriorityCellPopoverBody props */
interface PriorityCellPopoverBodyProps {
  issue: IssueResponse
  isSaving: boolean
  onChange: (priority: number) => void
}

/**
 * popover 가 열렸을 때만 마운트된다 — 권한 조회가 여기서만 발생한다 (FR12·NFR1).
 *
 * 이 컴포넌트를 `EditableCell` **밖으로** 끌어올리면 목록 초기 렌더에서 행 수만큼
 * 권한 조회가 터진다. `IssueTable.test.tsx` 의 FR12 가드가 그 회귀를 잡는다.
 *
 * @param props 대상 이슈 · 저장 진행 여부 · 변경 콜백
 * @returns 우선순위 선택 목록
 */
function PriorityCellPopoverBody({
  issue,
  isSaving,
  onChange,
}: PriorityCellPopoverBodyProps): JSX.Element {
  const permissions = useIssuePermissions(issue.key)

  return (
    <PriorityCellEditor
      value={issue.priority}
      // fail-closed — 권한이 확정되기 전에는 false (D-6)
      canEdit={permissions.data?.permissions.UPDATE === true}
      isSaving={isSaving}
      onChange={onChange}
    />
  )
}

/**
 * 우선순위 셀 조립 — 텍스트(닫힘) + 선택 목록(열림).
 *
 * mutation 훅은 popover **밖**(이 컴포넌트)에 둔다. 저장은 popover 를 닫은 뒤 시작하므로
 * (Maxi 확정 2026-08-04) 훅이 popover 안에 있으면 mutate 직후 언마운트돼 관찰자가 사라진다.
 * `useMutation` 은 네트워크를 유발하지 않으므로 밖에 두어도 FR12(조회 지연)와 무관하다.
 *
 * @param props 대상 이슈 · 목록 queryKey
 * @returns 편집 가능한 우선순위 셀
 */
export function PriorityCell({ issue, listQueryKey }: PriorityCellProps): JSX.Element {
  const [open, setOpen] = useState(false)
  const mutation = useIssueListCellField(listQueryKey)

  /** 우선순위 선택 — 먼저 닫고 저장한다. 낙관적 patch 라 닫아도 결과가 셀에 즉시 보인다 */
  function handleChange(next: number): void {
    setOpen(false)
    mutation.mutate({
      issueKey: issue.key,
      field: 'priority',
      toPriority: next,
      expectedVersion: issue.version,
    })
  }

  return (
    <EditableCell
      open={open}
      onOpenChange={setOpen}
      // ★목록은 행이 여러 개다. 이슈 키를 접두로 붙이지 않으면 e2e strict mode 로 즉사한다
      label={`${issue.key} 우선순위 변경`}
      display={<PriorityCellDisplay priorityName={issue.priorityName} />}
    >
      <PriorityCellPopoverBody issue={issue} isSaving={mutation.isPending} onChange={handleChange} />
    </EditableCell>
  )
}
