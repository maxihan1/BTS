// 백로그·스프린트 드래그 앤 드롭 액션 판정 순수 함수 라이브러리 (FR-BL-01/02 D6/D7)
import type { BacklogView } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 카드 droppable id 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드 droppable id를 생성한다.
 *
 * BacklogCard의 useDroppable id와 BacklogBoard의 over 판별에 동일하게 사용되므로
 * 단일 정의로 유지한다.
 *
 * @param context 카드가 위치한 컨텍스트 ('backlog' | 'sprint')
 * @param issueKey 이슈 키
 */
export function cardDroppableId(context: 'backlog' | 'sprint', issueKey: string): string {
  return `card:${context}:${issueKey}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 드롭 존 데이터 파싱 — BacklogBoard의 over.data에서 호출하는 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * BacklogBoard의 handleDragEnd에서 over 대상의 드롭 존 정보를 담는 타입.
 */
export interface DropZoneData {
  context: 'backlog' | 'sprint'
  sprintId: string | null
  orderedKeys: readonly string[]
  dropIndex: number
}

/**
 * 칸 droppable data(context + orderedKeys)를 DropZoneData로 파싱한다.
 * `type: 'card'` 인 카드 droppable은 처리하지 않는다 (null 반환).
 * 유효하지 않으면 null을 반환한다.
 *
 * ★반환하는 `orderedKeys` 는 칸이 **화면에 그리고 있는** 목록이다(F16 이후 필터 후).
 * rank 계산에 그대로 넘기지 말 것 — {@link resolveOverToDropZone} 이 원본 `view` 로 덮는다.
 *
 * @param data over.data.current
 */
export function extractColumnDropZone(data: Record<string, unknown> | undefined): DropZoneData | null {
  if (data === undefined) return null
  const context = data['context']
  if (context !== 'backlog' && context !== 'sprint') return null
  if (data['type'] === 'card') return null
  const orderedKeys = Array.isArray(data['orderedKeys']) ? (data['orderedKeys'] as string[]) : []
  return {
    context,
    sprintId: typeof data['sprintId'] === 'string' ? data['sprintId'] : null,
    orderedKeys,
    dropIndex: typeof data['dropIndex'] === 'number' ? data['dropIndex'] : orderedKeys.length,
  }
}

/**
 * 카드 droppable data + 칸 이슈 키 목록을 받아 칸 droppable 형태의 DropZoneData를 반환한다.
 *
 * `type: 'card'` 가 없으면 null을 반환한다.
 *
 * 카드 위에 드롭 = 그 카드의 인덱스 위치에 삽입(앞으로 넣기).
 *
 * @param data over.data.current
 * @param getOrderedKeys 대상 칸의 이슈 키 배열을 반환하는 조회 함수
 */
export function extractCardDropZone(
  data: Record<string, unknown> | undefined,
  getOrderedKeys: (context: 'backlog' | 'sprint', sprintId: string | null) => readonly string[],
): DropZoneData | null {
  if (data === undefined) return null
  if (data['type'] !== 'card') return null
  const context = data['context']
  if (context !== 'backlog' && context !== 'sprint') return null
  const overKey = data['key']
  if (typeof overKey !== 'string') return null
  const sprintId = typeof data['sprintId'] === 'string' ? data['sprintId'] : null

  const orderedKeys = getOrderedKeys(context, sprintId)
  const overIdx = orderedKeys.indexOf(overKey)
  const dropIndex = overIdx === -1 ? orderedKeys.length : overIdx

  return { context, sprintId, orderedKeys, dropIndex }
}

// ─────────────────────────────────────────────────────────────────────────────
// resolveOverToDropZone — over 대상 → DropZoneData 공용 입력 구성
// ─────────────────────────────────────────────────────────────────────────────

/**
 * dnd-kit `Over` 중 드롭 판정이 실제로 읽는 부분만 추린 구조 타입.
 *
 * dnd-kit 타입을 그대로 받으면 이 순수 모듈이 라이브러리에 묶이고 테스트에서
 * `Over` 전체를 만들어야 한다. 읽는 것은 `data.current` 하나뿐이라 그것만 요구한다.
 */
export interface BacklogDropTarget {
  data: { current?: Record<string, unknown> | undefined }
}

/** 뷰에서 대상 칸의 rank 순 이슈 키 목록을 조회한다 */
function orderedKeysOf(
  view: BacklogView,
  context: 'backlog' | 'sprint',
  sprintId: string | null,
): readonly string[] {
  if (context === 'backlog') return view.backlog.map((i) => i.key)
  const entry = view.sprints.find((s) => s.sprint.sprintId === sprintId)
  return entry !== undefined ? entry.issues.map((i) => i.key) : []
}

/**
 * 드래그가 올라가 있는 대상(`over`)을 드롭 존 정보로 환산한다.
 *
 * 칸 droppable 이든 카드 droppable 이든 **대상 칸의 순서는 `view` 에서 조회한다**.
 *
 * ### 왜 칸 droppable 의 `data.orderedKeys` 를 안 쓰나 (F16 R6)
 * 그 값은 칸 컴포넌트가 **받은 목록**, 즉 F16 이후로는 **필터를 통과한 것만**이다
 * (`BacklogColumn.tsx:94` · `SprintColumn.tsx:74` ← `BacklogBoard` 의 `display`).
 * 그대로 rank 계산에 쓰면 두 가지가 깨진다.
 * ① 「맨 뒤」가 **보이는 것의 뒤**가 되어 숨은 카드를 건너뛴 자리에 꽂힌다.
 * ② 그 칸의 다른 카드가 전부 숨으면 `others.length === 0` 이라 `noop-move` 로 빠져
 *    **아무 일도 일어나지 않는다** — 숨은 카드가 실재하는데도.
 * 필터는 **표시**만 좁히고 **동작 대상**은 원본 전량이다. `data.orderedKeys` 는 지우지
 * 않는다 — 키보드 방향키의 「빈 칸인가」 판정이 그 값을 읽고
 * (`backlog-keyboard-coordinates.ts:56` `isEmptyColumn`), 그쪽은 **보이는 것**이 맞다.
 *
 * ### 왜 훅이 아니라 여기 있나
 * 이 **입력 구성**은 원래 `use-backlog-drag.ts` 안에 갇혀 있었다. 그 결과 같은 판정을 해야 하는
 * 드래그 공지 모듈이 구성을 **복제**할 수밖에 없었는데, 판정 함수가 같아도 입력이 다르면
 * 공지와 실제 mutation 이 어긋난다 — 어긋남은 판정이 아니라 입력에서 난다
 * (FR-UX-13 F15 스펙 §리뷰 반영 C-5). 그래서 구성 자체를 공용화했다.
 *
 * ### 왜 이동 0 판정이 여기 있나 (T12)
 * 종전에는 `use-backlog-drag.ts` 의 `handleDragEnd` 안에서만 걸렀다. 그러자 mutation 은 0건인데
 * 공지는 「순서를 변경했습니다.」를 읽는 **거짓말**이 났다 — 공지 모듈이 그 사실을 알 길이
 * 없었기 때문이다(실브라우저 실측). 판정을 드롭 판정 단계로 올려 두 소비자가 같은 함수의
 * 같은 반환값을 보게 하면 다시 갈라질 수 없다. C-5 와 정확히 같은 논지의 확장이다.
 *
 * @param view 카드 droppable 경로에서 대상 칸의 순서를 조회할 현재 백로그 데이터
 * @param over dnd-kit 의 `over`. 없으면 null
 * @param isZeroMove 드래그 이동량이 0인가 (`isZeroMoveDrop(event.delta)`).
 *   true 면 사용자가 카드를 어디로도 옮기지 않았다는 뜻이라 적용할 드롭 존이 없다
 * @returns 드롭 존 정보. 판정 불가면 null
 */
export function resolveOverToDropZone(
  view: BacklogView | undefined,
  over: BacklogDropTarget | null | undefined,
  isZeroMove: boolean,
): DropZoneData | null {
  // 이동이 0이면 카드 자신의 droppable 이 `disabled: isDragging` 으로 빠져 있어 칸 droppable 로
  // 폴백하고 `dropIndex = orderedKeys.length` 가 잡힌다 — 그대로 두면 **카드가 맨 뒤로 날아간다**.
  if (isZeroMove) return null

  if (over === null || over === undefined) return null

  const overData = over.data.current

  const columnZone = extractColumnDropZone(overData)
  if (columnZone !== null) {
    // 뷰가 아직 없으면 조회할 원본이 없다 — data 가 실어 온 것이 가진 전부다.
    if (view === undefined) return columnZone
    // 칸 빈 영역 드롭 = 「그 칸의 맨 뒤」. 그 '맨 뒤'는 **원본** 기준이어야 한다 (위 KDoc).
    const orderedKeys = orderedKeysOf(view, columnZone.context, columnZone.sprintId)
    return { ...columnZone, orderedKeys, dropIndex: orderedKeys.length }
  }

  // 카드 droppable 경로는 대상 칸의 순서를 알아야 하므로 뷰 데이터가 필요하다.
  if (view === undefined) return null
  return extractCardDropZone(overData, (ctx, sid) => orderedKeysOf(view, ctx, sid))
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 드롭 위치 이웃 이슈 키.
 *
 * - `previousIssueKey`: 삽입 위치 바로 앞 이슈 키. 맨 앞이면 undefined.
 * - `nextIssueKey`: 삽입 위치 바로 뒤 이슈 키. 맨 뒤이면 undefined.
 */
export interface NeighborResult {
  previousIssueKey: string | undefined
  nextIssueKey: string | undefined
}

/**
 * 드래그 드롭 입력 파라미터.
 *
 * - `issueKey`: 드래그된 이슈 키
 * - `fromContext`: 출발 칸 컨텍스트 ('backlog' | 'sprint')
 * - `fromSprintId`: 출발 스프린트 UUID. backlog이면 null.
 * - `toContext`: 도착 칸 컨텍스트
 * - `toSprintId`: 도착 스프린트 UUID. backlog이면 null.
 * - `targetKeys`: 도착 칸의 rank 순 이슈 키 목록 (issueKey 포함 원본).
 * - `dropIndex`: targetKeys 기준 드롭 목표 위치 인덱스 (0 = 맨 앞).
 */
export interface BacklogDropInput {
  issueKey: string
  fromContext: 'backlog' | 'sprint'
  fromSprintId: string | null
  toContext: 'backlog' | 'sprint'
  toSprintId: string | null
  targetKeys: readonly string[]
  dropIndex: number
}

/**
 * 드래그 드롭 액션 판정 결과.
 *
 * - `noop`: 제자리 드롭 — 아무것도 하지 않는다.
 * - `noop-move`: E1(같은 칸, 자신 외 이슈 없음) — rerank 불필요.
 * - `rerank`: 같은 칸 재정렬 — rerank만 호출한다.
 * - `assign`: 백로그→스프린트 또는 스프린트X→스프린트Y — assign 후 rerank.
 *   E1이면 `rerank` 필드가 undefined.
 * - `unassign`: 스프린트→백로그 — unassign 후 rerank. E1이면 `rerank` 필드가 undefined.
 */
export type BacklogDropAction =
  | { kind: 'noop' }
  | { kind: 'noop-move' }
  | { kind: 'rerank'; sprintId?: undefined; rerank: NeighborResult }
  | { kind: 'assign'; sprintId: string; rerank?: NeighborResult }
  | { kind: 'unassign'; sprintId: string; rerank?: NeighborResult }

// ─────────────────────────────────────────────────────────────────────────────
// computeNeighbors — 삽입 위치의 이웃 이슈 키 계산
// ─────────────────────────────────────────────────────────────────────────────

/**
 * issueKey가 제거된 rank 순 목록과 삽입 인덱스를 받아 이웃 키를 반환한다.
 *
 * 호출 전 targetKeys에서 issueKey를 제거해 전달해야 한다.
 *
 * 예: ['A', 'C'], dropIndex=1 → { prev: 'A', next: 'C' }
 *
 * @param keysWithoutSelf issueKey가 이미 제거된 rank 순 목록
 * @param dropIndex 삽입 인덱스 (0 = 맨 앞, length = 맨 뒤)
 */
export function computeNeighbors(
  keysWithoutSelf: readonly string[],
  dropIndex: number,
): NeighborResult {
  const clamped = Math.max(0, Math.min(dropIndex, keysWithoutSelf.length))
  const previousIssueKey = clamped > 0 ? keysWithoutSelf[clamped - 1] : undefined
  const nextIssueKey = clamped < keysWithoutSelf.length ? keysWithoutSelf[clamped] : undefined
  return { previousIssueKey, nextIssueKey }
}

// ─────────────────────────────────────────────────────────────────────────────
// resolveBacklogDropAction — 드롭 액션 판정 (순수 함수)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 드래그 드롭 결과를 받아 수행할 액션을 판정한다.
 *
 * **시나리오 매핑**:
 * - S1 백로그→백로그: rerank-only
 * - S2 백로그→스프린트Y: assign(Y) + rerank
 * - S3 스프린트X→백로그: unassign(X) + rerank
 * - S4 스프린트X→스프린트Y: assign(Y) + rerank
 * - S5 스프린트X→같은 스프린트X: rerank-only
 * - E1 대상 칸 자신 외 이슈 없음: noop-move(같은 칸) 또는 assign/unassign(다른 칸, rerank 생략)
 * - 제자리 드롭: noop (dropIndex가 originIdx 또는 originIdx+1)
 *
 * **부수효과 없음** — 순수 함수.
 *
 * @param input 드롭 입력 (targetKeys는 issueKey 포함 원본 목록)
 */
export function resolveBacklogDropAction(input: BacklogDropInput): BacklogDropAction {
  const {
    issueKey,
    fromContext,
    fromSprintId,
    toContext,
    toSprintId,
    targetKeys,
    dropIndex,
  } = input

  const isSameLane =
    fromContext === toContext &&
    fromSprintId === toSprintId

  if (isSameLane) {
    // issueKey 원본 인덱스
    const originIdx = targetKeys.indexOf(issueKey)

    // E1: 자신 외 이슈 없음 → noop-move
    const others = targetKeys.filter((k) => k !== issueKey)
    if (others.length === 0) {
      return { kind: 'noop-move' }
    }

    // 제자리 판정: dropIndex === originIdx이면 순서 변화 없음
    // 예: [A, B, C]에서 B(idx=1)를 dropIndex=1로 드롭 → 순서 동일 → noop
    if (originIdx !== -1 && dropIndex === originIdx) {
      return { kind: 'noop' }
    }

    // 재정렬: adjustedIdx 계산
    // dropIndex > originIdx이면 issueKey 제거 후 인덱스가 1 줄어듦
    let adjustedIdx = dropIndex
    if (originIdx !== -1 && originIdx < dropIndex) {
      adjustedIdx = Math.max(0, dropIndex - 1)
    }
    adjustedIdx = Math.min(adjustedIdx, others.length)

    const neighbors = computeNeighbors(others, adjustedIdx)
    return { kind: 'rerank', rerank: neighbors }
  }

  // 크로스 칸 이동
  // targetKeys = 도착 칸의 기존 이슈 목록. issueKey는 보통 없지만 filter로 방어.
  const crossKeys = targetKeys.filter((k) => k !== issueKey)
  const isEmptyTarget = crossKeys.length === 0

  if (toContext === 'sprint' && toSprintId !== null) {
    if (isEmptyTarget) {
      return { kind: 'assign', sprintId: toSprintId }
    }
    const idx = Math.min(dropIndex, crossKeys.length)
    return { kind: 'assign', sprintId: toSprintId, rerank: computeNeighbors(crossKeys, idx) }
  }

  if (toContext === 'backlog' && fromSprintId !== null) {
    if (isEmptyTarget) {
      return { kind: 'unassign', sprintId: fromSprintId }
    }
    const idx = Math.min(dropIndex, crossKeys.length)
    return { kind: 'unassign', sprintId: fromSprintId, rerank: computeNeighbors(crossKeys, idx) }
  }

  return { kind: 'noop' }
}
