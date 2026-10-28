// 보드 카드 · 백로그 행이 함께 쓰는 이슈 작업 메뉴 — 이동 · 키/링크 복사 · 담당자 · 우선순위 (JB-B-2 · JCB-17)
import type { JSX, SyntheticEvent } from 'react'
import { useContext } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { MoreHorizontal } from 'lucide-react'
import { toast } from 'sonner'
import { changeAssignee, updateIssue } from '@/api/issues'
import { ApiError } from '@/api/client'
import { invalidateIssueViews } from '@/api/issue-view-invalidation'
import { useAuthStore } from '@/auth/authStore'
import { issueDetailStrings } from '@/i18n/ko'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { IssueMoveActionsContext } from './issue-move-actions-context'

const PRIORITIES = [1, 2, 3, 4, 5] as const

const labels = {
  trigger: (issueKey: string) => `${issueKey} 작업`,
  move: '이동',
  copyLink: '링크 복사',
  copyKey: '키 복사',
  copiedLink: '링크를 복사했습니다.',
  copiedKey: (issueKey: string) => `${issueKey} 키를 복사했습니다.`,
  copyFailed: '클립보드에 복사하지 못했습니다.',
  assignee: '담당자',
  assignToMe: '나에게 할당',
  unassign: '할당 해제',
  priority: '우선순위',
} as const

/**
 * 카드 · 행의 이벤트가 바깥 드래그 핸들(@dnd-kit listeners)까지 올라가지 않게 막는다.
 * 메뉴는 portal 로 그려지지만 React 합성 이벤트는 **트리 기준**으로 버블링한다 — 막지 않으면
 * 메뉴 항목을 누르는 순간 카드 드래그가 시작되거나 Space 가 키보드 드래그를 연다.
 */
function stop(event: SyntheticEvent): void {
  event.stopPropagation()
}

