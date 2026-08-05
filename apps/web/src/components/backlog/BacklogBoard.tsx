// 백로그·스프린트 보드 루트 컴포넌트 — DnD 오케스트레이션 + 라이프사이클 (FR-BL-01/02 D6/D7)
import type { JSX } from 'react'
import { useMemo, useState } from 'react'
import { DndContext, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import { toast } from 'sonner'
import { useBacklog, useCreateSprint } from '@/hooks/use-backlog'
import { useUsersByIdsChunked } from '@/hooks/use-users'
import { collectAssigneeIds, buildAssigneeNameMap } from './backlog-assignee-names'
import { BacklogColumn } from './BacklogColumn'
import { SprintColumn } from './SprintColumn'
import { CreateSprintForm } from './CreateSprintForm'
import { StartSprintDialog } from './StartSprintDialog'
import { CompleteSprintDialog } from './CompleteSprintDialog'
import { cardFirstCollision } from './backlog-collision'
import { useBacklogCreateIssue } from './use-backlog-create-issue'
import { useBacklogDrag } from './use-backlog-drag'
import { CreateIssueDialog } from '@/components/issue/CreateIssueDialog'
import { Button } from '@/components/ui/button'
import { backlogLabels } from '@/i18n/backlog-labels'
import {
  backlogScreenReaderInstructions,
  buildBacklogAnnouncements,
} from '@/lib/backlog-announcements'
import { backlogKeyboardSensorOptions } from '@/lib/backlog-keyboard-coordinates'
import type { BacklogView, SprintWithIssues } from '@/api/backlog'

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

/** 열려 있는 스프린트 다이얼로그 — 종류와 대상 (F15 FR-3 · FR-5) */
interface SprintDialogTarget {
  /** 시작 다이얼로그인지 완료 다이얼로그인지 */
  readonly kind: 'start' | 'complete'
  /** 대상 스프린트 UUID */
  readonly sprintId: string
}

/**
 * 데이터가 아직 없을 때 공지 빌더에 넘길 빈 뷰.
 *
 * `useMemo` 는 조기 반환보다 **위**에 있어야 하므로(렌더마다 훅 개수가 같아야 한다)
 * `undefined` 를 대신할 값이 필요하다. 이 값으로 만든 공지는 로딩·에러 화면에서
 * `DndContext` 자체가 렌더되지 않아 소비되지 않는다.
 */
const EMPTY_BACKLOG_VIEW: BacklogView = { backlog: [], sprints: [], truncated: false }

// ─────────────────────────────────────────────────────────────────────────────
// BacklogBoard 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 보드 루트.
 *
 * - useBacklog로 데이터를 로드하고 SprintColumn들 + BacklogColumn을 **세로로** 쌓는다 (F15 FR-1).
 * - DndContext + PointerSensor(distance:5)로 드래그를 관리한다.
 * - onDragEnd에서 resolveBacklogDropAction으로 시나리오를 판정해 mutation을 호출한다.
 * - C1: assign/unassign 성공 후 rerank 실패 → 경고 토스트. 이동은 완료됐으므로 에러 토스트 금지.
 * - truncated=true이면 경고 배너를 표시한다.
 * - 조회 실패 시 안내와 재시도 버튼을 그린다 (FR-UX-13 F5 G2).
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

  /**
   * 열려 있는 스프린트 다이얼로그. **화면당 1개**다 — 칸마다 두면 `role="dialog"` 가
   * N개가 되어 조회가 strict mode 로 깨진다 (`CreateIssueDialog` 가 확립한 FR-15 원칙).
   */
  const [sprintDialog, setSprintDialog] = useState<SprintDialogTarget | null>(null)

  const { data: backlogView, isLoading, isError, isFetching, refetch } = useBacklog(projectKey)

  // 담당자 이름 — 화면에 등장하는 id 만 모아 50개씩 나눠 전량 조회한다 (스펙 M1).
  // ★조기 반환(`isLoading`)보다 **위**에 있어야 한다. 아래로 내리면 렌더마다 훅 개수가
  //   달라져 React 가 즉사한다. 그래서 두 순수 함수가 `undefined` 를 받아낸다.
  const assigneeIds = useMemo(() => collectAssigneeIds(backlogView), [backlogView])
  const { data: assigneeUsers } = useUsersByIdsChunked(assigneeIds)
  const assigneeNames = useMemo(
    () => buildAssigneeNameMap(backlogView, assigneeUsers),
    [backlogView, assigneeUsers],
  )

  const createSprint = useCreateSprint(projectKey)

  /** 드래그 처리 — 드롭 판정과 이동/재정렬 mutation 을 함께 쥔다. */
  const drag = useBacklogDrag(projectKey, backlogView, canReorderIssue)

  // 한국어 드래그 공지 (FR-9). ★`canReorderIssue` 를 반드시 넘긴다 — 안 넘기면 권한이
  // 없어 mutation 이 0건인 사용자에게 「옮겼습니다」를 읽어 주는 거짓말이 된다 (FR-17).
  // ★`drag.wasLastDropZeroMove` 도 같은 이유다 — 훅과 lib 이 각각 맞아도 이 통로가 끊기면
  //   집자마자 놓아 mutation 이 0건인데 「순서를 변경했습니다」를 읽는다 (T12).
  const announcements = useMemo(
    () =>
      buildBacklogAnnouncements(
        backlogView ?? EMPTY_BACKLOG_VIEW,
        canReorderIssue,
        drag.wasLastDropZeroMove,
      ),
    [backlogView, canReorderIssue, drag.wasLastDropZeroMove],
  )

  const sensors = useSensors(
    // `distance: 5` 는 **그대로 둔다** — 카드 안 `Link` 클릭 보존이 이 값에 걸려 있다 (FR-8).
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    // 좌표 계산기 + 활성화 키 제한을 함께 넘긴다 (FR-15 · FR-16). 기본값은 방향키 1회에
    // 25px 이라 세로 스택에서 옆 섹션까지 30회가 넘고, `start` 의 Enter 가 카드 안 이슈
    // 링크를 삼켜 키보드 사용자가 이슈로 갈 수 없다.
    useSensor(KeyboardSensor, backlogKeyboardSensorOptions),
  )

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16" aria-label="로딩 중">
        <span className="text-sm text-muted-foreground">로딩 중...</span>
      </div>
    )
  }

  // 조회 실패 — 재시도 수단이 없으면 사용자는 새로고침 말고 탈출구가 없다 (ActiveProjectGate 선례).
  //
  // 순서는 `isLoading` **다음**으로 둔다. 다만 「앞에 두면 재조회 중에도 에러 화면이 깜빡인다」는
  // 근거는 **거짓이다** — `isLoading = isPending && isFetching` 이고 `isPending`·`isError` 는
  // 같은 `status` 열거의 배타 값이라 **동시에 참이 될 수 없다**
  // (@tanstack/query-core@5.100.11 `build/modern/queryObserver.js:308-310`).
  // 즉 순서를 바꿔도 현재 관측 가능한 차이가 없고, 그래서 이 순서만을 봉인하는 테스트도
  // 존재할 수 없다(도달 불가능한 픽스처를 지어내야 하므로). 순서를 고정하는 것은
  // 「로딩이 먼저」라는 의도를 코드에 남기기 위함이 전부다.
  // 도달 가능한 로딩 상태 자체는 `BacklogBoard.test.tsx` 의 T4-3 이 잰다 (FR-8·E8).
  if (isError) {
    return (
      <div role="alert" className="flex flex-col items-start gap-3 p-8 text-destructive">
        <p>{backlogLabels.loadFailed}</p>
        {/* 재조회 중에는 버튼이 스스로 상태를 말한다 (design review D3).
            `isLoading` 은 최초 1회만 true 라 재조회를 못 잡는다 — `isFetching` 이어야 한다.
            비활성화가 연타로 인한 중복 요청을 구조적으로 막는다. */}
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={isFetching}
          onClick={() => { void refetch() }}
        >
          {isFetching ? backlogLabels.retrying : backlogLabels.retry}
        </Button>
      </div>
    )
  }

  if (backlogView === undefined) return <div />

  const { backlog, sprints, truncated } = backlogView

  /** 다이얼로그 닫힘 — 대상을 비워 **언마운트**한다. 낡은 폼·회차 state 가 남지 않는다 */
  function handleSprintDialogOpenChange(next: boolean): void {
    if (!next) setSprintDialog(null)
  }

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
        accessibility={{ announcements, screenReaderInstructions: backlogScreenReaderInstructions }}
      >
        {/* 세로 스택 (F15 FR-1) — 스프린트가 먼저, 백로그가 맨 마지막이다 (Jira 와 같은 순서).
            ★클라이언트 정렬을 넣지 않는다 — 백엔드 `sprintComparator` 가 ACTIVE → PLANNED →
            COMPLETED, 그다음 startDate 로 이미 정렬해 내려주므로 규칙이 두 벌로 갈라진다. */}
        <div className="flex flex-col gap-3">
          {sprints.map(({ sprint, issues }) => (
            <SprintColumn
              key={sprint.sprintId}
              projectKey={projectKey}
              sprint={sprint}
              issues={issues}
              assigneeNames={assigneeNames}
              isOver={drag.overDroppableId === `sprint-${sprint.sprintId}`}
              // 버튼은 이제 곧장 mutation 을 쏘지 않고 다이얼로그를 연다 (FR-3 · FR-5).
              // 버튼 **이름**(`스프린트 시작`·`스프린트 완료`)은 그대로다 (FR-10 즉사 계약).
              onStart={canManageSprint
                ? () => { setSprintDialog({ kind: 'start', sprintId: sprint.sprintId }) }
                : undefined}
              onComplete={canManageSprint
                ? () => { setSprintDialog({ kind: 'complete', sprintId: sprint.sprintId }) }
                : undefined}
              canCreateIssue={canCreateIssue}
              onCreateIssue={createIssue.openForSprint(sprint.sprintId)}
            />
          ))}
          <BacklogColumn
            projectKey={projectKey}
            issues={backlog}
            assigneeNames={assigneeNames}
            isOver={drag.overDroppableId === 'backlog'}
            canCreateIssue={canCreateIssue}
            onCreateIssue={createIssue.openForBacklog}
          />
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

      <SprintDialogHost
        target={sprintDialog}
        projectKey={projectKey}
        sprints={sprints}
        truncated={truncated}
        canReorderIssue={canReorderIssue}
        onOpenChange={handleSprintDialogOpenChange}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SprintDialogHost — 시작/완료 다이얼로그 마운트 지점
