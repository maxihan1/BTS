// 보드 설정 — 컬럼 한 개의 카드 (이름 · 담은 상태 · WIP · 카드 수) (부채 177 R4)
import type { JSX } from 'react'
import type { BoardColumn } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { Badge } from '@/components/ui/badge'

/** ColumnSettingsCard props */
export interface ColumnSettingsCardProps {
  /** 그릴 컬럼. 보드 상세 응답의 `columns[n]` 을 그대로 받는다. */
  column: BoardColumn
  /**
   * 이 보드의 카드 목록이 조회 상한에 잘렸는지 (`BoardDetail.truncated`).
   *
   * ★잘렸으면 **카드 수를 주장하지 않는다.** `column.cards.length` 는 잘린 목록의 길이라
   * 1000 이 넘는 보드에서 거짓이 된다(eng 리뷰 BLOCKER-1). 서버의 `removedCardCount` 도
   * 같은 잘린 목록에서 세므로 두 곳이 같은 한계를 공유한다.
   */
  truncated: boolean
}

/**
 * 컬럼 한 개를 설정 화면에 그린다.
 *
 * 이 PR 의 이 컴포넌트는 **읽기 전용**이다 — 이름 편집 · WIP 편집 · 상태 드래그는 후속 task 가
 * 여기에 붙인다. 읽기가 먼저 서야 조작이 붙을 자리가 생긴다.
 *
 * ### 상태 0개 컬럼을 명시한다
 * 백엔드가 상태 0개 컬럼을 허용한다(#444 E1) — 지라의 「컬럼 먼저, 상태는 드래그로」 흐름이
 * 그것을 요구한다(J23→J27). 그 컬럼은 보드 화면에서 **항상 비어 있는데**, 설정 화면이 그냥
 * 비워 두면 사용자가 미완성임을 모른다. 「상태 없음」을 글자로 적는다.
 */
export function ColumnSettingsCard({ column, truncated }: ColumnSettingsCardProps): JSX.Element {
  const cardCountText = truncated
    ? boardLabels.settings.cardCountTruncated
    : boardLabels.settings.cardCount(column.cards.length)

  return (
    <article
      aria-label={column.name}
      className="ring-foreground/10 bg-card w-72 shrink-0 rounded-lg p-4 ring-1"
    >
      <h3 className="text-sm font-semibold">{column.name}</h3>

      <p className="text-muted-foreground mt-1 text-xs">
        {cardCountText}
        {' · '}
        {column.wipLimit === null
          ? boardLabels.settings.wipUnlimited
          : boardLabels.settings.wipLimitLabel(column.wipLimit)}
      </p>

      {column.states.length === 0 ? (
        // 미완 표시다 — 다음 행동(상태를 끌어다 놓기)이 남았음을 말한다.
        <p className="text-muted-foreground mt-4 text-sm italic">
          {boardLabels.settings.columnNoStates}
        </p>
      ) : (
        <ul className="mt-4 flex flex-col gap-2">
          {column.states.map((state) => (
            <li key={state.key}>
              <Badge variant="neutral" className="w-full justify-start font-normal">
                {state.name}
              </Badge>
            </li>
          ))}
        </ul>
      )}
    </article>
  )
}
