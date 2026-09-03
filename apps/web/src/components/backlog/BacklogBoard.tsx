// 백로그·스프린트 보드 루트 컴포넌트 — 필터/에픽 배선 + DnD 오케스트레이션 + 라이프사이클 (FR-BL-01/02 D6/D7 · FR-UX-13 F16)
import type { JSX } from 'react'
import { useCallback, useMemo, useState } from 'react'
import { DndContext, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import { toast } from 'sonner'
import { useBacklog, useCreateSprint } from '@/hooks/use-backlog'
import { useUsersByIdsChunked } from '@/hooks/use-users'
import { useBacklogEpics } from '@/hooks/use-backlog-epics'
import { useIssueTypes } from '@/hooks/use-issue-types'
import type { IssueTypeResponse } from '@/api/issue-types'
import { collectAssigneeIds, buildAssigneeNameMap } from './backlog-assignee-names'
import { BacklogColumn } from './BacklogColumn'
import { SprintColumn } from './SprintColumn'
import { StartSprintDialog } from './StartSprintDialog'
import { CompleteSprintDialog } from './CompleteSprintDialog'
import { BacklogFilterBar } from './BacklogFilterBar'
import { BacklogEpicPanel } from './BacklogEpicPanel'
import { cardFirstCollision } from './backlog-collision'
import { sprintCreateErrorMessage } from './backlog-sprint-create-error'
import { useBacklogCreateIssue } from './use-backlog-create-issue'
import type { BacklogCreateIssue } from './use-backlog-create-issue'
import { useBacklogDrag } from './use-backlog-drag'
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'
import { FilteredEmptyState } from '@/components/filters/FilteredEmptyState'
import { Button } from '@/components/ui/button'
import { backlogLabels } from '@/i18n/backlog-labels'
import {
  backlogScreenReaderInstructions,
  buildBacklogAnnouncements,
} from '@/lib/backlog-announcements'
import { backlogKeyboardSensorOptions } from '@/lib/backlog-keyboard-coordinates'
import { NO_EPIC, emptyBacklogFilter, filterBacklogView, isEmptyFilter } from '@/lib/backlog-filter'
import type { BacklogFilter } from '@/lib/backlog-filter'
import type { BacklogIssue, BacklogView, SprintMeta, SprintWithIssues } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 라벨 상수 (FR-UX-13 F16)
//
// `i18n/backlog-labels.ts` 가 아니라 이 파일이 소유한다 — `BacklogFilterBar` ·
// `BacklogEpicPanel` 이 같은 FR 에서 확립한 관례다. 값은 객체가 아니라 문자열 상수여야
// `react-refresh/only-export-components` 의 `allowConstantExport` 를 만족한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필터 결과가 0건일 때의 안내 1행 (F16-10).
 *
 * 이슈 목록 화면의 `필터 조건에 맞는 이슈가 없습니다.` 와 **다른 화면**이라 공존하지 않는다.
 */
export const BACKLOG_FILTERED_EMPTY_TITLE = '조건에 맞는 이슈가 없습니다.'

/**
 * 빈 상태의 초기화 CTA 이름.
 *
 * **`초기화` 단독으로 짓지 않는다** — 필터바가 이미 그 이름의 버튼을 갖고 있어(`filterBarLabels`)
 * 같은 화면에 동명 버튼이 둘이 된다. `routes/issues.index.tsx` 가 같은 이유로 `필터 초기화` 를
 * 쓰고 있고, 그 화면의 `IssueFilterBar` 와 이미 공존한다.
 */
export const BACKLOG_FILTER_RESET_LABEL = '필터 초기화'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogBoard 컴포넌트 Props */
export interface BacklogBoardProps {
  /** 프로젝트 키 — 백로그 데이터 조회 + 스프린트 생성에 사용 */
  projectKey: string
  /**
   * 화면이 보고 있는 보드 UUID. `?board=` 미지정이면 `undefined` (FR-BD-04).
   *
   * 🛑 **선택 prop 이 아니다.** 이 값은 조회 키(`backlogKeys.detail`)·스프린트 생성 body ·
   * 두 다이얼로그로 그대로 흘러간다. 빠뜨리면 어느 경로도 에러를 내지 않고 **기본 보드**로
   * 조용히 갈아탄다 — 화면은 A 보드인데 판정은 B 보드로 나는 오판이라 어떤 가드에도 안 걸린다.
   *
   * 값이 `undefined` 인 것 자체는 정상이다(사용자가 boardId 를 타이핑하지 않는다 · J17).
   * 그때 기본 보드를 고르는 것은 **서버**이고 프론트는 고르지 않는다.
   *
   * ★스프린트 생성 body 에도 **항상 싣는다** (E10). 빼면 백엔드가 첫 스크럼 보드로 폴백해
   * 두 번째 스크럼 보드에서 만든 스프린트가 남의 보드에 붙는다(부채 E-6).
   */
  boardId: string | undefined
  /**
   * 현재 필터 (F16-9). **이 컴포넌트는 제어형**이라 필터 state 를 갖지 않는다.
   *
   * 진실 출처는 URL 이고 소유자는 라우트다. 여기에 기본값이나 내부 폴백 state 를 두면
   * 「URL 이 비어 있을 때만 도는 두 번째 상태」가 생겨, 그때부터 화면과 주소창이 서로
   * 다른 말을 한다.
   */
  filter: BacklogFilter
  /** 필터 변경 요청 — 소유자(라우트)가 URL 에 싣는다 */
  onFilterChange: (next: BacklogFilter) => void
  /**
   * 스프린트 관리 권한(CREATE).
   * false이면 스프린트 생성·시작·완료 버튼이 비활성화된다.
   */
  canManageSprint?: boolean
  /**
   * 이슈 재정렬·할당·해제 권한(UPDATE).
   * false이면 드래그가 동작하지 않는다(DnD onDragEnd에서 조기 반환).
   */
  canReorderIssue?: boolean
  /**
   * 이슈 생성 권한(CREATE). **fail-closed** — 로딩·에러·미보유는 전부 false 다 (FR-UX-09 F3 FR-6).
   *
   * `canManageSprint` 와 출처는 같지만(`permissions.CREATE`) **이름을 분리한다** —
   * 「스프린트 관리」와 「이슈 생성」은 다른 행위이고, 한쪽 권한이 갈라지는 날
   * 같은 prop 을 쓰고 있으면 두 화면이 한꺼번에 잘못된다.
   */
  canCreateIssue?: boolean
}

// (드롭 존 파싱 헬퍼는 backlog-drag.ts의 순수 함수로 위임)

/** 열려 있는 스프린트 다이얼로그 — 종류와 대상 (F15 FR-3 · FR-5) */
interface SprintDialogTarget {
  /** 시작 다이얼로그인지 완료 다이얼로그인지 */
  readonly kind: 'start' | 'complete'
  /** 대상 스프린트 UUID */
  readonly sprintId: string
}

/**
 * 데이터가 아직 없을 때 공지 빌더에 넘길 빈 뷰.
 *
 * `useMemo` 는 조기 반환보다 **위**에 있어야 하므로(렌더마다 훅 개수가 같아야 한다)
 * `undefined` 를 대신할 값이 필요하다. 이 값으로 만든 공지는 로딩·에러 화면에서
 * `DndContext` 자체가 렌더되지 않아 소비되지 않는다.
 */
const EMPTY_BACKLOG_VIEW: BacklogView = { backlog: [], sprints: [], truncated: false }

// ─────────────────────────────────────────────────────────────────────────────
// 표시용 파생 (FR-UX-13 F16) — ★「보이는 것」과 「동작 대상」의 경계
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 세로 스택이 그릴 스프린트 섹션 하나 — **표시 전용 파생**이다.
 *
 * ★필드 이름이 `sprint` 가 아니라 `meta` 인 것은 취향이 아니라 **방어**다.
 * TypeScript 는 프로퍼티의 `readonly` 를 대입 가능성 판정에서 **무시**하므로, 이름이
 * `sprint` 였다면 이 배열이 `SprintWithIssues[]` 로 그대로 대입돼 필터 결과를 스프린트 완료
 * 다이얼로그에 넘기는 실수를 타입이 못 막는다. 그 실수는 되돌릴 수 없다 —
 * 완료된 스프린트에 남은 이슈는 `unassignIssue` 가 `status <> COMPLETED` 조건부 DELETE 라
 * 조용히 204 를 주고 `UNIQUE(issue_key)` 때문에 다른 스프린트로도 못 옮겨 **영구 동결**된다.
 */
interface BacklogDisplaySection {
  /** 섹션 헤더가 읽는 스프린트 메타 */
  readonly meta: SprintMeta
  /** 필터를 통과한 이슈만. 0건이어도 섹션 자체는 남는다(사라지면 「스프린트가 없어졌다」로 읽힌다) */
  readonly issues: BacklogIssue[]
}

/** 한 번의 필터 계산이 만드는 표시용 한 벌 */
interface BacklogDisplay {
  /** 스프린트 섹션 전량 (응답 순서 보존 — 백엔드 `sprintComparator` 가 이미 정렬했다) */
  readonly sections: readonly BacklogDisplaySection[]
  /** 백로그 칸에 그릴 이슈 */
  readonly backlogIssues: BacklogIssue[]
  /** 필터를 통과한 이슈 총수 — 0건 빈 상태 판정에 쓴다 */
  readonly visibleCount: number
}

/**
 * 필터를 적용해 **칸 컴포넌트가 받을 모양**으로 한 번에 만든다.
 *
 * 배열을 여기서 한 번만 복사하는 것이 요점이다. 렌더 안에서 `[...issues]` 를 하면 매 렌더
 * 새 참조가 되어 `BacklogColumn`·`SprintColumn` 의 `memo` 가 통째로 죽는다 — 백로그는 최대
 * 1,000건이고 제목 검색이 250ms 디바운스로 부모를 반복 재렌더한다.
 *
 * @param view 원본 백로그 응답. 아직 로딩 중이면 `undefined`
 * @param filter 현재 필터
 */
function buildDisplay(view: BacklogView | undefined, filter: BacklogFilter): BacklogDisplay {
  const filtered = filterBacklogView(view ?? EMPTY_BACKLOG_VIEW, filter)
  const sections = filtered.sprints.map((group) => ({
    meta: group.sprint,
    issues: [...group.issues],
  }))
  const backlogIssues = [...filtered.backlog]
  return {
    sections,
    backlogIssues,
    visibleCount:
      backlogIssues.length + sections.reduce((sum, section) => sum + section.issues.length, 0),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 필터 배선 (FR-UX-13 F16) — 상태 소유자는 **URL** 이다
// ─────────────────────────────────────────────────────────────────────────────

/** {@link useBacklogFilterWiring} 반환값 — 화면 곳곳이 소비하는 필터 한 벌 */
interface BacklogFilterWiring {
  /** 화면에 실제로 적용되는 필터 (EC9 정합을 마친 값) */
  readonly filter: BacklogFilter
  /** 필터바가 부르는 전체 교체 */
  readonly setFilter: (next: BacklogFilter) => void
  /** 에픽 패널이 부르는 축 하나 교체 (참조 안정) */
  readonly setEpicKeys: (next: string[]) => void
  /** 4축 전부 비우기 (참조 안정) */
  readonly reset: () => void
  /**
   * 필터바 재마운트 열쇠.
   *
   * ★`BacklogFilterBar` 는 제목 검색어를 **로컬 state 로** 쥐고 디바운스한다(그래야 키 입력
   * 마다 부모가 재렌더되지 않는다). 부모가 밖에서 `query` 만 비우면 로컬 입력이 그대로 남아
   * 디바운스가 **지운 검색어를 즉시 되돌려 놓는다** — 초기화가 튕긴다. 값이 바뀌면 필터바가
   * 재마운트되어 로컬 입력까지 함께 비워진다 (`BacklogFilterBar` KDoc 이 명시한 부모 책임).
   *
   * ★이것은 필터 값이 아니라 **표시 토큰**이다. 필터의 두 번째 사본이 아니므로 URL 소유권과
   * 충돌하지 않는다.
   */
  readonly filterBarKey: number
}

/** {@link useBacklogFilterWiring} 이 쥔 필터바 표시 상태 */
interface FilterBarRemountState {
  /** 재마운트 열쇠 */
  readonly key: number
  /** 초기화를 올려 두고 **URL 이 비워지기를 기다리는 중**인가 */
  readonly awaitingReset: boolean
}

/**
 * URL 이 들고 온 에픽 키 중 **이 백로그에 실재하는 것만** 남긴다 (EC9).
 *
 * 링크는 오래 산다 — 에픽이 지워지거나 다른 프로젝트의 URL 을 붙여 넣으면 아무것도 안 맞는
 * 키가 들어온다. 그대로 걸면 화면이 통째로 0건이 되어 「고장」으로 읽히므로, **조건을 버리고
 * 전량을 보인다**. 아는 키는 그대로 남긴다 — 하나가 낡았다고 나머지 선택까지 버리지 않는다.
 *
 * {@link NO_EPIC} 은 실제 이슈 키가 아니라 예약 센티널이라 `useBacklogEpics` 목록에 절대
 * 담기지 않는다. 목록만 기준으로 삼으면 「에픽 없음」 축이 통째로 죽으므로 항상 통과시킨다.
 *
 * @param filter URL 에서 복원한 필터
 * @param knownEpicKeys 백로그 응답에서 파생한 에픽 키 전량
 * @returns 정합을 마친 필터. 버릴 키가 없으면 **입력 참조를 그대로** 돌려준다
 */
function withKnownEpicsOnly(
  filter: BacklogFilter,
  knownEpicKeys: readonly string[],
): BacklogFilter {
  const known = new Set<string>(knownEpicKeys)
  const epicKeys = filter.epicKeys.filter((key) => key === NO_EPIC || known.has(key))
  return epicKeys.length === filter.epicKeys.length ? filter : { ...filter, epicKeys }
}

/**
 * 제어형 필터를 화면이 쓰기 좋은 한 벌로 묶는다.
 *
 * **필터 state 를 만들지 않는다.** 소유자는 URL(라우트)이고 이 훅은 ① EC9 정합 ② 필터바
 * 재마운트 토큰 두 가지만 더한다. 폴백 state 를 두면 소유자가 두 벌이 되어 새로고침·링크
 * 공유가 화면과 어긋나기 시작한다.
 *
 * @param filter 라우트가 URL 에서 복원해 넘긴 필터
 * @param onFilterChange 변경 요청 — 라우트가 URL 에 싣는다
 * @param knownEpicKeys 백로그 응답에서 파생한 에픽 키 전량 (EC9 정합 기준)
 */
function useBacklogFilterWiring(
  filter: BacklogFilter,
  onFilterChange: (next: BacklogFilter) => void,
  knownEpicKeys: readonly string[],
): BacklogFilterWiring {
  const effectiveFilter = useMemo(
    () => withKnownEpicsOnly(filter, knownEpicKeys),
    [filter, knownEpicKeys],
  )
  const [barState, setBarState] = useState<FilterBarRemountState>({ key: 0, awaitingReset: false })

  // ★★재마운트는 **URL 이 실제로 비워진 렌더**에서 일어나야 한다.
  //
  //   초기화 시점에 곧바로 열쇠를 올리면, 그 렌더의 `filter` 는 아직 옛 값이라 새로 마운트된
  //   필터바가 **지운 검색어를 그대로 다시 집어 든다**. 그다음 URL 이 비워지면 디바운스가
  //   그 값을 도로 밀어 올려 초기화가 통째로 무효가 된다 — 유닛은 초록인데 브라우저에서만
  //   「눌러도 아무 일이 없는」 버튼이 된다(Task 8 눈확인 실측).
  //
  //   effect 로 미루는 것도 답이 아니다. 자식 effect 가 부모보다 **먼저** 돌아, 비워진 URL 을
  //   본 필터바가 열쇠가 오르기 전에 낡은 값을 밀어 올린다. 그래서 렌더 중 조정
  //   (React 공식 「prop 이 바뀔 때 state 조정」 패턴)이다 — 이 갱신은 자식이 커밋되기 전에
  //   흡수되므로 낡은 필터바 인스턴스가 애초에 그 렌더를 보지 못한다.
  if (barState.awaitingReset && isEmptyFilter(effectiveFilter)) {
    setBarState({ key: barState.key + 1, awaitingReset: false })
  }

  const setEpicKeys = useCallback(
    (next: string[]) => {
      onFilterChange({ ...effectiveFilter, epicKeys: next })
    },
    [effectiveFilter, onFilterChange],
  )

  const reset = useCallback(() => {
    setBarState((prev) => ({ ...prev, awaitingReset: true }))
    onFilterChange(emptyBacklogFilter())
  }, [onFilterChange])

  return {
    filter: effectiveFilter,
    setFilter: onFilterChange,
    setEpicKeys,
    reset,
    filterBarKey: barState.key,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogBoard 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 보드 루트.
 *
 * - useBacklog로 데이터를 로드하고 SprintColumn들 + BacklogColumn을 **세로로** 쌓는다 (F15 FR-1).
 * - 스프린트 생성 폼은 스택 바깥이 아니라 **BacklogColumn 헤더 안**에 있다 (F16 F16-11).
 *   이 컴포넌트는 제출 콜백만 `useCallback` 안정 참조로 넘긴다.
 * - 필터바·에픽 패널은 칸 `region` **바깥**, 세로 스택 **위**다 (F16). 섹션 안에 넣으면
 *   `backlog.spec.ts:253` 의 칸 locator(region textContent 선두 앵커)가 즉사한다.
 * - ★필터는 **제어형**이다 (F16-9). 값의 소유자는 URL(라우트)이고 여기엔 필터 state 가 없다 —
 *   폴백을 두면 소유자가 두 벌이 되어 새로고침·링크 공유가 화면과 어긋난다.
 * - ★필터는 **표시만** 좁힌다. 스프린트 완료·DnD·드래그 공지는 전부 **원본 `backlogView`** 를
 *   본다 (F16 R6). 두 집합을 한 변수로 합치지 않는다.
 * - DndContext + PointerSensor(distance:5)로 드래그를 관리한다.
 * - onDragEnd에서 resolveBacklogDropAction으로 시나리오를 판정해 mutation을 호출한다.
 * - C1: assign/unassign 성공 후 rerank 실패 → 경고 토스트. 이동은 완료됐으므로 에러 토스트 금지.
 * - truncated=true이면 경고 배너를 표시한다.
 * - 조회 실패 시 안내와 재시도 버튼을 그린다 (FR-UX-13 F5 G2).
 */
export function BacklogBoard({
  projectKey,
  boardId,
  filter,
  onFilterChange,
  canManageSprint = true,
  canReorderIssue = true,
  canCreateIssue = false,
}: BacklogBoardProps): JSX.Element {

  /**
   * 이슈 생성 흐름 — 어느 칸이 열었는지 · 생성 후 배정 · 목록 갱신을 한 곳에 모았다.
   *
   * ★모달은 **화면당 1개**다 (FR-15). 칸마다 두면 `role="dialog"` 가 N개가 되어
   * 조회가 strict mode 로 깨진다 (F2 가 겪은 164발생 함정과 같은 결).
   */
  const createIssue = useBacklogCreateIssue(projectKey)

  /**
   * 열려 있는 스프린트 다이얼로그. **화면당 1개**다 — 칸마다 두면 `role="dialog"` 가
   * N개가 되어 조회가 strict mode 로 깨진다 (`CreateIssueDialog` 가 확립한 FR-15 원칙).
   */
  const [sprintDialog, setSprintDialog] = useState<SprintDialogTarget | null>(null)

  const { data: backlogView, isLoading, isError, isFetching, refetch } = useBacklog(projectKey, boardId)

  // 담당자 이름 — 화면에 등장하는 id 만 모아 50개씩 나눠 전량 조회한다 (스펙 M1).
  // ★조기 반환(`isLoading`)보다 **위**에 있어야 한다. 아래로 내리면 렌더마다 훅 개수가
  //   달라져 React 가 즉사한다. 그래서 두 순수 함수가 `undefined` 를 받아낸다.
  const assigneeIds = useMemo(() => collectAssigneeIds(backlogView), [backlogView])
  const { data: assigneeUsers } = useUsersByIdsChunked(assigneeIds)
  const assigneeNames = useMemo(
    () => buildAssigneeNameMap(backlogView, assigneeUsers),
    [backlogView, assigneeUsers],
  )

  // 이슈 타입 — 카드의 유형 아이콘·이름 해석용 맵 (FR-UX-14 F14 Task 4). 칸마다 부르지 않고
  // 이 컴포넌트에서 **1회만** 조회해 스프린트 칸 전체 + 백로그 칸에 공유한다.
  // ★조기 반환(`isLoading`)보다 **위**에 있어야 한다 — 위 담당자 이름 블록과 같은 이유다.
  // 조회 실패·로딩 중이면 빈 배열 → 빈 맵 → 전 카드가 FR6 fallback(typeKey 원문) 경로를 탄다.
  // ★구조분해 기본값(`= []`)을 쓰지 않는다. 조회 실패·로딩 중이면 `data` 가 `undefined` 라
  // `= []` 가 **매 렌더 새 배열 리터럴**로 평가되고, 그 값이 아래 useMemo 의존성이라
  // 맵 참조가 매 렌더 바뀐다 → 칸(Column)의 memo 얕은 비교가 깨져 **드래그 중 포인터가
  // 움직일 때마다 전 칸의 카드 전량이 재렌더**된다. `undefined` 는 렌더마다 같은 값이라
  // 의존성이 안정된다. (NFR1/NFR2 의 실질 조건은 카드 prop 이 아니라 이 칸 경계다.)
  const { data: issueTypes } = useIssueTypes()
  const issueTypesByKey = useMemo(
    () => new Map((issueTypes ?? []).map((t) => [t.key, t])),
    [issueTypes],
  )

  // 필터 (F16). ★상태 소유자는 **URL** 이다 (F16-9) — 이 컴포넌트는 받은 값을 그릴 뿐이고,
  // 훅은 EC9 정합과 필터바 재마운트 토큰만 얹는다.
  const { epicKeys, epicNames } = useBacklogEpics(backlogView)
  const filterState = useBacklogFilterWiring(filter, onFilterChange, epicKeys)

  // ★표시용 파생. `useMemo` 는 성능 최적화가 아니라 **배선 조건**이다 — 칸들이 `memo` 라
  //   매 렌더 새 배열을 넘기면 재렌더 스킵이 통째로 죽는다(최대 1,000건).
  //   조기 반환(`isLoading`)보다 **위**에 있어야 렌더마다 훅 개수가 같다.
  const display = useMemo(
    () => buildDisplay(backlogView, filterState.filter),
    [backlogView, filterState.filter],
  )

  const createSprint = useCreateSprint(projectKey)

  // 스프린트 생성 제출 — `BacklogColumn` 헤더의 폼이 부른다 (F16 F16-11).
  //
  // ★`useCallback` 이 **성능 최적화가 아니라 배선 조건**이다. `BacklogColumn` 은 `memo` 라
  //   매 렌더 새 함수를 넘기면 memo 가 통째로 죽어 카드 전량이 재렌더된다(최대 1,000건).
  //   의존은 `createSprint` 객체가 아니라 **`createSprint.mutate`** 다 — `useMutation` 은
  //   렌더마다 새 결과 객체를 만들지만 `mutate` 는 observer 에 묶인 안정 참조다.
  //   조기 반환(`isLoading`)보다 **위**에 있어야 렌더마다 훅 개수가 같다.
  const createSprintMutate = createSprint.mutate
  const handleCreateSprint = useCallback(
    (name: string) => {
      createSprintMutate(
        { projectKey, boardId, name },
        { onError: (error) => toast.error(sprintCreateErrorMessage(error)) },
      )
    },
    [createSprintMutate, projectKey, boardId],
  )

  /** 드래그 처리 — 드롭 판정과 이동/재정렬 mutation 을 함께 쥔다. */
  const drag = useBacklogDrag(projectKey, backlogView, canReorderIssue)

  // 한국어 드래그 공지 (FR-9). ★`canReorderIssue` 를 반드시 넘긴다 — 안 넘기면 권한이
  // 없어 mutation 이 0건인 사용자에게 「옮겼습니다」를 읽어 주는 거짓말이 된다 (FR-17).
  // ★`drag.wasLastDropZeroMove` 도 같은 이유다 — 훅과 lib 이 각각 맞아도 이 통로가 끊기면
  //   집자마자 놓아 mutation 이 0건인데 「순서를 변경했습니다」를 읽는다 (T12).
  const announcements = useMemo(
    () =>
      buildBacklogAnnouncements(
        backlogView ?? EMPTY_BACKLOG_VIEW,
        canReorderIssue,
        drag.wasLastDropZeroMove,
      ),
    [backlogView, canReorderIssue, drag.wasLastDropZeroMove],
  )

  const sensors = useSensors(
    // `distance: 5` 는 **그대로 둔다** — 카드 안 `Link` 클릭 보존이 이 값에 걸려 있다 (FR-8).
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    // 좌표 계산기 + 활성화 키 제한을 함께 넘긴다 (FR-15 · FR-16). 기본값은 방향키 1회에
    // 25px 이라 세로 스택에서 옆 섹션까지 30회가 넘고, `start` 의 Enter 가 카드 안 이슈
    // 링크를 삼켜 키보드 사용자가 이슈로 갈 수 없다.
    useSensor(KeyboardSensor, backlogKeyboardSensorOptions),
  )

  if (isLoading) return <BacklogLoading />

  // 조회 실패 — 재시도 수단이 없으면 사용자는 새로고침 말고 탈출구가 없다 (ActiveProjectGate 선례).
  //
  // 순서는 `isLoading` **다음**으로 둔다. 다만 「앞에 두면 재조회 중에도 에러 화면이 깜빡인다」는
  // 근거는 **거짓이다** — `isLoading = isPending && isFetching` 이고 `isPending`·`isError` 는
  // 같은 `status` 열거의 배타 값이라 **동시에 참이 될 수 없다**
  // (@tanstack/query-core@5.100.11 `build/modern/queryObserver.js:308-310`).
  // 즉 순서를 바꿔도 현재 관측 가능한 차이가 없고, 그래서 이 순서만을 봉인하는 테스트도
  // 존재할 수 없다(도달 불가능한 픽스처를 지어내야 하므로). 순서를 고정하는 것은
  // 「로딩이 먼저」라는 의도를 코드에 남기기 위함이 전부다.
  // 도달 가능한 로딩 상태 자체는 `BacklogBoard.test.tsx` 의 T4-3 이 잰다 (FR-8·E8).
  if (isError) {
    return <BacklogLoadError isFetching={isFetching} onRetry={() => { void refetch() }} />
  }

  if (backlogView === undefined) return <div />

  // ★원본이다. 필터 결과가 아니다 — 아래에서 **동작 대상 집합**으로만 쓴다 (F16 R6).
  const { sprints, truncated } = backlogView

  /** 다이얼로그 닫힘 — 대상을 비워 **언마운트**한다. 낡은 폼·회차 state 가 남지 않는다 */
  function handleSprintDialogOpenChange(next: boolean): void {
    if (!next) setSprintDialog(null)
  }

  return (
    <div className="flex flex-col gap-4">
      <BacklogBoardHeader
        projectKey={projectKey}
        truncated={truncated}
        filterState={filterState}
        epicKeys={epicKeys}
        epicNames={epicNames}
      />

      <DndContext
        sensors={sensors}
        collisionDetection={cardFirstCollision}
        onDragOver={drag.handleDragOver}
        onDragEnd={drag.handleDragEnd}
        // 취소(Esc)는 `onDragOver(null)` 을 부르지 않아(`active` 가 이미 null 이라 조기 반환)
        // 마지막 하이라이트가 섹션에 그대로 남는다. 「아무 데도 올라가 있지 않다」를
        // 같은 통로로 알려 지운다 — mutation 경로인 `handleDragEnd` 는 쓸 수 없다.
        onDragCancel={(event) => { drag.handleDragOver({ ...event, over: null }) }}
        accessibility={{ announcements, screenReaderInstructions: backlogScreenReaderInstructions }}
      >
        <BacklogStack
          projectKey={projectKey}
          boardId={boardId}
          display={display}
          assigneeNames={assigneeNames}
          issueTypesByKey={issueTypesByKey}
          overDroppableId={drag.overDroppableId}
          // 「필터가 걸려 있는데 보이는 것이 0건」일 때만 빈 상태다 (EC1).
          // 필터가 없는 0건은 칸의 기존 「이슈 없음」이 말한다 — 「고장」과 「할 일 없음」의 구별.
          filterActive={!isEmptyFilter(filterState.filter)}
          onResetFilter={filterState.reset}
          canManageSprint={canManageSprint}
          canCreateIssue={canCreateIssue}
          createIssue={createIssue}
          onOpenSprintDialog={setSprintDialog}
          onCreateSprint={handleCreateSprint}
          createSprintDisabled={!canManageSprint || createSprint.isPending}
        />
      </DndContext>

      {/* 이슈 생성 모달 — 화면당 1개. 어느 칸이 눌렀는지는 `createTarget` 이 쥔다 (FR-15).
          프로젝트는 명시로 넘긴다 — 전역 활성 프로젝트를 경유하면 목록 대조 가드가
          아직 통과하지 못한 순간 다른 프로젝트가 채워진 채로 열린다 (스펙 §8 D-A). */}
      <CreateIssueDialog
        open={createIssue.isOpen}
        // 닫힘은 **가시성만** 끈다 — 대상은 훅이 `onCreated` 에서 읽은 뒤에 비운다.
        onOpenChange={(open) => { if (!open) createIssue.close() }}
        initialProjectKey={projectKey}
        onCreated={createIssue.onCreated}
      />

      <SprintDialogHost
        target={sprintDialog}
        projectKey={projectKey}
        boardId={boardId}
        sprints={sprints}
        truncated={truncated}
        canReorderIssue={canReorderIssue}
        onOpenChange={handleSprintDialogOpenChange}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 조회 상태 표시 — 로딩 / 실패
// ─────────────────────────────────────────────────────────────────────────────

/** 최초 로드 중 표시. 접근명 `로딩 중` 은 e2e·유닛이 함께 조회한다 (F5 T4-3) */
function BacklogLoading(): JSX.Element {
  return (
    <div className="flex items-center justify-center py-16" aria-label="로딩 중">
      <span className="text-sm text-muted-foreground">로딩 중...</span>
    </div>
  )
}

/**
 * 조회 실패 안내 + 재시도 (FR-UX-13 F5 G2).
 *
 * @param isFetching 재조회 중인가. `isLoading` 은 최초 1회만 true 라 재조회를 못 잡는다
 * @param onRetry 재조회 요청
 */
function BacklogLoadError({
  isFetching,
  onRetry,
}: {
  readonly isFetching: boolean
  readonly onRetry: () => void
}): JSX.Element {
  return (
    <div role="alert" className="flex flex-col items-start gap-3 p-8 text-destructive">
      <p>{backlogLabels.loadFailed}</p>
      {/* 재조회 중에는 버튼이 스스로 상태를 말한다 (design review D3).
          비활성화가 연타로 인한 중복 요청을 구조적으로 막는다. */}
      <Button type="button" variant="outline" size="sm" disabled={isFetching} onClick={onRetry}>
        {isFetching ? backlogLabels.retrying : backlogLabels.retry}
      </Button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogBoardHeader — 잘림 경고 + 필터 컨트롤 (세로 스택 **밖**)
// ─────────────────────────────────────────────────────────────────────────────

/** {@link BacklogBoardHeader} Props */
interface BacklogBoardHeaderProps {
  /** 소속 프로젝트 키 — 필터바 내부 쿼리와 패널 접힘 영속에 쓴다 */
  readonly projectKey: string
  /** 백로그 응답의 `truncated` — **원본 값**이다. 필터가 잘림을 풀지 못한다 */
  readonly truncated: boolean
  /** 필터 배선 한 벌 — 값의 소유자는 URL 이다 (F16-9) */
  readonly filterState: BacklogFilterWiring
  /** 백로그에 등장하는 에픽 키 전량 */
  readonly epicKeys: string[]
  /** 에픽 키 → 표시 이름 */
  readonly epicNames: ReadonlyMap<string, string>
}

/**
 * 잘림 경고 배너와 필터 컨트롤 2종.
 *
 * ★**칸 `region` 바깥, 세로 스택 위**라는 것이 계약이다 (F16 §렌더 순서).
 * `backlog.spec.ts:253` 의 칸 locator 가 region textContent 선두를 앵커링하므로, 이 셋 중
 * 하나라도 섹션 안으로 들어가면 그 즉시 e2e 가 죽는다.
 *
 * ★잘림 경고가 **필터 결과와 같은 분기에 있지 않다**는 것도 계약이다 (C5). 필터 결과가
 * 0건일 때 「조건에 맞는 이슈가 없습니다」만 뜨면 거짓말이다 — 안 온 이슈가 조건에 맞을 수 있다.
 *
 * ★`epicNames` 는 필터바와 패널에 **같은 객체**를 넘긴다. 갈리면 칩과 목록이 서로 다른
 * 이름을 말한다.
 *
 * @param props 프로젝트 키 · 잘림 여부 · 필터 상태 · 에픽 목록
 * @returns 경고 배너(조건부) + 필터바 + 에픽 패널
 */
function BacklogBoardHeader({
  projectKey,
  truncated,
  filterState,
  epicKeys,
  epicNames,
}: BacklogBoardHeaderProps): JSX.Element {
  return (
    <>
      {truncated && (
        <div
          role="alert"
          className="rounded-md border border-warning bg-warning/10 px-4 py-2 text-sm"
        >
          {backlogLabels.truncatedWarning}
        </div>
      )}

      {/* `key` 는 초기화가 **튕기지 않게** 하는 배선이다 — `filterBarKey` KDoc 참조 */}
      <BacklogFilterBar
        key={filterState.filterBarKey}
        projectKey={projectKey}
        value={filterState.filter}
        onChange={filterState.setFilter}
        epicNames={epicNames}
      />
      <BacklogEpicPanel
        projectKey={projectKey}
        epicKeys={epicKeys}
        epicNames={epicNames}
        value={filterState.filter.epicKeys}
        onChange={filterState.setEpicKeys}
      />
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogStack — 세로 스택 또는 필터 0건 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

/** {@link BacklogStack} Props */
interface BacklogStackProps {
  /** 소속 프로젝트 키 — 칸의 접힘 영속·번다운 링크에 쓴다 */
  readonly projectKey: string
  /**
   * 화면이 보고 있는 보드 UUID. `?board=` 미지정이면 `undefined` (FR-BD-04).
   *
   * 칸 → 헤더 → `⋯` → 편집 다이얼로그로 **그대로 흘려보낸다.** 거기서 409 복구가 보드
   * 스코프 캐시를 완전 일치 키로 읽으므로 중간에서 끊으면 재시도가 409 를 되풀이한다.
   */
  readonly boardId: string | undefined
  /** 표시용 파생 한 벌 (필터 적용 후) */
  readonly display: BacklogDisplay
  /** 이슈 키 → 담당자 표시 이름 */
  readonly assigneeNames: Map<string, string>
  /** 이슈 타입 키 → 응답 맵 — 칸에 그대로 흘려보내 카드 유형 아이콘을 해석한다 (F14 Task 4) */
  readonly issueTypesByKey: Map<string, IssueTypeResponse>
  /** 드래그가 올라가 있는 droppable id */
  readonly overDroppableId: string | null
  /** 필터가 하나라도 걸려 있는가 — 0건 빈 상태의 종류를 가른다 (EC1) */
  readonly filterActive: boolean
  /** 빈 상태의 초기화 CTA */
  readonly onResetFilter: () => void
  /** 스프린트 관리 권한(CREATE) */
  readonly canManageSprint: boolean
  /** 이슈 생성 권한(CREATE) — fail-closed */
  readonly canCreateIssue: boolean
  /** 이슈 생성 흐름 — 진입점 콜백을 칸별로 꺼낸다 */
  readonly createIssue: BacklogCreateIssue
  /** 시작/완료 다이얼로그 열기 */
  readonly onOpenSprintDialog: (target: SprintDialogTarget) => void
  /** 스프린트 생성 제출 (참조 안정) */
  readonly onCreateSprint: (name: string) => void
  /** 스프린트 생성 폼 비활성 여부 (권한 없음 또는 진행 중) */
  readonly createSprintDisabled: boolean
}

/**
 * 세로 스택 본문 — 스프린트 칸들 + 백로그 칸, 또는 필터 0건 빈 상태.
 *
 * `BacklogBoard` 가 200줄(`DEVELOPMENT.md §2.2`)을 넘어 분리했다. 경계는 **책임**을 따랐다 —
 * 여기는 「무엇을 그리나」이고, 남은 쪽은 데이터·필터·다이얼로그 라이프사이클이다.
 *
 * 필터 0건이면 칸을 **대체**한다. 「0개 이슈」 칸을 N개 남기면 사용자는 조건을 좁혀서 없는
 * 것인지 데이터가 없는 것인지 구별할 수 없고, 초기화 수단도 화면 위쪽 필터바에만 남는다.
 *
 * @param props 표시용 파생과 칸에 그대로 흘려보낼 권한·콜백
 * @returns 세로 스택 또는 빈 상태
 */
function BacklogStack({
  projectKey,
  boardId,
  display,
  assigneeNames,
  issueTypesByKey,
  overDroppableId,
  filterActive,
  onResetFilter,
  canManageSprint,
  canCreateIssue,
  createIssue,
  onOpenSprintDialog,
  onCreateSprint,
  createSprintDisabled,
}: BacklogStackProps): JSX.Element {
  if (filterActive && display.visibleCount === 0) {
    return (
      <FilteredEmptyState
        title={BACKLOG_FILTERED_EMPTY_TITLE}
        resetLabel={BACKLOG_FILTER_RESET_LABEL}
        // 보드 화면과 같은 값이다 — 빈 상태로 바뀌는 순간 스택 영역이 오그라들며 레이아웃이 튄다
        className="min-h-48"
        onReset={onResetFilter}
      />
    )
  }

  return (
    // 세로 스택 (F15 FR-1) — 스프린트가 먼저, 백로그가 맨 마지막이다 (Jira 와 같은 순서).
    // ★클라이언트 정렬을 넣지 않는다 — 백엔드 `sprintComparator` 가 ACTIVE → PLANNED →
    // COMPLETED, 그다음 startDate 로 이미 정렬해 내려주므로 규칙이 두 벌로 갈라진다.
    <div className="flex flex-col gap-3">
      {display.sections.map(({ meta, issues }) => (
        <SprintColumn
          key={meta.sprintId}
          projectKey={projectKey}
          boardId={boardId}
          sprint={meta}
          issues={issues}
          assigneeNames={assigneeNames}
          issueTypesByKey={issueTypesByKey}
          isOver={overDroppableId === `sprint-${meta.sprintId}`}
          // 버튼은 곧장 mutation 을 쏘지 않고 다이얼로그를 연다 (F15 FR-3 · FR-5).
          // 버튼 **이름**(`스프린트 시작`·`스프린트 완료`)은 그대로다 (FR-10 즉사 계약).
          onStart={canManageSprint
            ? () => { onOpenSprintDialog({ kind: 'start', sprintId: meta.sprintId }) }
            : undefined}
          onComplete={canManageSprint
            ? () => { onOpenSprintDialog({ kind: 'complete', sprintId: meta.sprintId }) }
            : undefined}
          canCreateIssue={canCreateIssue}
          // `⋯` 메뉴(편집·삭제)의 게이트. 시작/완료와 **같은 권한**이므로 같은 값을 쓴다 —
          // 권한이 없으면 버튼이 비활성이 아니라 메뉴가 통째로 부재한다 (FR-5).
          canManageSprint={canManageSprint}
          onCreateIssue={createIssue.openForSprint(meta.sprintId)}
        />
      ))}
      <BacklogColumn
        projectKey={projectKey}
        issues={display.backlogIssues}
        assigneeNames={assigneeNames}
        issueTypesByKey={issueTypesByKey}
        isOver={overDroppableId === 'backlog'}
        canCreateIssue={canCreateIssue}
        onCreateIssue={createIssue.openForBacklog}
        // 스프린트 생성 폼은 이 칸의 헤더가 소유한다 (F16 F16-11).
        // 「권한 없음 OR 생성 진행 중」 판정은 부모 **한 곳**에서만 한다.
        onCreateSprint={onCreateSprint}
        createSprintDisabled={createSprintDisabled}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SprintDialogHost — 시작/완료 다이얼로그 마운트 지점
// ─────────────────────────────────────────────────────────────────────────────

/** {@link SprintDialogHost} Props */
interface SprintDialogHostProps {
  /** 열려 있는 다이얼로그. `null` 이면 아무것도 마운트하지 않는다 */
  readonly target: SprintDialogTarget | null
  /** mutation·invalidate 대상 프로젝트 키. 전역 활성 프로젝트를 경유하지 않는다 */
  readonly projectKey: string
  /**
   * 화면이 보고 있는 보드 UUID (FR-BD-04). 두 다이얼로그가 **백로그 캐시를 완전 일치 키로**
   * 읽으므로 여기서 끊기면 시작의 409 복구와 완료의 재검증이 다른 보드를 본다(각 파일 KDoc).
   */
  readonly boardId: string | undefined
  /** 현재 뷰의 스프린트 전량 — 대상 조회와 이관 후보 목록에 함께 쓴다 */
  readonly sprints: readonly SprintWithIssues[]
  /** 백로그 응답의 `truncated`. 완료 제출 차단 조건이다 (FR-13 · E15) */
  readonly truncated: boolean
  /** 이슈 재정렬·할당·해제 권한(UPDATE) (FR-12) */
  readonly canReorderIssue: boolean
  /** 닫힘 요청 */
  readonly onOpenChange: (open: boolean) => void
}

/**
 * 스프린트 시작/완료 다이얼로그를 **화면당 1개씩만** 마운트한다 (FR-15 원칙 승계).
 *
 * ### 왜 대상이 없을 때 아예 마운트하지 않나
 * 완료 다이얼로그는 `GET /api/v1/workflows` 를 부른다(NFR-2). 항상 마운트하면 백로그를
 * 여는 **모든** 사용자에게 요청이 1건 늘어 NFR-1(요청 수 무증가)을 깬다.
 *
 * ### 왜 `key` 를 주나
 * 두 다이얼로그 모두 기준값·회차 상태를 내부 state 에 쥔다(각 파일 KDoc 의 명시 요구).
 * 대상이 바뀌어도 재마운트되지 않으면 낡은 값이 그대로 남는다.
 *
 * @param props 열린 다이얼로그와 대상 조회에 필요한 뷰 조각
 * @returns 열려 있는 다이얼로그 1개. 없으면 `null`
 */
function SprintDialogHost({
  target,
  projectKey,
  boardId,
  sprints,
  truncated,
  canReorderIssue,
  onOpenChange,
}: SprintDialogHostProps): JSX.Element | null {
  if (target === null) return null

  // 완료 다이얼로그가 이슈 전량을 필요로 하므로 `SprintWithIssues` 를 통째로 넘긴다 —
  // `SprintMeta` 만으로는 미완료 판정(FR-7)을 할 수 없다.
  const entry = sprints.find((item) => item.sprint.sprintId === target.sprintId)
  if (entry === undefined) return null

  if (target.kind === 'start') {
    return (
      <StartSprintDialog
        key={entry.sprint.sprintId}
        open
        onOpenChange={onOpenChange}
        projectKey={projectKey}
        boardId={boardId}
        sprint={entry.sprint}
      />
    )
  }

  return (
    <CompleteSprintDialog
      key={entry.sprint.sprintId}
      open
      onOpenChange={onOpenChange}
      projectKey={projectKey}
      boardId={boardId}
      sprint={entry}
      allSprints={sprints.map((item) => item.sprint)}
      truncated={truncated}
      // 이관은 `POST /{id}/issues`·`DELETE` 둘 다 UPDATE 인데 `complete` 만 CREATE 다.
      // CREATE 만 가진 사용자는 이관이 전건 403 이므로 그 UI 를 게이팅한다 (FR-12).
      canReorderIssue={canReorderIssue}
    />
  )
}
