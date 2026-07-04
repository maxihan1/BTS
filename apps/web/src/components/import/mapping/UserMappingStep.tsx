// Import 매핑 마법사 — 전 작성자 식별자를 BTS 유저로 매핑하는 단계 컴포넌트 (FR-IM-02 D6 Task-4)
import type { JSX, ChangeEvent } from 'react'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { fetchUsers } from '@/api/users'
import type { UserSummary } from '@/api/users'
import { useDebounce } from '@/hooks/use-debounce'
import type { UserCollectionResponse } from '@/api/import-mappings'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 다른 사용자 검색 debounce 지연 시간 (ms) — SenderAutocomplete 미러 */
const SEARCH_DEBOUNCE_MS = 250

/** 검색 최소 쿼리 길이 — 이 길이 이상일 때 API 호출 */
const SEARCH_MIN_QUERY_LENGTH = 1

/** 사용자 매핑 collect 결과 항목 하나의 타입 */
type UserMappingEntry = UserCollectionResponse['users'][number]

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** UserMappingStep 컴포넌트 props */
export interface UserMappingStepProps {
  /** collectUsers 응답의 소스 작성자 식별자 목록 */
  users: UserCollectionResponse['users']
  /** sourceIdentifier → targetUserId(UUID) | null(미매핑) override map */
  value: Record<string, string | null>
  /** override map 변경 콜백 — 항상 전체 map을 전달한다 */
  onChange: (next: Record<string, string | null>) => void
  /** [다음] 클릭 콜백 */
  onNext: () => void
  /** [이전] 클릭 콜백 — 생략 시 [이전] 버튼 미노출 */
  onBack?: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 소스 작성자 식별자의 현재 유효 매핑값을 계산한다.
 * override map에 명시적 키가 있으면 그 값을 쓰고, 없으면 추천 사용자를 기본값으로 쓴다.
 * 추천이 없으면 기본 미매핑(null)이다.
 *
 * @param entry collectUsers 응답 항목 하나
 * @param value 현재 override map
 * @returns 유효 매핑 대상 사용자 UUID 또는 null(미매핑)
 */
function resolveEffectiveValue(entry: UserMappingEntry, value: Record<string, string | null>): string | null {
  if (Object.prototype.hasOwnProperty.call(value, entry.sourceIdentifier)) {
    return value[entry.sourceIdentifier] ?? null
  }
  return entry.suggestedUserId ?? null
}

// ─────────────────────────────────────────────────────────────────────────────
// UserSearchResultList — 다른 사용자 검색 결과 listbox (UserMappingRow에서 분리)
// ─────────────────────────────────────────────────────────────────────────────

interface UserSearchResultListProps {
  readonly listboxLabel: string
  readonly candidates: UserSummary[]
  readonly effectiveValue: string | null
  readonly onSelect: (candidate: UserSummary) => void
}

function UserSearchResultList({
  listboxLabel,
  candidates,
  effectiveValue,
  onSelect,
}: UserSearchResultListProps): JSX.Element {
  return (
    <ul
      role="listbox"
      aria-label={listboxLabel}
      className="mt-1 max-h-40 overflow-y-auto rounded-md border border-border bg-popover"
    >
      {candidates.map((candidate) => (
        <li
          key={candidate.id}
          role="option"
          aria-selected={effectiveValue === candidate.id}
          onClick={() => {
            onSelect(candidate)
          }}
          className="cursor-pointer select-none px-2 py-1.5 text-sm hover:bg-accent hover:text-accent-foreground"
        >
          {candidate.displayName ?? candidate.username}
        </li>
      ))}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// UserMappingRow — 행 하나(추천/미매핑 선택 + 다른 사용자 검색)
// ─────────────────────────────────────────────────────────────────────────────

interface UserMappingRowProps {
  readonly entry: UserMappingEntry
  readonly effectiveValue: string | null
  readonly onSelect: (userId: string | null) => void
}

function UserMappingRow({ entry, effectiveValue, onSelect }: UserMappingRowProps): JSX.Element {
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebounce(query, SEARCH_DEBOUNCE_MS)

  const { data: candidates = [], isFetching } = useQuery({
    queryKey: ['import-mapping', 'user-search', entry.sourceIdentifier, debouncedQuery],
    queryFn: (): Promise<UserSummary[]> => fetchUsers(debouncedQuery),
    enabled: debouncedQuery.length >= SEARCH_MIN_QUERY_LENGTH,
    staleTime: 30_000,
  })

  function handleQueryChange(e: ChangeEvent<HTMLInputElement>): void {
    setQuery(e.target.value)
  }

  function handleCandidateSelect(candidate: UserSummary): void {
    onSelect(candidate.id)
    setQuery('')
  }

  return (
    <div
      data-testid={`user-mapping-row-${entry.sourceIdentifier}`}
      className="rounded-md border border-border p-3"
    >
      <p className="mb-2 text-sm font-medium text-foreground">{entry.sourceIdentifier}</p>

      <div className="mb-2 flex flex-wrap gap-2">
        {entry.suggestedUserId != null && (
          <Button
            type="button"
            variant={effectiveValue === entry.suggestedUserId ? 'default' : 'outline'}
            size="sm"
            aria-pressed={effectiveValue === entry.suggestedUserId}
            onClick={() => {
              onSelect(entry.suggestedUserId ?? null)
            }}
          >
            추천: {entry.suggestedDisplayName ?? entry.suggestedUserId}
          </Button>
        )}
        <Button
          type="button"
          variant={effectiveValue === null ? 'default' : 'outline'}
          size="sm"
          aria-pressed={effectiveValue === null}
          onClick={() => {
            onSelect(null)
          }}
        >
          미매핑(이메일 폴백)
        </Button>
      </div>

      <div className="relative">
        <Input
          value={query}
          onChange={handleQueryChange}
          placeholder="다른 사용자 검색"
          aria-label={`${entry.sourceIdentifier} 다른 사용자 검색`}
        />
        {isFetching && <p className="mt-1 text-xs text-muted-foreground">검색 중...</p>}
        {!isFetching && candidates.length > 0 && (
          <UserSearchResultList
            listboxLabel={`${entry.sourceIdentifier} 검색 결과`}
            candidates={candidates}
            effectiveValue={effectiveValue}
            onSelect={handleCandidateSelect}
          />
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// UserMappingStep (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Import 매핑 마법사 — 사용자 매핑 단계.
 *
 * collectUsers 응답으로 받은 소스 작성자 식별자별로 행을 렌더한다. 각 행은
 * - 추천 사용자(있으면) 선택 버튼
 * - 미매핑(이메일 폴백) 선택 버튼
 * - 다른 사용자 검색(Input + fetchUsers debounce 결과 리스트)
 * 세 가지 선택지를 제공하며, 어느 쪽을 선택해도 `onChange`로 override map 전체를 전달한다.
 *
 * users가 빈 배열이면(컨테이너가 자동 스킵하지 않고 사용자가 되돌아온 경우) 방어적으로
 * 안내 메시지만 렌더한다.
 *
 * @param users collectUsers 응답의 소스 작성자 식별자 목록
 * @param value 현재 override map(sourceIdentifier → targetUserId | null)
 * @param onChange override map 변경 콜백
 * @param onNext [다음] 클릭 콜백
 * @param onBack [이전] 클릭 콜백 — 생략 시 버튼 미노출
 */
export function UserMappingStep({ users, value, onChange, onNext, onBack }: UserMappingStepProps): JSX.Element {
  function handleSelect(sourceIdentifier: string, userId: string | null): void {
    onChange({ ...value, [sourceIdentifier]: userId })
  }

  return (
    <div>
      {users.length === 0 ? (
        <p className="mb-4 text-sm text-muted-foreground">매핑할 작성자가 없습니다.</p>
      ) : (
        <div className="flex flex-col gap-3">
          {users.map((entry) => (
            <UserMappingRow
              key={entry.sourceIdentifier}
              entry={entry}
              effectiveValue={resolveEffectiveValue(entry, value)}
              onSelect={(userId) => {
                handleSelect(entry.sourceIdentifier, userId)
              }}
            />
          ))}
        </div>
      )}

      <div className="mt-4 flex justify-end gap-2">
        {onBack !== undefined && (
          <Button type="button" variant="outline" size="sm" onClick={onBack}>
            이전
          </Button>
        )}
        <Button type="button" size="sm" onClick={onNext}>
          다음
        </Button>
      </div>
    </div>
  )
}
