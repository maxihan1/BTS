// 알림 보관함 헤더 종(🔔) 아이콘 컴포넌트 — 미읽음 뱃지 + 그 자리 최근 알림 팝오버 (FR-UX-03 D6/D7 · JN-1)
import { useId, useState } from 'react'
import type { MouseEvent } from 'react'
import { Link } from '@tanstack/react-router'
import { Bell } from 'lucide-react'
import { INBOX_TABS, useInbox, useMarkRead, useUnreadCount } from '@/api/inbox'
import type { InboxItem } from '@/api/inbox'
import { useActorNames } from '@/api/inbox-actors'
import { useOpenIssueDetail } from '@/components/issue/use-open-issue-detail'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { useDateFormat } from '@/hooks/use-date-format'
import { inboxLabels } from '@/i18n/inbox-labels'
import { formatUnreadBadge } from './inboxBellUtils'

/** 팝오버에 싣는 최근 알림 수 — 나머지는 「전체 보기」(/inbox) */
const POPOVER_PAGE_SIZE = 10

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Header 우측에 삽입되는 알림 종 아이콘.
 * - useUnreadCount() 훅으로 미읽음 카운트를 구독한다.
 * - 카운트가 0이면 뱃지를 숨기고, 1 이상이면 뱃지에 숫자를 표시한다.
 * - 100 이상이면 "99+"로 표시한다.
 * - 클릭 시 페이지를 옮기지 않고 그 자리에 최근 알림 팝오버를 연다(Jira 와 같다). Esc 로 닫힌다.
 */
export const InboxBell = () => {
  const [open, setOpen] = useState(false)
  const { data: count = 0 } = useUnreadCount()

  const badgeText = formatUnreadBadge(count)

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          className="relative"
          aria-label={inboxLabels.bell.ariaLabel}
        >
          <Bell className="size-4" />
          {badgeText !== null && (
            <span
              data-testid="inbox-unread-badge"
              className="absolute -right-1 -top-1 flex min-w-[1.125rem] items-center justify-center rounded-full bg-destructive px-1 py-0.5 text-[0.625rem] font-semibold leading-none text-destructive-foreground"
            >
              <span className="sr-only">{inboxLabels.bell.unreadBadgeScreenReader}</span>
              {badgeText}
            </span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="end"
        className="flex w-96 flex-col p-0"
        aria-label={inboxLabels.popover.title}
      >
        {/* PopoverContent 는 열렸을 때만 마운트된다 — 목록 조회도 그때만 돈다 */}
        <InboxPopoverPanel onClose={() => setOpen(false)} />
      </PopoverContent>
    </Popover>
  )
}

/** 팝오버 본문 — 제목 + 「읽지 않은 항목만」 스위치 · 최근 알림 · 「전체 보기」 */
function InboxPopoverPanel({ onClose }: { onClose: () => void }) {
  const [unreadOnly, setUnreadOnly] = useState(false)
  const switchId = useId()
  const tab = unreadOnly ? INBOX_TABS.UNREAD : INBOX_TABS.ALL
  const { data, isLoading } = useInbox({ tab, size: POPOVER_PAGE_SIZE })
  const items = data?.content ?? []
  const { data: actorNameMap } = useActorNames(items)

  return (
    <>
      <div className="flex items-center justify-between border-b px-4 py-3">
        <h2 className="text-base font-semibold">{inboxLabels.popover.title}</h2>
        <label htmlFor={switchId} className="flex items-center gap-2 text-xs text-muted-foreground">
          {inboxLabels.popover.unreadOnly}
          <Switch id={switchId} checked={unreadOnly} onCheckedChange={setUnreadOnly} />
        </label>
      </div>

      <div className="max-h-[28rem] overflow-y-auto">
        {isLoading ? (
          <div className="flex flex-col gap-2 p-4" aria-hidden="true">
            <Skeleton className="h-12" />
            <Skeleton className="h-12" />
            <Skeleton className="h-12" />
          </div>
        ) : items.length === 0 ? (
          <p className="py-10 text-center text-sm text-muted-foreground">
            {unreadOnly ? inboxLabels.empty.unread : inboxLabels.empty.all}
          </p>
        ) : (
          <ul className="divide-y">
            {items.map((item) => (
              <li key={item.id}>
                <InboxPopoverItem
                  item={item}
                  actorName={item.actorUserId !== null ? (actorNameMap?.get(item.actorUserId) ?? null) : null}
                  onClose={onClose}
                />
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="border-t px-4 py-2 text-center">
        <Link to="/inbox" onClick={onClose} className="text-sm font-medium text-primary hover:underline">
          {inboxLabels.popover.viewAll}
        </Link>
      </div>
    </>
  )
}

/**
 * 알림 한 줄. 누르면 안읽음이면 읽음 처리하고 이동한 뒤 팝오버를 닫는다.
 * 이슈 알림은 이슈 상세(댓글 딥링크 포함), 이슈 없는 알림은 /inbox 로 간다.
 */
function InboxPopoverItem({
  item,
  actorName,
  onClose,
}: {
  item: InboxItem
  actorName: string | null
  onClose: () => void
}) {
  const openIssueDetail = useOpenIssueDetail()
  const { mutate: markRead } = useMarkRead()
  const { formatDateTime } = useDateFormat()
  const isUnread = item.readAt === null

  const handleClick = (e: MouseEvent) => {
    if (isUnread) markRead({ id: item.id, read: true })
    if (item.issueKey !== null) openIssueDetail(item.issueKey, e, item.commentId)
    onClose()
  }

  const body = (
    <>
      <span
        className={`mt-1.5 size-2 shrink-0 rounded-full ${isUnread ? 'bg-primary' : 'bg-transparent'}`}
        aria-label={isUnread ? inboxLabels.marker.unread : undefined}
      />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className={`text-sm leading-snug ${isUnread ? 'font-semibold' : 'text-muted-foreground'}`}>
          {item.title}
        </span>
        <span className="flex gap-2 text-xs text-muted-foreground">
          <span className="truncate">{actorName ?? inboxLabels.sender.system}</span>
          {item.issueKey !== null && <span className="shrink-0">{item.issueKey}</span>}
          <time dateTime={item.createdAt} className="ml-auto shrink-0">
            {formatDateTime(item.createdAt)}
          </time>
        </span>
      </span>
    </>
  )
  const rowClass = 'flex gap-2 px-4 py-3 hover:bg-accent'

  return item.issueKey !== null ? (
    <Link
      to="/issues/$key"
      params={{ key: item.issueKey }}
      // 새 탭·링크 복사 경로에도 딥링크가 남아야 한다 — onClick 은 좌클릭에서만 모달로 가로챈다.
      search={item.commentId !== null ? { comment: item.commentId } : {}}
      className={rowClass}
      onClick={handleClick}
    >
      {body}
    </Link>
  ) : (
    <Link to="/inbox" className={rowClass} onClick={handleClick}>
      {body}
    </Link>
  )
}
