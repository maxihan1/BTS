// 보드 카드 필터 바 — 공유 FilterBar에 순수 위임하는 얇은 래퍼 (FR-UX-06 PR17)
import type { JSX } from 'react'
import { FilterBar } from '@/components/filters/FilterBar'
import type { BoardCardFilterParams } from '@/api/boards'

/** BoardFilterBar props */
export interface BoardFilterBarProps {
  /** 프로젝트 키 — useComponents 쿼리에 사용 */
  projectKey: string
  /** 현재 필터 파라미터 — 제어형, 상태는 부모 소유 */
  value: BoardCardFilterParams
  /** 필터 변경 콜백 — 새 필터 전체를 전달 */
  onChange: (next: BoardCardFilterParams) => void
}

/** 보드 카드 필터 바 — 담당자/라벨/컴포넌트/칩/초기화를 공유 FilterBar에 위임. status 섹션 없음. */
export function BoardFilterBar({ projectKey, value, onChange }: BoardFilterBarProps): JSX.Element {
  return <FilterBar projectKey={projectKey} value={value} onChange={onChange} idPrefix="board-filter" />
}
