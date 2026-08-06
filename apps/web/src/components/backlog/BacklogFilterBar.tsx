// 백로그 전용 필터바 — 공유 FilterBar 에 제목 검색을 얹고 에픽은 「칩(제거)」으로만 노출하는 얇은 래퍼 (FR-UX-13 F16)
import type { JSX } from 'react'
import { useEffect, useState } from 'react'
import { FilterBar } from '@/components/filters/FilterBar'
import type { FilterChipData } from '@/components/filters/FilterBar'
import { useDebounce } from '@/hooks/use-debounce'
import { backlogLabels } from '@/i18n/backlog-labels'
import { NO_EPIC, emptyBacklogFilter, toFilterBarValue } from '@/lib/backlog-filter'
import type { BacklogFilter } from '@/lib/backlog-filter'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 / 라벨
// ─────────────────────────────────────────────────────────────────────────────

/** 제목 검색 디바운스(ms) — `FilterBar` 담당자 typeahead 와 **같은 값**이어야 한다 (NFR N3) */
export const SEARCH_DEBOUNCE_MS = 250

/** element id 접두사 — `FilterBar` 가 `${idPrefix}-assignee-input` 등에 쓴다 */
const ID_PREFIX = 'backlog-filter'

// ─────────────────────────────────────────────────────────────────────────────
// BacklogFilterBar (공개 컴포넌트)
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogFilterBar props */
export interface BacklogFilterBarProps {
  /** 프로젝트 키 — `FilterBar` 내부 쿼리에 사용 */
  readonly projectKey: string
  /** 현재 백로그 필터 — 제어형, 상태는 부모 소유 */
  readonly value: BacklogFilter
  /** 필터 변경 콜백 — 새 필터 전체를 전달 */
  readonly onChange: (next: BacklogFilter) => void
  /**
   * 에픽 키 → 이름 맵 (`use-backlog-epics`).
   *
   * 해석 실패/로딩 중인 키는 담기지 않는다 — 그때는 **키를 그대로** 보인다 (F16-6, EC3).
   */
  readonly epicNames: ReadonlyMap<string, string>
}

/**
 * 백로그 필터 바 — 제어형.
 *
 * 담당자/미배정/칩/초기화/카운트는 공유 {@link FilterBar} 에 위임하고, 제목 검색만 이 래퍼가
 * 소유해 `leadingSection` 으로 주입한다.
 *
 * **라벨·컴포넌트 섹션은 감춘다** (`hiddenSections`) — 백로그 응답(`backlogIssueSchema`)에 두
 * 필드가 아예 없어 클라이언트가 거를 근거가 없다. 그대로 두면 눌러도 아무 일이 없는 장식 필터다.
 *
 * **에픽은 칩(제거)으로만 나타난다** — 선택 입력 컨트롤은 에픽 패널이 유일 소유한다 (C4).
 * 같은 상태를 두 UI 가 입력하면 어느 쪽이 진실인지가 흐려진다.
 *
 * WCAG AA: 칩 `aria-label`·터치 타깃 44px 은 `FilterBar` 가 보장하고, 검색 입력은
 * `label htmlFor` 연결 + 44px 을 여기서 직접 충족한다.
 */
