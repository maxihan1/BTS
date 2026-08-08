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
      // ★ role="img" 가 없으면 aria-label 이 무효다 — 맨 span 은 role=generic 이고
      // ARIA 에서 generic 은 **name-prohibited** 라 스크린리더가 "2h 30m" 만 읽고
      // "추정" 이라는 맥락이 사라진다. IssueTypeIcon.tsx:50 과 같은 처방이다.
      role="img"
      aria-label={cardLabels.estimateAriaLabel(formatted)}
      className="shrink-0 rounded-sm px-1 py-0.5 text-xs text-muted-foreground ring-1 ring-border"
    >
      {formatted}
    </span>
  )
}
