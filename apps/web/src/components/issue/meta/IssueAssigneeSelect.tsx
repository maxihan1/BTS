// 이슈 담당자 선택 셀렉터 (IssueMetaPanel 분해 B, FR-IS-03)
import type { JSX } from 'react'
import type { UserSummary } from '@/api/users'
import { issueDetailStrings } from '@/i18n/ko'
import { AssigneeUserList } from '@/components/issue/meta/AssigneeUserList'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueAssigneeSelect props */
export interface IssueAssigneeSelectProps {
  /**
   * 현재 담당자 UUID — issue.assigneeId props 파생, useState 초기화 금지 (stale key prop 회귀 방지).
   * null이면 미할당.
   */
  value: string | null
  /**
   * 현재 담당자 UserSummary — useUsersByIds로 별도 조회한 값 (C1 버그 수정).
   * 검색결과(users)와 분리해 담당자 이름을 안정적으로 표시한다.
   * null이면 "미지정" 표시.
   */
  currentAssignee: UserSummary | null
  /** 사용자 검색 결과 목록 — 드롭다운 후보 전용 */
  users: UserSummary[]
  /** 검색어 변경 콜백 */
  onSearch: (query: string) => void
  /** 담당자 변경 콜백 — UUID 또는 null(해제) */
  onAssigneeChange: (userId: string | null) => void
  /**
   * 수정 권한 여부 — false이면 검색 input·해제 버튼 disabled (FR-PM-02).
   * fail-closed: 권한 미확정 시 false 전달 권장.
   */
  canEdit: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 담당자 셀렉터 컴포넌트.
 *
 * - value는 부모 props에서 파생(issue.assigneeId) — stale key prop 회귀 방지
 * - currentAssignee prop으로 현재 담당자 이름 표시 (C1 버그 수정)
 *   users(검색결과)가 아닌 별도 id 조회 결과를 사용해 50건 한도 이외 담당자도 정확히 표시
 * - 미할당 시 "미지정" 텍스트 표시
 * - 검색 input: native input, onChange 시 onSearch 호출
 * - 사용자 목록(users): 드롭다운 후보 전용 — 선택 시 onAssigneeChange(id)
 * - 담당자 해제 버튼: value !== null이면 노출, 클릭 시 onAssigneeChange(null)
 * - WCAG AA: min-h-[44px], aria-label
 */
export function IssueAssigneeSelect({
  value,
  currentAssignee,
  users,
  onSearch,
  onAssigneeChange,
  canEdit,
}: IssueAssigneeSelectProps): JSX.Element {
  /** 현재 담당자 표시 이름 — displayName 우선, 없으면 username */
  function getDisplayName(user: UserSummary): string {
    return user.displayName ?? user.username
  }

  return (
    <div className="flex flex-col gap-1.5">
      {/* 현재 담당자 표시 — currentAssignee prop 기반 (C1 수정: users 검색결과 의존 제거) */}
      <div className="flex items-center justify-between gap-1">
        <span className="text-sm font-medium truncate" data-testid="assignee-current-name">
          {currentAssignee !== null
            ? getDisplayName(currentAssignee)
            : issueDetailStrings.assigneeUnassigned}
        </span>
        {/* 담당자 해제 버튼 — 할당된 경우에만 노출, canEdit=false이면 disabled (FR-PM-02) */}
        {value !== null && (
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={() => onAssigneeChange(null)}
            disabled={!canEdit}
            className="min-h-[44px] shrink-0 px-1 text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:cursor-not-allowed"
            aria-label={issueDetailStrings.assigneeUnassignButton}
          >
            {issueDetailStrings.assigneeUnassignButton}
          </Button>
        )}
      </div>

      {/* 검색 input — canEdit=false이면 disabled (FR-PM-02) */}
      <input
        type="text"
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
        placeholder={issueDetailStrings.assigneeSearchPlaceholder}
        aria-label={issueDetailStrings.assigneeSearchPlaceholder}
        disabled={!canEdit}
        onChange={(e) => onSearch(e.target.value)}
        // ★이 셀렉터가 <form> 안에 들어가면(이슈 생성 폼, FR-UX-09 F2) Enter 가 HTML
        // 암묵적 제출을 일으켜 검색하려던 사용자가 이슈를 만들어 버린다. 여기는 값을 넣는
        // 칸이 아니라 **검색창**이라 Enter 에 제출 의미가 없다. 형제 LabelAutocompleteInput
        // 도 같은 이유로 Enter 를 가로챈다. 폼 밖(이슈 상세)에서는 원래 무동작이라 무변화.
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.preventDefault()
        }}
      />

      {/* 검색 결과 사용자 목록 */}
      {users.length > 0 && (
        <AssigneeUserList users={users} onSelect={onAssigneeChange} />
      )}
    </div>
  )
}
