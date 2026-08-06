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

  // deps 에 value·onChange 가 있어 부모 리렌더마다 재실행되지만, 첫 줄 가드가 즉시 되돌린다.
  // 가드 기준이 `debouncedQuery` 라 타이핑 중(디바운스 미완)에는 값이 새어 나가지 않는다.
  useEffect(() => {
    if (debouncedQuery === value.query) return
    onChange({ ...value, query: debouncedQuery })
  }, [debouncedQuery, value, onChange])

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
