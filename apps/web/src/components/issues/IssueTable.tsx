// 이슈 목록을 ui/table 기반 표로 렌더하는 프레젠테이션 컴포넌트 — 정렬 헤더·컬럼 표시·선택 (FR-UX-06 Phase 5 PR18 Task 4)
import type { JSX } from 'react'
import { ChevronDown, ChevronUp, ChevronsUpDown } from 'lucide-react'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useDateFormat } from '@/hooks/use-date-format'
import type { IssueResponse, IssueSortField } from '@/api/issues'
import { ISSUE_COLUMNS, getSortField } from './issue-columns'
import type { IssueCellEditContext, IssueColumnDef } from './issue-columns'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

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
// 정렬 헤더 셀
// ─────────────────────────────────────────────────────────────────────────────

interface SortableHeaderCellProps {
  column: IssueColumnDef
  sortField: IssueSortField
  sort: IssueTableSortState | null
  onSort: (field: IssueSortField) => void
}

/**
 * 정렬 가능 컬럼의 헤더 셀(GAP-1).
 *
 * `<button>`으로 감싸 클릭 가능함을 명시하고, 현재 정렬 컬럼이면 `aria-sort` +
 * 방향 아이콘(▲asc/▼desc)을 상시 노출한다. 비활성 정렬가능 컬럼은 hover 시에만
 * 옅게 보이는 힌트 아이콘(ChevronsUpDown)을 노출한다.
 */
function SortableHeaderCell({ column, sortField, sort, onSort }: SortableHeaderCellProps): JSX.Element {
  const isActive = sort !== null && sort.field === sortField
  const ariaSort = isActive ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'

  return (
    <TableHead className={column.className} aria-sort={ariaSort}>
      {/* PR22 — group/ 접두사 유틸리티(group-hover:opacity-40)가 자식 아이콘에 걸려 있어
          className 의 `group` 클래스를 유지해야 한다. 프리미티브는 group/button 을 따로 쓴다. */}
      <Button
        type="button"
        variant="ghost"
        size="xs"
        onClick={() => onSort(sortField)}
        className="group -mx-1 cursor-pointer rounded px-1 hover:bg-(--bg-neutral-hover)"
      >
        {column.header}
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
}: IssueTableHeaderRowProps): JSX.Element {
  return (
    <TableRow>
      <TableHead className="w-10">
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
        const sortField = getSortField(column)
        if (sortField === undefined) {
          return (
            <TableHead key={column.key} className={column.className}>
              {column.header}
            </TableHead>
          )
        }
        return (
          <SortableHeaderCell key={column.key} column={column} sortField={sortField} sort={sort} onSort={onSort} />
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
  selectedKey,
  edit,
}: IssueTableDataRowProps): JSX.Element {
  const handleRowNavigate = (): void => onNavigate(issue.key)
  const isCurrent = selectedKey != null && issue.key === selectedKey

  return (
    <TableRow onClick={handleRowNavigate} className="cursor-pointer" {...getCurrentRowAttrs(isCurrent)}>
      <TableCell className="w-10">
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
  selectedKey,
  edit,
}: IssueTableProps): JSX.Element {
  const { formatDate } = useDateFormat()
  const visibleSet = new Set(visibleColumnKeys)
  const columns = ISSUE_COLUMNS.filter((column) => column.required || visibleSet.has(column.key))

  return (
    <Table aria-label="이슈 목록">
      <TableHeader>
        <IssueTableHeaderRow
          columns={columns}
          sort={sort}
          onSort={onSort}
          selectAllChecked={selection.isAllPageSelected}
          onSelectAllPage={selection.onSelectAllPage}
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
            selectedKey={selectedKey}
            edit={edit}
          />
        ))}
      </TableBody>
    </Table>
  )
}
