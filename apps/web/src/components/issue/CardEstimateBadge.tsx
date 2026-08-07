// 보드/백로그 카드의 추정 시간 배지 — lib/duration.formatSeconds 위임 (FR-UX-14 F14 Task 2)
import type { JSX } from 'react'
import { formatSeconds } from '@/lib/duration'
import { cardLabels } from '@/i18n/card-labels'

interface CardEstimateBadgeProps {
  /** 원본 추정 시간(초). null이면 미추정 상태로 아무것도 렌더하지 않는다 */
  seconds: number | null
}

/**
 * 보드·백로그 카드가 **공유**하는 추정 시간 배지.
 *
 * `null`(미추정)과 `0`(추정했지만 값이 0)은 서로 다른 의미이므로 `0`은
 * 그대로 통과시켜 "0m"을 표시한다. 시간 표시 포맷은 `lib/duration.formatSeconds`에
 * 위임한다 — 이 컴포넌트는 새 포맷을 만들지 않는다.
 *
 * @param seconds 원본 추정 시간(초). `null`이면 미추정
 */
export function CardEstimateBadge({ seconds }: CardEstimateBadgeProps): JSX.Element | null {
  if (seconds === null) return null // FR10 — 0은 통과시킨다

  const formatted = formatSeconds(seconds)

  return (
    <span
      aria-label={cardLabels.estimateAriaLabel(formatted)}
      className="shrink-0 rounded-sm px-1 py-0.5 text-xs text-muted-foreground ring-1 ring-border"
    >
      {formatted}
    </span>
  )
}