// ─────────────────────────────────────────────────────────────────────────────

/** {@link SprintDialogHost} Props */
interface SprintDialogHostProps {
  /** 열려 있는 다이얼로그. `null` 이면 아무것도 마운트하지 않는다 */
  readonly target: SprintDialogTarget | null
  /** mutation·invalidate 대상 프로젝트 키. 전역 활성 프로젝트를 경유하지 않는다 */
  readonly projectKey: string
  /** 현재 뷰의 스프린트 전량 — 대상 조회와 이관 후보 목록에 함께 쓴다 */
  readonly sprints: readonly SprintWithIssues[]
  /** 백로그 응답의 `truncated`. 완료 제출 차단 조건이다 (FR-13 · E15) */
  readonly truncated: boolean
  /** 이슈 재정렬·할당·해제 권한(UPDATE) (FR-12) */
  readonly canReorderIssue: boolean
  /** 닫힘 요청 */
  readonly onOpenChange: (open: boolean) => void
}

/**
 * 스프린트 시작/완료 다이얼로그를 **화면당 1개씩만** 마운트한다 (FR-15 원칙 승계).
 *
 * ### 왜 대상이 없을 때 아예 마운트하지 않나
 * 완료 다이얼로그는 `GET /api/v1/workflows` 를 부른다(NFR-2). 항상 마운트하면 백로그를
 * 여는 **모든** 사용자에게 요청이 1건 늘어 NFR-1(요청 수 무증가)을 깬다.
 *
 * ### 왜 `key` 를 주나
 * 두 다이얼로그 모두 기준값·회차 상태를 내부 state 에 쥔다(각 파일 KDoc 의 명시 요구).
 * 대상이 바뀌어도 재마운트되지 않으면 낡은 값이 그대로 남는다.
 *
 * @param props 열린 다이얼로그와 대상 조회에 필요한 뷰 조각
 * @returns 열려 있는 다이얼로그 1개. 없으면 `null`
 */
