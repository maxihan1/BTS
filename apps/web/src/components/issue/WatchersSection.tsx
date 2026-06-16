// 이슈 상세 감시자 섹션 — Watch 토글 + 카운트 + 명단 (FR-WT-01 D6)
import { useAuthUser } from '@/auth/authStore'
import { useWatchers, useAddWatcher, useRemoveWatcher } from '@/api/issue-watchers'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'

/** 명단 기본 표시 최대 인원 수 */
const WATCHER_LIST_MAX = 8

interface WatchersSectionProps {
  /** 이슈 키 (예: "ATLAS-1") */
  issueKey: string
}

/**
 * 이슈 감시자 섹션.
 *
 * - 현재 워처 카운트와 displayName 목록을 표시한다.
 * - 본인은 "(나)" 접미사로 식별한다.
 * - Watch/Unwatch 토글 버튼으로 감시 상태를 변경한다.
 * - mutation in-flight 동안 버튼을 disabled 처리해 더블클릭을 방지한다.
 *
 * @param issueKey 이슈 키
 */
export const WatchersSection = ({ issueKey }: WatchersSectionProps) => {
  const user = useAuthUser()
  const { data, isLoading, isError } = useWatchers(issueKey)
  const addWatcher = useAddWatcher(issueKey)
  const removeWatcher = useRemoveWatcher(issueKey)

  const isMutating = addWatcher.isPending || removeWatcher.isPending

  const handleToggle = () => {
    if (isMutating || user === null) return

    if (data?.isWatching) {
      removeWatcher.mutate(user.userId)
    } else {
      addWatcher.mutate(undefined)
    }
  }

  return (
    <section data-testid="watchers-section" aria-label={issueDetailStrings.watchersLabel}>
      <h2 className="text-sm font-medium text-muted-foreground mb-2">
        {issueDetailStrings.watchersLabel}
      </h2>

      {/* 토글 버튼 */}
      <Button
        variant="outline"
        size="sm"
        className="mb-3"
        onClick={handleToggle}
        disabled={isMutating || user === null}
        aria-pressed={data?.isWatching ?? false}
        data-testid="watch-toggle-button"
      >
        {data?.isWatching ? issueDetailStrings.unwatchButton : issueDetailStrings.watchButton}
      </Button>

      {/* 로딩 상태 */}
      {isLoading && (
        <p className="text-sm text-muted-foreground">{issueDetailStrings.watchersLoading}</p>
      )}

      {/* 에러 상태 */}
      {isError && !isLoading && (
        <p className="text-sm text-destructive">{issueDetailStrings.watchersError}</p>
      )}

      {/* 정상 상태 */}
      {data !== undefined && (
        <>
          {/* 카운트 */}
          <p className="text-sm font-medium mb-1">
            {issueDetailStrings.watchersCount(data.count)}
          </p>

          {/* 빈 상태 */}
          {data.count === 0 && (
            <p className="text-sm text-muted-foreground">{issueDetailStrings.watchersEmpty}</p>
          )}

          {/* 명단 */}
          {data.count > 0 && (
            <WatcherList watchers={data.watchers} currentUserId={user?.userId ?? null} />
          )}
        </>
      )}
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface WatcherListProps {
  watchers: ReadonlyArray<{ userId: string; displayName: string }>
  currentUserId: string | null
}

/** 워처 명단 — 최대 WATCHER_LIST_MAX명까지 표시하고 초과는 "+N명 더"로 요약 */
const WatcherList = ({ watchers, currentUserId }: WatcherListProps) => {
  const visible = watchers.slice(0, WATCHER_LIST_MAX)
  const overflow = watchers.length - visible.length

  return (
    <ul className="space-y-1">
      {visible.map((w) => {
        const isSelf = currentUserId !== null && w.userId === currentUserId
        return (
          <li key={w.userId} className="text-sm">
            {w.displayName}
            {isSelf && (
              <span className="ml-1 text-muted-foreground">
                {issueDetailStrings.watcherSelfSuffix}
              </span>
            )}
          </li>
        )
      })}
      {overflow > 0 && (
        <li className="text-sm text-muted-foreground">
          {issueDetailStrings.watchersMore(overflow)}
        </li>
      )}
    </ul>
  )
}