export function BacklogFilterBar({
  projectKey,
  value,
  onChange,
  epicNames,
}: BacklogFilterBarProps): JSX.Element {
  // 검색어만 로컬 상태다 — 매 키스트로크를 부모로 올리면 디바운스가 무의미해진다.
  // 부모가 query 를 바꿔 이 값과 갈릴 수 있는 경우(뒤로가기 등)는 부모가 key 로 재마운트한다.
  const [queryInput, setQueryInput] = useState(value.query)
  const debouncedQuery = useDebounce(queryInput, SEARCH_DEBOUNCE_MS)

  // ★부모로 올리는 값은 **디바운스가 현재 입력을 따라잡은 값**뿐이다 (첫 줄).
  //
  //   `onReset` 은 `queryInput` 만 비우고 `useDebounce` 안의 낡은 값은 250ms 더 살아 있다.
  //   「부모 값과 다른가」만 보면 그 낡은 검색어가 방금 지운 값을 **부모로 도로 밀어 올려**,
  //   검색창은 비었는데 「조건에 맞는 이슈가 없습니다」가 0.25초 동안 다시 뜬다.
  //   빈 상태 CTA 경로는 부모 재마운트(`BacklogBoard` 의 `filterBarKey`)가 막지만, 필터바
  //   자체의 `초기화` 는 재마운트를 타지 않는다 — 두 경로는 서로를 대신하지 못한다.
  //   (재마운트 쪽은 로컬 입력이 **안 비워지는** 경우를 막으므로 이 가드로 대체되지도 않는다.)
  //
  //   둘째 줄은 부모가 이미 같은 값을 쥔 경우다. 없으면 `onChange` 가 새 객체를 만들어
  //   deps 를 다시 흔드는 무한 루프가 된다.
  useEffect(() => {
    if (debouncedQuery !== queryInput) return
    if (debouncedQuery === value.query) return
    onChange({ ...value, query: debouncedQuery })
  }, [queryInput, debouncedQuery, value, onChange])

  const epicChips: FilterChipData[] = value.epicKeys.map((key) => ({
    id: `epic-${key}`,
    label: epicChipLabel(key, epicNames),
    onRemove: () => onChange({ ...value, epicKeys: value.epicKeys.filter((k) => k !== key) }),
  }))

  // 백로그 고유 축(에픽·검색어)은 FilterBar 의 activeCount 계산에 없다.
  // 가산하지 않으면 「N개 적용 중」이 화면에 보이는 칩보다 작아져 거짓말이 된다 (R2).
  const queryActiveCount = value.query.trim().length > 0 ? 1 : 0

  return (
    <FilterBar
      projectKey={projectKey}
      value={toFilterBarValue(value)}
      onChange={(next) =>
        onChange({
          ...value,
          assigneeIds: next.assigneeIds,
          includeUnassigned: next.includeUnassigned,
        })
      }
      idPrefix={ID_PREFIX}
      leadingSection={<BacklogSearchInput value={queryInput} onChange={setQueryInput} />}
      leadingChips={epicChips}
      extraActiveCount={value.epicKeys.length + queryActiveCount}
      hiddenSections={{ labels: true, components: true }}
      onReset={() => {
        setQueryInput('')
        onChange(emptyBacklogFilter())
      }}
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 에픽 칩 라벨
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 칩에 보일 이름을 고른다.
 *
 * `NO_EPIC` 은 실제 이슈 키가 아니라 센티널이라 이름 맵에 **절대 담기지 않는다** —
 * 맵 조회에만 맡기면 화면에 `__none__` 이 그대로 새어 나온다.
 * 해석 실패/로딩 중에는 키를 보인다 (F16-6 — 빈 값 금지).
 */
function epicChipLabel(key: string, epicNames: ReadonlyMap<string, string>): string {
  if (key === NO_EPIC) return backlogLabels.filter.noEpic
  return epicNames.get(key) ?? key
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogSearchInput — 제목 검색 전용 비공개 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface BacklogSearchInputProps {
  /** 현재 입력값 — 디바운스 전의 로컬 값 */
  readonly value: string
  /** 입력 변경 콜백 */
  readonly onChange: (next: string) => void
}

/**
 * 제목 검색 입력 — `FilterBar` 의 `leadingSection` 슬롯에 주입한다.
 *
 * 접근명은 `label htmlFor` 로만 준다(중복 `aria-label` 없음). `type="search"` 를 쓰면 role 이
 * `searchbox` 가 되어 상단바 전역 검색과 같은 role 로 묶이므로 `type="text"` 를 쓴다.
 */
function BacklogSearchInput({ value, onChange }: BacklogSearchInputProps): JSX.Element {
  const inputId = `${ID_PREFIX}-query-input`
  return (
    <div className="flex min-w-[200px] flex-col gap-1">
      <label htmlFor={inputId} className="text-xs font-medium text-muted-foreground">
        {backlogLabels.filter.searchLabel}
      </label>
      <input
        id={inputId}
        type="text"
        className="min-h-[44px] rounded-md border bg-background px-3 py-2 text-sm"
        placeholder={backlogLabels.filter.searchPlaceholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  )
}