function SprintDialogHost({
  target,
  projectKey,
  sprints,
  truncated,
  canReorderIssue,
  onOpenChange,
}: SprintDialogHostProps): JSX.Element | null {
  if (target === null) return null

  // 완료 다이얼로그가 이슈 전량을 필요로 하므로 `SprintWithIssues` 를 통째로 넘긴다 —
  // `SprintMeta` 만으로는 미완료 판정(FR-7)을 할 수 없다.
  const entry = sprints.find((item) => item.sprint.sprintId === target.sprintId)
  if (entry === undefined) return null

  if (target.kind === 'start') {
    return (
      <StartSprintDialog
        key={entry.sprint.sprintId}
        open
        onOpenChange={onOpenChange}
        projectKey={projectKey}
        sprint={entry.sprint}
      />
    )
  }

  return (
    <CompleteSprintDialog
      key={entry.sprint.sprintId}
      open
      onOpenChange={onOpenChange}
      projectKey={projectKey}
      sprint={entry}
      allSprints={sprints.map((item) => item.sprint)}
      truncated={truncated}
      // 이관은 `POST /{id}/issues`·`DELETE` 둘 다 UPDATE 인데 `complete` 만 CREATE 다.
      // CREATE 만 가진 사용자는 이관이 전건 403 이므로 그 UI 를 게이팅한다 (FR-12).
      canReorderIssue={canReorderIssue}
    />
  )
}
