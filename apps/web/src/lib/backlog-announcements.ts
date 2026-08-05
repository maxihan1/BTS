// 백로그 드래그 앤 드롭을 한국어로 읽어 주는 공지 빌더 (FR-UX-13 F15 · FR-9 · FR-17)
import type { Active, Announcements, Over, ScreenReaderInstructions } from '@dnd-kit/core'
import type { BacklogView } from '@/api/backlog'
import type { BacklogDragData } from '@/components/backlog/BacklogCard'
import { backlogLabels } from '@/i18n/backlog-labels'
import { resolveBacklogDropAction, resolveOverToDropZone } from './backlog-drag'
import type { BacklogDropAction } from './backlog-drag'

const announce = backlogLabels.announce

// ─────────────────────────────────────────────────────────────────────────────
// 이 모듈이 하지 않는 두 가지
//
// ① **조사(助詞) 계산.** 문구가 「{가변} + 고정 명사」 꼴이라 가변 부분 바로 뒤에 조사가
//    붙지 않는다. 조사가 붙는 「스프린트」·「백로그」는 둘 다 종성이 없어 언제나 `로` 다
//    (`스프린트로`·`백로그로`). 받침 판별이 **원리적으로** 불필요하므로
//    `KanbanBoard.tsx` 의 비-export `josaEuro` 를 공용 모듈로 끌어내지 않는다 —
//    이 PR 과 무관한 파일을 건드리지 않기 위함이다 (FR-9).
// ② **dnd-kit 런타임 참조.** 가져오는 것은 타입뿐이라 DOM 없이 단위 테스트가 된다.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 입력 읽기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 드래그 카드 id 에서 이슈 키만 떼어낸다.
 *
 * `BacklogCard` 의 draggable id 는 `` `${context}:${issue.key}` `` 라, 그대로 읽으면
 * 스크린리더가 `sprint:ATLAS-4` 같은 **내부 id** 를 소리 내 읽는다 (T-KB-1).
 */
function issueKeyOf(activeId: string | number): string {
  const id = String(activeId)
  const separator = id.indexOf(':')
  return separator === -1 ? id : id.slice(separator + 1)
}

/**
 * 드래그 중인 카드의 출발 정보를 읽는다. 형태가 아니면 null.
 *
 * `use-backlog-drag.ts` 와 **같은 출처**(`active.data`)를 쓴다 — 출발 정보를 뷰에서 다시
 * 찾아 만들면 공지와 실제 mutation 이 어긋난다.
 */
function readDragOrigin(raw: unknown): BacklogDragData | null {
  if (raw === null || typeof raw !== 'object') return null
  const data = raw as Record<string, unknown>
  const issueKey = data['issueKey']
  const context = data['context']
  if (typeof issueKey !== 'string') return null
  if (context !== 'backlog' && context !== 'sprint') return null
  return {
    issueKey,
    context,
    sprintId: typeof data['sprintId'] === 'string' ? data['sprintId'] : null,
  }
}

/** 판정 결과 + 출발 정보 — 같은 칸 재정렬은 「어느 칸인가」를 출발에서만 알 수 있다 */
interface DropJudgement {
  origin: BacklogDragData
  action: BacklogDropAction
}

/**
 * 드래그 상태를 실제 mutation 과 **같은 경로**로 판정한다.
 *
 * 입력 구성(`resolveOverToDropZone`)까지 공용 함수를 쓴다. 판정 함수만 같고 입력을
 * 복제하면 어긋남이 입력에서 난다 (스펙 §리뷰 반영 C-5).
 *
 * @param isZeroMove 이동량 0 여부. dnd-kit 의 `Announcements` 는 `{active, over}` 만 주고
 *   **delta 가 없어서**(`dist/components/Accessibility/types.d.ts:10`) 밖에서 받아야 한다
 */
function judgeDrop(
  view: BacklogView,
  active: Active,
  over: Over | null,
  isZeroMove: boolean,
): DropJudgement | null {
  const origin = readDragOrigin(active.data.current)
  if (origin === null) return null

  const zone = resolveOverToDropZone(view, over, isZeroMove)
  if (zone === null) return null

  const action = resolveBacklogDropAction({
    issueKey: origin.issueKey,
    fromContext: origin.context,
    fromSprintId: origin.sprintId,
    toContext: zone.context,
    toSprintId: zone.sprintId,
    targetKeys: zone.orderedKeys,
    dropIndex: zone.dropIndex,
  })
  return { origin, action }
}

/**
 * 스프린트 이름을 뷰에서 찾는다. 못 찾으면 id 를 그대로 돌려준다.
 *
 * 화면의 스프린트 칸은 이 뷰로 그려지므로 못 찾는 것은 드래그 도중 뷰가 갱신된 경우뿐이다.
 * 그때도 mutation 은 그대로 나가므로 「이동할 수 없다」로 바꾸면 화면과 어긋난다 —
 * 이름을 잃어도 **이동 사실은 알린다** (`KanbanBoard.tsx:59` findColumnName 과 같은 관례).
 */
function sprintNameOf(view: BacklogView, sprintId: string): string {
  return view.sprints.find((entry) => entry.sprint.sprintId === sprintId)?.sprint.name ?? sprintId
}

