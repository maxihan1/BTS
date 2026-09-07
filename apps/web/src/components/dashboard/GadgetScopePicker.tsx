// 가젯 설정의 스코프 선택기 3종 — UUID·프로젝트키를 손으로 타이핑하지 않게 한다
import type { JSX } from 'react'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { cn } from '@/lib/utils'
import { useProjects } from '@/hooks/use-projects'
import { useBoards } from '@/hooks/use-boards'
import { fetchOwnedFilters, fetchSharedFilters, savedFiltersKey } from '@/api/saved-filters'
import { gadgetPickerLabels } from '@/i18n/dashboard-labels'

/** 폼의 다른 입력과 같은 모양을 쓴다 — 선택기만 튀면 폼이 두 벌처럼 보인다. */
const SELECT_CLASS = cn(
  'w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm shadow-xs outline-none',
  'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
)

interface PickerProps {
  /** 현재 값 (빈 문자열 = 미선택) */
  readonly value: string
  /** 값 변경 콜백 */
  readonly onChange: (next: string) => void
  /** label 의 htmlFor 와 잇는 id */
  readonly id: string
  /**
   * 접근명. `<label htmlFor>` 가 없는 자리에서만 넘긴다.
   *
   * ★`BoardPicker` 가 **내장**한 프로젝트 선택기가 그 자리다. 폼의 `<label>` 은 바깥
   * 보드 select 를 가리키므로 내장 select 는 이름이 빈 combobox 가 된다 —
   * 스크린리더로는 드롭다운 둘 중 어느 것이 프로젝트인지 알 수 없다.
   * 증상은 테스트에도 이미 나 있었다. 이름으로 못 잡아서 유닛은 `getAllByRole('combobox')[0]`,
   * e2e 는 `selects.nth(0)` 로 **인덱스**를 썼고, 필드 순서가 바뀌면 둘 다 조용히
   * 엉뚱한 요소를 잰다.
   */
  readonly ariaLabel?: string
}

/**
 * 프로젝트 선택기 — 값은 프로젝트 **키**다(UUID 아님).
 *
 * 보관(archived) 프로젝트는 제외한다. 가젯이 가리킬 대상이 아니고, 목록에 섞이면
 * 사용자가 왜 데이터가 안 나오는지 못 짚는다.
 */
export function ProjectPicker({ value, onChange, id, ariaLabel }: PickerProps): JSX.Element {
  const { data: projects = [], isPending } = useProjects()

  return (
    <select
      id={id}
      aria-label={ariaLabel}
      className={SELECT_CLASS}
      value={value}
      onChange={(e) => {
        onChange(e.target.value)
      }}
      disabled={isPending}
    >
      <option value="">{isPending ? gadgetPickerLabels.loading : gadgetPickerLabels.selectProject}</option>
      {projects.map((p) => (
        <option key={p.key} value={p.key}>
          {p.name} ({p.key})
        </option>
      ))}
    </select>
  )
}

/** 공유 필터 조회 페이지 크기 — 드롭다운 하나에 담기는 현실적 상한. */
const SHARED_FILTER_PAGE_SIZE = 100

/**
 * 저장된 필터 선택기 — 값은 필터 UUID.
 *
 * ★**내 필터와 공유받은 필터를 함께** 낸다. 종전에는 내 것만 냈는데, 그러면 이 PR 이
 * `filterId` 를 자유 입력에서 드롭다운으로 바꾸면서 **기존 기능이 줄어든다** —
 * 예전에는 공유받은 필터의 UUID 를 붙여넣을 수 있었다. `filter_result`·`issue_count` 는
 * 이미 출시된 가젯이라 신규 가젯의 제약이 아니라 축소다(리뷰 지적 · Maxi 판정 「지금 고친다」).
 *
 * ★`<optgroup>` 으로 나눈다. 두 묶음에 같은 이름의 필터가 있을 수 있고, 그때 어느 쪽 것인지
 * 모르면 고른 뒤에야 엉뚱한 결과를 본다.
 */
