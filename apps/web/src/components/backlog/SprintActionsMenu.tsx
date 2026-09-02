// 스프린트 칸 헤더의 `⋯` 관리 메뉴 — 편집 진입 · 삭제 확인 + 권한 부재 게이팅 (FR-BL-02 D6 FR-3·FR-5)
import type { JSX } from 'react'
import { useState } from 'react'
import { MoreHorizontal, Pencil, Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { backlogLabels, sprintDeleteErrorMessage } from '@/i18n/backlog-labels'
import { useDeleteSprint } from '@/hooks/use-backlog'
import { ApiError } from '@/api/client'
import type { SprintMeta } from '@/api/backlog'
import { EditSprintDialog } from './EditSprintDialog'

/**
 * `⋯` 트리거 클래스 — 헤더의 접기 토글과 **같은 규율**이다.
 *
 * `min-h-11`(44px)은 모바일 터치 타깃 요구(NFR-4)이고 `md` 부터 원래 높이로 돌아간다.
 * 두 아이콘 버튼이 한 줄에 서므로 크기가 갈리면 눈에 띈다.
 */
const TRIGGER_CLASS = 'min-h-11 min-w-11 text-muted-foreground md:min-h-0 md:min-w-0'

/** 실패의 상태 코드를 꺼낸다. 응답 자체가 없었으면(연결 끊김 · 삭제 상한) `null` */
function statusOf(error: unknown): number | null {
  return error instanceof ApiError ? error.status : null
}

/** {@link SprintDeleteConfirm} Props */
interface SprintDeleteConfirmProps {
  /** mutation·무효화 대상 프로젝트 키 */
  readonly projectKey: string
  /** 지울 스프린트 */
  readonly sprint: SprintMeta
  /** 그 스프린트에 담긴 이슈 수 — 확인 문구가 이 값으로 갈린다 (E-5) */
  readonly issueCount: number
  /** 닫힘 요청 — 취소·Esc·오버레이·성공이 모두 이리로 온다 */
  readonly onClose: () => void
}

/**
 * 스프린트 삭제 확인 창 (FR-3 · E-3).
 *
 * ### 왜 별도 컴포넌트인가 — 창의 수명 = 실패의 수명
 * 실패 사유와 mutation 을 이 컴포넌트가 쥐고, 소비자는 **열려 있을 때만 마운트**한다.
 * 창을 닫으면 지난 실패가 함께 사라진다 — 실패를 손으로 지우는 코드를 두면 지우는 자리를
 * 한 군데 빠뜨리는 순간 되살아난다 (`SprintDialogHost` 가 세운 언마운트 관례).
 *
 * ### 성공했을 때만 닫는다
 * `ConfirmDialog` 는 확인 뒤 **스스로 닫지 않는다**(그 프리미티브의 제어 컴포넌트 계약).
 * 실패는 창 안 `error` 로 남고, `confirming` 동안 취소·Esc·오버레이·X 가 전부 잠겨 실패가
 * 갈 곳이 보장된다. 갇히지 않는 근거는 `useDeleteSprint` 의 삭제 상한이다.
 * 목록 갱신은 **invalidate-only** 다 (NFR-3) — 그 훅의 `onSuccess` 가 이미 덮는다.
 */
function SprintDeleteConfirm({
  projectKey,
  sprint,
  issueCount,
  onClose,
}: SprintDeleteConfirmProps): JSX.Element {
  const deleteSprint = useDeleteSprint(projectKey)
  const [error, setError] = useState<string | undefined>(undefined)

  /** 확인 — 성공하면 닫고, 실패하면 사유만 갈아 끼운다 */
  function handleConfirm(): void {
    setError(undefined)
    deleteSprint.mutate(sprint.sprintId, {
      onSuccess: onClose,
      onError: (cause: unknown) => {
        setError(sprintDeleteErrorMessage(statusOf(cause)))
      },
    })
  }

  return (
    <ConfirmDialog
      open
      onOpenChange={(next: boolean) => { if (!next) onClose() }}
      title={backlogLabels.deleteSprint}
      description={backlogLabels.sprintActions.deleteDescription(sprint.name, issueCount)}
      confirmLabel={
        deleteSprint.isPending
          ? backlogLabels.sprintActions.deleting
          : backlogLabels.sprintActions.deleteConfirm
      }
      cancelLabel={backlogLabels.sprintActions.deleteCancel}
      onConfirm={handleConfirm}
      confirming={deleteSprint.isPending}
      error={error}
      destructive
    />
  )
}

/** {@link SprintActionsMenu} Props */
export interface SprintActionsMenuProps {
  /** 소속 프로젝트 키 — mutation·무효화 대상 */
  readonly projectKey: string
  /** 대상 스프린트 */
  readonly sprint: SprintMeta
  /**
   * 화면이 보고 있는 보드 UUID. `?board=` 미지정이면 `undefined` (FR-BD-04).
   *
   * 🛑 **선택 prop 이 아니다.** 편집 다이얼로그의 409 복구가 백로그 캐시를 완전 일치 키로
   * 읽으므로 빠뜨리면 기준값 교체가 무음으로 멈추고 재시도가 409 를 되풀이한다.
   */
  readonly boardId: string | undefined
  /** 이 스프린트에 담긴 이슈 수 — 삭제 확인 문구가 이 값으로 갈린다 (E-5) */
  readonly issueCount: number
  /** 스프린트 관리 권한(CREATE). **fail-closed** — 로딩·에러·미보유는 전부 `false` 다 */
  readonly canManage: boolean
}

/**
 * 스프린트 칸 헤더의 `⋯` 관리 메뉴 — 편집 · 삭제 (FR-1 · FR-3 · J3~J5).
 *
 * ### 왜 헤더에서 분리했나
 * `SprintColumnHeader` 가 200줄(`DEVELOPMENT.md §2.2`)을 넘어서였다 — 그 헤더 자신이
 * `SprintColumn` 에서 갈라져 나온 것과 같은 기준이다. 경계는 「메뉴와 그것이 여는 두 창」.
 *
 * ### 권한이 없으면 **렌더하지 않는다** (FR-5)
 * 비활성으로 두면 「눌러도 되는 것처럼 보이는 것」이 남고, 그건 Jira 동작이 아니다.
 * 두 항목이 같은 권한(`IssuePermission.CREATE`)을 쓰므로 권한이 없으면 메뉴가 통째로 빈다 —
 * 열면 비는 메뉴는 「고장났다」로 읽히므로 트리거까지 내린다(`BoardActionsMenu` 와 같은 판단).
 *
 * 두 창은 **열려 있을 때만 마운트**한다 — 닫으면 언마운트되어 낡은 폼·회차·실패가 남지 않는다.
 * 편집 창은 기준값을 폼 내부 state 에 두므로 `key` 로 대상 교체 시 재마운트를 강제한다.
 *
 * @param props 프로젝트·스프린트·보드·이슈 수·권한
 * @returns `⋯` 메뉴. 권한이 없으면 `null`
 */
export function SprintActionsMenu({
  projectKey,
  sprint,
  boardId,
  issueCount,
  canManage,
}: SprintActionsMenuProps): JSX.Element | null {
  const [editOpen, setEditOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)

  if (!canManage) return null

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className={TRIGGER_CLASS}
            aria-label={backlogLabels.sprintActions.triggerAriaLabel(sprint.name)}
          >
            <MoreHorizontal aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={() => { setEditOpen(true) }}>
            <Pencil aria-hidden="true" />
            {backlogLabels.editSprint}
          </DropdownMenuItem>
          <DropdownMenuItem variant="destructive" onSelect={() => { setDeleteOpen(true) }}>
            <Trash2 aria-hidden="true" />
            {backlogLabels.deleteSprint}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      {editOpen && (
        <EditSprintDialog
          key={sprint.sprintId}
          open
          onOpenChange={(next: boolean) => { setEditOpen(next) }}
          sprint={sprint}
          projectKey={projectKey}
          boardId={boardId}
        />
      )}

      {deleteOpen && (
        <SprintDeleteConfirm
          projectKey={projectKey}
          sprint={sprint}
          issueCount={issueCount}
          onClose={() => { setDeleteOpen(false) }}
        />
      )}
    </>
  )
}