// ─────────────────────────────────────────────────────────────────────────────
// 문구 만들기
// ─────────────────────────────────────────────────────────────────────────────

/** 드래그가 올라가 있는 대상을 예고형으로 읽는다 — 3갈래(대상 있음 · 대상 없음 · 판정 noop) */
function describeOver(view: BacklogView, active: Active, over: Over | null): string {
  // 이동 0 판정은 **드롭에만** 건다. 집은 직후에도 이동량은 0인데, 그때 「이동할 수 없다」를
  // 읽으면 아직 아무것도 정하지 않은 사용자에게 갈 곳이 없다고 겁을 주게 된다.
  const judged = judgeDrop(view, active, over, false)
  if (judged === null) return announce.outOfDropZone

  const { origin, action } = judged
  switch (action.kind) {
    case 'noop':
    case 'noop-move':
      return announce.cannotMoveHere
    case 'assign':
      return announce.overSprint(sprintNameOf(view, action.sprintId))
    case 'unassign':
      return announce.overBacklog
    case 'rerank':
      // 같은 칸 재정렬이라 대상 칸 = 출발 칸이다. 스프린트 카드만 sprintId 를 갖는다.
      return origin.sprintId === null
        ? announce.overBacklog
        : announce.overSprint(sprintNameOf(view, origin.sprintId))
    default: {
      const exhaustiveCheck: never = action
      return exhaustiveCheck
    }
  }
}

/**
 * 드롭 결과를 완료형으로 읽는다 — `resolveBacklogDropAction` 의 kind 5종 전부.
 *
 * 한 갈래라도 빠뜨리면 그 순간 스크린리더가 **침묵**하는데 테스트는 초록이다 (C-4).
 */
function describeEnd(
  view: BacklogView,
  active: Active,
  over: Over | null,
  canReorderIssue: boolean,
  isZeroMove: boolean,
): string {
  // FR-17. UPDATE 권한이 없으면 `use-backlog-drag.ts` 가 mutation 을 0건으로 막는다.
  // 그 상태에서 「옮겼습니다」를 읽으면 스크린리더 사용자에게만 거짓말이 된다.
  if (!canReorderIssue) return announce.forbidden

  const judged = judgeDrop(view, active, over, isZeroMove)
  // 드롭존 밖에서 놓았거나 이동이 0이면 아무 일도 일어나지 않는다 (T12)
  if (judged === null) return announce.noChange

  const { action } = judged
  switch (action.kind) {
    case 'noop':
      return announce.noChange
    case 'noop-move':
      return announce.cannotMoveHere
    case 'rerank':
      return announce.reordered
    case 'assign':
      return announce.movedToSprint(sprintNameOf(view, action.sprintId))
    case 'unassign':
      return announce.movedToBacklog
    default: {
      const exhaustiveCheck: never = action
      return exhaustiveCheck
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 공개 API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `DndContext` 의 `accessibility.announcements` 에 넣을 한국어 공지를 만든다.
 *
 * `KanbanBoard` 의 동명 헬퍼는 재사용할 수 없다 — 시그니처가 `BoardDetail` 전용이고
 * export 도 되어 있지 않다 (FR-9 실측).
 *
 * ### 왜 이동 0 여부를 인자로 받나 (T12)
 * dnd-kit 의 `Announcements.onDragEnd` 는 `{active, over}` 만 받고 **delta 가 없다**
 * (`@dnd-kit/core@6.3.1` `dist/components/Accessibility/types.d.ts:10`). 그래서 「집자마자 그대로
 * 놓았다」를 이 모듈 혼자서는 알 수 없고, 실제로 mutation 은 0건인데 「순서를 변경했습니다.」를
 * 읽는 거짓말이 났다. 판정은 `useBacklogDrag` 가 드롭 때 한 번만 하고 그 결과를 여기로 넘긴다 —
 * 판정을 두 번 하지 않으므로 두 경로가 다른 답을 낼 수 없다.
 *
 * @param view 스프린트 이름·칸 순서를 조회할 현재 백로그 데이터
 * @param canReorderIssue UPDATE 권한. false 면 이동 결과를 알리지 않는다 (FR-17)
 * @param wasLastDropZeroMove 방금 끝난 드롭의 이동량이 0이었는지 —
 *   `useBacklogDrag().wasLastDropZeroMove` 를 그대로 넘긴다
 */
export function buildBacklogAnnouncements(
  view: BacklogView,
  canReorderIssue: boolean,
  wasLastDropZeroMove: () => boolean,
): Announcements {
  return {
    onDragStart: ({ active }) => announce.dragStart(issueKeyOf(active.id)),
    onDragOver: ({ active, over }) => describeOver(view, active, over),
    onDragEnd: ({ active, over }) =>
      describeEnd(view, active, over, canReorderIssue, wasLastDropZeroMove()),
    onDragCancel: () => announce.cancelled,
  }
}

/**
 * `DndContext` 의 `accessibility.screenReaderInstructions`.
 *
 * 지정하지 않으면 dnd-kit 의 **영어 기본값**이 그대로 남는다 — 공지가 꺼져 있는 것이 아니라
 * 영어로 켜져 있는 것이다.
 */
export const backlogScreenReaderInstructions: ScreenReaderInstructions = {
  draggable: announce.instructions,
}
