// 백로그 세로 스택의 키보드 드래그 좌표·활성화 키를 계산하는 순수 모듈 (FR-UX-13 F15 FR-15/FR-16)
import { KeyboardCode } from '@dnd-kit/core'
import type { KeyboardCodes, KeyboardCoordinateGetter, KeyboardSensorOptions, Translate } from '@dnd-kit/core'

/**
 * 같은 후보를 「아래(위)」로 오인하지 않기 위한 최소 중심 간격(px).
 * 카드 간격이 8px 라 1px 은 이웃을 건너뛰지 않으면서 측정 오차만 흡수한다.
 */
const CENTER_EPSILON_PX = 1

/** 화면 좌표 (viewport 기준 px) */
export interface BacklogPoint {
  x: number
  y: number
}

/** droppable 의 화면 사각형 — dnd-kit `ClientRect` 중 이 모듈이 쓰는 부분만 */
export interface BacklogRect {
  top: number
  left: number
  width: number
  height: number
}

/** 방향키가 착지할 수 있는 droppable 한 건 */
export interface BacklogKeyboardCandidate {
  id: string
  center: BacklogPoint
}

/** droppable 스냅샷 — dnd-kit 자료구조를 벗겨낸 순수 입력 */
export interface BacklogDroppableSnapshot {
  id: string
  /** 측정된 rect. 접힌 섹션처럼 화면에 없으면 null */
  rect: BacklogRect | null
  /** `useDroppable({ data })` 원본 */
  data: Record<string, unknown> | undefined
}

/** `backlogKeyboardCoordinates` 입력 */
export interface BacklogKeyboardInput {
  /** 눌린 키의 `KeyboardEvent.code` */
  code: string
  /** 드래그 중인 카드의 현재 충돌 사각형. 측정 전이면 null */
  collisionRect: BacklogRect | null
  /** 현재 등록된 droppable 스냅샷. **배열 순서는 결과에 영향을 주지 않는다** */
  snapshots: readonly BacklogDroppableSnapshot[]
}

/** rect 의 중심점을 구한다. */
function centerOf(rect: BacklogRect): BacklogPoint {
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
}

/** 카드가 0건인 칸 droppable 인가 — 빈 스프린트로도 옮길 수 있어야 기능이 온전하다. */
function isEmptyColumn(data: Record<string, unknown>): boolean {
  const context = data['context']
  if (context !== 'backlog' && context !== 'sprint') return false
  const orderedKeys = data['orderedKeys']
  return Array.isArray(orderedKeys) && orderedKeys.length === 0
}

/**
 * 방향키가 착지할 droppable 후보를 고른다 — 카드는 전부, 칸은 **카드가 0건일 때만**.
 *
 * 카드가 있는 칸까지 넣으면 방향키 한 번이 「칸 → 그 칸의 첫 카드」 두 번으로 늘어난다.
 *
 * @param snapshots droppable 스냅샷 (순서 무관)
 */
export function collectBacklogKeyboardCandidates(
  snapshots: readonly BacklogDroppableSnapshot[],
): BacklogKeyboardCandidate[] {
  const candidates: BacklogKeyboardCandidate[] = []
  for (const { id, rect, data } of snapshots) {
    // rect 가 없거나 넓이가 0이면 화면에 없다는 뜻이다 (접힌 섹션)
    if (rect === null || rect.width <= 0 || rect.height <= 0) continue
    if (data === undefined) continue
    if (data['type'] !== 'card' && !isEmptyColumn(data)) continue
    candidates.push({ id, center: centerOf(rect) })
  }
  return candidates
}

/**
 * 현재 중심에서 세로로 가장 가까운 후보 중심을 찾는다.
 *
 * 배열 순서가 아니라 **화면 세로 위치**로만 고르므로 후보 정렬이 필요 없다 — 세로 스택은
 * 화면 순서가 곧 렌더 순서이고, 정렬을 넣으면 백엔드가 정한 순서를 클라이언트가 다시 판단한다.
 *
 * @param sign 1 = 아래쪽, -1 = 위쪽
 */
function nearestInDirection(
  from: BacklogPoint,
  candidates: readonly BacklogKeyboardCandidate[],
  sign: 1 | -1,
): BacklogPoint | null {
  let bestGap = Number.POSITIVE_INFINITY
  let best: BacklogPoint | null = null
  for (const candidate of candidates) {
    const gap = (candidate.center.y - from.y) * sign
    if (gap <= CENTER_EPSILON_PX) continue
    if (gap < bestGap) {
      bestGap = gap
      best = candidate.center
    }
  }
  return best
}

/**
 * 방향키 한 번이 만들 **중심 좌표**를 고른다.
 *
 * @returns 이동할 중심. 갈 곳이 없으면 `from` 그대로. 방향키가 아니면 null
 */
function nextCenter(
  code: string,
  from: BacklogPoint,
  candidates: readonly BacklogKeyboardCandidate[],
): BacklogPoint | null {
  switch (code) {
    case KeyboardCode.Down:
      return nearestInDirection(from, candidates, 1) ?? from
    case KeyboardCode.Up:
      return nearestInDirection(from, candidates, -1) ?? from
    // 세로 스택엔 가로 이웃이 없다. 좌표를 그대로 둬 카드가 옆으로 새지 않게 한다
    case KeyboardCode.Left:
    case KeyboardCode.Right:
      return from
    default:
      return null
  }
}

