// 이슈 목록을 ui/table 기반 표로 렌더하는 프레젠테이션 컴포넌트 — 정렬 헤더·컬럼 표시·선택 (FR-UX-06 Phase 5 PR18 Task 4)
import type { JSX } from 'react'
import { useRef } from 'react'
import { ChevronDown, ChevronUp, ChevronsUpDown } from 'lucide-react'
import { MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH } from '@/hooks/use-column-widths'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useDateFormat } from '@/hooks/use-date-format'
import type { IssueResponse, IssueSortField } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import { resolveCardType } from '@/components/issue/resolve-card-type'
import { ISSUE_COLUMNS, ISSUE_COLUMN_DEFAULT_WIDTHS, getSortField } from './issue-columns'
import type { IssueCellEditContext, IssueColumnDef } from './issue-columns'
import type { StateCategory } from './issue-visuals'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 미전달 시 쓰는 빈 맵 상수 2종.
 *
 * 기본값을 `new Map()` 리터럴로 쓰면 렌더마다 새 객체가 생겨 자식이 매번 갱신된다.
 * 모듈 상수 하나를 공유하면 참조가 고정된다.
 */
const EMPTY_ISSUE_TYPES: Map<string, IssueTypeResponse> = new Map()
const EMPTY_STATUS_META: Map<string, StatusMeta> = new Map()

/** 체크박스 열 폭(px) — 컬럼 시스템 밖의 구조적 고정 열이라 조절 대상이 아니다 */
const CHECKBOX_COLUMN_WIDTH = 40

/** `onResizeColumn` 미전달 시의 기본값 — 매 렌더 새 함수를 만들지 않도록 모듈 상수로 둔다 */
function noopResize(): void {}

/** 상태 키 하나의 해석 결과 — 배지의 표기와 색을 함께 결정한다 */
export interface StatusMeta {
  /** 표시 이름 (예: `진행 중`) */
  name: string
  /** 카테고리 — 색의 근거 */
  category: StateCategory
}

/** 현재 정렬 상태 — null이면 정렬 미적용(서버 기본 정렬 유지) */
export interface IssueTableSortState {
  field: IssueSortField
  dir: 'asc' | 'desc'
}

/** 선택(체크박스) 상태 + 콜백 묶음 */
export interface IssueTableSelectionProps {
  /** 특정 이슈 키의 선택 여부 */
  isSelected: (key: string) => boolean
  /** 개별 체크박스 토글 콜백 */
  onToggle: (key: string) => void
  /** 현재 페이지 전체 선택 토글 콜백 */
  onSelectAllPage: () => void
  /** 현재 페이지 전체 선택 여부 */
  isAllPageSelected: boolean
}

export interface IssueTableProps {
  /** 렌더할 이슈 목록 */
  issues: IssueResponse[]
  /** 현재 표시 중인 컬럼 키 목록(useColumnVisibility().visible을 그대로 전달 가능) */
  visibleColumnKeys: readonly string[]
  /** 현재 정렬 상태. null이면 정렬 미적용 */
  sort: IssueTableSortState | null
  /** 정렬 헤더 클릭 콜백 — 다음 정렬 상태 계산(3-state 토글 등)은 호출 측 책임 */
  onSort: (field: IssueSortField) => void
  /** 선택 상태 + 콜백 */
  selection: IssueTableSelectionProps
  /** 행 네비게이션 콜백 */
  onNavigate: (key: string) => void
  /** assigneeId → 표시 이름 맵(FilterBar.tsx assigneeNameMap 관례와 동일) */
  assigneeNameMap: Map<string, string>
  /**
   * typeKey → 이슈 유형 맵. 조회 실패·로딩 중이면 빈 맵/미전달 —
   * 그때는 전 행이 `typeKey` 원문 + Circle 아이콘 폴백을 탄다(FR6, BoardColumn 과 같은 계약).
   */
  issueTypesByKey?: Map<string, IssueTypeResponse>
  /**
   * 상태 키 → 표시 이름·카테고리 맵. 미전달이면 배지가 원시 키 + 중립색으로 폴백한다.
   * 정본은 `useWorkflows()` — 이 컴포넌트는 훅을 갖지 않으므로 호출 측이 만들어 넘긴다.
   */
  statusMetaByKey?: Map<string, StatusMeta>
  /**
   * 컬럼 키 → 폭(px). 미전달이면 {@link ISSUE_COLUMN_DEFAULT_WIDTHS}.
   * 영속은 호출 측 `useColumnWidths` 가 한다 — 이 컴포넌트는 훅을 갖지 않는다.
   */
  widths?: Readonly<Record<string, number>>
  /** 폭 확정 콜백. 미전달이면 손잡이는 보이되 아무 일도 하지 않는다(읽기 전용 표) */
  onResizeColumn?: (key: string, width: number) => void
  /**
   * 우측 상세 페인(split view)에 현재 열려 있는 이슈 키 — bulk `selection`(체크박스)과는
   * 완전히 독립된 별개 강조다. undefined/null이면 어떤 행도 강조하지 않는다.
   */
  selectedKey?: string | null
  /**
   * 셀 인라인 편집 컨텍스트 (FR-UX-11 F9). 미전달이면 읽기 전용(기존 동작).
   * `listQueryKey`는 호출 측의 `useQuery` queryKey와 **같은 값**이어야 한다.
   */
  edit?: IssueCellEditContext
}

