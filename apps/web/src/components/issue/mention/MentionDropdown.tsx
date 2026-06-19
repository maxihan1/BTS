// 멘션 자동완성 드롭다운 UI 컴포넌트 — FR-MN-02 Task 2
import type { JSX } from 'react'
import { cn } from '@/lib/utils'
import type { UserSummary } from '@/api/users'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 드롭다운 listbox 기본 id */
const DEFAULT_LISTBOX_ID = 'mention-autocomplete-listbox'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** MentionDropdown 컴포넌트 props */
export interface MentionDropdownProps {
  /** 표시할 사용자 후보 목록 */
  candidates: UserSummary[]
  /** 현재 키보드 포커스 인덱스 (-1 = 없음) */
  activeIndex: number
  /** 후보 선택 콜백 */
  onSelect: (candidate: UserSummary) => void
  /** listbox id (기본값: 'mention-autocomplete-listbox') */
  listboxId?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// MentionDropdown
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 멘션 자동완성 드롭다운 컴포넌트.
 *
 * - 직접 DOM ul/li로 구성해 jsdom 호환성 보장 (ResizeObserver 미사용).
 * - role="listbox"/role="option" ARIA 패턴 준수.
 * - onMouseDown preventDefault → blur보다 먼저 onSelect 실행.
 * - 각 후보: displayName(있으면) + @username 함께 표시.
 *
 * @param candidates 표시할 사용자 후보 목록
 * @param activeIndex 현재 키보드 포커스 인덱스 (-1 = 없음)
 * @param onSelect 후보 선택 콜백
 * @param listboxId listbox 요소 id
 */
export function MentionDropdown({
  candidates,
  activeIndex,
  onSelect,
  listboxId = DEFAULT_LISTBOX_ID,
}: MentionDropdownProps): JSX.Element {
  return (
    <ul
      id={listboxId}
      role="listbox"
      aria-label="멘션 사용자 자동완성"
      className="absolute z-50 mt-1 w-full max-h-48 overflow-y-auto rounded-md border border-border bg-popover p-1 shadow-md"
      data-testid="mention-dropdown"
    >
      {candidates.map((candidate, index) => {
        const displayText = candidate.displayName ?? candidate.username
        const isActive = index === activeIndex
        return (
          <li
            key={candidate.id}
            id={`${listboxId}-option-${index}`}
            role="option"
            aria-selected={isActive}
            onMouseDown={(e) => {
              // blur 이벤트보다 먼저 실행되도록 기본 동작 차단
              e.preventDefault()
              onSelect(candidate)
            }}
            className={cn(
              'relative flex cursor-pointer select-none items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none',
              isActive
                ? 'bg-accent text-accent-foreground'
                : 'hover:bg-accent hover:text-accent-foreground',
            )}
            data-testid={`mention-option-${candidate.username}`}
          >
            <span className="font-medium">{displayText}</span>
            <span className="text-muted-foreground text-xs">@{candidate.username}</span>
          </li>
        )
      })}
    </ul>
  )
}
