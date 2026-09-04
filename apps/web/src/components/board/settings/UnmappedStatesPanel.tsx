// 보드 설정 — 미매핑 상태 패널 (지라 Unmapped statuses · 부채 177 R4 · J27)
import type { JSX } from 'react'
import type { ColumnState } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { Badge } from '@/components/ui/badge'

/** UnmappedStatesPanel props */
export interface UnmappedStatesPanelProps {
  /** 어느 컬럼에도 매핑되지 않은 상태 목록. 보드 상세 응답의 `unmappedStates` 를 그대로 받는다. */
  states: readonly ColumnState[]
}

/**
 * 미매핑 상태 패널 — 지라의 **Unmapped statuses** 에 대응한다(J27).
 *
 * 여기 있는 상태의 이슈는 **보드에 나타나지 않는다**. 보드 응답의 `unplacedCount` 가
 * 「몇 건이 빠졌나」만 세는 데 반해 이 목록은 **왜 빠졌나**를 말한다.
 *
 * ### 빈 상태를 경고로 그리지 않는다
 * 미매핑 0 건은 **정상**이다 — 모든 워크플로우 상태가 컬럼에 배정됐다는 뜻이다. 회색 「없음」이나
 * 경고색으로 그리면 사용자가 정상을 결손으로 읽는다. 이 화면의 빈 상태 3종(컬럼 0개 · 미매핑 0건 ·
 * 상태 0개 컬럼)은 **온도가 서로 다르고**, 그 구분이 스펙 §8b 가 표로 못박은 것이다.
 */
export function UnmappedStatesPanel({ states }: UnmappedStatesPanelProps): JSX.Element {
  return (
    <section
      aria-labelledby="board-settings-unmapped-heading"
      className="bg-muted/30 ring-foreground/10 w-64 shrink-0 rounded-lg p-4 ring-1"
    >
      <h2 id="board-settings-unmapped-heading" className="text-sm font-semibold">
        {boardLabels.settings.unmappedHeading}
      </h2>
      <p className="text-muted-foreground mt-1 text-xs">
        {boardLabels.settings.unmappedDescription}
      </p>

      {states.length === 0 ? (
        // 안심 문구다. `text-muted-foreground` 로 두되 경고색을 쓰지 않는다.
        <p className="text-muted-foreground mt-4 text-sm">{boardLabels.settings.unmappedEmpty}</p>
      ) : (
        <ul className="mt-4 flex flex-col gap-2">
          {states.map((state) => (
            <li key={state.key}>
              <Badge variant="neutral" className="w-full justify-start font-normal">
                {state.name}
              </Badge>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
