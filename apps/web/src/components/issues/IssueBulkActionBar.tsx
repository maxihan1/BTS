// 이슈 일괄 액션 바 컴포넌트 — 선택된 이슈에 대한 일괄 편집/전환/선택 해제 액션 제공
import type { JSX } from 'react'
import { Button } from '@/components/ui/button'

/** IssueBulkActionBar props */
interface IssueBulkActionBarProps {
  /** 현재 선택된 이슈 수. 0이면 컴포넌트를 렌더하지 않는다. */
  count: number
  /** 일괄 편집 버튼 클릭 콜백 */
  onEdit: () => void
  /** 일괄 전환 버튼 클릭 콜백 */
  onTransition: () => void
  /** 선택 해제 버튼 클릭 콜백 */
  onClear: () => void
}

/**
 * 이슈 목록에서 복수 선택 시 표시되는 일괄 액션 바.
 * count가 0이면 null을 반환한다.
 * props 기반 순수 컴포넌트로 라우터/전역 상태에 비의존적이다.
 */
export const IssueBulkActionBar = ({
  count,
  onEdit,
  onTransition,
  onClear,
}: IssueBulkActionBarProps): JSX.Element | null => {
  if (count === 0) {
    return null
  }

  return (
    <div
      role="toolbar"
      aria-label="일괄 액션 바"
      data-testid="bulk-action-bar"
      className="flex items-center gap-2 rounded-lg border border-border bg-background px-4 py-2 shadow-sm"
    >
      <span
        aria-live="polite"
        aria-atomic="true"
        className="text-sm font-medium text-foreground"
      >
        {count}건 선택됨
      </span>
      <div className="ml-auto flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={onEdit}>
          일괄 편집
        </Button>
        <Button variant="outline" size="sm" onClick={onTransition}>
          일괄 전환
        </Button>
        <Button variant="ghost" size="sm" onClick={onClear}>
          선택 해제
        </Button>
      </div>
    </div>
  )
}