// ─────────────────────────────────────────────────────────────────────────────
// 컬럼 폭 조절 손잡이
// ─────────────────────────────────────────────────────────────────────────────

/** 키보드 한 번에 움직이는 폭(px). Shift 를 누르면 {@link KEYBOARD_STEP_LARGE_PX} */
const KEYBOARD_STEP_PX = 16

/** Shift + 화살표로 움직이는 폭(px) — 먼 거리를 옮길 때 화살표 40번을 누르지 않게 한다 */
const KEYBOARD_STEP_LARGE_PX = 64

interface ColumnResizeHandleProps {
  /** 접근성 이름에 쓸 컬럼 표시 이름 */
  header: string
  /** 현재 폭(px) — 드래그 시작점이자 `aria-valuenow` */
  width: number
  /** 새 폭 확정 콜백. 하한·상한 클램프는 `useColumnWidths` 가 한다 */
  onResize: (width: number) => void
}

/**
 * 헤더 오른쪽 경계의 폭 조절 손잡이.
 *
 * Jira Cloud 와 같은 조작이다 — 「drag the right border of a column to resize it」
 * (support.atlassian.com/jira-software-cloud/docs/what-is-the-new-jira-issue-search-experience/,
 * 2026-09-07 조회).
 *
 * ★**키보드로도 조절된다.** 드래그 전용으로 두면 포인터를 못 쓰는 사용자에게 이 기능이
 * 통째로 없는 것과 같다. WAI-ARIA `separator`(window splitter) 패턴 — 화살표 좌우로
 * {@link KEYBOARD_STEP_PX}px, Shift 조합이면 {@link KEYBOARD_STEP_LARGE_PX}px 움직인다.
 *
 * ★**클릭이 헤더로 번지지 않게 끊는다.** 손잡이는 정렬 헤더 안에 있어서, 전파를 막지
 * 않으면 폭을 조절할 때마다 정렬이 뒤집힌다.
 *
 * @param props 컬럼 이름 · 현재 폭 · 확정 콜백
 * @returns 폭 조절 손잡이
 */
