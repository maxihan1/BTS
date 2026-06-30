// 이슈 건수 가젯 — issue_count 타입 전용 (FR-DB-02 D6/D7 Task-5)
import type { JSX } from 'react'
import { useGadgetData } from './useGadgetData'
import type { GadgetConfig } from './gadget-types'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** IssueCountGadget props */
export interface IssueCountGadgetProps {
  /** 가젯 설정 (filterId 필수) */
  config: GadgetConfig
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 건수 가젯.
 *
 * 필터 결과 총 건수(totalElements)를 큰 숫자로 표시한다.
 * - 로딩 / 에러 상태 처리.
 * - 컴팩트 중앙 정렬 레이아웃.
 */
export function IssueCountGadget({ config }: IssueCountGadgetProps): JSX.Element {
  const { isLoading, isError, totalElements } = useGadgetData('issue_count', config)

  if (isLoading) {
    return (
      <div className="flex flex-1 items-center justify-center p-4 text-sm text-muted-foreground">
        이슈 건수를 불러오는 중입니다
      </div>
    )
  }

  if (isError) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-1 p-4">
        <p className="text-sm text-destructive">이슈 건수를 불러오지 못했습니다</p>
        <p className="text-xs text-muted-foreground">잠시 후 다시 시도해 주세요</p>
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-1 p-4">
      <span className="text-4xl font-bold tabular-nums text-foreground" aria-label={`이슈 건수 ${totalElements ?? 0}건`}>
        {totalElements ?? 0}
      </span>
      <span className="text-sm text-muted-foreground">이슈</span>
    </div>
  )
}
