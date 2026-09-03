// 요약 분포 위젯 공용 — 상태·우선순위·유형·담당자 4종이 같은 막대 목록을 쓴다 (Jira 패리티 J4)
import type { JSX } from 'react'
import type { DistributionRow } from './summary-view-model'
import { projectSummaryLabels as labels } from '@/i18n/project-summary-labels'

interface DistributionWidgetProps {
  /** 위젯 제목 */
  readonly title: string
  /** 제목 아래 각주 — 창 특례처럼 값의 범위를 밝혀야 할 때만 */
  readonly note?: string
  /** 막대 행 */
  readonly rows: readonly DistributionRow[]
}

/**
 * 분포 막대 목록 위젯.
 *
 * 막대 길이는 **그 위젯 안 최댓값** 기준이다. 위젯끼리 스케일을 공유하면 담당자 1명짜리
 * 프로젝트에서 상태 막대가 전부 눌려 읽히지 않는다.
 *
 * @param title 위젯 제목
 * @param note 값 범위 각주
 * @param rows 막대 행
 */
export function DistributionWidget({ title, note, rows }: DistributionWidgetProps): JSX.Element {
  const max = rows.reduce((acc, row) => Math.max(acc, row.count), 0)

  return (
    <section className="bg-card rounded-lg border p-4" aria-label={title}>
      <h2 className="text-sm font-semibold">{title}</h2>
      {note !== undefined && <p className="text-muted-foreground mt-0.5 text-xs">{note}</p>}
      {rows.length === 0 ? (
        <p className="text-muted-foreground mt-3 text-sm">{labels.status.empty}</p>
      ) : (
        <ul className="mt-3 space-y-2">
          {rows.map((row) => (
            <li key={row.id} className="space-y-1">
              <div className="flex items-baseline justify-between gap-2 text-sm">
                <span className="truncate">{row.label}</span>
                <span className="text-muted-foreground tabular-nums">
                  {row.count}
                  {labels.distribution.countSuffix}
                </span>
              </div>
              <div className="bg-muted h-1.5 overflow-hidden rounded-full" aria-hidden="true">
                <div
                  className="bg-primary h-full rounded-full"
                  style={{ width: max === 0 ? '0%' : `${String((row.count / max) * 100)}%` }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
