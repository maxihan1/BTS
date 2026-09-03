// 프로젝트 활동 피드 위젯 — 자체 useQuery 로 요약과 실패를 분리한다 (Jira 패리티 J4)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { ApiError } from '@/api/client'
import { useProjectActivity } from '@/hooks/use-project-summary'
import { formatActivityTime, summarizeEntry } from './summary-view-model'
import { projectSummaryLabels as labels } from '@/i18n/project-summary-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 위젯
// ─────────────────────────────────────────────────────────────────────────────

interface ProjectActivityFeedProps {
  /** 조회할 프로젝트 키 */
  readonly projectKey: string
  /** 최대 항목 수. 기본 10 — 착지 화면 위젯이라 백엔드 상한(50)까지 받지 않는다 */
  readonly limit?: number
}

/**
 * 프로젝트 활동 피드 위젯.
 *
 * **요약 집계와 별도 쿼리**다. 활동이 403·500 이어도 카드와 분포는 그대로 보인다 —
 * 하나의 실패가 착지 화면 전체를 비우지 않게 하는 것이 이 분리의 목적이다.
 *
 * @param projectKey 조회할 프로젝트 키
 * @param limit 최대 항목 수
 */
export function ProjectActivityFeed({
  projectKey,
  limit = 10,
}: ProjectActivityFeedProps): JSX.Element {
  const { data, isPending, isError, error } = useProjectActivity(projectKey, limit)

  const body = ((): JSX.Element => {
    if (isPending) {
      return (
        <p role="status" className="text-muted-foreground mt-3 text-sm">
          {labels.status.loading}
        </p>
      )
    }
    if (isError) {
      const message =
        error instanceof ApiError && error.status === 403
          ? labels.status.forbidden
          : labels.status.activityFailed
      return <p className="text-muted-foreground mt-3 text-sm">{message}</p>
    }

    const visible = data.entries.filter((entry) => entry.items.length > 0)
    if (visible.length === 0) {
      return <p className="text-muted-foreground mt-3 text-sm">{labels.activity.empty}</p>
    }

    return (
      <ul className="mt-3 space-y-3">
        {visible.map((entry, index) => (
          <li key={`${entry.issueKey}-${entry.createdAt}-${String(index)}`} className="text-sm">
            <div className="flex flex-wrap items-baseline gap-x-2">
              <Link
                to="/issues/$key"
                params={{ key: entry.issueKey }}
                className="font-medium hover:underline"
              >
                {entry.issueKey}
              </Link>
              <span className="text-muted-foreground">{summarizeEntry(entry)}</span>
            </div>
            <p className="text-muted-foreground mt-0.5 text-xs">
              {entry.actorName ?? labels.activity.unknownActor}
              {' · '}
              {formatActivityTime(entry.createdAt)}
            </p>
          </li>
        ))}
      </ul>
    )
  })()

  return (
    <section className="rounded-lg border bg-card p-4" aria-label={labels.activity.title}>
      <h2 className="text-sm font-semibold">{labels.activity.title}</h2>
      {body}
    </section>
  )
}
