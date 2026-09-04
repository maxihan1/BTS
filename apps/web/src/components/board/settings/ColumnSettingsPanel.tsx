// 보드 설정 — Columns 탭 본문 (컬럼 가로 배치 + 미매핑 상태 패널) (부채 177 R4 · J22)
import type { JSX } from 'react'
import type { BoardDetail } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { EmptyState } from '@/components/ui/empty-state'
import { ColumnSettingsCard } from './ColumnSettingsCard'
import { UnmappedStatesPanel } from './UnmappedStatesPanel'

/** ColumnSettingsPanel props */
export interface ColumnSettingsPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회 API 를 만들지 않았다. */
  board: BoardDetail
}

/**
 * 지라 Board settings 의 **Columns 탭** 본문.
 *
 * 컬럼을 `displayOrder` 순으로 가로 배치하고 오른쪽에 미매핑 상태 패널을 둔다 — 지라와 같은
 * 배치이고, 그래야 「미매핑에서 컬럼으로 끌어다 놓는다」(J27)가 한 화면 안에서 성립한다.
 *
 * ### 반응형은 보드 화면의 검증된 패턴을 승계한다
 * `flex gap-4 overflow-x-auto` — `KanbanBoard.tsx:638` 과 같다. **모바일 분기를 새로 만들지
 * 않는다**(보드 화면도 안 만든다). 두 화면이 갈리면 컬럼 배치라는 같은 문제의 답이 두 개가 된다.
 *
 * ### 조회 API 를 새로 만들지 않았다
 * `unmappedStates` 는 #444 가 이미 보드 상세 응답에 실었다. 다만 그 응답은 카드도 함께 실어
 * 최대 1000장을 끌어온다 — 설정 화면은 카드 **수**만 필요하다. 그 비용은 NFR **N8** 로 등재했고
 * 이 PR 은 신규 조회 API 를 만들지 않는다(범위 초과).
 */
export function ColumnSettingsPanel({ board }: ColumnSettingsPanelProps): JSX.Element {
  if (board.columns.length === 0) {
    // 행동 유도가 필요한 빈 상태다 — 미매핑 0건의 「안심」과 온도가 다르다(스펙 §8b).
    // 추가 버튼은 후속 task 가 이 자리에 붙인다.
    return <EmptyState title={boardLabels.settings.columnsEmpty} />
  }

  return (
    <div className="flex gap-4 overflow-x-auto pb-4">
      {board.columns.map((column) => (
        <ColumnSettingsCard key={column.columnId} column={column} truncated={board.truncated} />
      ))}
      <UnmappedStatesPanel states={board.unmappedStates} />
    </div>
  )
}
