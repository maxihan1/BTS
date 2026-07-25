// 발신자 자동완성 컴포넌트 — 이름 검색 후 선택 시 userId(UUID)를 반환 (FR-UX-03)
import * as React from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchUsers, fetchUsersByIds } from '@/api/users'
import type { UserSummary } from '@/api/users'
import { inboxLabels } from '@/i18n/inbox-labels'
import { cn } from '@/lib/utils'
import { useDebounce } from '@/hooks/use-debounce'
import { Button } from '@/components/ui/button'

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

/** SenderAutocomplete 컴포넌트 props */
export interface SenderAutocompleteProps {
  /**
   * 현재 선택된 발신자 UUID.
   * undefined이면 미선택 상태.
   */
  selectedSenderId: string | undefined
  /**
   * 발신자 선택/해제 콜백.
   * 선택 시 userId(UUID), 해제 시 undefined를 전달한다.
   */
  onSenderChange: (userId: string | undefined) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 발신자 자동완성 컴포넌트.
 *
 * - 이름 입력 → fetchUsers debounce 후 후보 listbox 표시
 * - 후보 선택 → onSenderChange(userId) 호출
 * - 해제 버튼 클릭 → onSenderChange(undefined) 호출
 * - 초기 selectedSenderId가 있으면 fetchUsersByIds로 이름을 조회해 표시
 * - 사용자가 UUID를 직접 입력해도 senderId가 설정되지 않는다(이름 검색→선택만 허용)
 *
 * @param selectedSenderId 현재 선택된 발신자 UUID (undefined = 미선택)
 * @param onSenderChange 발신자 변경 콜백
 */
export function SenderAutocomplete({ selectedSenderId, onSenderChange }: SenderAutocompleteProps) {
  // 발신자 검색 입력 텍스트 (선택 전 입력 중인 값, 선택 완료 시 표시 이름)
  const [senderQuery, setSenderQuery] = React.useState('')
  // 발신자 자동완성 드롭다운 열림 여부
  const [senderDropdownOpen, setSenderDropdownOpen] = React.useState(false)
  // 현재 선택된 발신자 사용자 (selectedSenderId가 있을 때 이름 표시용)
  const [selectedSender, setSelectedSender] = React.useState<UserSummary | null>(null)
  // 드롭다운 키보드 포커스 인덱스
  const [activeSenderIndex, setActiveSenderIndex] = React.useState(-1)

  // SENDER_DEBOUNCE_MS 지연 후 API 요청 — 매 키스트로크마다 요청이 발생하지 않도록 방지
  const debouncedSenderQuery = useDebounce(senderQuery, SENDER_DEBOUNCE_MS)

  // selectedSenderId가 이미 있지만 selectedSender 상태가 없는 경우 이름을 조회한다
  // (초기 selectedSenderId가 있는 경우 — 부모에서 외부 상태로 전달될 때)
  const needsInitialSenderLookup =
    selectedSenderId !== undefined && selectedSender === null && senderQuery === ''

  const { data: senderCandidates = [] } = useQuery({
    queryKey: ['inbox', 'sender-search', needsInitialSenderLookup ? selectedSenderId : null, debouncedSenderQuery],
    queryFn: async (): Promise<UserSummary[]> => {
      if (needsInitialSenderLookup && selectedSenderId !== undefined) {
        // selectedSenderId로 초기 사용자 정보 조회
        return fetchUsersByIds([selectedSenderId])
      }
      if (debouncedSenderQuery.length >= SENDER_MIN_QUERY_LENGTH) {
        return fetchUsers(debouncedSenderQuery)
      }
      return []
    },
    enabled: needsInitialSenderLookup || debouncedSenderQuery.length >= SENDER_MIN_QUERY_LENGTH,
    staleTime: 30_000,
  })

  // 초기 selectedSenderId 조회 결과로 selectedSender 초기화
  React.useEffect(() => {
    if (needsInitialSenderLookup && senderCandidates.length > 0) {
      const found = senderCandidates.find((u) => u.id === selectedSenderId)
      if (found !== undefined) {
        setSelectedSender(found)
        setSenderQuery(resolveDisplayName(found))
      }
    }
  }, [needsInitialSenderLookup, senderCandidates, selectedSenderId])

  // 후보 드롭다운 표시 여부 — 선택 완료 상태이거나 쿼리가 짧으면 숨김
  const showSenderDropdown =
    senderDropdownOpen &&
    senderCandidates.length > 0 &&
    selectedSender === null &&
    debouncedSenderQuery.length >= SENDER_MIN_QUERY_LENGTH

  // ─────────────────────────────────────────────────────────────────────────
  // 핸들러
  // ─────────────────────────────────────────────────────────────────────────

  function handleSenderInputChange(e: React.ChangeEvent<HTMLInputElement>) {
    const value = e.target.value
    setSenderQuery(value)
    // 입력 중에는 선택 해제
    if (selectedSender !== null) {
      setSelectedSender(null)
      onSenderChange(undefined)
    }
    setActiveSenderIndex(-1)
  }

  function handleSenderSelect(user: UserSummary) {
    setSelectedSender(user)
    setSenderQuery(resolveDisplayName(user))
    setSenderDropdownOpen(false)
    setActiveSenderIndex(-1)
    onSenderChange(user.id)
  }

  function handleSenderClear() {
    setSelectedSender(null)
    setSenderQuery('')
    setSenderDropdownOpen(false)
    setActiveSenderIndex(-1)
    onSenderChange(undefined)
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
  // 렌더
  // ─────────────────────────────────────────────────────────────────────────

  return (
    <div className="relative flex-1 min-w-40">
      {selectedSender !== null ? (
        /* 발신자 선택 완료 상태 — 이름 + 해제 버튼 */
        <div className="flex h-9 items-center gap-1 rounded-md border border-input bg-transparent px-3 text-sm">
          <span className="flex-1 truncate">{resolveDisplayName(selectedSender)}</span>
          {/* PR22 — plan 초안은 이 발생을 P5 옵션 행이라 적었으나 실측은 선택칩의 × 해제 버튼이다(IN) */}
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            onClick={handleSenderClear}
            aria-label={inboxLabels.filter.senderClear}
            className="shrink-0 text-muted-foreground hover:text-foreground"
            data-testid="inbox-filter-sender-clear"
          >
            ×
          </Button>
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
          aria-label={inboxLabels.filter.senderAutocomplete}
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
  )
}
