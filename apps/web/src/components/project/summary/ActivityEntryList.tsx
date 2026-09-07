// 활동 항목 목록 — 프로젝트 요약 위젯과 대시보드 가젯이 공유하는 본문
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { useOpenIssueDetail } from '@/components/issue/use-open-issue-detail'
import type { ProjectActivityEntry } from '@/api/project-summary'
import { formatActivityTime, summarizeEntry } from './summary-view-model'
import { projectSummaryLabels as labels } from '@/i18n/project-summary-labels'

interface ActivityEntryListProps {
  /** 표시할 항목. 호출측이 **이미 걸러서** 넘긴다 (`items.length > 0`) */
  readonly entries: readonly ProjectActivityEntry[]
}

/**
 * 활동 항목을 최신순 목록으로 그린다.
 *
 * ★껍데기(카드 테두리·제목)를 두르지 않는다. 요약 화면은 `<section>` 카드 안에 넣고,
 * 대시보드 가젯은 타일이 이미 테두리와 제목을 갖고 있어 본문만 필요하다. 껍데기를 여기 두면
 * 가젯에서 테두리 안에 테두리, 제목 아래 제목이 된다.
 *
 * ★상태 분기(로딩·오류·빈)도 여기 없다. 두 소비처의 문구 체계가 다르다 —
 * 요약 화면은 `projectSummaryLabels`, 가젯은 `gadgetStateLabels` 를 쓴다.
 * 여기서 하나로 뭉치면 한쪽 문구를 고칠 때 다른 쪽이 조용히 따라 바뀐다.
 *
 * 공유하는 것은 **항목을 어떻게 읽는가**다 — 요약 문장·시각 표기·이슈 링크 동작.
 * 갈리면 같은 활동이 두 화면에서 다르게 읽힌다.
 */
export function ActivityEntryList({ entries }: ActivityEntryListProps): JSX.Element {
  const openIssueDetail = useOpenIssueDetail()

  return (
    <ul className="space-y-3">
      {entries.map((entry, index) => (
        <li key={`${entry.issueKey}-${entry.createdAt}-${String(index)}`} className="text-sm">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <Link
              to="/issues/$key"
              params={{ key: entry.issueKey }}
              className="font-medium hover:underline"
              onClick={(e) => {
                openIssueDetail(entry.issueKey, e)
              }}
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
}
