// 이슈 목록 가젯 — assigned_to_me/recently_created/filter_result 공용 (FR-DB-02 D6/D7 Task-5)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { useOpenIssueDetail } from '@/components/issue/use-open-issue-detail'
import { useGadgetData } from './useGadgetData'
import type { GadgetConfig } from './gadget-types'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * IssueListGadget이 처리하는 가젯 타입.
 * 이슈 목록을 GadgetIssueRow[]로 표시하는 3종 가젯이다.
 * 건수 전용인 issue_count는 IssueCountGadget이 담당한다.
 */
export type IssueListGadgetType = 'assigned_to_me' | 'recently_created' | 'filter_result'

/** IssueListGadget props */
export interface IssueListGadgetProps {
  /** 가젯 타입 — 3종 공용 */
  gadgetType: IssueListGadgetType
  /** 가젯 설정 (projectKey 또는 filterId) */
  config: GadgetConfig
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 목록 가젯.
 *
 * assigned_to_me / recently_created / filter_result 3종 공용.
 * - 이슈 키 클릭 시 이슈 상세 SPA 이동 (TanStack Router Link).
 * - 로딩 / 에러 / 빈 상태(S8) 처리.
 * - 타일 본문 영역에 맞는 컴팩트 레이아웃.
 */
export function IssueListGadget({ gadgetType, config }: IssueListGadgetProps): JSX.Element {
  const openIssueDetail = useOpenIssueDetail()
  const { isLoading, isError, rows } = useGadgetData(gadgetType, config)

  if (isLoading) {
    return (
      <div className="flex flex-1 items-center justify-center p-4 text-sm text-muted-foreground">
        이슈 목록을 불러오는 중입니다
      </div>
    )
  }

  if (isError) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-1 p-4">
        <p className="text-sm text-destructive">이슈 목록을 불러오지 못했습니다</p>
        <p className="text-xs text-muted-foreground">잠시 후 다시 시도해 주세요</p>
      </div>
    )
  }

  if (rows === undefined || rows.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center p-4 text-sm text-muted-foreground">
        이슈가 없습니다
      </div>
    )
  }

  return (
    <ul className="h-full overflow-y-auto divide-y divide-border">
      {rows.map((row) => (
        <li key={row.key} className="flex items-center gap-2 px-3 py-2 hover:bg-accent/40 transition-colors">
          <Link
            to="/issues/$key"
            params={{ key: row.key }}
            className="text-xs font-mono text-muted-foreground hover:text-primary hover:underline shrink-0"
            onClick={(e) => { openIssueDetail(row.key, e) }}
          >
            {row.key}
          </Link>
          <span className="text-sm truncate text-foreground">{row.summary}</span>
        </li>
      ))}
    </ul>
  )
}
