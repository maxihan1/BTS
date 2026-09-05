// 이슈 메타패널의 보고자 행 — UserSummary 를 사람 이름으로 그리고 실패 시 UUID 로 폴백
import type { JSX } from 'react'
import type { UserSummary } from '@/api/users'
import { issueDetailStrings } from '@/i18n/ko'

/** {@link IssueReporterRow} props */
interface IssueReporterRowProps {
  /** 해석된 보고자. null 이면 해석 실패(탈퇴·비활성 사용자 또는 조회 미완) */
  reporter: UserSummary | null
  /** 해석 실패 시 그대로 보여줄 원본 UUID */
  fallbackId: string
}

/**
 * 보고자 행.
 *
 * ## 폴백이 빈칸이 아니라 UUID 인 이유
 * 비우면 「보고자가 없는 이슈」로 읽힌다. 읽기 어려운 UUID 보다 나쁜 거짓말이다.
 * 보고자는 이슈 생성 시 반드시 정해지므로 「없음」이라는 상태 자체가 존재하지 않는다.
 *
 * ## 이름 사다리는 담당자와 같다
 * `displayName ?? username` — `IssueAssigneeSelect.getDisplayName` 과 같은 순서다.
 * 두 자리가 다른 규칙으로 갈라지면 같은 사람이 화면 위치에 따라 다른 이름으로 보인다.
 *
 * @param reporter 해석된 보고자 (실패 시 null)
 * @param fallbackId 해석 실패 시 표시할 원본 UUID
 */
export function IssueReporterRow({ reporter, fallbackId }: IssueReporterRowProps): JSX.Element {
  return (
    <div className="px-3.5 py-3 border-b border-border">
      <p className="text-xs text-muted-foreground mb-1">{issueDetailStrings.reporterLabel}</p>
      <p className="text-sm font-medium truncate" data-testid="issue-reporter-name">
        {reporter !== null ? (reporter.displayName ?? reporter.username) : fallbackId}
      </p>
    </div>
  )
}
