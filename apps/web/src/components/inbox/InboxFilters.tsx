// 알림 보관함 검색 필터 컴포넌트 — 텍스트/발신자/기간 필터 제공 (FR-UX-03)
import * as React from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchUsers, fetchUsersByIds } from '@/api/users'
import type { UserSummary } from '@/api/users'
import type { InboxFilters } from '@/api/inbox'
import { inboxLabels } from '@/i18n/inbox-labels'
import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/use-debounce'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 발신자 자동완성 검색 debounce 지연 시간 (ms) */
const SENDER_DEBOUNCE_MS = 250

/** 발신자 자동완성 드롭다운 listbox ID — aria-controls 연결용 */
const SENDER_LISTBOX_ID = 'inbox-filters-sender-listbox'

/** 발신자 입력창 최소 쿼리 길이 — 이 길이 이상일 때 API 호출 */
const SENDER_MIN_QUERY_LENGTH = 1

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 요약의 표시 이름을 반환한다.
 * displayName이 있으면 우선, 없으면 username을 사용한다.
 *
 * @param user 사용자 요약 객체
 * @returns 표시할 이름 문자열
 */
function resolveDisplayName(user: UserSummary): string {
  return user.displayName ?? user.username
}

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** InboxFilters 컴포넌트 props */
export interface InboxFiltersProps {
  /** 현재 검색 필터 상태 (controlled) */
  filters: InboxFilters
  /** 필터 변경 콜백 — 부모가 실제 필터 상태를 관리한다 */
  onFiltersChange: (next: InboxFilters) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 보관함 검색 필터 컴포넌트.
 *
 * - 텍스트 입력(q): 제목/본문 검색어
 * - 발신자 자동완성: 이름 입력 → fetchUsers 후보 표시 → 선택 시 senderId(UUID) 설정.
 *   사용자가 UUID를 직접 입력해도 senderId가 설정되지 않는다(이름 검색→선택만 허용).
 * - 기간: from/to date 입력 (ISO-8601 날짜 문자열)
 *
 * REFACTOR 단계에서 발신자 자동완성에 debounce가 적용된다.
 *
 * @param filters 현재 검색 필터 상태
 * @param onFiltersChange 필터 변경 콜백
 */
export function InboxFilters({ filters, onFiltersChange }: InboxFiltersProps) {
  // 발신자 검색 입력 텍스트 (선택 전 입력 중인 값, 선택 완료 시 표시 이름)
  const [senderQuery, setSenderQuery] = React.useState('')
  // 발신자 자동완성 드롭다운 열림 여부
  const [senderDropdownOpen, setSenderDropdownOpen] = React.useState(false)
  // 현재 선택된 발신자 사용자 (senderId가 있을 때 이름 표시용)
  const [selectedSender, setSelectedSender] = React.useState<UserSummary | null>(null)
  // 드롭다운 키보드 포커스 인덱스
  const [activeSenderIndex, setActiveSenderIndex] = React.useState(-1)

  // SENDER_DEBOUNCE_MS 지연 후 API 요청 — 매 키스트로크마다 요청이 발생하지 않도록 방지
  const debouncedSenderQuery = useDebounce(senderQuery, SENDER_DEBOUNCE_MS)

  // senderId가 이미 있지만 selectedSender가 없는 경우 이름을 조회한다
  // (초기 filters.senderId가 있는 경우 — 부모에서 외부 상태로 전달될 때)
  const needsInitialSenderLookup =
    filters.senderId !== undefined && selectedSender === null && senderQuery === ''

  const { data: senderCandidates = [] } = useQuery({
    queryKey: ['inbox', 'sender-search', needsInitialSenderLookup ? filters.senderId : null, debouncedSenderQuery],
    queryFn: async (): Promise<UserSummary[]> => {
      if (needsInitialSenderLookup && filters.senderId !== undefined) {
        // senderId로 초기 사용자 정보 조회
        return fetchUsersByIds([filters.senderId])
      }
      if (debouncedSenderQuery.length >= SENDER_MIN_QUERY_LENGTH) {
        return fetchUsers(debouncedSenderQuery)
      }
      return []
    },
    enabled: needsInitialSenderLookup || debouncedSenderQuery.length >= SENDER_MIN_QUERY_LENGTH,
    staleTime: 30_000,
  })

  // 초기 senderId 조회 결과로 selectedSender 초기화
  React.useEffect(() => {
    if (needsInitialSenderLookup && senderCandidates.length > 0) {
      const found = senderCandidates.find((u) => u.id === filters.senderId)
      if (found !== undefined) {
        setSelectedSender(found)
        setSenderQuery(resolveDisplayName(found))
      }
    }
  }, [needsInitialSenderLookup, senderCandidates, filters.senderId])

  // 후보 드롭다운 표시 여부 — 선택 완료 상태이거나 쿼리가 짧으면 숨김
  const showSenderDropdown =
    senderDropdownOpen &&
    senderCandidates.length > 0 &&
    selectedSender === null &&
    debouncedSenderQuery.length >= SENDER_MIN_QUERY_LENGTH

  // ─────────────────────────────────────────────────────────────────────────
  // 텍스트 검색 핸들러
  // ─────────────────────────────────────────────────────────────────────────

  function handleQChange(e: React.ChangeEvent<HTMLInputElement>) {
    const value = e.target.value
    const next: InboxFilters = {
      ...filters,
      q: value === '' ? undefined : value,
    }
    onFiltersChange(next)
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 발신자 자동완성 핸들러
  // ─────────────────────────────────────────────────────────────────────────

  function handleSenderInputChange(e: React.ChangeEvent<HTMLInputElement>) {
    const value = e.target.value
    setSenderQuery(value)
    // 입력 중에는 선택 해제
    if (selectedSender !== null) {
      setSelectedSender(null)
      onFiltersChange({ ...filters, senderId: undefined })
    }
    setActiveSenderIndex(-1)
  }

  function handleSenderSelect(user: UserSummary) {
    setSelectedSender(user)
    setSenderQuery(resolveDisplayName(user))
    setSenderDropdownOpen(false)
    setActiveSenderIndex(-1)
    onFiltersChange({ ...filters, senderId: user.id })
  }

  function handleSenderClear() {
    setSelectedSender(null)
    setSenderQuery('')
    setSenderDropdownOpen(false)
    setActiveSenderIndex(-1)
    onFiltersChange({ ...filters, senderId: undefined })
  }

  function handleSenderKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveSenderIndex((prev) =>
        senderCandidates.length === 0 ? -1 : (prev + 1) % senderCandidates.length,
      )
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveSenderIndex((prev) =>
        senderCandidates.length === 0
          ? -1
          : (prev - 1 + senderCandidates.length) % senderCandidates.length,
      )
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const candidate = senderCandidates[activeSenderIndex]
      if (activeSenderIndex >= 0 && candidate !== undefined) {
        handleSenderSelect(candidate)
      }
    } else if (e.key === 'Escape') {
      setSenderDropdownOpen(false)
      setActiveSenderIndex(-1)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 기간 핸들러
  // ─────────────────────────────────────────────────────────────────────────

  function handleFromChange(e: React.ChangeEvent<HTMLInputElement>) {
    const value = e.target.value
    onFiltersChange({ ...filters, from: value === '' ? undefined : value })
  }

  function handleToChange(e: React.ChangeEvent<HTMLInputElement>) {
    const value = e.target.value
    onFiltersChange({ ...filters, to: value === '' ? undefined : value })
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 렌더
  // ─────────────────────────────────────────────────────────────────────────

  return (
    <div
      className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-end"
      data-testid="inbox-filters"
    >
      {/* 텍스트 검색 */}
      <div className="flex-1 min-w-40">
        <input
          type="text"
          value={filters.q ?? ''}
          onChange={handleQChange}
          placeholder={inboxLabels.search.placeholder}
          aria-label={inboxLabels.search.placeholder}
          className={cn(
            'h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm',
            'placeholder:text-muted-foreground',
            'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
          )}
          data-testid="inbox-filter-q"
        />
      </div>

      {/* 발신자 자동완성 */}
      <div className="relative flex-1 min-w-40">
        {selectedSender !== null ? (
          /* 발신자 선택 완료 상태 — 이름 + 해제 버튼 */
          <div className="flex h-9 items-center gap-1 rounded-md border border-input bg-transparent px-3 text-sm">
            <span className="flex-1 truncate">{resolveDisplayName(selectedSender)}</span>
            <button
              type="button"
              onClick={handleSenderClear}
              aria-label="발신자 선택 해제"
              className="shrink-0 text-muted-foreground hover:text-foreground"
              data-testid="inbox-filter-sender-clear"
            >
              ×
            </button>
          </div>
        ) : (
          /* 발신자 검색 입력 */
          <input
            type="text"
            value={senderQuery}
            onChange={handleSenderInputChange}
            onFocus={() => setSenderDropdownOpen(true)}
            onBlur={() => {
              // 클릭 이벤트보다 blur가 먼저 발생하므로 150ms 후 닫음
              setTimeout(() => setSenderDropdownOpen(false), 150)
            }}
            onKeyDown={handleSenderKeyDown}
            placeholder={inboxLabels.search.sender}
            aria-label={inboxLabels.search.sender}
            aria-autocomplete="list"
            aria-controls={showSenderDropdown ? SENDER_LISTBOX_ID : undefined}
            aria-expanded={showSenderDropdown}
            aria-activedescendant={
              activeSenderIndex >= 0 ? `sender-option-${activeSenderIndex}` : undefined
            }
            className={cn(
              'h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm',
              'placeholder:text-muted-foreground',
              'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
            )}
            data-testid="inbox-filter-sender"
          />
        )}

        {/* 발신자 자동완성 드롭다운 */}
        {showSenderDropdown && (
          <ul
            id={SENDER_LISTBOX_ID}
            role="listbox"
            aria-label="발신자 자동완성"
            className={cn(
              'absolute z-50 mt-1 w-full max-h-48 overflow-y-auto',
              'rounded-md border border-border bg-popover p-1 shadow-md',
            )}
            data-testid="inbox-filter-sender-dropdown"
          >
            {senderCandidates.map((user, index) => (
              <li
                key={user.id}
                id={`sender-option-${index}`}
                role="option"
                aria-selected={index === activeSenderIndex}
                aria-label={resolveDisplayName(user)}
                onMouseDown={(e) => {
                  // blur보다 먼저 실행 — blur의 setTimeout을 유효하게 유지
                  e.preventDefault()
                  handleSenderSelect(user)
                }}
                className={cn(
                  'relative flex cursor-pointer select-none items-center rounded-sm px-2 py-1.5 text-sm outline-none',
                  index === activeSenderIndex
                    ? 'bg-accent text-accent-foreground'
                    : 'hover:bg-accent hover:text-accent-foreground',
                )}
                data-testid={`inbox-filter-sender-option-${user.id}`}
              >
                {resolveDisplayName(user)}
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* 기간 시작일 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="inbox-filter-from"
          className="text-xs text-muted-foreground"
        >
          {inboxLabels.search.dateFrom}
        </label>
        <input
          id="inbox-filter-from"
          type="date"
          value={filters.from ?? ''}
          onChange={handleFromChange}
          className={cn(
            'h-9 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm',
            'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
          )}
          data-testid="inbox-filter-from"
        />
      </div>

      {/* 기간 종료일 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="inbox-filter-to"
          className="text-xs text-muted-foreground"
        >
          {inboxLabels.search.dateTo}
        </label>
        <input
          id="inbox-filter-to"
          type="date"
          value={filters.to ?? ''}
          onChange={handleToChange}
          className={cn(
            'h-9 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm',
            'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring',
          )}
          data-testid="inbox-filter-to"
        />
      </div>
    </div>
  )
}