export function FilterPicker({ value, onChange, id }: PickerProps): JSX.Element {
  // SavedFilterMenu 와 같은 queryKey 라 캐시를 공유한다.
  const owned = useQuery({
    queryKey: savedFiltersKey.owned(),
    queryFn: fetchOwnedFilters,
    staleTime: 30_000,
  })
  const shared = useQuery({
    queryKey: savedFiltersKey.shared(0, SHARED_FILTER_PAGE_SIZE),
    queryFn: () => fetchSharedFilters(0, SHARED_FILTER_PAGE_SIZE),
    staleTime: 30_000,
  })

  const ownedFilters = owned.data ?? []
  const sharedFilters = shared.data ?? []
  // 둘 다 끝나야 목록이 완성된다. 한쪽만 보고 열면 나머지가 뒤늦게 튀어 들어온다.
  const isPending = owned.isPending || shared.isPending

  return (
    <select
      id={id}
      className={SELECT_CLASS}
      value={value}
      onChange={(e) => {
        onChange(e.target.value)
      }}
      disabled={isPending}
    >
      <option value="">{isPending ? gadgetPickerLabels.loading : gadgetPickerLabels.selectFilter}</option>
      {ownedFilters.length > 0 && (
        <optgroup label={gadgetPickerLabels.ownedFilters}>
          {ownedFilters.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name} ({f.projectKey})
            </option>
          ))}
        </optgroup>
      )}
      {sharedFilters.length > 0 && (
        <optgroup label={gadgetPickerLabels.sharedFilters}>
          {sharedFilters.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name} ({f.projectKey})
            </option>
          ))}
        </optgroup>
      )}
    </select>
  )
}

/**
 * 보드 선택기 — 값은 보드 UUID.
 *
 * ★프로젝트 드롭다운을 **내장**한다. `GET /boards` 가 `projectKey` 를 요구하는데
 * `sprint_burndown` 의 config 에는 `boardId` 하나뿐이기 때문이다. 프로젝트를 config 에
 * 추가하는 대신 여기서만 쓰고 버린다 — 저장되는 것은 `boardId` 뿐이고, 보드를 알면
 * 프로젝트는 서버가 안다.
 *
 * ★프로젝트를 바꾸면 고른 보드를 **비운다**(엣지 E8). 안 비우면 A 프로젝트의 보드 UUID 가
 * B 프로젝트를 고른 상태로 저장돼 「보드를 골랐는데 데이터가 안 나온다」가 된다.
 * 비우는 것을 조용히 하지 않고 안내를 띄운다(리뷰 D-4) — 조용히 비면 값이 사라진 것처럼 보인다.
 */
export function BoardPicker({ value, onChange, id }: PickerProps): JSX.Element {
  const [projectKey, setProjectKey] = useState('')
  const [wasReset, setWasReset] = useState(false)
  const { data: boards = [], isPending } = useBoards(projectKey)

  const handleProjectChange = (nextProjectKey: string): void => {
    setProjectKey(nextProjectKey)
    // 이전 프로젝트의 보드가 남아 있으면 비운다. 비웠다는 사실을 사용자에게 알린다.
    if (value !== '') {
      onChange('')
      setWasReset(true)
    }
  }

  return (
    <div className="space-y-2">
      <ProjectPicker
        id={`${id}-project`}
        value={projectKey}
        onChange={handleProjectChange}
        ariaLabel={gadgetPickerLabels.selectProject}
      />
      <select
        id={id}
        className={SELECT_CLASS}
        value={value}
        onChange={(e) => {
          setWasReset(false)
          onChange(e.target.value)
        }}
        disabled={projectKey === '' || isPending}
      >
        <option value="">
          {projectKey === ''
            ? gadgetPickerLabels.selectProjectFirst
            : isPending
              ? gadgetPickerLabels.loading
              : gadgetPickerLabels.selectBoard}
        </option>
        {boards.map((b) => (
          <option key={b.boardId} value={b.boardId}>
            {/* 종류를 함께 낸다 — 칸반을 고르면 가젯이 「활성 스프린트가 없습니다」로 뜬다.
                고르기 전에 알려주는 편이 고르고 나서 비는 것보다 낫다. */}
            {b.name} ({b.boardType === 'SCRUM' ? gadgetPickerLabels.scrum : gadgetPickerLabels.kanban})
          </option>
        ))}
      </select>
      {wasReset && (
        <p role="status" className="text-muted-foreground text-xs">
          {gadgetPickerLabels.boardResetByProjectChange}
        </p>
      )}
    </div>
  )
}
