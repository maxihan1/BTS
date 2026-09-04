// 알림 보관함 단건 항목 컴포넌트 — 제목/발신자/생성시각/읽음강조/버튼/issueKey 링크 (FR-UX-03 D6/D7)
import { Link } from '@tanstack/react-router'
import { useOpenIssueDetail } from '@/components/issue/use-open-issue-detail'
import type { InboxItem } from '@/api/inbox'
import { inboxLabels } from '@/i18n/inbox-labels'
import { useDateFormat } from '@/hooks/use-date-format'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** InboxListItem 컴포넌트 props */
export interface InboxListItemProps {
  /** 렌더할 Inbox 항목 */
  item: InboxItem
  /**
   * 발신자 표시 이름.
   * 부모(InboxPage)가 useActorNames Map에서 해석해 전달한다.
   * null이면 시스템 발신으로 표시.
   */
  actorName: string | null
  /**
   * 읽음 상태 토글 콜백.
   * @param id 항목 UUID
   * @param read 변경할 읽음 여부 (true = 읽음으로, false = 안읽음으로)
   */
  onToggleRead: (id: string, read: boolean) => void
  /**
   * 보관 상태 토글 콜백.
   * @param id 항목 UUID
   * @param archived 변경할 보관 여부 (true = 보관, false = 보관 해제)
   */
  onToggleArchive: (id: string, archived: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 보관함 단건 항목 컴포넌트.
 *
 * 렌더 내용.
 * - 제목(title)
 * - 본문(body, null이면 미렌더)
 * - 발신자명(actorName ?? 시스템 라벨)
 * - 생성 시각(createdAt, KST 포맷)
 * - 안읽음 강조 마커(readAt === null)
 * - 읽음/안읽음 토글 버튼
 * - 보관/보관해제 토글 버튼
 * - issueKey 있으면 /issues/{issueKey} SPA Link
 */
export const InboxListItem = ({
  item,
  actorName,
  onToggleRead,
  onToggleArchive,
}: InboxListItemProps) => {
  const openIssueDetail = useOpenIssueDetail()
  const { formatDateTime } = useDateFormat()
  const isUnread = item.readAt === null
  const isArchived = item.archivedAt !== null
  const displaySender = actorName ?? inboxLabels.sender.system

  return (
    <article
      className="flex flex-col gap-2 rounded-md border bg-card p-4 shadow-sm"
      aria-label={item.title}
    >
      {/* 제목 행 — 안읽음 마커 + 제목 + issueKey 링크 */}
      <div className="flex items-start gap-2">
        {isUnread && (
          <span
            data-testid="inbox-item-unread-marker"
            className="mt-1.5 size-2 shrink-0 rounded-full bg-primary"
            aria-label={inboxLabels.marker.unread}
          />
        )}
        <h3
          className={`flex-1 text-sm leading-snug ${isUnread ? 'font-semibold' : 'font-normal text-muted-foreground'}`}
        >
          {item.title}
        </h3>
      </div>

      {/* 본문 */}
      {item.body !== null && (
        <p
          data-testid="inbox-item-body"
          className="text-sm text-muted-foreground line-clamp-2"
        >
          {item.body}
        </p>
      )}

      {/* 메타 행 — 발신자 + issueKey 링크 + 생성 시각 */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span>{displaySender}</span>

        {item.issueKey !== null && (
          <Link
            to="/issues/$key"
            params={{ key: item.issueKey }}
            className="font-medium text-primary hover:underline"
            onClick={(e) => { openIssueDetail(item.issueKey as string, e) }}
          >
            {item.issueKey}
          </Link>
        )}

        <time
          data-testid="inbox-item-created-at"
          dateTime={item.createdAt}
          className="ml-auto shrink-0"
        >
          {formatDateTime(item.createdAt)}
        </time>
      </div>

      {/* 액션 버튼 행 */}
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="ghost"
          size="xs"
          onClick={() => onToggleRead(item.id, isUnread)}
          className="rounded hover:bg-accent"
        >
          {isUnread ? inboxLabels.item.markRead : inboxLabels.item.markUnread}
        </Button>

        <Button
          type="button"
          variant="ghost"
          size="xs"
          onClick={() => onToggleArchive(item.id, !isArchived)}
          className="rounded hover:bg-accent"
        >
          {isArchived ? inboxLabels.item.unarchive : inboxLabels.item.archive}
        </Button>
      </div>
    </article>
  )
}