function ColumnResizeHandle({ header, width, onResize }: ColumnResizeHandleProps): JSX.Element {
  // 드래그 시작 지점 — pointermove 마다 여기서의 **누적 delta** 로 계산한다.
  // 직전 폭에 매 프레임 delta 를 더하면 클램프에 걸린 뒤 포인터와 손잡이가 어긋난다.
  const dragStart = useRef<{ clientX: number; width: number } | null>(null)

  function handlePointerDown(event: React.PointerEvent<HTMLDivElement>): void {
    event.preventDefault()
    event.stopPropagation()
    dragStart.current = { clientX: event.clientX, width }
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  function handlePointerMove(event: React.PointerEvent<HTMLDivElement>): void {
    const start = dragStart.current
    if (start === null) return
    onResize(start.width + (event.clientX - start.clientX))
  }

  function handlePointerUp(event: React.PointerEvent<HTMLDivElement>): void {
    if (dragStart.current === null) return
    dragStart.current = null
    event.currentTarget.releasePointerCapture(event.pointerId)
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLDivElement>): void {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
    event.preventDefault()
    event.stopPropagation()
    const step = event.shiftKey ? KEYBOARD_STEP_LARGE_PX : KEYBOARD_STEP_PX
    onResize(width + (event.key === 'ArrowLeft' ? -step : step))
  }

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={`${header} 열 너비 조절`}
      aria-valuenow={width}
      aria-valuemin={MIN_COLUMN_WIDTH}
      aria-valuemax={MAX_COLUMN_WIDTH}
      tabIndex={0}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      onKeyDown={handleKeyDown}
      // 헤더 정렬 클릭·행 이동으로 번지지 않게 끊는다
      onClick={(event) => event.stopPropagation()}
      // 잡는 영역은 8px 이되(경계에 걸쳐 배치) 보이는 것은 1px 구분선이다.
      // ★구분선을 **상시 노출**한다. hover 에서만 나타나게 두면 조절할 수 있다는 사실을
      //   알 방법이 화면에 없다 — 기능이 있어도 없는 것과 같다. Jira 리스트 뷰도 헤더에
      //   옅은 세로 구분선을 상시 둔다. hover/focus 에서 파랑으로 굵어져 잡을 곳을 알린다.
      className="absolute top-0 right-0 z-10 h-full w-2 translate-x-1/2 cursor-col-resize touch-none select-none before:absolute before:inset-y-1.5 before:left-1/2 before:w-px before:-translate-x-1/2 before:bg-(--border) hover:before:w-0.5 hover:before:bg-(--border-focus) focus-visible:outline-none focus-visible:before:w-0.5 focus-visible:before:bg-(--border-focus)"
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 정렬 헤더 셀
// ─────────────────────────────────────────────────────────────────────────────

interface SortableHeaderCellProps {
  column: IssueColumnDef
  sortField: IssueSortField
  sort: IssueTableSortState | null
  onSort: (field: IssueSortField) => void
  /** 현재 폭(px) — 손잡이의 드래그 시작점 */
  width: number
  /** 폭 확정 콜백 */
  onResize: (key: string, width: number) => void
}

/**
 * 정렬 가능 컬럼의 헤더 셀(GAP-1).
 *
 * `<button>`으로 감싸 클릭 가능함을 명시하고, 현재 정렬 컬럼이면 `aria-sort` +
 * 방향 아이콘(▲asc/▼desc)을 상시 노출한다. 비활성 정렬가능 컬럼은 hover 시에만
 * 옅게 보이는 힌트 아이콘(ChevronsUpDown)을 노출한다.
 */
function SortableHeaderCell({
  column,
  sortField,
  sort,
  onSort,
  width,
  onResize,
}: SortableHeaderCellProps): JSX.Element {
  const isActive = sort !== null && sort.field === sortField
  const ariaSort = isActive ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'

  return (
    // ★헤더에는 `column.className`(넘침 숨김)을 **걸지 않는다**. 손잡이는 경계에 걸쳐
    //   배치되는데 `overflow:hidden` 이 그 바깥 절반을 잘라내, 잡으려고 누른 지점이 셀 밖
    //   좌표가 되어 **드래그가 통째로 안 먹었다**(e2e R1 이 실측으로 적발). 헤더 라벨의
    //   줄임표는 안쪽 `truncate` span 이 대신한다.
    // ★`aria-label` — 손잡이의 라벨("요약 열 너비 조절")이 헤더의 **내용 기반 접근성
    //   이름**에 섞여 들어가 「요약 요약 열 너비 조절」이 된다. 이름을 명시해 끊는다.
    <TableHead className="relative" aria-label={column.header} aria-sort={ariaSort}>
      {/* PR22 — group/ 접두사 유틸리티(group-hover:opacity-40)가 자식 아이콘에 걸려 있어
          className 의 `group` 클래스를 유지해야 한다. 프리미티브는 group/button 을 따로 쓴다. */}
      <Button
        type="button"
        variant="ghost"
        size="xs"
        onClick={() => onSort(sortField)}
        // `max-w-full min-w-0` — 열을 좁히면 헤더 라벨도 셀 안에서 줄임표로 잘린다.
        // 없으면 버튼이 셀 밖으로 삐져나와 옆 열 위에 겹쳐 그려진다.
        className="group -mx-1 max-w-full min-w-0 cursor-pointer rounded px-1 hover:bg-(--bg-neutral-hover)"
      >
        <span className="truncate">{column.header}</span>
        {isActive && sort !== null ? (
          sort.dir === 'asc' ? (
            <ChevronUp className="size-3.5" aria-hidden="true" />
          ) : (
            <ChevronDown className="size-3.5" aria-hidden="true" />
          )
        ) : (
          <ChevronsUpDown className="size-3.5 opacity-0 group-hover:opacity-40" aria-hidden="true" />
        )}
      </Button>
      <ColumnResizeHandle
        header={column.header}
        width={width}
        onResize={(next) => onResize(column.key, next)}
      />
    </TableHead>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 헤더 행 — 전체 선택 체크박스 + 컬럼 헤더(정렬 가능/불가 분기)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueTableHeaderRowProps {
  columns: readonly IssueColumnDef[]
  sort: IssueTableSortState | null
  onSort: (field: IssueSortField) => void
  selectAllChecked: boolean
  onSelectAllPage: () => void
  /** 컬럼 키 → 현재 폭(px) */
  widths: Readonly<Record<string, number>>
  /** 폭 확정 콜백 */
  onResizeColumn: (key: string, width: number) => void
}

/**
 * 테이블 헤더 행 — 전체 선택 체크박스(★e2e 계약 보존: `select-all-page`) +
 * 표시 중인 컬럼 헤더를 렌더한다. 정렬 가능 컬럼은 {@link SortableHeaderCell},
 * 그 외는 일반 `<TableHead>`로 분기한다.
 */
function IssueTableHeaderRow({
  columns,
  sort,
  onSort,
  selectAllChecked,
  onSelectAllPage,
  widths,
  onResizeColumn,
}: IssueTableHeaderRowProps): JSX.Element {
  return (
    <TableRow>
      <TableHead>
        <input
          type="checkbox"
          aria-label="현재 페이지 전체 선택"
          data-testid="select-all-page"
          checked={selectAllChecked}
          onChange={onSelectAllPage}
          className="h-4 w-4 cursor-pointer accent-primary"
        />
      </TableHead>
      {columns.map((column) => {
        const width = widths[column.key] ?? column.defaultWidth
        const sortField = getSortField(column)
        if (sortField === undefined) {
          return (
            // className·aria-label 은 {@link SortableHeaderCell} 과 같은 이유다
            <TableHead key={column.key} className="relative" aria-label={column.header}>
              <span className="block truncate">{column.header}</span>
              <ColumnResizeHandle
                header={column.header}
                width={width}
                onResize={(next) => onResizeColumn(column.key, next)}
              />
            </TableHead>
          )
        }
        return (
          <SortableHeaderCell
            key={column.key}
            column={column}
            sortField={sortField}
            sort={sort}
            onSort={onSort}
            width={width}
            onResize={onResizeColumn}
          />
        )
      })}
    </TableRow>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 데이터 행 — 선택 체크박스 + 컬럼 셀
// ─────────────────────────────────────────────────────────────────────────────

/**
 * split view "현재 행" 강조에 쓸 `<TableRow>` 속성.
 *
 * ★split 선택(`selectedKey`) ≠ bulk 선택(`selection`) — 이 둘은 완전히 독립된
 * 개념이다. bulk 선택은 일괄 작업(체크박스)용이고, 여기서 다루는 강조는 우측
 * 상세 페인에 현재 열려 있는 단일 행을 표시하기 위한 것이다.
 *
 * `data-state="selected"`는 새 클래스를 만들지 않고 ui/table의 `TableRow`에 이미
 * 정의된 `data-[state=selected]:bg-(--bg-selected)` 관례를 재사용한다(하드코딩
 * 색상 없음, 사이드바 활성 항목과 동일 토큰).
 */
function getCurrentRowAttrs(isCurrent: boolean): { 'data-state'?: 'selected'; 'aria-current'?: 'true' } {
  if (!isCurrent) return {}
  return { 'data-state': 'selected', 'aria-current': 'true' }
}

interface IssueTableDataRowProps {
  issue: IssueResponse
  columns: readonly IssueColumnDef[]
  selection: IssueTableSelectionProps
  onNavigate: (key: string) => void
  assigneeNameMap: Map<string, string>
  formatDate: (iso: string | null) => string
  /** typeKey → 이슈 유형 맵(IssueTableProps.issueTypesByKey 그대로 전파) */
  issueTypesByKey: Map<string, IssueTypeResponse>
  /** 상태 키 → 표시 이름·카테고리 맵(IssueTableProps.statusMetaByKey 그대로 전파) */
  statusMetaByKey: Map<string, StatusMeta>
  /** split view 현재 선택 이슈 키(IssueTableProps.selectedKey 그대로 전파) */
  selectedKey?: string | null
  /** 셀 인라인 편집 컨텍스트(IssueTableProps.edit 그대로 전파) */
  edit?: IssueCellEditContext
}

/**
 * 이슈 단건 데이터 행.
 *
 * ★e2e 셀렉터 verbatim 보존 — 체크박스(`select-{key}`/`aria-label="이슈 선택"`)는
 * 기존 IssueCard(routes/issues.index.tsx)와 동일 계약이다. 나머지 컬럼 셀 마크업은
 * {@link IssueColumnDef.render}(issue-columns.ts)에 위임한다.
 *
 * 행(tr) 클릭 → onNavigate. 체크박스 클릭은 onClick에서 stopPropagation해
 * 행 이동으로 이어지지 않게 한다(onChange의 토글 로직은 그대로 동작).
 *
 * ★split 선택(`selectedKey` — 우측 상세 페인에 현재 열린 행)은 bulk 선택
 * (`selection` — 일괄 작업용 체크박스)과 완전히 별개다. 강조 속성 계산은
 * {@link getCurrentRowAttrs} 참고 — 체크박스 선택 여부와 무관하게 동시 적용될 수 있다.
 */
function IssueTableDataRow({
  issue,
  columns,
  selection,
  onNavigate,
  assigneeNameMap,
  formatDate,
  issueTypesByKey,
  statusMetaByKey,
  selectedKey,
  edit,
}: IssueTableDataRowProps): JSX.Element {
  const handleRowNavigate = (): void => onNavigate(issue.key)
  const isCurrent = selectedKey != null && issue.key === selectedKey
  // 보드·백로그 카드와 **같은 헬퍼**로 해석한다 — 해석 실패 시 typeKey 원문 폴백(FR6)이
  // 한 곳에만 있어야 목록과 보드가 다른 말을 하지 않는다.
  const cardType = resolveCardType(issueTypesByKey, issue.typeKey)
  const statusMeta = statusMetaByKey.get(issue.currentStateKey)

  return (
    <TableRow onClick={handleRowNavigate} className="cursor-pointer" {...getCurrentRowAttrs(isCurrent)}>
      <TableCell>
        <input
          type="checkbox"
          aria-label="이슈 선택"
          data-testid={`select-${issue.key}`}
          checked={selection.isSelected(issue.key)}
          onChange={() => selection.onToggle(issue.key)}
          onClick={(event) => event.stopPropagation()}
          className="h-4 w-4 cursor-pointer accent-primary"
        />
      </TableCell>
      {columns.map((column) => (
        <TableCell key={column.key} className={column.className}>
          {column.render(issue, {
            assigneeName: issue.assigneeId !== null ? assigneeNameMap.get(issue.assigneeId) : undefined,
            typeIconName: cardType.iconName,
            typeName: cardType.typeName,
            statusName: statusMeta?.name,
            statusCategory: statusMeta?.category,
            formatDate,
            onNavigate: handleRowNavigate,
            edit,
          })}
        </TableCell>
      ))}
    </TableRow>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueTable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 목록을 `ui/table` 기반 표로 렌더하는 프레젠테이션 컴포넌트.
 *
 * - 데이터 페칭·URL·선택 상태 관리는 호출 측(routes/issues.index.tsx) 책임이다.
 *   이 컴포넌트는 props만으로 동작한다.
 * - ★e2e 셀렉터 계약 보존 — 체크박스(`data-testid=select-{key}`, `aria-label="이슈 선택"`),
 *   요약(`data-testid=issue-summary-{key}`), 행 링크(`aria-label={key}`, role=link),
 *   상태 배지(`role="status"`)는 기존 IssueCard(routes/issues.index.tsx)와 동일하게
 *   유지한다. 전체 선택 체크박스(`data-testid=select-all-page`)도 기존 IssueListContent와
 *   동일 계약을 유지해 기존 일괄 작업 e2e(issue-bulk-operations.spec.ts)를 보호한다.
 * - 표시 컬럼 필터링(`visibleColumnKeys`)은 required 컬럼을 무조건 포함시켜
 *   숨김 불가 계약(GAP-2)을 강제한다.
 * - `selectedKey`(split view 현재 상세 행 강조)는 `selection`(bulk 체크박스)과 별개다.
 *   자세한 계약은 {@link IssueTableDataRow} 참고.
 */
export function IssueTable({
  issues,
  visibleColumnKeys,
  sort,
  onSort,
  selection,
  onNavigate,
  assigneeNameMap,
  issueTypesByKey = EMPTY_ISSUE_TYPES,
  statusMetaByKey = EMPTY_STATUS_META,
  widths = ISSUE_COLUMN_DEFAULT_WIDTHS,
  onResizeColumn = noopResize,
  selectedKey,
  edit,
}: IssueTableProps): JSX.Element {
  const { formatDate } = useDateFormat()
  const visibleSet = new Set(visibleColumnKeys)
  const columns = ISSUE_COLUMNS.filter((column) => column.required || visibleSet.has(column.key))
  const columnWidth = (column: IssueColumnDef): number => widths[column.key] ?? column.defaultWidth
  // 표 전체 폭 = 체크박스 열 + 표시 중인 열들의 폭 합.
  // ★`w-full`(=컨테이너 100%) 을 **쓰지 않는다**. 100% 로 두면 컨테이너가 넓을 때
  //   `table-fixed` 가 남는 폭을 전 열에 비례 배분해, 사용자가 드래그로 정한 px 과 화면에
  //   그려지는 폭이 어긋난다(끌어 놓으면 도로 늘어나는 것처럼 보인다). 합으로 못박으면
  //   남는 폭은 마지막 열 **오른쪽**에 여백으로 남고, 모자라면 컨테이너가 가로 스크롤한다
  //   — Jira 리스트 뷰와 같은 거동이다.
  const totalWidth = CHECKBOX_COLUMN_WIDTH + columns.reduce((sum, column) => sum + columnWidth(column), 0)

  return (
    // ★`table-fixed` — `<colgroup>` 이 선언한 px 폭을 실제로 지키게 한다. 기본 auto
    //   레이아웃에서는 폭이 **힌트**일 뿐이라 요약 열이 가장 긴 텍스트만큼 늘어나고,
    //   그만큼 뒤 열이 가로 스크롤 밖으로 밀린다. 셀의 줄임표(`text-ellipsis`)도 이 값이
    //   있어야 실제로 걸린다. 프리미티브(`ui/table.tsx`)가 아니라 **이 표에만** 건다.
    <Table aria-label="이슈 목록" className="w-auto table-fixed" style={{ width: totalWidth }}>
      {/* 폭 정본. `<col>` 에 몰아 두면 헤더 셀과 데이터 셀이 폭을 따로 선언하지 않아
          둘이 어긋날 수 없다. 체크박스 열은 컬럼 시스템 밖의 구조적 고정 열이다. */}
      <colgroup>
        <col style={{ width: CHECKBOX_COLUMN_WIDTH }} />
        {columns.map((column) => (
          <col key={column.key} style={{ width: columnWidth(column) }} />
        ))}
      </colgroup>
      <TableHeader>
        <IssueTableHeaderRow
          columns={columns}
          sort={sort}
          onSort={onSort}
          selectAllChecked={selection.isAllPageSelected}
          onSelectAllPage={selection.onSelectAllPage}
          widths={widths}
          onResizeColumn={onResizeColumn}
        />
      </TableHeader>
      <TableBody>
        {issues.map((issue) => (
          <IssueTableDataRow
            key={issue.key}
            issue={issue}
            columns={columns}
            selection={selection}
            onNavigate={onNavigate}
            assigneeNameMap={assigneeNameMap}
            formatDate={formatDate}
            issueTypesByKey={issueTypesByKey}
            statusMetaByKey={statusMetaByKey}
            selectedKey={selectedKey}
            edit={edit}
          />
        ))}
      </TableBody>
    </Table>
  )
}
