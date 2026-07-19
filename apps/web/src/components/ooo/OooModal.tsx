// 부재중(Out of Office) 설정 모달 — 기간 + 대리자 검색 선택 + 안내 메시지 저장(replace)/해제 (FR-PR-03 Task 7)
import type { JSX } from 'react'
import { useEffect, useState } from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from '@/components/ui/dialog'
import { useOooQuery, useUpdateOooMutation, useClearOooMutation } from '@/hooks/use-ooo'
import { refreshWhoami } from '@/api/useProfile'
import { useUserSearch } from '@/hooks/use-user-directory'
import { useUsersByIds } from '@/hooks/use-users'
import type { UserSummary } from '@/api/users'
import { toInstant, toLocalInputValue } from '@/lib/ooo-datetime'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { oooLabels } from '@/i18n/ooo-labels'

/** OooModal props — controlled(open/onOpenChange). Header가 소유(StatusModal 선례). */
export interface OooModalProps {
  readonly open: boolean
  readonly onOpenChange: (open: boolean) => void
}

/** 안내 메시지 최대 길이 — 백엔드 NFR3(500자)와 일치 */
const MESSAGE_MAX_LENGTH = 500
/** 대리자 검색을 활성화하는 최소 쿼리 길이 — useUserSearch enabled 가드와 일치(AddMemberDialog 선례) */
const DELEGATE_SEARCH_MIN_LENGTH = 2

/** UserSummary의 표시 이름을 반환한다(displayName 우선, 없으면 username). AddMemberDialog 선례. */
function resolveUserLabel(user: UserSummary): string {
  return user.displayName ?? user.username
}

