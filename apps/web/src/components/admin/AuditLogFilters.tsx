// 감사 로그 조회 필터 컴포넌트 — 이벤트유형·날짜범위·사용자 typeahead·초기화 (제어형)
import type { JSX } from 'react'
import { useState } from 'react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import { useUserSearch } from '@/hooks/use-user-directory'
import type { AuditLogQueryParams } from '@/api/audit-logs'
import { AUTH_EVENT_TYPES } from '@/api/audit-logs'
import { auditLogLabels, authEventTypeLabels } from '@/i18n/audit-log-labels'
import type { UserSummary } from '@/api/users'

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 → ISO Instant 변환 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * date input 값(YYYY-MM-DD)을 시작 Instant로 변환한다.
 * 경계 포함 — created_at >= from.
 * 빈 값이면 undefined 반환.
 */
function dateToStartInstant(dateStr: string): string | undefined {
  if (!dateStr) return undefined
  return `${dateStr}T00:00:00.000Z`
}

/**
 * date input 값(YYYY-MM-DD)을 종료 Instant로 변환한다.
 * 경계 포함 — created_at <= to.
 * 빈 값이면 undefined 반환.
 */
function dateToEndInstant(dateStr: string): string | undefined {
  if (!dateStr) return undefined
  return `${dateStr}T23:59:59.999Z`
}

/**
 * ISO Instant에서 date input 값(YYYY-MM-DD)을 추출한다.
 * undefined이면 빈 문자열 반환.
 */
function instantToDateInput(instant: string | undefined): string {
  if (!instant) return ''
  return instant.substring(0, 10)
}

// ─────────────────────────────────────────────────────────────────────────────
// 사용자 typeahead 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface UserTypeaheadProps {
  readonly query: string
  readonly selectedUserId: string | undefined
  readonly onSelect: (user: UserSummary) => void
}

/**
 * 사용자 검색 결과 목록.
 * query 2자 미만이면 렌더하지 않는다 (AddMemberDialog SearchResultList 선례).
 */
function UserSearchResults({ query, selectedUserId, onSelect }: UserTypeaheadProps): JSX.Element | null {
  const { data: results, isFetching } = useUserSearch(query)

  if (query.length < 2) return null

  if (isFetching) {
    return (
      <p className="text-xs text-muted-foreground py-1 px-1">검색 중...</p>
    )
  }

  if (results === undefined || results.length === 0) {
    return (
      <p className="text-sm text-muted-foreground py-1 px-1">검색 결과 없음</p>
    )
  }

  return (
    <ul className="mt-1 max-h-40 overflow-y-auto rounded-md border divide-y bg-popover shadow-md">
      {results.map((user) => {
        const isSelected = selectedUserId === user.id
        const label = user.displayName ?? user.username
        return (
          <li key={user.id}>
            <button
              type="button"
              className={`w-full text-left px-3 py-1.5 text-sm hover:bg-accent transition-colors ${isSelected ? 'bg-accent font-medium' : ''}`}
              onClick={() => { onSelect(user) }}
            >
              {label}
            </button>
          </li>
        )
      })}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface AuditLogFiltersProps {
  /** 현재 필터 파라미터 — 제어 컴포넌트 */
  readonly params: AuditLogQueryParams
  /** 필터 변경 콜백 — partial 변경만 전달, 부모에서 머지 */
  readonly onFilterChange: (partial: Partial<AuditLogQueryParams>) => void
}

/**
 * 감사 로그 조회 필터 컴포넌트.
 *
 * - 제어형: value는 모두 params에서 읽음 (react-usestate-stale-key-prop 방지).
 * - 사용자 typeahead: useUserSearch + SearchResultList (AddMemberDialog 선례).
 * - 날짜 입력: <input type="date"> → ISO Instant 변환 (시작 00:00:00.000Z / 종료 23:59:59.999Z).
 * - 이벤트 유형: Select(shadcn ui 래퍼).
 * - 초기화: 전체 필터 리셋.
 */
export function AuditLogFilters({ params, onFilterChange }: AuditLogFiltersProps): JSX.Element {
  const [userQuery, setUserQuery] = useState('')

  const handleEventTypeChange = (value: string) => {
    onFilterChange({ eventType: value === '__all__' ? undefined : value })
  }

  const handleFromChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onFilterChange({ from: dateToStartInstant(e.target.value) })
  }

  const handleToChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onFilterChange({ to: dateToEndInstant(e.target.value) })
  }

  const handleUserSelect = (user: UserSummary) => {
    onFilterChange({ userId: user.id })
    setUserQuery(user.displayName ?? user.username)
  }

  const handleReset = () => {
    onFilterChange({
      eventType: undefined,
      userId: undefined,
      from: undefined,
      to: undefined,
    })
    setUserQuery('')
  }

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-muted/30 p-4">
      {/* 이벤트 유형 Select */}
      <div className="flex min-w-[180px] flex-col gap-1">
        <label className="text-xs font-medium text-muted-foreground">
          {auditLogLabels.filter.eventType}
        </label>
        <Select
          value={params.eventType ?? '__all__'}
          onValueChange={handleEventTypeChange}
        >
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="__all__">{auditLogLabels.filter.allEvents}</SelectItem>
            {AUTH_EVENT_TYPES.map((type) => (
              <SelectItem key={type} value={type}>
                {(authEventTypeLabels as Record<string, string>)[type] ?? type}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 시작일 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-muted-foreground">
          {auditLogLabels.filter.from}
        </label>
        <input
          type="date"
          className="rounded-md border bg-background px-3 py-2 text-sm"
          value={instantToDateInput(params.from)}
          onChange={handleFromChange}
        />
      </div>

      {/* 종료일 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-muted-foreground">
          {auditLogLabels.filter.to}
        </label>
        <input
          type="date"
          className="rounded-md border bg-background px-3 py-2 text-sm"
          value={instantToDateInput(params.to)}
          onChange={handleToChange}
        />
      </div>

      {/* 사용자 typeahead */}
      <div className="relative flex min-w-[200px] flex-col gap-1">
        <label className="text-xs font-medium text-muted-foreground">
          {auditLogLabels.filter.userId}
        </label>
        <input
          type="text"
          className="rounded-md border bg-background px-3 py-2 text-sm"
          placeholder={auditLogLabels.filter.searchPlaceholder}
          value={userQuery}
          onChange={(e) => { setUserQuery(e.target.value) }}
        />
        <UserSearchResults
          query={userQuery}
          selectedUserId={params.userId}
          onSelect={handleUserSelect}
        />
      </div>

      {/* 초기화 버튼 */}
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={handleReset}
      >
        {auditLogLabels.filter.reset}
      </Button>
    </div>
  )
}
