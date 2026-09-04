// 보드 관리 `⋯` 메뉴 — 이름 변경 · 삭제 (FR-BD-01-2a/2b · Jira 근거 J3·J4·J5)
//
// 보드 화면 헤더와 사이드바 트리의 보드 행이 **같은 컴포넌트**를 쓴다. 두 벌로 두면
// 권한 게이팅·실패 문구·삭제 확인 문구가 갈리고, `⋯` 의 접근성 이름도 서로 어긋난다.
// ⚠️ 두 자리의 트리거 이름은 `boardLabels.actions.triggerAriaLabel` 하나라 **바이트 단위로
//    같다** — e2e 는 반드시 컨테이너로 스코프를 좁혀 잡는다(계약 §2).
import type { FormEvent, JSX } from 'react'
import { useState } from 'react'
import { MoreHorizontal, Pencil, Trash2 } from 'lucide-react'
import { ApiError } from '@/api/client'
import { useUpdateBoardName, useDeleteBoard } from '@/hooks/use-boards'
import { extractErrorCode } from '@/lib/extract-error-code'
import { boardLabels, boardManageErrorMessage } from '@/i18n/board-labels'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/**
 * 보드 관리 mutation 실패를 확인 창 안에 실을 문구로 옮긴다.
 *
 * 상태 코드조차 없는 실패(연결 끊김 · `useDeleteBoard` 타임아웃)는 errorCode 가 없어
 * 「응답 없음」으로 간다 — 그 경우 사용자가 할 다음 행동은 재시도이지 값 수정이 아니다
 * (`lib/move-error-message.ts` 가 세운 세 갈래와 같은 규칙).
 *
 * 코드→문구 표 자체는 `i18n/board-labels.ts` 의 공유 util 하나뿐이다. 화면마다 인라인으로
 * 만들면 키가 갈려 raw 코드가 노출된다 (PR #106).
 */
function resolveBoardActionError(err: unknown, fallback: string): string {
  if (!(err instanceof ApiError)) return boardLabels.actions.noResponse
  return boardManageErrorMessage(extractErrorCode(err.body), fallback)
}

/** 보드 이름 변경 다이얼로그 props */
interface RenameBoardDialogProps {
  /** 열림 상태 — 소비자가 쥔다 */
  open: boolean
  /** 열림 상태 변경 요청 */
  onOpenChange: (open: boolean) => void
  /** 보드가 속한 프로젝트 키 — 목록 캐시 무효화 대상 */
  projectKey: string
  /** 이름을 바꿀 보드 UUID */
  boardId: string
  /** 현재 이름 — 입력 초기값 */
  currentName: string
}

/**
 * 보드 이름 변경 다이얼로그 (J3).
 *
 * Jira 는 보드 **설정 화면**의 연필로 이름을 바꾸지만 BTS 에는 그 화면이 없어 `⋯` 메뉴에서
 * 연다 (plan 의 의도적 편차 X2).
 *
 * 실패는 창 **안**에 남기고 성공했을 때만 닫는다 — 모달 오버레이가 화면 배너를 가리므로
 * `ConfirmDialog` 가 세운 것과 같은 규칙을 쓴다. 입력 초기값을 props 로 잡으므로 부모가
 * `key` 로 재마운트해 지난 값이 남지 않게 한다.
 */
