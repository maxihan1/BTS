// 라벨 자동완성 입력 컴포넌트 — cmdk Command + 직접 구성 listbox, FR-IS-09 Task-5
import * as React from 'react'
import { Command as CommandPrimitive } from 'cmdk'
import { useLabels } from '@/hooks/use-labels'
import { useDebounce } from '@/hooks/use-debounce'
import { cn } from '@/lib/utils'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 라벨 검색어 debounce 지연 시간(ms) */
const DEBOUNCE_DELAY_MS = 250

/** listbox ID — aria-controls 연결에 사용 */
const LISTBOX_ID = 'label-autocomplete-listbox'

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** LabelAutocompleteInput 컴포넌트 props */
interface LabelAutocompleteInputProps {
  /** 현재 입력 텍스트 (controlled) */
  value: string
  /** 입력 텍스트 변경 콜백 */
  onChange: (value: string) => void
  /**
   * 라벨 확정 콜백 — 후보 클릭 또는 Enter 키 시 호출.
   * 자동완성 후보에 없는 신규 라벨도 허용(free-form).
   */
  onCommit: (label: string) => void
  /** 비활성 여부 — 최대 라벨 개수 도달 시 true */
  disabled?: boolean
  /** 이미 추가된 라벨 목록 — 후보에서 제외(선택사항) */
  existingLabels?: string[]
  /** 입력창 placeholder 텍스트 (선택사항, 기본값: '라벨 입력 또는 검색...') */
  placeholder?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 라벨 자동완성 입력 컴포넌트.
 *
 * 입력값을 250ms debounce 후 useLabels 훅으로 후보를 조회한다.
 * cmdk CommandPrimitive.Input으로 키보드 네비게이션 지원,
 * listbox/option은 직접 DOM으로 구성해 jsdom 호환성 보장.
 * 후보 클릭 또는 Enter로 라벨 확정(free-form 신규 라벨 포함).
 * 빈 입력 포커스 시 인기 라벨(useLabels(""))을 표시한다.
 *
 * @param value 현재 입력 텍스트 (controlled)
 * @param onChange 입력 텍스트 변경 콜백
 * @param onCommit 라벨 확정 콜백
 * @param disabled 비활성 여부
 * @param existingLabels 이미 추가된 라벨 목록 (후보에서 제외)
 */
export function LabelAutocompleteInput({
  value,
  onChange,
  onCommit,
  disabled = false,
  existingLabels = [],
  placeholder = '라벨 입력 또는 검색...',
}: LabelAutocompleteInputProps) {
  const [open, setOpen] = React.useState(false)
  const [activeIndex, setActiveIndex] = React.useState(-1)

  const debouncedQuery = useDebounce(value, DEBOUNCE_DELAY_MS)
  const { data: candidates = [] } = useLabels(debouncedQuery)

  /** 이미 추가된 라벨을 제외한 후보 목록 */
  const filteredCandidates = candidates.filter(
    (label) => !existingLabels.includes(label),
  )

  const showDropdown = open && filteredCandidates.length > 0

  /** 라벨 확정 처리 — 선택값 전달 후 입력창 초기화 */
  function handleCommit(label: string) {
    if (label.trim() === '') return
    onCommit(label.trim())
    onChange('')
    setOpen(false)
    setActiveIndex(-1)
  }

  /** 키보드 핸들러 — Enter: 현재 입력값 또는 선택된 후보 확정, Arrow: 포커스 이동 */
  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter') {
      e.preventDefault()
      if (activeIndex >= 0 && filteredCandidates[activeIndex] !== undefined) {
        handleCommit(filteredCandidates[activeIndex])
      } else if (value.trim() !== '') {
        handleCommit(value)
      }
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveIndex((prev) =>
        filteredCandidates.length === 0 ? -1 : (prev + 1) % filteredCandidates.length,
      )
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((prev) =>
        filteredCandidates.length === 0
          ? -1
          : (prev - 1 + filteredCandidates.length) % filteredCandidates.length,
      )
    } else if (e.key === 'Escape') {
      setOpen(false)
      setActiveIndex(-1)
    }
  }

  // 입력값 변경 시 activeIndex 초기화
  React.useEffect(() => {
    setActiveIndex(-1)
  }, [value])

  return (
    <div
      className="relative"
      data-testid="label-autocomplete-wrapper"
    >
      {/*
        CommandPrimitive(루트)는 키보드 네비게이션 컨텍스트 제공.
        CommandPrimitive.List(ResizeObserver 사용)는 제외하고
        listbox/option을 직접 구성해 jsdom 호환성 보장.
      */}
      <CommandPrimitive
        shouldFilter={false}
        label="라벨 자동완성"
      >
        <CommandPrimitive.Input
          aria-autocomplete="list"
          aria-controls={showDropdown ? LISTBOX_ID : undefined}
          aria-expanded={showDropdown}
          aria-activedescendant={
            activeIndex >= 0 ? `label-option-${activeIndex}` : undefined
          }
          value={value}
          onValueChange={onChange}
          onFocus={() => setOpen(true)}
          onBlur={() => {
            // 클릭 이벤트보다 blur가 먼저 발생하므로 150ms 지연 후 닫음
            setTimeout(() => setOpen(false), 150)
          }}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          placeholder={placeholder}
          className={cn(
            'h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none',
            'placeholder:text-muted-foreground',
            'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
            'disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50',
          )}
          data-testid="label-autocomplete-input"
        />
      </CommandPrimitive>

      {/* 드롭다운 후보 목록 — ResizeObserver 없는 직접 DOM 구성 */}
      {showDropdown && (
        <ul
          id={LISTBOX_ID}
          role="listbox"
          aria-label="라벨 자동완성"
          className="absolute z-50 mt-1 w-full max-h-48 overflow-y-auto rounded-md border border-border bg-popover p-1 shadow-md"
          data-testid="label-autocomplete-dropdown"
        >
          {filteredCandidates.map((label, index) => (
            <li
              key={label}
              id={`label-option-${index}`}
              role="option"
              aria-selected={index === activeIndex}
              aria-label={label}
              onMouseDown={(e) => {
                // blur 이벤트보다 먼저 실행 — blur의 setTimeout 취소 방지
                e.preventDefault()
                handleCommit(label)
              }}
              className={cn(
                'relative flex cursor-pointer select-none items-center rounded-sm px-2 py-1.5 text-sm outline-none',
                index === activeIndex
                  ? 'bg-accent text-accent-foreground'
                  : 'hover:bg-accent hover:text-accent-foreground',
              )}
              data-testid={`label-option-${label}`}
            >
              {label}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