/**
 * 방향키 한 번이 만들 **좌상단 좌표**를 계산한다 (FR-15).
 *
 * dnd-kit 은 좌상단 좌표를 주고받지만 후보 선택은 **중심 기준**이라야 드래그 카드가 대상 위에
 * 정확히 겹친다. 그래서 안에서 중심으로 바꿔 고르고 다시 좌상단으로 돌려놓는다.
 *
 * @returns 이동할 좌상단 좌표. 처리 대상이 아니면 null
 */
export function backlogKeyboardCoordinates(input: BacklogKeyboardInput): BacklogPoint | null {
  const { code, collisionRect, snapshots } = input
  if (collisionRect === null) return null
  const candidates = collectBacklogKeyboardCandidates(snapshots)
  const target = nextCenter(code, centerOf(collisionRect), candidates)
  if (target === null) return null
  return { x: target.x - collisionRect.width / 2, y: target.y - collisionRect.height / 2 }
}

/**
 * `KeyboardSensor` 에 넣는 좌표 계산기 (FR-15).
 *
 * 기본 계산기는 방향키 한 번에 25px 만 움직여(`core.esm.js:1111,1121`) 세로 스택에서는 옆
 * 섹션까지 30~40회가 필요하다. 이 계산기는 다음/이전 카드 중심으로 한 번에 점프한다.
 */
export const backlogCoordinateGetter: KeyboardCoordinateGetter = (event, { context }) => {
  const snapshots: BacklogDroppableSnapshot[] = context.droppableContainers
    .getEnabled()
    .map((container) => ({
      id: String(container.id),
      rect: context.droppableRects.get(container.id) ?? container.rect.current,
      data: container.data.current,
    }))
  const next = backlogKeyboardCoordinates({
    code: event.code,
    collisionRect: context.collisionRect,
    snapshots,
  })
  return next ?? undefined
}

/**
 * 백로그 키보드 드래그 활성화 키 (FR-16).
 *
 * 기본값은 `start` 에 **Enter**, `end` 에 **Tab** 이 들어 있다(`core.esm.js:1099-1101`). 카드가
 * `setActivatorNodeRef` 를 쓰지 않아 dnd-kit 의 조기 반환 가드(`:1358-1362`)가 통과하므로, 카드
 * 안 이슈 링크에 포커스한 채 Enter 를 누르면 `preventDefault()` 가 걸려 **이슈 상세로 갈 수
 * 없다**. Tab 도 「포커스 이동」이 아니라 「드롭」이 되어 기대와 정반대다.
 *
 * ※ 보드 화면(`KanbanBoard.tsx`)도 같은 선재 결함을 갖지만 **이 PR 범위 밖**이다 —
 *   한 PR = 한 관심사. 별도 후속으로 남긴다.
 *
 * ### 🛑 `end` 와 `cancel` 에 같은 키를 넣지 말 것
 * `KeyboardSensor.handleKeyDown` 은 `end` 를 **먼저** 검사하고 즉시 `return` 한다
 * (`@dnd-kit/core@6.3.1` `dist/core.cjs.development.js:1196-1203`). 그래서 Esc 를 `end` 에도
 * 넣으면 `cancel` 분기가 **영영 실행되지 않고** Esc 가 취소가 아니라 드롭이 되어
 * `handleDragEnd` 의 mutation 이 발사된다. 「스페이스바로 놓거나 Esc 로 취소합니다」라고
 * 읽어 주는 안내(`backlogLabels.announce.instructions`)가 스크린리더 사용자에게 거짓이 된다.
 * 서로소임은 `backlog-keyboard-coordinates.test.ts` 의 교집합 단언이 지킨다.
 */
export const backlogKeyboardCodes: KeyboardCodes = {
  start: [KeyboardCode.Space],
  cancel: [KeyboardCode.Esc],
  end: [KeyboardCode.Space],
}

/** `useSensor(KeyboardSensor, backlogKeyboardSensorOptions)` 로 넘길 옵션 묶음. */
export const backlogKeyboardSensorOptions: KeyboardSensorOptions = {
  keyboardCodes: backlogKeyboardCodes,
  coordinateGetter: backlogCoordinateGetter,
}

/**
 * 이동이 0인 드롭인지 판정한다 (T-KB-3).
 *
 * 키보드로 집고(Space) 방향키 없이 바로 놓으면(Space) translate 가 `{0,0}` 인데, 이때 카드
 * 자신의 droppable 은 `disabled: isDragging` 으로 빠져 있고 카드 간격이 8px 라 어느 카드와도
 * 겹치지 않는다. 그러면 칸 droppable 로 폴백하고 `extractColumnDropZone` 이
 * `dropIndex = orderedKeys.length` 를 줘서 **카드가 맨 뒤로 날아간다**. 마우스는 5px 활성화
 * 임계 때문에 이 경로가 없다.
 *
 * **연결 지점.** `use-backlog-drag.ts` 의 `handleDragEnd` 에서 `setOverDroppableId(null)` 직후
 * `if (isZeroMoveDrop(event.delta)) return` 으로 끊는다. 그러면 mutation 이 0회다.
 *
 * @param delta `DragEndEvent.delta`
 */
export function isZeroMoveDrop(delta: Translate): boolean {
  return delta.x === 0 && delta.y === 0
}