function RenameBoardDialog({
  open,
  onOpenChange,
  projectKey,
  boardId,
  currentName,
}: RenameBoardDialogProps): JSX.Element {
  const [name, setName] = useState(currentName)
  const [error, setError] = useState<string | undefined>(undefined)
  const { mutate, isPending } = useUpdateBoardName(projectKey, boardId)

  function handleSubmit(e: FormEvent<HTMLFormElement>): void {
    e.preventDefault()
    const trimmed = name.trim()
    // 공백 이름은 백엔드가 400 으로 막는다 — 왕복하지 않고 여기서 멈춘다.
    if (trimmed === '') return

    setError(undefined)
    mutate(
      { name: trimmed },
      {
        onSuccess: () => { onOpenChange(false) },
        onError: (err: unknown) => {
          setError(resolveBoardActionError(err, boardLabels.actions.renameFailed))
        },
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent aria-describedby={undefined} className="max-w-md">
        <DialogHeader>
          <DialogTitle>{boardLabels.actions.renameDialogTitle}</DialogTitle>
        </DialogHeader>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div className="space-y-1">
            <Label htmlFor="board-rename-name">{boardLabels.actions.renameNameLabel}</Label>
            <Input
              id="board-rename-name"
              value={name}
              onChange={(e) => {
                setName(e.target.value)
                setError(undefined)
              }}
              disabled={isPending}
              aria-describedby={error !== undefined ? 'board-rename-error' : undefined}
            />
          </div>
          {error !== undefined && (
            <p
              id="board-rename-error"
              role="alert"
              className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive ring-1 ring-foreground/10"
            >
              {error}
            </p>
          )}
          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              disabled={isPending}
              onClick={() => { onOpenChange(false) }}
            >
              {boardLabels.actions.renameCancel}
            </Button>
            <Button type="submit" disabled={isPending || name.trim() === ''}>
              {boardLabels.actions.renameSubmit}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** 보드 관리 메뉴 props */
interface BoardActionsMenuProps {
  /** 보드가 속한 프로젝트 키 */
  projectKey: string
  /** 현재 보고 있는 보드 UUID */
  boardId: string
  /** 현재 보드 이름 — 트리거 이름과 삭제 확인 문구에 쓴다 */
  boardName: string
  /** 이름 변경 항목 노출 여부 — 프로젝트 CREATE 권한 */
  canRename: boolean
  /** 삭제 항목 노출 여부 — 보드 응답의 `canDelete` 가 참일 때만 */
  canDelete: boolean
  /** 삭제가 성공한 뒤 호출 — 부모가 남은 보드로 이동한다 (E2) */
  onDeleted: () => void
  /**
   * 트리거 버튼 크기. 기본은 보드 헤더용 `icon` 이고, 사이드바 트리처럼 행 높이가 낮은 자리는
   * `icon-xs` 를 넘긴다 — 기본값으로 두면 트리 행이 버튼 높이만큼 벌어진다.
   */
  triggerSize?: 'icon' | 'icon-xs'
}

/**
 * 보드 `⋯` 관리 메뉴 — 이름 변경 · 삭제. 보드 화면 헤더와 사이드바 트리의 보드 행이 공유한다.
 *
 * - 권한이 없는 항목은 **렌더하지 않는다**. 비활성으로 두면 「눌러도 되는 것처럼 보이는 것」이
 *   남고, 그건 J5 가 적은 Jira 동작이 아니다 (FR-BD-01-2d).
 * - 삭제 확인은 `components/ui/confirm-dialog.tsx` 를 쓴다. 그 프리미티브는 확인 뒤 **스스로
 *   닫지 않으므로** 성공했을 때만 여기서 닫고, 실패는 `error` prop 으로 창 안에 남긴다(S7).
 * - `confirming` 은 mutation 의 `isPending` 이다 — 그 동안 취소·Esc·오버레이·X 가 전부 잠긴다.
 *   갇히지 않는 근거는 `useDeleteBoard` 의 타임아웃이다.
 */
export function BoardActionsMenu({
  projectKey,
  boardId,
  boardName,
  canRename,
  canDelete,
  onDeleted,
  triggerSize = 'icon',
}: BoardActionsMenuProps): JSX.Element | null {
  const [renameOpen, setRenameOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleteError, setDeleteError] = useState<string | undefined>(undefined)
  const deleteMutation = useDeleteBoard(projectKey)

  function handleDeleteConfirm(): void {
    setDeleteError(undefined)
    deleteMutation.mutate(
      { boardId },
      {
        onSuccess: () => {
          setDeleteOpen(false)
          onDeleted()
        },
        onError: (err: unknown) => {
          setDeleteError(resolveBoardActionError(err, boardLabels.actions.deleteFailed))
        },
      },
    )
  }

  // 항목이 하나도 없으면 트리거 자체를 내린다 — 열면 비는 메뉴는 「권한이 없다」가 아니라
  // 「고장났다」로 읽힌다.
  if (!canRename && !canDelete) return null

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size={triggerSize}
            aria-label={boardLabels.actions.triggerAriaLabel(boardName)}
          >
            <MoreHorizontal aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          {canRename && (
            <DropdownMenuItem onSelect={() => { setRenameOpen(true) }}>
              <Pencil aria-hidden="true" />
              {boardLabels.actions.renameItem}
            </DropdownMenuItem>
          )}
          {canDelete && (
            <DropdownMenuItem
              variant="destructive"
              onSelect={() => {
                // 여는 시점에 지난 실패를 지운다 — 다른 보드를 지우려고 연 창에 앞 실패가
                // 되살아나는 자리를 없앤다.
                setDeleteError(undefined)
                setDeleteOpen(true)
              }}
            >
              <Trash2 aria-hidden="true" />
              {boardLabels.actions.deleteItem}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>

      {/* 이름이 바뀌면 입력 초기값도 새로 잡아야 한다 — props 로 state 를 초기화하는
          컴포넌트는 key 로 재마운트해야 stale 값이 남지 않는다. */}
      <RenameBoardDialog
        key={`${boardId}:${boardName}`}
        open={renameOpen}
        onOpenChange={setRenameOpen}
        projectKey={projectKey}
        boardId={boardId}
        currentName={boardName}
      />

      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={(next: boolean) => {
          setDeleteOpen(next)
          if (!next) setDeleteError(undefined)
        }}
        title={boardLabels.actions.deleteDialogTitle}
        description={boardLabels.actions.deleteDialogDescription(boardName)}
        confirmLabel={boardLabels.actions.deleteConfirm}
        cancelLabel={boardLabels.actions.deleteCancel}
        onConfirm={handleDeleteConfirm}
        confirming={deleteMutation.isPending}
        error={deleteError}
        destructive
      />
    </>
  )
}
