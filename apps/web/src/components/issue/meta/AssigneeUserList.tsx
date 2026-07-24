// 담당자 검색 결과 사용자 목록 컴포넌트 (IssueMetaPanel 분해 B, FR-IS-03)
import type { JSX } from 'react'
import type { UserSummary } from '@/api/users'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** AssigneeUserList props */
export interface AssigneeUserListProps {
  /** 사용자 목록 */
  users: UserSummary[]
  /** 선택 콜백 */
  onSelect: (userId: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 담당자 검색 결과 사용자 목록 컴포넌트.
 * - 각 사용자를 버튼으로 렌더
 * - displayName 우선, 없으면 username 표시
 * - WCAG AA: min-h-[44px]
 */
export function AssigneeUserList({ users, onSelect }: AssigneeUserListProps): JSX.Element {
  return (
    <ul className="flex flex-col gap-0.5 max-h-48 overflow-y-auto">
      {users.map((user) => {
        const displayName = user.displayName ?? user.username
        return (
          <li key={user.id}>
            <button
              type="button"
              onClick={() => onSelect(user.id)}
              className="w-full text-left text-sm px-2 py-1.5 rounded-md hover:bg-muted focus:outline-none focus:ring-1 focus:ring-ring min-h-[44px]"
              aria-label={displayName}
            >
              {displayName}
            </button>
          </li>
        )
      })}
    </ul>
  )
}
