// 백로그·스프린트 드래그 앤 드롭 액션 판정 순수 함수 라이브러리 (FR-BL-01/02 D6/D7)

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
