// 계정 연결 목록 컴포넌트 — links 배열 렌더, 빈 상태 + 추가 CTA
import type { JSX } from 'react'
import { AccountLinkCard } from './AccountLinkCard'
import { Button } from '@/components/ui/button'
import { accountLinkLabels } from '@/i18n/account-link-labels'
import type { AccountLinkResponse } from '@/api/account-links'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** AccountLinkList 컴포넌트 props */
export interface AccountLinkListProps {
  /** 표시할 계정 연결 배열 */
  readonly links: readonly AccountLinkResponse[]
  /** 해제 요청 콜백 — 연결 id를 인자로 받는다 */
  readonly onUnlink: (id: string) => void
  /** 계정 추가 CTA 버튼 클릭 콜백 */
  readonly onAddLink: () => void
  /**
   * 현재 해제 진행 중인 연결 id.
   * 해당 id의 카드 해제 버튼을 disabled로 만든다.
   */
  readonly unlinkingId?: string | undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// AccountLinkList
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 계정 연결 목록을 렌더하는 컴포넌트.
 *
 * - links 배열이 비어 있으면 emptyMessage + 추가 CTA 버튼 표시
 * - 각 연결은 AccountLinkCard로 렌더
 * - hasLocalPassword·linkable 등 정책 판단은 부모(Task 10)가 담당
 */
export function AccountLinkList({
  links,
  onUnlink,
  onAddLink,
  unlinkingId,
}: AccountLinkListProps): JSX.Element {
  if (links.length === 0) {
    return (
      <div className="flex flex-col items-center gap-4 py-8 text-center">
        <p className="text-sm text-muted-foreground">
          {accountLinkLabels.page.emptyMessage}
        </p>
        <Button variant="outline" onClick={onAddLink}>
          {accountLinkLabels.page.addCta}
        </Button>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {links.map((link) => (
        <AccountLinkCard
          key={link.id}
          link={link}
          onUnlink={onUnlink}
          isUnlinking={unlinkingId === link.id}
        />
      ))}
    </div>
  )
}
