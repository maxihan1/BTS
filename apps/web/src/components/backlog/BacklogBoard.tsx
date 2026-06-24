// 백로그·스프린트 보드 루트 컴포넌트 — DnD 오케스트레이션 + 라이프사이클 (FR-BL-01/02 D6/D7)
import type { JSX } from 'react'
import { useState } from 'react'
import {
  DndContext,
  PointerSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import type { DragEndEvent, DragOverEvent } from '@dnd-kit/core'
import { toast } from 'sonner'
import {
  useBacklog,
  useRerankIssue,
  useAssignToSprint,
  useUnassignFromSprint,
  useCreateSprint,
  useStartSprint,
  useCompleteSprint,
} from '@/hooks/use-backlog'
import {
  resolveBacklogDropAction,
  extractColumnDropZone,
  extractCardDropZone,
} from '@/lib/backlog-drag'
import type { NeighborResult, DropZoneData } from '@/lib/backlog-drag'
import type { BacklogDragData } from './BacklogCard'
import { BacklogColumn } from './BacklogColumn'
import { SprintColumn } from './SprintColumn'
import { CreateSprintForm } from './CreateSprintForm'
import { backlogLabels } from '@/i18n/backlog-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogBoard 컴포넌트 Props */
export interface BacklogBoardProps {
  /** 프로젝트 키 — 백로그 데이터 조회 + 스프린트 생성에 사용 */
  projectKey: string
  /**
   * 스프린트 관리 권한(CREATE).
   * false이면 스프린트 생성·시작·완료 버튼이 비활성화된다.
   */
  canManageSprint?: boolean
  /**
   * 이슈 재정렬·할당·해제 권한(UPDATE).
   * false이면 드래그가 동작하지 않는다(DnD onDragEnd에서 조기 반환).
   */
  canReorderIssue?: boolean
}

// (드롭 존 파싱 헬퍼는 backlog-drag.ts의 순수 함수로 위임)

// ─────────────────────────────────────────────────────────────────────────────
// BacklogBoard 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 보드 루트.
 *
 * - useBacklog로 데이터를 로드하고 BacklogColumn + SprintColumn들을 배치한다.
 * - DndContext + PointerSensor(distance:5)로 드래그를 관리한다.
 * - onDragEnd에서 resolveBacklogDropAction으로 시나리오를 판정해 mutation을 호출한다.
 * - C1: assign/unassign 성공 후 rerank 실패 → 경고 토스트. 이동은 완료됐으므로 에러 토스트 금지.
 * - truncated=true이면 경고 배너를 표시한다.
 */
export function BacklogBoard({
  projectKey,
  canManageSprint = true,
  canReorderIssue = true,
}: BacklogBoardProps): JSX.Element {
  const [overDroppableId, setOverDroppableId] = useState<string | null>(null)

  const { data: backlogView, isLoading } = useBacklog(projectKey)
  const rerankIssue = useRerankIssue(projectKey)
  const assignToSprint = useAssignToSprint(projectKey)
  const unassignFromSprint = useUnassignFromSprint(projectKey)
  const createSprint = useCreateSprint(projectKey)
  const startSprint = useStartSprint(projectKey)
  const completeSprint = useCompleteSprint(projectKey)

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  )

  /** C1: 이동 성공 후 rerank를 실행한다. rerank 실패 시 경고 토스트. */
  function rerankAfterMove(issueKey: string, rerank: NeighborResult | undefined): void {
    if (rerank === undefined) return
    rerankIssue.mutate(
      { issueKey, body: rerank },
      { onError: () => toast.warning(backlogLabels.rerankFailedWarning) },
    )
  }

  function handleDragOver(event: DragOverEvent): void {
    setOverDroppableId(event.over ? String(event.over.id) : null)
  }

  function handleDragEnd(event: DragEndEvent): void {
    setOverDroppableId(null)

    // UPDATE 권한 없으면 드래그 결과를 무시한다
    if (!canReorderIssue) return

    const activeData = event.active.data.current as BacklogDragData | undefined
    const overData = event.over?.data.current as Record<string, unknown> | undefined

    if (activeData === undefined || event.over === null) return

    const { issueKey, context: fromContext, sprintId: fromSprintId } = activeData

    // 칸 droppable over 경로 (orderedKeys가 data에 포함된 경우)
    let dropZone: DropZoneData | null = extractColumnDropZone(overData)

    if (dropZone === null && backlogView !== undefined) {
      // 카드 droppable over 경로: 대상 칸의 orderedKeys를 board 데이터에서 콜백으로 조회한다
      dropZone = extractCardDropZone(overData, (ctx, sid) => {
        if (ctx === 'backlog') return backlogView.backlog.map((i) => i.key)
        const entry = backlogView.sprints.find((s) => s.sprint.sprintId === sid)
        return entry !== undefined ? entry.issues.map((i) => i.key) : []
      })
    }

    if (dropZone === null) return

    const action = resolveBacklogDropAction({
      issueKey,
      fromContext,
      fromSprintId,
      toContext: dropZone.context,
      toSprintId: dropZone.sprintId,
      targetKeys: dropZone.orderedKeys,
      dropIndex: dropZone.dropIndex,
    })

    if (action.kind === 'noop' || action.kind === 'noop-move') return

    if (action.kind === 'assign') {
      assignToSprint.mutate(
        { sprintId: action.sprintId, issueKey },
        {
          onSuccess: () => rerankAfterMove(issueKey, action.rerank),
          onError: () => toast.error(backlogLabels.moveFailedError),
        },
      )
      return
    }

    if (action.kind === 'unassign') {
      unassignFromSprint.mutate(
        { sprintId: action.sprintId, issueKey },
        {
          onSuccess: () => rerankAfterMove(issueKey, action.rerank),
          onError: () => toast.error(backlogLabels.moveFailedError),
        },
      )
      return
    }

    // rerank-only (S1, S5)
    rerankIssue.mutate(
      { issueKey, body: action.rerank },
      { onError: () => toast.error(backlogLabels.moveFailedError) },
    )
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16" aria-label="로딩 중">
        <span className="text-sm text-muted-foreground">로딩 중...</span>
      </div>
    )
  }

  if (backlogView === undefined) return <div />

  const { backlog, sprints, truncated } = backlogView
  const assigneeNames = new Map<string, string>()

  return (
    <div className="flex flex-col gap-4">
      {truncated && (
        <div
          role="alert"
          className="rounded-md border border-warning bg-warning/10 px-4 py-2 text-sm text-warning-foreground"
        >
          {backlogLabels.truncatedWarning}
        </div>
      )}

      <CreateSprintForm
        projectKey={projectKey}
        onSubmit={(name) => {
          createSprint.mutate(
            { projectKey, name },
            { onError: () => toast.error(backlogLabels.moveFailedError) },
          )
        }}
        disabled={!canManageSprint || createSprint.isPending}
      />

      <DndContext
        sensors={sensors}
        onDragOver={handleDragOver}
        onDragEnd={handleDragEnd}
        accessibility={undefined}
      >
        <div className="flex gap-4 overflow-x-auto pb-4">
          <BacklogColumn
            issues={backlog}
            assigneeNames={assigneeNames}
            isOver={overDroppableId === 'backlog'}
          />
          {sprints.map(({ sprint, issues }) => (
            <SprintColumn
              key={sprint.sprintId}
              sprint={sprint}
              issues={issues}
              assigneeNames={assigneeNames}
              isOver={overDroppableId === `sprint-${sprint.sprintId}`}
              onStart={canManageSprint ? () => startSprint.mutate(sprint.sprintId, {
                onError: () => toast.error(backlogLabels.moveFailedError),
              }) : undefined}
              onComplete={canManageSprint ? () => completeSprint.mutate(sprint.sprintId, {
                onError: () => toast.error(backlogLabels.moveFailedError),
              }) : undefined}
            />
          ))}
        </div>
      </DndContext>
    </div>
  )
}
