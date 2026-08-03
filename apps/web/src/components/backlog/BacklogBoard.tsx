// 백로그·스프린트 보드 루트 컴포넌트 — DnD 오케스트레이션 + 라이프사이클 (FR-BL-01/02 D6/D7)
import type { JSX } from 'react'
import { useState, useCallback, useRef } from 'react'
import {
  DndContext,
  PointerSensor,
  pointerWithin,
  rectIntersection,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import type { CollisionDetection, DragEndEvent, DragOverEvent } from '@dnd-kit/core'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import {
  backlogKeys,
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
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'
import { backlogLabels } from '@/i18n/backlog-labels'
import { issueCreateStrings } from '@/i18n/ko'

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
  /**
   * 이슈 생성 권한(CREATE). **fail-closed** — 로딩·에러·미보유는 전부 false 다 (FR-UX-09 F3 FR-6).
   *
   * `canManageSprint` 와 출처는 같지만(`permissions.CREATE`) **이름을 분리한다** —
   * 「스프린트 관리」와 「이슈 생성」은 다른 행위이고, 한쪽 권한이 갈라지는 날
   * 같은 prop 을 쓰고 있으면 두 화면이 한꺼번에 잘못된다.
   */
  canCreateIssue?: boolean
}

// (드롭 존 파싱 헬퍼는 backlog-drag.ts의 순수 함수로 위임)

// ─────────────────────────────────────────────────────────────────────────────
// 커스텀 충돌 감지 — 카드 droppable 우선
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드 droppable(type:'card')을 칸 droppable보다 우선하는 충돌 감지 전략.
 *
 * 1. 카드 droppable만 포함한 컨테이너 목록으로 pointerWithin을 먼저 시도한다.
 *    pointerWithin이 결과를 반환하면 (포인터가 카드 위에 있음) 그것을 반환한다.
 * 2. 없으면 칸 droppable만으로 pointerWithin을 시도한다.
 * 3. 여전히 없으면 rectIntersection 폴백.
 *
 * droppableContainers를 카드 전용으로 필터링해 우선순위를 보장한다.
 */
const cardFirstCollision: CollisionDetection = (args) => {
  // 카드 droppable만 추출 (data.current.type === 'card')
  const cardContainers = args.droppableContainers.filter(
    (c) => (c.data.current as Record<string, unknown> | undefined)?.['type'] === 'card',
  )

  // 카드 droppable 대상 pointerWithin
  if (cardContainers.length > 0) {
    const cardCollisions = pointerWithin({ ...args, droppableContainers: cardContainers })
    if (cardCollisions.length > 0) return cardCollisions
  }

  // 칸 droppable 대상 pointerWithin (카드 제외)
  const columnContainers = args.droppableContainers.filter(
    (c) => (c.data.current as Record<string, unknown> | undefined)?.['type'] !== 'card',
  )
  const columnCollisions = pointerWithin({ ...args, droppableContainers: columnContainers })
  if (columnCollisions.length > 0) return columnCollisions

  return rectIntersection(args)
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
 * - C1: assign/unassign 성공 후 rerank 실패 → 경고 토스트. 이동은 완료됐으므로 에러 토스트 금지.
 * - truncated=true이면 경고 배너를 표시한다.
 */
export function BacklogBoard({
  projectKey,
  canManageSprint = true,
  canReorderIssue = true,
  canCreateIssue = false,
}: BacklogBoardProps): JSX.Element {
  const queryClient = useQueryClient()
  const [overDroppableId, setOverDroppableId] = useState<string | null>(null)

  /**
   * 이슈 생성 모달의 대상 — `null` 이면 닫힘, `'backlog'` 면 백로그 칸,
   * 그 외에는 그 스프린트 id 다 (FR-15).
   *
   * ★모달은 **화면당 1개**다. 칸마다 두면 `role="dialog"` 가 N개가 되어
   * 조회가 strict mode 로 깨진다 (F2 가 겪은 164발생 함정과 같은 결).
   */
  const [createTarget, setCreateTarget] = useState<string | null>(null)

  const openCreateForBacklog = useCallback(() => { setCreateTarget('backlog') }, [])
  /**
   * 스프린트 칸별 열기 콜백 캐시.
   *
   * 칸이 `memo` 라 매 렌더 새 함수를 주면 재렌더 스킵이 무력화된다 (NFR-4).
   * 스프린트 수만큼 콜백이 필요하므로 id 를 키로 한 번 만든 것을 재사용한다.
   */
  const openCreateForSprintRef = useRef(new Map<string, () => void>())
  const openCreateForSprint = useCallback((sprintId: string): (() => void) => {
    const cache = openCreateForSprintRef.current
    const existing = cache.get(sprintId)
    if (existing !== undefined) return existing
    const fn = (): void => { setCreateTarget(sprintId) }
    cache.set(sprintId, fn)
    return fn
  }, [])

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

  /**
   * 생성 성공 직후 처리 — 스프린트 칸에서 열었으면 그 스프린트에 배정한다 (FR-4).
   *
   * ### 왜 2회 호출인가
   * `POST /issues` 계약에 `sprintId` 가 없다(2026-08-03 실측). 그래서 생성 후
   * 기존 배정 API 를 한 번 더 부른다 (ADR D-2). 백엔드 확장은 별도 FR 후보다.
   *
   * ### 🛑 2차 실패를 1차 실패처럼 다루지 않는다
   * 여기서 실패해도 **이슈는 온전히 만들어졌다.** 빨간 실패 토스트를 띄우면
   * 「안 만들어졌다」로 읽혀 사용자가 다시 만들고 **중복 이슈**가 생긴다.
   * 경고 톤 + 이슈 키 + 「백로그에서 확인」으로 낸다 (FR-5, ADR D-2).
   * 선례 — `backlogLabels.rerankFailedWarning`(이동은 됐고 순서만 실패)이 같은 형태다.
   */
  function handleIssueCreated(issueKey: string): void {
    const target = createTarget
    setCreateTarget(null)

    // 백로그 칸에서 열었으면 배정할 것이 없다 — 스프린트 미지정이 곧 백로그다.
    //
    // ★그러나 **목록 갱신은 필요하다** (FR-9). 스프린트 경로는 `useAssignToSprint` 의
    // 성공 콜백이 무효화를 걸어주지만, 백로그 경로는 아무도 걸지 않아 만든 이슈가
    // 화면에 나타나지 않는다 — E2E S1 이 실측으로 잡은 결함이다.
    if (target === null || target === 'backlog') {
      void queryClient.invalidateQueries({ queryKey: backlogKeys.detail(projectKey) })
      return
    }

    assignToSprint.mutate(
      { sprintId: target, issueKey },
      { onError: () => toast.warning(issueCreateStrings.sprintAssignFailed(issueKey)) },
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
          className="rounded-md border border-warning bg-warning/10 px-4 py-2 text-sm"
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
        collisionDetection={cardFirstCollision}
        onDragOver={handleDragOver}
        onDragEnd={handleDragEnd}
        accessibility={undefined}
      >
        <div className="flex gap-4 overflow-x-auto pb-4">
          <BacklogColumn
            issues={backlog}
            assigneeNames={assigneeNames}
            isOver={overDroppableId === 'backlog'}
            canCreateIssue={canCreateIssue}
            onCreateIssue={openCreateForBacklog}
          />
          {sprints.map(({ sprint, issues }) => (
            <SprintColumn
              key={sprint.sprintId}
              projectKey={projectKey}
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
              canCreateIssue={canCreateIssue}
              onCreateIssue={openCreateForSprint(sprint.sprintId)}
            />
          ))}
        </div>
      </DndContext>

      {/* 이슈 생성 모달 — 화면당 1개. 어느 칸이 눌렀는지는 `createTarget` 이 쥔다 (FR-15).
          프로젝트는 명시로 넘긴다 — 전역 활성 프로젝트를 경유하면 목록 대조 가드가
          아직 통과하지 못한 순간 다른 프로젝트가 채워진 채로 열린다 (스펙 §8 D-A). */}
      <CreateIssueDialog
        open={createTarget !== null}
        onOpenChange={(open) => { if (!open) setCreateTarget(null) }}
        initialProjectKey={projectKey}
        onCreated={handleIssueCreated}
      />
    </div>
  )
}
