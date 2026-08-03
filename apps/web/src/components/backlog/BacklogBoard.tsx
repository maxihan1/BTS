// 백로그·스프린트 보드 루트 컴포넌트 — DnD 오케스트레이션 + 라이프사이클 (FR-BL-01/02 D6/D7)
import type { JSX } from 'react'
import { DndContext, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import { toast } from 'sonner'
import {
  useBacklog,
  useCreateSprint,
  useStartSprint,
  useCompleteSprint,
} from '@/hooks/use-backlog'
import { BacklogColumn } from './BacklogColumn'
import { SprintColumn } from './SprintColumn'
import { CreateSprintForm } from './CreateSprintForm'
import { cardFirstCollision } from './backlog-collision'
import { useBacklogCreateIssue } from './use-backlog-create-issue'
import { useBacklogDrag } from './use-backlog-drag'
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'
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

  /**
   * 이슈 생성 흐름 — 어느 칸이 열었는지 · 생성 후 배정 · 목록 갱신을 한 곳에 모았다.
   *
   * ★모달은 **화면당 1개**다 (FR-15). 칸마다 두면 `role="dialog"` 가 N개가 되어
   * 조회가 strict mode 로 깨진다 (F2 가 겪은 164발생 함정과 같은 결).
   */
  const createIssue = useBacklogCreateIssue(projectKey)


  const { data: backlogView, isLoading } = useBacklog(projectKey)
  const createSprint = useCreateSprint(projectKey)
  const startSprint = useStartSprint(projectKey)
  const completeSprint = useCompleteSprint(projectKey)

  /** 드래그 처리 — 드롭 판정과 이동/재정렬 mutation 을 함께 쥔다. */
  const drag = useBacklogDrag(projectKey, backlogView, canReorderIssue)

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
  )

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
        onDragOver={drag.handleDragOver}
        onDragEnd={drag.handleDragEnd}
        accessibility={undefined}
      >
        <div className="flex gap-4 overflow-x-auto pb-4">
          <BacklogColumn
            issues={backlog}
            assigneeNames={assigneeNames}
            isOver={drag.overDroppableId === 'backlog'}
            canCreateIssue={canCreateIssue}
            onCreateIssue={createIssue.openForBacklog}
          />
          {sprints.map(({ sprint, issues }) => (
            <SprintColumn
              key={sprint.sprintId}
              projectKey={projectKey}
              sprint={sprint}
              issues={issues}
              assigneeNames={assigneeNames}
              isOver={drag.overDroppableId === `sprint-${sprint.sprintId}`}
              onStart={canManageSprint ? () => startSprint.mutate(sprint.sprintId, {
                onError: () => toast.error(backlogLabels.moveFailedError),
              }) : undefined}
              onComplete={canManageSprint ? () => completeSprint.mutate(sprint.sprintId, {
                onError: () => toast.error(backlogLabels.moveFailedError),
              }) : undefined}
              canCreateIssue={canCreateIssue}
              onCreateIssue={createIssue.openForSprint(sprint.sprintId)}
            />
          ))}
        </div>
      </DndContext>

      {/* 이슈 생성 모달 — 화면당 1개. 어느 칸이 눌렀는지는 `createTarget` 이 쥔다 (FR-15).
          프로젝트는 명시로 넘긴다 — 전역 활성 프로젝트를 경유하면 목록 대조 가드가
          아직 통과하지 못한 순간 다른 프로젝트가 채워진 채로 열린다 (스펙 §8 D-A). */}
      <CreateIssueDialog
        open={createIssue.isOpen}
        // 닫힘은 **가시성만** 끈다 — 대상은 훅이 `onCreated` 에서 읽은 뒤에 비운다.
        onOpenChange={(open) => { if (!open) createIssue.close() }}
        initialProjectKey={projectKey}
        onCreated={createIssue.onCreated}
      />
    </div>
  )
}
