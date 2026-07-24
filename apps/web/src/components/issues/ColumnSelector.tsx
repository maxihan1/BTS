// 이슈 테이블 컬럼 표시 선택 드롭다운 — 필수 컬럼 비활성 체크박스 (FR-UX-06 Phase 5 PR18 Task 4 GAP-2)
import type { JSX } from 'react'
import { SlidersHorizontal } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { IssueColumnDef } from './issue-columns'

export interface ColumnSelectorProps {
  /** 선택 가능한 전체 컬럼 정의 목록(issue-columns.ts의 ISSUE_COLUMNS) */
  allColumns: readonly IssueColumnDef[]
  /** 특정 컬럼의 현재 표시 여부 조회 함수 */
  isVisible: (key: string) => boolean
  /** 컬럼 표시 토글 콜백. 필수 컬럼은 disabled 처리되어 호출되지 않는다 */
  onToggle: (key: string) => void
}

/**
 * 이슈 테이블 컬럼 표시를 토글하는 드롭다운 선택기(GAP-2).
 *
 * 트리거는 아이콘(`SlidersHorizontal`) + "컬럼" 라벨. 필수 컬럼(`column.required`)은
 * 체크 상태로 고정하고 `disabled` 처리해 항상 표시되도록 강제한다.
 * `onSelect`에서 `preventDefault`로 메뉴를 열어둔 채 여러 컬럼을 연속 토글할 수 있게 한다.
 */
export function ColumnSelector({ allColumns, isVisible, onToggle }: ColumnSelectorProps): JSX.Element {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <SlidersHorizontal aria-hidden="true" />
          컬럼
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {allColumns.map((column) => (
          <DropdownMenuCheckboxItem
            key={column.key}
            checked={column.required || isVisible(column.key)}
            disabled={column.required}
            onSelect={(event) => event.preventDefault()}
            onCheckedChange={() => onToggle(column.key)}
          >
            {column.header}
          </DropdownMenuCheckboxItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
