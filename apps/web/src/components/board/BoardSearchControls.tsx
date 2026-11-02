// 보드 필터 바 앞쪽 — 텍스트 검색 입력과 「하위 작업 표시」 토글 (JB-B-4 · JB-B-7)
import type { JSX } from 'react'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { boardLabels } from '@/i18n/board-labels'

/** BoardSearchControls props — 두 값 모두 부모(BoardPage) 소유 */
export interface BoardSearchControlsProps {
  query: string
  onQueryChange: (next: string) => void
  /** 하위 작업 카드 표시 여부 — 기본 false(숨김). 화면 상태라 새로 열면 다시 숨김이다 */
  showSubtasks: boolean
  onShowSubtasksChange: (next: boolean) => void
}

/** 보드 검색 입력 + 하위 작업 표시 Switch. BoardPage 200줄 래칫 때문에 따로 둔다. */
export function BoardSearchControls({
  query, onQueryChange, showSubtasks, onShowSubtasksChange,
}: BoardSearchControlsProps): JSX.Element {
  return (
    <>
      <Input
        type="search"
        aria-label={boardLabels.page.search}
        placeholder={boardLabels.page.search}
        value={query}
        onChange={(e) => onQueryChange(e.target.value)}
        className="h-8 w-48"
      />
      <label className="flex items-center gap-2 text-sm text-muted-foreground">
        <Switch checked={showSubtasks} onCheckedChange={onShowSubtasksChange} />
        {boardLabels.page.showSubtasks}
      </label>
    </>
  )
}
