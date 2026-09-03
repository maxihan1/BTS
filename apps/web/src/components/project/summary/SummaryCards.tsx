// 프로젝트 요약 상단 카드 4종 — 최근 7일 3종 + 마감 예정 (Jira 패리티 J4)
import type { JSX } from 'react'
import type { RecentCounts, UpcomingCounts } from '@/api/project-summary'
import { formatOverdue, formatWindowDelta } from './summary-view-model'
import { projectSummaryLabels as labels } from '@/i18n/project-summary-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 카드 한 장
// ─────────────────────────────────────────────────────────────────────────────

interface SummaryCardProps {
  /** 카드 제목 — 접근성 이름으로도 쓰인다 */
  readonly title: string
  /** 큰 숫자 */
  readonly value: number
  /** 하단 보조 문구 (델타 또는 지연) */
  readonly caption: string
  /** 보조 문구를 강조할지 — 지연이 있을 때 참 */
  readonly emphasizeCaption?: boolean
}

/** 값 하나와 보조 문구 하나를 담는 카드 */
function SummaryCard({ title, value, caption, emphasizeCaption }: SummaryCardProps): JSX.Element {
  return (
    <div className="bg-card rounded-lg border p-4" aria-label={title}>
      <p className="text-muted-foreground text-sm">{title}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
      <p
        className={
          emphasizeCaption === true
            ? 'text-destructive mt-1 text-xs'
            : 'text-muted-foreground mt-1 text-xs'
        }
      >
        {caption}
      </p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 카드 4장
// ─────────────────────────────────────────────────────────────────────────────

interface SummaryCardsProps {
  /** 최근 7일 카드 3종 재료 */
  readonly recent: RecentCounts
  /** 마감 예정 카드 재료 */
  readonly upcoming: UpcomingCounts
}

/**
 * 요약 화면 상단 카드 4장.
 *
 * 값이 0 이어도 카드를 숨기지 않는다 — 「이번 주 완료 0」은 빈 화면이 아니라 정보다.
 *
 * @param recent 최근 7일 카드 3종
 * @param upcoming 마감 예정·지연
 */
export function SummaryCards({ recent, upcoming }: SummaryCardsProps): JSX.Element {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <SummaryCard
        title={labels.cards.completed}
        value={recent.completed.current}
        caption={formatWindowDelta(recent.completed)}
      />
      <SummaryCard
        title={labels.cards.updated}
        value={recent.updated.current}
        caption={formatWindowDelta(recent.updated)}
      />
      <SummaryCard
        title={labels.cards.created}
        value={recent.created.current}
        caption={formatWindowDelta(recent.created)}
      />
      <SummaryCard
        title={labels.cards.due}
        value={upcoming.due}
        caption={formatOverdue(upcoming.overdue)}
        emphasizeCaption={upcoming.overdue > 0}
      />
    </div>
  )
}
