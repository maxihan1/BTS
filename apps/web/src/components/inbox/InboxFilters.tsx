// 알림 보관함 검색 필터 컴포넌트 — 텍스트/발신자/기간 필터 제공 (FR-UX-03)
import * as React from 'react'
import type { InboxFilters } from '@/api/inbox'
import { inboxLabels } from '@/i18n/inbox-labels'
import { cn } from '@/lib/utils'
import { SenderAutocomplete } from './SenderAutocomplete'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼 — 날짜 변환
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `<input type="date">` 값(YYYY-MM-DD)을 UTC 하루 시작 ISO Instant로 변환한다.
 * 백엔드 InboxController의 from: Instant? 파라미터와 호환된다.
 *
 * @param dateValue date input의 value 문자열 (YYYY-MM-DD)
 * @returns `2026-06-01T00:00:00.000Z` 형식의 ISO Instant 문자열
 */
function dateToFromInstant(dateValue: string): string {
  return `${dateValue}T00:00:00.000Z`
}

/**
 * `<input type="date">` 값(YYYY-MM-DD)을 UTC 하루 끝 ISO Instant로 변환한다.
 * to는 inclusive end-of-day이다.
 * 백엔드 InboxController의 to: Instant? 파라미터와 호환된다.
 *
 * @param dateValue date input의 value 문자열 (YYYY-MM-DD)
 * @returns `2026-06-30T23:59:59.999Z` 형식의 ISO Instant 문자열
 */
function dateToToInstant(dateValue: string): string {
  return `${dateValue}T23:59:59.999Z`
}

/**
 * ISO Instant 문자열에서 날짜 부분(YYYY-MM-DD)을 추출한다.
 * `<input type="date">` value에 역방향 바인딩할 때 사용한다.
 *
 * @param instant ISO 8601 형식 문자열 (YYYY-MM-DDTHH:mm:ss.sssZ 또는 YYYY-MM-DD)
 * @returns `2026-06-01` 형식의 날짜 문자열
 */
function instantToDateValue(instant: string): string {
  // T가 포함된 ISO Instant이면 날짜 부분만 슬라이스, 아니면 그대로 반환
  const tIndex = instant.indexOf('T')
  return tIndex >= 0 ? instant.slice(0, tIndex) : instant
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
 * - 발신자 자동완성(SenderAutocomplete): 이름 입력 → 후보 표시 → senderId(UUID) 설정
 * - 기간: from/to date 입력 — bare date를 ISO Instant(UTC)로 변환해 전달
 *   (백엔드 from/to: Instant? — Spring 기본 ISO_INSTANT 요구)
 *
 * @param filters 현재 검색 필터 상태
 * @param onFiltersChange 필터 변경 콜백
 */
export function InboxFilters({ filters, onFiltersChange }: InboxFiltersProps) {
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
  // 발신자 핸들러
  // ─────────────────────────────────────────────────────────────────────────

  function handleSenderChange(userId: string | undefined) {
    onFiltersChange({ ...filters, senderId: userId })
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 기간 핸들러
  // ─────────────────────────────────────────────────────────────────────────

  function handleFromChange(e: React.ChangeEvent<HTMLInputElement>) {
    const value = e.target.value
    // bare date(YYYY-MM-DD) → UTC 하루 시작 ISO Instant 변환
    // 백엔드 from: Instant?가 Spring 기본 ISO_INSTANT를 요구하므로 변환 필수
    onFiltersChange({ ...filters, from: value === '' ? undefined : dateToFromInstant(value) })
  }

  function handleToChange(e: React.ChangeEvent<HTMLInputElement>) {
    const value = e.target.value
    // bare date(YYYY-MM-DD) → UTC 하루 끝(inclusive) ISO Instant 변환
    onFiltersChange({ ...filters, to: value === '' ? undefined : dateToToInstant(value) })
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
      <SenderAutocomplete
        selectedSenderId={filters.senderId}
        onSenderChange={handleSenderChange}
      />

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
          value={filters.from !== undefined ? instantToDateValue(filters.from) : ''}
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
          value={filters.to !== undefined ? instantToDateValue(filters.to) : ''}
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
