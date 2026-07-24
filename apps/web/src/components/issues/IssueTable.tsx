// 이슈 목록을 ui/table 기반 표로 렌더하는 프레젠테이션 컴포넌트 — 정렬 헤더·컬럼 표시·선택 (FR-UX-06 Phase 5 PR18 Task 4)
import type { JSX } from 'react'
import { ChevronDown, ChevronUp, ChevronsUpDown } from 'lucide-react'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useDateFormat } from '@/hooks/use-date-format'
import type { IssueResponse, IssueSortField } from '@/api/issues'
import { ISSUE_COLUMNS, getSortField } from './issue-columns'
import type { IssueColumnDef } from './issue-columns'

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
    <TableHead key={column.key} className={column.className} aria-sort={ariaSort}>
      <button
        type="button"
        onClick={() => onSort(sortField)}
        className="group -mx-1 inline-flex cursor-pointer items-center gap-1 rounded px-1 hover:bg-(--bg-neutral-hover)"
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
      </button>
    </TableHead>
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
 * - 행 클릭 시 onNavigate를 호출하고, 체크박스 클릭은 stopPropagation으로 행 이동을 막는다.
 */
export function IssueTable({
  issues,
  visibleColumnKeys,
  sort,
  onSort,
  selection,
  onNavigate,
  assigneeNameMap,
}: IssueTableProps): JSX.Element {
  const { formatDate } = useDateFormat()
  const visibleSet = new Set(visibleColumnKeys)
  const columns = ISSUE_COLUMNS.filter((column) => column.required || visibleSet.has(column.key))

  return (
    <Table aria-label="이슈 목록">
      <TableHeader>
        <TableRow>
          <TableHead className="w-10">
            <input
              type="checkbox"
              aria-label="현재 페이지 전체 선택"
              data-testid="select-all-page"
              checked={selection.isAllPageSelected}
              onChange={selection.onSelectAllPage}
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
      </TableHeader>
      <TableBody>
        {issues.map((issue) => {
          const handleRowNavigate = (): void => onNavigate(issue.key)
          return (
            <TableRow key={issue.key} onClick={handleRowNavigate} className="cursor-pointer">
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
                    assigneeName:
                      issue.assigneeId !== null ? assigneeNameMap.get(issue.assigneeId) : undefined,
                    formatDate,
                    onNavigate: handleRowNavigate,
                  })}
                </TableCell>
              ))}
            </TableRow>
          )
        })}
      </TableBody>
    </Table>
  )
}
