// 스프린트 헤더의 상태 카테고리별 추정 합 배지 3개 — 할 일 · 진행 중 · 완료 (R3b · JB-L-3)
import { useMemo, type JSX } from 'react'
import type { BacklogIssue } from '@/api/backlog'
import type { StatusMeta } from '@/components/issues/IssueTable'
import { statusBadgeVariant, type StateCategory } from '@/components/issues/issue-visuals'
import { Badge } from '@/components/ui/badge'
import { formatSeconds } from '@/lib/duration'
import { backlogLabels } from '@/i18n/backlog-labels'

/** 배지 순서 — Jira 와 같이 할 일 → 진행 중 → 완료 */
const CATEGORIES: readonly StateCategory[] = ['TODO', 'IN_PROGRESS', 'DONE']

interface SprintEstimateBadgesProps {
  issues: readonly BacklogIssue[]
  /** 상태 키 → 카테고리. 워크플로 조회 전이면 빈 맵이라 배지가 모두 0 이다 */
  statusMetaByKey: ReadonlyMap<string, StatusMeta>
}

/**
 * Jira 스프린트 헤더의 「스토리 포인트 배지 3개」 대응.
 *
 * **편차** — BTS 에는 스토리 포인트가 없어 카드의 최초 추정 시간(`originalEstimateSeconds`)을 더한다.
 * 미추정(null)은 0 으로 더하고, 상태를 해석하지 못한 이슈는 어느 칸에도 넣지 않는다 —
 * 모르는 카테고리를 지어내지 않는다(`statusBadgeVariant` 와 같은 원칙).
 * 백로그 응답이 카드 수 상한으로 잘리면(`truncated`) 합도 보이는 이슈만의 합이다.
 */
export function SprintEstimateBadges({ issues, statusMetaByKey }: SprintEstimateBadgesProps): JSX.Element {
  const sums = useMemo(() => {
    const acc: Record<StateCategory, number> = { TODO: 0, IN_PROGRESS: 0, DONE: 0 }
    for (const issue of issues) {
      const category = statusMetaByKey.get(issue.currentStateKey)?.category
      if (category !== undefined) acc[category] += issue.originalEstimateSeconds ?? 0
    }
    return acc
  }, [issues, statusMetaByKey])

  return (
    <span className="flex items-center gap-1">
      {CATEGORIES.map((category) => {
        const formatted = formatSeconds(sums[category])
        const label = backlogLabels.estimateSumAriaLabel(backlogLabels.estimateCategory[category], formatted)
        return (
          // role="img" — 맨 span 은 aria-label 이 무효다 (CardEstimateBadge 와 같은 처방)
          <Badge key={category} role="img" aria-label={label} title={label} variant={statusBadgeVariant(category)}>
            {formatted}
          </Badge>
        )
      })}
    </span>
  )
}
