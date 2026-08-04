// 이슈 상세 감시자 섹션 — Watch 토글 + 카운트 + 명단 (FR-WT-01 D6)
import type { RefObject } from 'react'
import { useAuthUser } from '@/auth/authStore'
import { useWatchers, useAddWatcher, useRemoveWatcher } from '@/api/issue-watchers'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { toast } from 'sonner'

/** 명단 기본 표시 최대 인원 수 */
const WATCHER_LIST_MAX = 8

interface WatchersSectionProps {
  /** 이슈 키 (예: "ATLAS-1") */
  issueKey: string
  /**
   * Watch 토글 버튼으로 가는 ref — FR-UX-10 F11 단축키 `w`가 여기에 포커스를 준 뒤 click()한다.
   * 소비처는 routes/issues.$key.tsx(IssueMetaPanel.watchToggleRef 경유).
   *
   * 핸들러를 복제하지 않고 버튼을 미는 이유는 이 버튼이 이미 가진 canToggle 판정
   * (in-flight · 미인증 · GET 로딩/에러 윈도우)과 에러 토스트를 그대로 재사용하기 위해서다.
   *
   * 이 ref의 유무가 `aria-keyshortcuts` 노출 조건이기도 하다 —
   * "손잡이를 연결한 화면에만 단축키가 있다" (FavoriteButton과 같은 규칙).
   */
  focusRef?: RefObject<HTMLButtonElement | null>
}

/**
 * 이슈 감시자 섹션.
 *
 * - 현재 워처 카운트와 displayName 목록을 표시한다.
 * - 본인은 "(나)" 접미사로 식별한다.
 * - Watch/Unwatch 토글 버튼으로 감시 상태를 변경한다.
 * - 토글 불가 구간(mutation in-flight · 미인증 · GET 로딩/에러 윈도우)은 aria-disabled로
 *   알리고, 실제 차단은 handleToggle의 canToggle 가드가 수행한다 (아래 주석 참조).
 * - GET 로딩/에러 윈도우(data===undefined)에서 토글을 차단해 헛 POST를 방지한다.
 * - mutation 실패 시 toast.error로 사용자에게 피드백을 제공한다.
 *
 * @param issueKey 이슈 키
 * @param focusRef Watch 토글 버튼으로 가는 ref (단축키 `w`)
 */
export const WatchersSection = ({ issueKey, focusRef }: WatchersSectionProps) => {
  const user = useAuthUser()
  const { data, isLoading, isError } = useWatchers(issueKey)
  const addWatcher = useAddWatcher(issueKey)
  const removeWatcher = useRemoveWatcher(issueKey)

  const isMutating = addWatcher.isPending || removeWatcher.isPending

  /**
   * 토글이 가능한 조건.
   * - mutation in-flight 아님
   * - 인증 사용자가 존재하고 userId가 빈 문자열이 아님 (!! 로 falsy 방어)
   * - GET 응답 data가 확정됨 (undefined 이면 로딩/에러 윈도우 — 의도 역전 방지)
   */
  const canToggle = !isMutating && !!user?.userId && data !== undefined

  /**
   * mutation 실패 시 에러 코드를 추출해 toast로 사용자에게 알린다.
   * 현재는 errorCode와 무관하게 공통 메시지를 사용한다.
   * 향후 코드별 메시지 분기가 필요하면 extractWatcherErrorCode(error) 결과를 switch로 확장한다.
   */
  const onMutationError = () => {
    toast.error(issueDetailStrings.watchersError)
  }

  const handleToggle = () => {
    // data가 undefined이면 로딩/에러 윈도우 — 클릭을 무시해 헛 POST를 방지한다.
    // 네이티브 disabled를 벗겼으므로(아래 aria-disabled 주석) 브라우저가 클릭을 막아주지
    // 않는다. 이 한 줄이 중복 발행·헛 POST 차단의 유일한 증인이다 —
    // 지우면 WatchersSection.test.tsx S7b·S9c가 red다.
    if (!canToggle) return

    if (data.isWatching) {
      removeWatcher.mutate(user.userId, { onError: onMutationError })
    } else {
      addWatcher.mutate(undefined, { onError: onMutationError })
    }
  }

  return (
    <section data-testid="watchers-section" aria-label={issueDetailStrings.watchersLabel}>
      <h2 className="text-sm font-medium text-muted-foreground mb-2">
        {issueDetailStrings.watchersLabel}
      </h2>

      {/* 토글 버튼 */}
      <Button
        ref={focusRef}
        variant="outline"
        size="sm"
        // disabled: 스타일은 aria-disabled로 발동하지 않으므로 등가 표기를 직접 붙인다.
        // (프리미티브 button.tsx는 14개 화면이 공유하므로 여기서만 처리한다.)
        className="mb-3 aria-disabled:opacity-50 aria-disabled:cursor-not-allowed"
        onClick={handleToggle}
        // ★네이티브 disabled가 아니라 aria-disabled인 이유 (FR-UX-10 F11 Task-5b).
        //   브라우저는 disabled로 전환된 요소의 포커스를 <body>로 떨어뜨리고, 다시
        //   enabled가 돼도 되돌려주지 않는다. 그러면 뮤테이션이 끝나 aria-pressed가
        //   뒤집히는 순간 포커스가 이 버튼에 없어 스크린리더가 상태 변화를 읽지 않는다
        //   (`w` 단축키 사용자에게는 아무 일도 안 일어난 것과 같다).
        //   실측 추적: 뮤테이션 시작 active=BODY → 완료 후에도 active=BODY.
        //   aria-disabled는 포커스를 유지하면서 비활성만 알린다. 실제 차단은 handleToggle의
        //   canToggle 가드가 한다. pointer-events-none은 쓰지 않는다 — 클릭이 죽어
        //   같은 문제가 형태만 바꿔 재발한다.
        aria-disabled={!canToggle}
        aria-pressed={data?.isWatching ?? false}
        // 단축키 존재를 알 경로가 `?` 도움말 모달뿐이라 표준 속성으로도 알린다.
        // 키 문자 정본은 CONTEXT_SHORTCUTS(FR-UX-10 F11) — 재배치하면 여기도 같이 고친다.
        // 시각 툴팁은 만들지 않는다(신규 UI 0 제약 + 툴팁은 키보드 사용자에게 닿지 않는다).
        aria-keyshortcuts={focusRef !== undefined ? 'w' : undefined}
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