/** IssueActionsMenu props */
export interface IssueActionsMenuProps {
  issueKey: string
  /** 낙관적 잠금 버전 — 담당자 · 우선순위 변경의 `expectedVersion` */
  version: number
  priority: number
  assigneeId: string | null
  /** 우클릭으로도 열 수 있게 열림 상태는 카드가 쥔다 */
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * 이슈 작업 메뉴 — `⋯` 버튼과 그 메뉴.
 *
 * 이동 항목은 화면이 {@link IssueMoveActionsContext} 로 채운다(보드는 컬럼 안 순서,
 * 백로그는 섹션 안 순서 + 다른 스프린트 · 백로그). 나머지 항목은 이슈만 알면 되므로 여기서 처리한다.
 *
 * 훅과 항목은 열렸을 때만 마운트되는 {@link IssueActionsMenuItems} 에 있다 — 닫힌 카드 1,000장이
 * 순서 목록을 훑거나 QueryClient 를 찾지 않는다.
 */
export function IssueActionsMenu({ open, onOpenChange, ...issue }: IssueActionsMenuProps): JSX.Element {
  return (
    <DropdownMenu open={open} onOpenChange={onOpenChange}>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          aria-label={labels.trigger(issue.issueKey)}
          onPointerDown={stop}
          onKeyDown={stop}
          className={cn(
            'size-6 shrink-0',
            // 평소엔 숨기고 카드 hover · 포커스 · 열림에서 보인다. hover 가 없는 기기는 늘 보인다
            'opacity-0 group-hover:opacity-100 focus-visible:opacity-100 data-[state=open]:opacity-100',
            '[@media(hover:none)]:opacity-100',
          )}
        >
          <MoreHorizontal aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        className="w-56"
        onPointerDown={stop}
        onKeyDown={stop}
        onClick={stop}
      >
        <IssueActionsMenuItems {...issue} />
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * 메뉴 항목 — 메뉴가 열렸을 때만 마운트된다.
 *
 * 변경은 `useMutation` 없이 API 를 바로 부른다 — 항목은 고르는 순간 닫혀 언마운트되므로 observer 가
 * 결과를 받을 수 없다. 결과는 {@link invalidateIssueViews} 로 보드 · 백로그 · 목록 · 상세가 함께 다시 읽는다.
 */
function IssueActionsMenuItems({
  issueKey,
  version,
  priority,
  assigneeId,
}: Omit<IssueActionsMenuProps, 'open' | 'onOpenChange'>): JSX.Element {
  const queryClient = useQueryClient()
  const resolveMoves = useContext(IssueMoveActionsContext)
  const myId = useAuthStore((s) => s.user?.userId ?? null)
  const moves = resolveMoves !== null ? resolveMoves(issueKey) : []

  function run(request: () => Promise<unknown>, errorMessage: string): void {
    request()
      .catch((err: unknown) => {
        toast.error(
          err instanceof ApiError && err.status === 409 ? issueDetailStrings.versionConflictError : errorMessage,
        )
      })
      // 실패해도 다시 읽는다 — 409 면 낡은 version 을 들고 있는 것이다
      .finally(() => void invalidateIssueViews(queryClient, issueKey))
  }

  function copy(text: string, done: string): void {
    navigator.clipboard.writeText(text).then(
      () => toast.success(done),
      () => toast.error(labels.copyFailed),
    )
  }

  function setAssignee(next: string | null): void {
    run(() => changeAssignee(issueKey, { assigneeId: next, expectedVersion: version }), issueDetailStrings.assigneeChangeError)
  }

  return (
    <>
      {moves.length > 0 && (
        <>
          <DropdownMenuSub>
            <DropdownMenuSubTrigger>{labels.move}</DropdownMenuSubTrigger>
            <DropdownMenuSubContent>
              {moves.map((move) => (
                <DropdownMenuItem key={move.label} disabled={move.disabled} onSelect={move.onSelect}>
                  {move.label}
                </DropdownMenuItem>
              ))}
            </DropdownMenuSubContent>
          </DropdownMenuSub>
          <DropdownMenuSeparator />
        </>
      )}
      <DropdownMenuItem
        onSelect={() => copy(`${window.location.origin}/issues/${issueKey}`, labels.copiedLink)}
      >
        {labels.copyLink}
      </DropdownMenuItem>
      <DropdownMenuItem onSelect={() => copy(issueKey, labels.copiedKey(issueKey))}>
        {labels.copyKey}
      </DropdownMenuItem>
      <DropdownMenuSeparator />
      <DropdownMenuSub>
        <DropdownMenuSubTrigger>{labels.assignee}</DropdownMenuSubTrigger>
        <DropdownMenuSubContent>
          <DropdownMenuItem
            disabled={myId === null || myId === assigneeId}
            onSelect={() => setAssignee(myId)}
          >
            {labels.assignToMe}
          </DropdownMenuItem>
          <DropdownMenuItem disabled={assigneeId === null} onSelect={() => setAssignee(null)}>
            {labels.unassign}
          </DropdownMenuItem>
        </DropdownMenuSubContent>
      </DropdownMenuSub>
      <DropdownMenuSub>
        <DropdownMenuSubTrigger>{labels.priority}</DropdownMenuSubTrigger>
        <DropdownMenuSubContent>
          <DropdownMenuRadioGroup
            value={String(priority)}
            onValueChange={(value) =>
              run(
                () => updateIssue(issueKey, { priority: Number(value), expectedVersion: version }),
                issueDetailStrings.priorityChangeError,
              )
            }
          >
            {PRIORITIES.map((p) => (
              <DropdownMenuRadioItem key={p} value={String(p)}>
                {issueDetailStrings.priorityNames[p]}
              </DropdownMenuRadioItem>
            ))}
          </DropdownMenuRadioGroup>
        </DropdownMenuSubContent>
      </DropdownMenuSub>
    </>
  )
}
