// 스프린트 번다운 가젯 — 보드의 활성 스프린트를 자동으로 따라간다
import type { JSX } from 'react'
import { BurndownChart } from '@/components/burndown/BurndownChart'
import { EmptyState } from '@/components/ui/empty-state'
import { useSprintBurndownByBoard } from '@/hooks/use-sprint-burndown'
import { gadgetStateLabels } from '@/i18n/dashboard-labels'
import type { GadgetConfig } from './gadget-types'

interface SprintBurndownGadgetProps {
  /** 가젯 config — `boardId` 하나만 쓴다 */
  readonly config: GadgetConfig
}

/**
 * 보드의 **활성 스프린트** 번다운을 그린다.
 *
 * 차트는 `BurndownChart` 를 그대로 재사용한다 — 새 차트를 만들지 않는다(제약 C-3).
 * 그 컴포넌트는 높이가 `h-[360px]` 로 고정이라 타일이 그보다 작을 수 있어 스크롤 컨테이너로 감싼다.
 *
 * 세 가지 「비어 있음」을 **다르게** 낸다.
 * - 설정 없음(`boardId` 부재) → 「가젯 설정이 필요합니다」
 * - 활성 스프린트 없음(칸반이거나 미시작) → 「활성 스프린트가 없습니다」. **오류가 아니다**
 * - 조회 실패(404 포함) → 「데이터를 불러오지 못했습니다」
 *
 * 셋을 한 문구로 뭉치면 사용자가 「내가 뭘 잘못했나」와 「아직 시작 안 했다」를 구분하지 못한다.
 */
export function SprintBurndownGadget({ config }: SprintBurndownGadgetProps): JSX.Element {
  const boardId = config.boardId
  const { data, isLoading, isError, hasNoActiveSprint } = useSprintBurndownByBoard(boardId)

  if (boardId === undefined || boardId === '') {
    return <EmptyState title={gadgetStateLabels.notConfigured} />
  }

  if (isLoading) {
    return (
      <div className="text-muted-foreground flex flex-1 items-center justify-center p-4 text-sm">
        {gadgetStateLabels.loading}
      </div>
    )
  }

  // ★활성 스프린트 부재를 오류보다 **먼저** 판정한다. 순서가 뒤집히면 칸반 보드를 고른
  //   사용자가 「불러오지 못했습니다」를 보고 자기 설정이 틀렸다고 오해한다.
  if (hasNoActiveSprint) {
    return <EmptyState title={gadgetStateLabels.noActiveSprint} />
  }

  if (isError || data === undefined) {
    return <EmptyState title={gadgetStateLabels.loadFailed} />
  }

  return (
    <div className="flex-1 overflow-auto" data-testid="sprint-burndown-gadget">
      <BurndownChart response={data} view="burndown" />
    </div>
  )
}
