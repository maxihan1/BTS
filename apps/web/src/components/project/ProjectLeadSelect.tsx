// 프로젝트 리드 셀렉터 — 순수 props 표현 컴포넌트 (검색/선택/미지정 지원)
import type { JSX } from 'react'
import type { UserSummary } from '../../api/users'
import { projectLeadLabels } from '../../i18n/project-lead-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ProjectLeadSelect props */
interface ProjectLeadSelectProps {
  /** 검색 결과 사용자 목록 — 드롭다운 후보 전용 */
  users: UserSummary[]
  /** 현재 리드 UserSummary — 상위에서 id 조회 후 주입. null이면 "미지정" 표시 */
  currentLead: UserSummary | null
  /** 검색어 변경 콜백 — 상위에서 debounce 처리 */
  onSearch: (query: string) => void
  /** 리드 변경 콜백 — UUID 또는 null(해제) */
  onChange: (userId: string | null) => void
  /** 수정 비활성화 여부 — true이면 input·버튼 disabled */
  disabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function getDisplayName(user: UserSummary): string {
  return user.displayName ?? user.username
}

// ─────────────────────────────────────────────────────────────────────────────
// ProjectLeadSelect
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 리드 셀렉터.
 *
 * - 순수 표현 컴포넌트 — useUsers/useUsersByIds/debounce 내부 호출 금지.
 *   상위 컴포넌트가 검색/조회 결과를 props로 주입한다.
 * - 현재 리드: currentLead prop으로 표시 (검색결과와 독립)
 * - 검색 input: onChange 시 onSearch 호출
 * - 사용자 목록: 선택 시 onChange(id)
 * - "미지정" 버튼: 클릭 시 onChange(null)
 * - WCAG AA: min-h-[44px], aria-label
 */
export const ProjectLeadSelect = ({
  users,
  currentLead,
  onSearch,
  onChange,
  disabled = false,
}: ProjectLeadSelectProps): JSX.Element => {
  const { form } = projectLeadLabels

  return (
    <div className="flex flex-col gap-1.5" aria-label={form.leadLabel}>
      {/* 현재 리드 표시 */}
      <div className="flex items-center justify-between gap-1">
        <span
          className="text-sm font-medium truncate"
          data-testid="lead-current-name"
        >
          {currentLead !== null ? getDisplayName(currentLead) : form.leadUnassigned}
        </span>

        {/* "미지정" 해제 버튼 — 리드가 지정된 경우에만 노출 */}
        {currentLead !== null && (
          <button
            type="button"
            onClick={() => onChange(null)}
            disabled={disabled}
            className="text-xs text-muted-foreground hover:text-destructive focus:outline-none focus:ring-1 focus:ring-ring min-h-[44px] px-1 shrink-0 disabled:opacity-40 disabled:cursor-not-allowed"
            aria-label={form.leadUnassigned}
          >
            {form.leadUnassigned}
          </button>
        )}
      </div>

      {/* 검색 input */}
      <input
        type="text"
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
        placeholder={form.searchLabel}
        aria-label={form.searchLabel}
        disabled={disabled}
        onChange={(e) => onSearch(e.target.value)}
      />

      {/* 검색 결과 사용자 목록 */}
      {users.length > 0 && (
        <ul className="flex flex-col gap-0.5 max-h-48 overflow-y-auto">
          {users.map((user) => {
            const name = getDisplayName(user)
            return (
              <li key={user.id}>
                <button
                  type="button"
                  onClick={() => onChange(user.id)}
                  className="w-full text-left text-sm px-2 py-1.5 rounded-md hover:bg-muted focus:outline-none focus:ring-1 focus:ring-ring min-h-[44px]"
                  aria-label={name}
                >
                  {name}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
