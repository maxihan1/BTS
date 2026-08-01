// 이슈 생성 폼의 담당자 3-state · 검색 · 표시 스냅샷을 묶은 훅 (FR-UX-09 F2)
import { useMemo, useState } from 'react'
import { useUsers } from '@/hooks/use-users'
import { useDebounce } from '@/hooks/use-debounce'
import type { UserSummary } from '@/api/users'

/** 담당자 검색 debounce — 1,000명 규모에서 매 키스트로크 요청을 막는다 */
const ASSIGNEE_SEARCH_DEBOUNCE_MS = 300

/** `useAssigneePicker` 반환값 */
export interface AssigneePicker {
  /**
   * 담당자 **3-state** (백엔드 `JsonNullable`, ADR 2026-07-31 D-2).
   *
   * - `undefined` (초기값) — 사용자가 담당자를 건드리지 않았다. 요청 본문에서 **키를 뺀다**
   *   → 서버 자동 배정(`resolveDefaultAssignee`)이 그대로 돈다.
   * - `null` — 「해제」를 눌러 **명시적으로 미할당**을 골랐다. `null` 을 실어 자동 배정을 끈다.
   * - 값 — 그 사용자로 확정.
   *
   * ★초기값을 `null` 로 바꾸면 **모든 생성 요청이 자동 배정을 조용히 끈다**. 되돌리지 말 것.
   */
  intent: string | null | undefined
  /** 화면에 표시할 현재 담당자 — 고를 때 붙잡아 둔 스냅샷 */
  current: UserSummary | null
  /** 드롭다운 후보 — 검색어가 있을 때만 채워진다 */
  candidates: UserSummary[]
  /** 검색어 변경 */
  onSearch: (query: string) => void
  /** 선택/해제 */
  onChange: (userId: string | null) => void
}

/**
 * 이슈 생성 폼의 담당자 선택 상태.
 *
 * ### 후보는 검색해야 나온다
 * `useUsers('')` 는 **전체 사용자 목록**을 돌려준다. 그대로 넘기면 아직 아무것도 검색하지
 * 않았는데 후보가 쌓여 모달 세로를 통째로 잡아먹는다(눈확인에서 4명 노출 확인).
 * 사내 1,000명 규모에서는 더 나쁘다.
 *
 * ### 표시용 담당자는 스냅샷이다
 * ★검색 결과에서 `find(...)` 로 매번 찾으면 안 된다. 검색어를 바꾸는 순간 골라둔 사람이
 * 목록에서 빠져 화면이 「미지정」으로 뒤집힌다(전송값은 멀쩡한데 표시만 거짓말).
 * 이슈 상세가 이미 겪고 고친 결함이다 — `routes/issues.$key.tsx` 의 `C1 버그 수정` 주석.
 *
 * 상세 화면은 담당자 id 가 서버 데이터로 들어와 고르는 순간이 없어 `useUsersByIds` 로
 * 재조회한다. 생성 폼은 **사용자가 후보에서 고른 순간 객체를 이미 손에 쥐고 있어**
 * 재조회가 필요 없다 — 요청도 아끼고, 응답을 기다리는 동안 이름이 깜빡이지도 않는다.
 */
export function useAssigneePicker(): AssigneePicker {
  const [intent, setIntent] = useState<string | null | undefined>(undefined)
  const [picked, setPicked] = useState<UserSummary | null>(null)
  const [searchQuery, setSearchQuery] = useState('')

  const debouncedQuery = useDebounce(searchQuery, ASSIGNEE_SEARCH_DEBOUNCE_MS)
  const { data: allCandidates = [] } = useUsers(debouncedQuery)

  const candidates = useMemo(
    () => (debouncedQuery.trim() === '' ? [] : allCandidates),
    [debouncedQuery, allCandidates],
  )

  function onChange(userId: string | null): void {
    setIntent(userId)
    setPicked(userId === null ? null : allCandidates.find((u) => u.id === userId) ?? null)
  }

  return { intent, current: picked, candidates, onSearch: setSearchQuery, onChange }
}
