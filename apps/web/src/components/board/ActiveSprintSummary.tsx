// 스크럼 보드 헤더의 활성 스프린트 표기 — 이름 + 기간 (FR-BD-04 FR-2)
//
// `formatSprintPeriod` 를 함께 export 하므로 fast-refresh 규칙을 끈다. 그 함수는 이 컴포넌트의
// 표기 규칙 자체라 같은 파일에 두는 것이 맞고, 테스트가 네 조합을 직접 재려면 export 가 필요하다.
// 같은 사유의 선례 — `ScrumSprintEmptyState.tsx` · `KanbanBoard.tsx:30` · `CfdChart.tsx:1`.
/* eslint-disable react-refresh/only-export-components */
import type { JSX } from 'react'
import type { ActiveSprint } from '@/api/boards'

/**
 * 스프린트 기간 표기를 만든다. 양끝이 **둘 다 nullable** 이라 네 조합을 각각 다룬다.
 *
 * 서버 `ActiveSprintResponse` 의 `startDate`·`endDate` 는 선택 입력이다 —
 * 「기간 미정으로 시작한 스프린트」가 실제로 존재하므로 한쪽만 있는 경우가 표기에서 사라지면 안 된다.
 *
 * @param startDate 시작일. 미설정이면 null
 * @param endDate 종료일. 미설정이면 null
 * @returns 표기 문자열. 양끝이 모두 없으면 null(그 줄을 아예 그리지 않는다)
 */
export function formatSprintPeriod(
  startDate: string | null,
  endDate: string | null,
): string | null {
  if (startDate === null && endDate === null) return null
  if (startDate === null) return `~ ${endDate ?? ''}`
  if (endDate === null) return `${startDate} ~`
  return `${startDate} ~ ${endDate}`
}

/** ActiveSprintSummary Props */
export interface ActiveSprintSummaryProps {
  /**
   * 이 보드의 활성 스프린트. **칸반은 항상 null** 이다(`activeSprintSchema` KDoc).
   *
   * null 이면 이 컴포넌트가 아무것도 그리지 않는다 — 호출부에서 조건을 걸지 않아도
   * 칸반 경로가 무변경으로 남는다.
   */
  readonly activeSprint: ActiveSprint | null
}

/**
 * 보드 헤더의 활성 스프린트 표기 — 이름 옆에 기간.
 *
 * 「지금 이 보드가 무엇을 보여주고 있는가」는 스크럼에서 스프린트가 정하므로 보드 이름 옆이 그 자리다
 * (FR-2 · J5 — *"the board displays only the work items added to the sprint you started"*).
 * 칸반은 `activeSprint` 가 null 이라 이 자리가 통째로 비어 **무변경**이다.
 *
 * @param activeSprint 활성 스프린트. null 이면 아무것도 그리지 않는다
 */
export function ActiveSprintSummary({ activeSprint }: ActiveSprintSummaryProps): JSX.Element | null {
  if (activeSprint === null) return null

  const period = formatSprintPeriod(activeSprint.startDate, activeSprint.endDate)

  return (
    <div className="flex items-center gap-2 text-sm" data-testid="active-sprint-summary">
      <span className="font-medium">{activeSprint.name}</span>
      {period !== null && <span className="text-muted-foreground">{period}</span>}
    </div>
  )
}