/** 공백만 있는 메시지는 null로 정규화한다 — 백엔드 blank→null 정규화(EC6)와 일치(StatusModal.normalize 선례). */
function normalizeMessage(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

interface DelegateResultListProps {
  readonly query: string
  readonly onSelect: (user: UserSummary) => void
}

/**
 * 대리자 검색 결과 목록 — AddMemberDialog.SearchResultList 미러.
 * query가 {@link DELEGATE_SEARCH_MIN_LENGTH} 미만이면 렌더하지 않는다(useUserSearch enabled 가드와 일치).
 */
function DelegateResultList({ query, onSelect }: DelegateResultListProps): JSX.Element | null {
  const { data: results, isFetching } = useUserSearch(query)

  if (query.length < DELEGATE_SEARCH_MIN_LENGTH) return null

  if (isFetching) {
    return <p className="text-xs text-muted-foreground py-2 px-1">검색 중...</p>
  }

  if (results === undefined || results.length === 0) {
    return <p className="text-sm text-muted-foreground py-2 px-1">{oooLabels.delegateNoResults}</p>
  }

  return (
    <ul className="mt-1 max-h-40 overflow-y-auto rounded-md border divide-y">
      {results.map((user) => (
        <li key={user.id}>
          <button
            type="button"
            className="w-full text-left px-3 py-2 text-sm hover:bg-accent transition-colors"
            onClick={() => { onSelect(user) }}
          >
            {resolveUserLabel(user)}
            {user.displayName !== null && (
              <span className="ml-1 text-xs text-muted-foreground">@{user.username}</span>
            )}
          </button>
        </li>
      ))}
    </ul>
  )
}

/**
 * 부재중 설정 모달 (FR-PR-03).
 *
 * 기간(시작/종료) + 대리자(검색 선택, 선택 안 함 허용) + 안내 메시지를 입력해 부재중을
 * 원자적으로 교체(replace)하거나 해제한다. 저장 성공 시 {@link refreshWhoami}로
 * authStore.user를 최신화해 Header 부재중 표시를 즉시 반영한다(StatusModal 선례).
 *
 * 열림 전이(open false→true)에서만 현재 설정으로 폼을 초기화한다 — 재열림 시 이전 세션 입력이
 * stale하게 남지 않도록 한다(Radix controlled Dialog 토글닫기 stale 함정 방어, memory:
 * react-usestate-stale-key-prop). 단, 모달이 열린 채 OOO 쿼리가 refetch(window focus/invalidate)되어
 * `current`만 바뀌는 경우는 재초기화하지 않는다 — 편집 중 입력이 서버값으로 덮이는 것을 방지한다
 * (current가 open 이후 늦게 로드되는 경우는 허용 가능한 트레이드오프).
 *
 * 대리자는 id만 상태로 보관하고 표시 이름은 {@link useUsersByIds}로 파생한다(ComponentFormDialog/
 * ProjectLeadSelect 선례 — 검색으로 새로 고른 경우와 기존 설정을 단일 출처로 통일). 단, 방금 검색
 * 결과에서 선택한 이름은 {@link useUsersByIds} 파생 결과가 도착하기 전까지 잠시 비어 있을 수 있어
 * (로딩 윈도) `delegateName` state를 fallback으로 별도 보관한다 — 그 사이 raw UUID가 노출되는 것을
 * 방지한다.
 *
 * 기간 검증(종료<=시작, 종료<=현재)과 저장/해제 실패는 인라인 에러 배너(StatusModal 선례,
 * `role="alert"`)로 분기별 구체 메시지를 안내한다.
 */
export function OooModal({ open, onOpenChange }: OooModalProps): JSX.Element {
  const { data: current } = useOooQuery()
  const updateMutation = useUpdateOooMutation()
  const clearMutation = useClearOooMutation()

  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [delegateUserId, setDelegateUserId] = useState<string | null>(null)
  const [delegateQuery, setDelegateQuery] = useState('')
  /** 선택된 대리자의 표시 이름 fallback — {@link useUsersByIds} 로딩 윈도 동안 사용(F4). */
  const [delegateName, setDelegateName] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setStartsAt(toLocalInputValue(current?.startsAt ?? null))
      setEndsAt(toLocalInputValue(current?.endsAt ?? null))
      setDelegateUserId(current?.delegateUserId ?? null)
      setDelegateQuery('')
      setDelegateName(current?.delegateName ?? null)
      setMessage(current?.message ?? '')
      setErrorMessage(null)
    }
    // current를 의존성에 포함하지 않는다 — 열림 전이(open false→true)에서만 초기화하고, 모달이
    // 열린 채 OOO 쿼리 refetch로 current만 바뀌는 경우는 재초기화하지 않는다(F2, 편집 중 입력
    // 유실 방지가 우선순위).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const { data: delegateUsers } = useUsersByIds(delegateUserId !== null ? [delegateUserId] : [])
  const selectedDelegate = delegateUsers?.[0]
  const selectedDelegateLabel = selectedDelegate !== undefined
    ? resolveUserLabel(selectedDelegate)
    : delegateName

  const isPending = updateMutation.isPending || clearMutation.isPending

  function handleSaved(): void {
    setErrorMessage(null)
    void refreshWhoami()
    onOpenChange(false)
  }

  function handleSelectDelegate(user: UserSummary): void {
    setDelegateUserId(user.id)
    setDelegateQuery('')
    setDelegateName(resolveUserLabel(user))
  }

  function handleClearDelegate(): void {
    setDelegateUserId(null)
    setDelegateName(null)
  }

  function handleSave(): void {
    const startInstant = toInstant(startsAt)
    const endInstant = toInstant(endsAt)

    if (startInstant === null || endInstant === null) {
      setErrorMessage(oooLabels.periodRequiredError)
      return
    }
    if (new Date(endInstant).getTime() <= new Date(startInstant).getTime()) {
      setErrorMessage(oooLabels.endBeforeStartError)
      return
    }
    if (new Date(endInstant).getTime() <= Date.now()) {
      setErrorMessage(oooLabels.endNotFutureError)
      return
    }

    updateMutation.mutate(
      { startsAt: startInstant, endsAt: endInstant, delegateUserId, message: normalizeMessage(message) },
      { onSuccess: handleSaved, onError: () => { setErrorMessage(oooLabels.saveFailedError) } },
    )
  }

  function handleClear(): void {
    clearMutation.mutate(undefined, {
      onSuccess: handleSaved,
      onError: () => { setErrorMessage(oooLabels.saveFailedError) },
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{oooLabels.title}</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <Label htmlFor="ooo-starts-at">{oooLabels.startsAtLabel}</Label>
              <Input
                id="ooo-starts-at" type="datetime-local" value={startsAt}
                disabled={isPending} onChange={(e) => { setStartsAt(e.target.value) }}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="ooo-ends-at">{oooLabels.endsAtLabel}</Label>
              <Input
                id="ooo-ends-at" type="datetime-local" value={endsAt}
                disabled={isPending} onChange={(e) => { setEndsAt(e.target.value) }}
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="ooo-delegate-search">{oooLabels.delegateLabel}</Label>
            <input
              id="ooo-delegate-search" type="text" value={delegateQuery}
              placeholder={oooLabels.delegateSearchPlaceholder}
              disabled={isPending} autoComplete="off"
              onChange={(e) => { setDelegateQuery(e.target.value) }}
              className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
            />
            <DelegateResultList query={delegateQuery} onSelect={handleSelectDelegate} />
            {delegateUserId !== null && (
              <p className="text-sm text-muted-foreground">
                {oooLabels.delegateSelected}.{' '}
                <span className="font-medium text-foreground">
                  {selectedDelegateLabel ?? delegateUserId}
                </span>{' '}
                <button
                  type="button"
                  aria-label="대리자 선택 해제"
                  className="text-xs text-muted-foreground underline"
                  onClick={handleClearDelegate}
                >
                  ×
                </button>
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="ooo-message">{oooLabels.messageLabel}</Label>
            <textarea
              id="ooo-message" value={message} placeholder={oooLabels.messagePlaceholder}
              maxLength={MESSAGE_MAX_LENGTH} rows={3} disabled={isPending}
              onChange={(e) => { setMessage(e.target.value) }}
              className="w-full resize-y rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground"
            />
          </div>

          {errorMessage !== null && (
            <p role="alert" className="text-sm text-destructive">
              {errorMessage}
            </p>
          )}
        </div>

        <DialogFooter className="sm:justify-between">
          <Button type="button" variant="outline" size="sm" disabled={isPending} onClick={handleClear}>
            {oooLabels.clearButton}
          </Button>
          <Button type="button" size="sm" disabled={isPending} onClick={handleSave}>
            {updateMutation.isPending ? oooLabels.savingButton : oooLabels.saveButton}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
