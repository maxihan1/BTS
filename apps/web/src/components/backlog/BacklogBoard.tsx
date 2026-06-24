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
import { resolveBacklogDropAction } from '@/lib/backlog-drag'
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
   * 관리 권한 여부.
   * false이면 드래그·시작·완료·생성 버튼이 비활성화된다.
   */
  canManage?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — over.data에서 컨텍스트 추출
// ─────────────────────────────────────────────────────────────────────────────

interface DropZoneData {
  context: 'backlog' | 'sprint'
  sprintId: string | null
  orderedKeys?: readonly string[]
  dropIndex?: number
}

function extractDropZone(data: Record<string, unknown> | undefined): DropZoneData | null {
  if (data === undefined) return null
  const context = data['context']
  if (context !== 'backlog' && context !== 'sprint') return null
  return {
    context,
    sprintId: typeof data['sprintId'] === 'string' ? data['sprintId'] : null,
    orderedKeys: Array.isArray(data['orderedKeys']) ? (data['orderedKeys'] as string[]) : undefined,
    dropIndex: typeof data['dropIndex'] === 'number' ? data['dropIndex'] : undefined,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// BacklogBoard 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 보드 루트.
 *
 * - useBacklog로 데이터를 로드하고 BacklogColumn + SprintColumn들을 배치한다.
 * - DndContext + PointerSensor(distance:5)로 드래그를 관리한다.
 * - onDragEnd에서 resolveBacklogDropAction으로 시나리오를 판정해 mutation을 호출한다.
 * - C1 부분실패: assign/unassign 성공 후 rerank 실패 → 경고 토스트.
 * - truncated=true이면 경고 배너를 표시한다.
 */
export function BacklogBoard({ projectKey, canManage = true }: BacklogBoardProps): JSX.Element {
  const [overDroppableId, setOverDroppableId] = useState<string | null>(null)

  const { data: backlogView, isLoading } = useBacklog(projectKey)
  const rerankIssue = useRerankIssue(projectKey)
  const assignToSprint = useAssignToSprint(projectKey)
  const unassignFromSprint = useUnassignFromSprint(projectKey)
  const createSprint = useCreateSprint(projectKey)
  const startSprint = useStartSprint(projectKey)
  const completeSprint = useCompleteSprint(projectKey)

  // PointerSensor: distance 5px 이상 이동해야 드래그 시작 (클릭·링크 보존)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  )

  function handleDragOver(event: DragOverEvent): void {
    setOverDroppableId(event.over ? String(event.over.id) : null)
  }

  function handleDragEnd(event: DragEndEvent): void {
    setOverDroppableId(null)

    const activeData = event.active.data.current as BacklogDragData | undefined
    const overData = event.over?.data.current as Record<string, unknown> | undefined

    if (activeData === undefined || event.over === null) return

    const dropZone = extractDropZone(overData)
    if (dropZone === null) return

    const { issueKey, context: fromContext, sprintId: fromSprintId } = activeData
    const { context: toContext, sprintId: toSprintId, orderedKeys, dropIndex } = dropZone

    // over 드래그 중 제공된 orderedKeys와 dropIndex가 없으면 대상 칸 맨 뒤로 처리
    const targetKeys = orderedKeys ?? []
    const targetDropIndex = dropIndex ?? targetKeys.length

    const action = resolveBacklogDropAction({
      issueKey,
      fromContext,
      fromSprintId,
      toContext,
      toSprintId,
      targetKeys,
      dropIndex: targetDropIndex,
    })

    if (action.kind === 'noop' || action.kind === 'noop-move') return

    // C1: 이동 먼저 → 성공 후 rerank
    if (action.kind === 'assign') {
      assignToSprint.mutate(
        { sprintId: action.sprintId, issueKey },
        {
          onSuccess: () => {
            if (action.rerank === undefined) return
            rerankIssue.mutate(
              { issueKey, body: action.rerank },
              {
                onError: () => {
                  toast.warning(backlogLabels.rerankFailedWarning)
                },
              },
            )
          },
          onError: () => {
            toast.error(backlogLabels.moveFailedError)
          },
        },
      )
      return
    }

    if (action.kind === 'unassign') {
      unassignFromSprint.mutate(
        { sprintId: action.sprintId, issueKey },
        {
          onSuccess: () => {
            if (action.rerank === undefined) return
            rerankIssue.mutate(
              { issueKey, body: action.rerank },
              {
                onError: () => {
                  toast.warning(backlogLabels.rerankFailedWarning)
                },
              },
            )
          },
          onError: () => {
            toast.error(backlogLabels.moveFailedError)
          },
        },
      )
      return
    }

    // rerank-only (S1, S5)
    rerankIssue.mutate(
      { issueKey, body: action.rerank },
      {
        onError: () => {
          toast.error(backlogLabels.moveFailedError)
        },
      },
    )
  }

  function handleCreateSprint(name: string): void {
    createSprint.mutate(
      { projectKey, name },
      {
        onError: () => {
          toast.error(backlogLabels.moveFailedError)
        },
      },
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
      {/* truncated 경고 배너 */}
      {truncated && (
        <div
          role="alert"
          className="rounded-md border border-warning bg-warning/10 px-4 py-2 text-sm text-warning-foreground"
        >
          {backlogLabels.truncatedWarning}
        </div>
      )}

      {/* 스프린트 생성 폼 */}
      <CreateSprintForm
        projectKey={projectKey}
        onSubmit={handleCreateSprint}
        disabled={!canManage || createSprint.isPending}
      />

      {/* 보드 칸들 — 가로 스크롤 */}
      <DndContext
        sensors={sensors}
        onDragOver={handleDragOver}
        onDragEnd={handleDragEnd}
        accessibility={undefined}
      >
        <div className="flex gap-4 overflow-x-auto pb-4">
          {/* 백로그 칸 */}
          <BacklogColumn
            issues={backlog}
            assigneeNames={assigneeNames}
            isOver={overDroppableId === 'backlog'}
          />

          {/* 스프린트 칸들 */}
          {sprints.map(({ sprint, issues }) => (
            <SprintColumn
              key={sprint.sprintId}
              sprint={sprint}
              issues={issues}
              assigneeNames={assigneeNames}
              isOver={overDroppableId === `sprint-${sprint.sprintId}`}
              onStart={
                canManage
                  ? () => {
                      startSprint.mutate(sprint.sprintId, {
                        onError: () => {
                          toast.error(backlogLabels.moveFailedError)
                        },
                      })
                    }
                  : undefined
              }
              onComplete={
                canManage
                  ? () => {
                      completeSprint.mutate(sprint.sprintId, {
                        onError: () => {
                          toast.error(backlogLabels.moveFailedError)
                        },
                      })
                    }
                  : undefined
              }
            />
          ))}
        </div>
      </DndContext>
    </div>
  )
}
