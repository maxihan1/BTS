// 이슈 생성 폼 「배정」 그룹 — 담당자·우선순위·라벨 (FR-UX-09 F2, design 리뷰 D7)
import type { JSX } from 'react'
import { IssueAssigneeSelect } from '@/components/issue/meta/IssueAssigneeSelect'
import { IssuePrioritySelect } from '@/components/issue/meta/IssuePrioritySelect'
import { LabelChipsEditor } from '@/components/issue/meta/LabelChipsEditor'
import { issueCreateStrings, issueDetailStrings } from '@/i18n/ko'
import type { AssigneePicker } from '@/components/issue/create/use-assignee-picker'

/** IssueCreateAssignmentFields props */
export interface IssueCreateAssignmentFieldsProps {
  /** 담당자 3-state · 검색 · 표시 스냅샷 묶음 */
  assignee: AssigneePicker
  priority: number
  onPriorityChange: (priority: number) => void
  labels: string[]
  onLabelsChange: (labels: string[]) => void
}

/**
 * 「배정」 필드 그룹.
 *
 * 셋 다 이슈 상세의 `meta/` 컴포넌트를 그대로 재사용한다 — 같은 뜻의 입력이 화면마다
 * 다르게 생기면 사용자가 다시 배워야 한다.
 *
 * ★라벨만 `IssueLabelsEdit` 대신 [LabelChipsEditor] 를 쓴다. 전자는 「저장」 버튼을
 * 내장해서 폼 안에 넣으면 「이슈 생성」과 겹친다 (learnings 2026-05-31).
 */
export function IssueCreateAssignmentFields({
  assignee,
  priority,
  onPriorityChange,
  labels,
  onLabelsChange,
}: IssueCreateAssignmentFieldsProps): JSX.Element {
  return (
    <>
      <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {issueCreateStrings.groupAssignmentLabel}
      </p>

      {/* 담당자 — meta/IssueAssigneeSelect 재사용 (FR-6). 3-state 는 assignee.intent 가 쥔다 */}
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium leading-none">
          {issueDetailStrings.assigneeLabel}
        </span>
        <IssueAssigneeSelect
          value={assignee.intent ?? null}
          currentAssignee={assignee.current}
          users={assignee.candidates}
          onSearch={assignee.onSearch}
          onAssigneeChange={assignee.onChange}
          canEdit
        />
        {/* 화면상 「비어 있음」이 두 뜻(자동 배정 / 미할당 확정)이라 안내가 필요하다 */}
        <p className="text-xs text-muted-foreground">{issueCreateStrings.assigneeAutoHint}</p>
      </div>

      {/* 우선순위 — meta/IssuePrioritySelect 재사용 (FR-7) */}
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium leading-none">
          {issueDetailStrings.priorityLabel}
        </span>
        <IssuePrioritySelect value={priority} onPriorityChange={onPriorityChange} />
      </div>

      {/* 라벨 — 저장 버튼 없는 LabelChipsEditor (FR-8) */}
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium leading-none">{issueDetailStrings.labelsLabel}</span>
        <LabelChipsEditor value={labels} onChange={onLabelsChange} />
      </div>
    </>
  )
}
