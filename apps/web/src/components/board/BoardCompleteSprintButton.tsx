// 스크럼 보드 툴바의 「스프린트 완료」 — 백로그의 완료 다이얼로그를 그대로 연다 (JB-B-3)
import type { JSX } from 'react'
import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { CompleteSprintDialog } from '@/components/backlog/CompleteSprintDialog'
import { useBacklog } from '@/hooks/use-backlog'
import { backlogLabels } from '@/i18n/backlog-labels'

/** BoardCompleteSprintButton props */
export interface BoardCompleteSprintButtonProps {
  readonly projectKey: string
  readonly boardId: string
  /** 보드의 활성 스프린트 id (`BoardDetail.activeSprint.sprintId`) */
  readonly sprintId: string
  /** 프로젝트 CREATE 권한 — 백로그 헤더의 완료 버튼과 같은 게이팅. false 면 버튼이 없다 */
  readonly canManageSprint: boolean
  /** 프로젝트 UPDATE 권한 — 미완료 이슈 이관 UI 게이팅 (`CompleteSprintDialog` FR-12) */
  readonly canReorderIssue: boolean
}

/**
 * 보드에서 스프린트를 끝낸다 — 백로그로 옮겨 가지 않아도 된다.
 *
 * 다이얼로그는 스프린트의 **이슈 전량**(미완료 판정)과 이관 대상 스프린트 목록이 필요한데, 보드 응답에는
 * 컬럼에 매핑된 카드만 있다. 그래서 열 때만 백로그 뷰를 읽는다 — 버튼만 있을 땐 요청이 늘지 않는다.
 * 완료 뒤 무효화(`useCompleteSprint`)가 보드 캐시까지 덮으므로 보드가 빈 스프린트 상태로 바뀐다.
 */
export function BoardCompleteSprintButton({
  projectKey,
  boardId,
  sprintId,
  canManageSprint,
  canReorderIssue,
}: BoardCompleteSprintButtonProps): JSX.Element | null {
  const [open, setOpen] = useState(false)

  if (!canManageSprint) return null

  return (
    <>
      <Button type="button" size="sm" onClick={() => setOpen(true)}>
        {backlogLabels.completeSprint}
      </Button>
      {open && (
        <CompleteSprintDialogLoader
          projectKey={projectKey}
          boardId={boardId}
          sprintId={sprintId}
          canReorderIssue={canReorderIssue}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  )
}

/** 열렸을 때만 마운트 — 백로그 뷰를 읽어 그 스프린트의 완료 다이얼로그를 띄운다 */
function CompleteSprintDialogLoader({
  projectKey,
  boardId,
  sprintId,
  canReorderIssue,
  onClose,
}: Omit<BoardCompleteSprintButtonProps, 'canManageSprint'> & { readonly onClose: () => void }): JSX.Element | null {
  const { data: view } = useBacklog(projectKey, boardId)
  const entry = view?.sprints.find((item) => item.sprint.sprintId === sprintId)
  const missing = view !== undefined && entry === undefined

  // 그 사이 스프린트가 사라졌으면(다른 사람이 완료) 닫는다 — 안 닫으면 버튼이 다시 눌리지 않는다
  useEffect(() => {
    if (missing) onClose()
  }, [missing, onClose])

  // 읽는 중이면 아직 그릴 것이 없다
  if (view === undefined || entry === undefined) return null

  return (
    <CompleteSprintDialog
      open
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
      projectKey={projectKey}
      boardId={boardId}
      sprint={entry}
      allSprints={view.sprints.map((item) => item.sprint)}
      truncated={view.truncated}
      canReorderIssue={canReorderIssue}
    />
  )
}
