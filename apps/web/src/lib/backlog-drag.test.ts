// backlog-drag 순수 함수 단위 테스트 — 시나리오 판정·이웃 계산 (FR-BL-01/02 D6/D7)
import { describe, it, expect } from 'vitest'
import {
  resolveBacklogDropAction,
  computeNeighbors,
  resolveOverToDropZone,
} from './backlog-drag'
import type { NeighborResult } from './backlog-drag'
import type { BacklogView } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 키 목록 (rank 순, issueKey **제외** 후 목록).
 *
 * computeNeighbors는 "삽입할 위치 기준 이웃"을 반환한다.
 * targetKeys 안에서 드래그 카드는 이미 제거된 상태여야 한다.
 */
const BACKLOG_KEYS_EX_2 = ['ATLAS-1', 'ATLAS-3'] // ATLAS-2 제외
const SPRINT_A_KEYS = ['ATLAS-4', 'ATLAS-5']
const SPRINT_B_KEYS = ['ATLAS-6', 'ATLAS-7']
const SPRINT_A_ID = 'sprint-a-uuid'
const SPRINT_B_ID = 'sprint-b-uuid'

// ─────────────────────────────────────────────────────────────────────────────
// computeNeighbors — 이웃 계산
// ─────────────────────────────────────────────────────────────────────────────

describe('computeNeighbors', () => {
  it('목록 중간에 삽입 시 이전·다음 키를 반환한다', () => {
    // ['ATLAS-1', 'ATLAS-3'] 사이(인덱스 1)에 삽입 → prev='ATLAS-1', next='ATLAS-3'
    const result: NeighborResult = computeNeighbors(BACKLOG_KEYS_EX_2, 1)
    expect(result).toEqual<NeighborResult>({
      previousIssueKey: 'ATLAS-1',
      nextIssueKey: 'ATLAS-3',
    })
  })

  it('목록 맨 앞에 삽입 시 previousIssueKey=undefined를 반환한다', () => {
    const result = computeNeighbors(BACKLOG_KEYS_EX_2, 0)
    expect(result).toEqual<NeighborResult>({
      previousIssueKey: undefined,
      nextIssueKey: 'ATLAS-1',
    })
  })

  it('목록 맨 뒤에 삽입 시 nextIssueKey=undefined를 반환한다', () => {
    // 2개 목록의 끝(인덱스 2)
    const result = computeNeighbors(BACKLOG_KEYS_EX_2, 2)
    expect(result).toEqual<NeighborResult>({
      previousIssueKey: 'ATLAS-3',
      nextIssueKey: undefined,
    })
  })

  it('빈 목록이면 둘 다 undefined를 반환한다', () => {
    const result = computeNeighbors([], 0)
    expect(result).toEqual<NeighborResult>({
      previousIssueKey: undefined,
      nextIssueKey: undefined,
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// resolveBacklogDropAction — 시나리오 판정
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveBacklogDropAction', () => {
  // S1 백로그→백로그 재정렬
  describe('S1: 백로그 → 백로그 (재정렬)', () => {
    it('rerank-only 액션을 반환한다', () => {
      // ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'] 중 ATLAS-2를 dropIndex=2(ATLAS-3 앞)로 이동
      // → issueKey 제외 후 ['ATLAS-1', 'ATLAS-3'], adjustedIdx=1
      // → 이웃: prev='ATLAS-1', next='ATLAS-3'
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-2',
        fromContext: 'backlog',
        fromSprintId: null,
        toContext: 'backlog',
        toSprintId: null,
        targetKeys: ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'],
        dropIndex: 2,
      })
      expect(action.kind).toBe('rerank')
      if (action.kind === 'rerank') {
        expect(action.rerank).toEqual<NeighborResult>({
          previousIssueKey: 'ATLAS-1',
          nextIssueKey: 'ATLAS-3',
        })
        expect(action.sprintId).toBeUndefined()
      }
    })

    it('E1: 대상 칸이 비어 이웃 없으면 noop-move를 반환한다', () => {
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-2',
        fromContext: 'backlog',
        fromSprintId: null,
        toContext: 'backlog',
        toSprintId: null,
        targetKeys: [],
        dropIndex: 0,
      })
      expect(action.kind).toBe('noop-move')
    })
  })

  // S5 스프린트X→같은 스프린트X 재정렬
  describe('S5: 스프린트 → 같은 스프린트 (재정렬)', () => {
    it('rerank-only 액션을 반환한다', () => {
      // ['ATLAS-4', 'ATLAS-5'] 중 ATLAS-4를 dropIndex=1(ATLAS-5 앞)로 이동
      // → issueKey 제외 후 ['ATLAS-5'], adjustedIdx=0 (원래 idx=0 < dropIndex=1이므로 1-1=0 → adjusted=0)
      // 아니면 dropIndex=1이 조정 없이 그대로 → computeNeighbors(['ATLAS-5'], 1) → prev='ATLAS-5', next=undefined
      // 기대값: {prev:'ATLAS-4', next:'ATLAS-5'} — 즉 ATLAS-4가 그대로 앞에 남는 재정렬 아닌 케이스
      //
      // 이 케이스는 ATLAS-4가 dropIndex=1 → ATLAS-5 뒤로 이동하는 것:
      // 제거 후 ['ATLAS-5'], dropIndex=1 → prev='ATLAS-5', next=undefined
      // 기대: {prev:'ATLAS-4', next:'ATLAS-5'} 이건 다른 dropIndex와 일치 안 함
      //
      // 테스트 시나리오 수정: ATLAS-4를 dropIndex=1에 이동 = ATLAS-5 앞 위치 유지(제자리가 아님)
      // dropIndex=0이면 제자리(원래 0번), dropIndex=1이면 ATLAS-5 뒤로
      // 기대: prev='ATLAS-5', next=undefined 이 되어야 함
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-4',
        fromContext: 'sprint',
        fromSprintId: SPRINT_A_ID,
        toContext: 'sprint',
        toSprintId: SPRINT_A_ID,
        targetKeys: SPRINT_A_KEYS, // ['ATLAS-4', 'ATLAS-5']
        dropIndex: 1,
      })
      expect(action.kind).toBe('rerank')
      if (action.kind === 'rerank') {
        expect(action.sprintId).toBeUndefined()
        expect(action.rerank).toEqual<NeighborResult>({
          previousIssueKey: undefined,
          nextIssueKey: 'ATLAS-5',
        })
      }
    })
  })

  // S2 백로그→스프린트Y
  describe('S2: 백로그 → 스프린트 (할당)', () => {
    it('assign + rerank 액션을 반환한다', () => {
      // 백로그의 ATLAS-1을 스프린트A(['ATLAS-4','ATLAS-5'])의 dropIndex=1(ATLAS-5 앞)에 삽입
      // → issueKey 없으므로 crossKeys=['ATLAS-4','ATLAS-5'], dropIdx=1
      // → 이웃: prev='ATLAS-4', next='ATLAS-5'
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-1',
        fromContext: 'backlog',
        fromSprintId: null,
        toContext: 'sprint',
        toSprintId: SPRINT_A_ID,
        targetKeys: SPRINT_A_KEYS,
        dropIndex: 1,
      })
      expect(action.kind).toBe('assign')
      if (action.kind === 'assign') {
        expect(action.sprintId).toBe(SPRINT_A_ID)
        expect(action.rerank).toEqual<NeighborResult>({
          previousIssueKey: 'ATLAS-4',
          nextIssueKey: 'ATLAS-5',
        })
      }
    })

    it('E1: 대상 스프린트 칸이 비어 있으면 rerank를 생략한다', () => {
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-1',
        fromContext: 'backlog',
        fromSprintId: null,
        toContext: 'sprint',
        toSprintId: SPRINT_A_ID,
        targetKeys: [],
        dropIndex: 0,
      })
      expect(action.kind).toBe('assign')
      if (action.kind === 'assign') {
        expect(action.sprintId).toBe(SPRINT_A_ID)
        expect(action.rerank).toBeUndefined()
      }
    })
  })

  // S3 스프린트X→백로그
  describe('S3: 스프린트 → 백로그 (해제)', () => {
    it('unassign + rerank 액션을 반환한다', () => {
      // 스프린트A의 ATLAS-4를 백로그(['ATLAS-1','ATLAS-2','ATLAS-3'])의 dropIndex=0(맨 앞)에 삽입
      // → issueKey ATLAS-4가 targetKeys에 없으므로 crossKeys=['ATLAS-1','ATLAS-2','ATLAS-3'], dropIdx=0
      // → 이웃: prev=undefined, next='ATLAS-1'
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-4',
        fromContext: 'sprint',
        fromSprintId: SPRINT_A_ID,
        toContext: 'backlog',
        toSprintId: null,
        targetKeys: ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'],
        dropIndex: 0,
      })
      expect(action.kind).toBe('unassign')
      if (action.kind === 'unassign') {
        expect(action.sprintId).toBe(SPRINT_A_ID)
        expect(action.rerank).toEqual<NeighborResult>({
          previousIssueKey: undefined,
          nextIssueKey: 'ATLAS-1',
        })
      }
    })
  })

  // S4 스프린트X→스프린트Y
  describe('S4: 스프린트X → 스프린트Y (재할당)', () => {
    it('assign(새 스프린트) + rerank 액션을 반환한다', () => {
      // 스프린트A의 ATLAS-4를 스프린트B(['ATLAS-6','ATLAS-7'])의 dropIndex=2(맨 뒤)에 삽입
      // → issueKey ATLAS-4가 targetKeys에 없으므로 crossKeys=['ATLAS-6','ATLAS-7'], dropIdx=2(clamp=2)
      // → 이웃: prev='ATLAS-7', next=undefined
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-4',
        fromContext: 'sprint',
        fromSprintId: SPRINT_A_ID,
        toContext: 'sprint',
        toSprintId: SPRINT_B_ID,
        targetKeys: SPRINT_B_KEYS,
        dropIndex: 2,
      })
      expect(action.kind).toBe('assign')
      if (action.kind === 'assign') {
        expect(action.sprintId).toBe(SPRINT_B_ID)
        expect(action.rerank).toEqual<NeighborResult>({
          previousIssueKey: 'ATLAS-7',
          nextIssueKey: undefined,
        })
      }
    })
  })

  // 제자리 드롭 (같은 칸, 같은 위치)
  describe('제자리 드롭 — noop', () => {
    it('같은 칸에 원래 위치로 드롭하면 noop을 반환한다', () => {
      // ATLAS-2가 ['ATLAS-1','ATLAS-2','ATLAS-3'] 중 index=1에 있고
      // dropIndex=1(원래 자리)로 이동 → 제자리
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-2',
        fromContext: 'backlog',
        fromSprintId: null,
        toContext: 'backlog',
        toSprintId: null,
        targetKeys: ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'],
        dropIndex: 1, // 바로 자기 자리 (index=1)
      })
      expect(action.kind).toBe('noop')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// resolveOverToDropZone — over 대상 → DropZoneData 공용 입력 구성 (FR-UX-13 F15 C-5)
//
// ★왜 공용화하나.
// 드롭 판정 함수(`resolveBacklogDropAction`)를 같이 쓰는 것만으로는 부족하다. 실제 mutation 은
// `use-backlog-drag.ts` 안에서 「칸 droppable 먼저, 없으면 카드 droppable + 대상 칸의 orderedKeys
// 콜백」으로 **입력을 구성**해 왔는데, 공지 모듈(T4)이 그 구성을 복제하면 판정이 같아도 결과가
// 어긋난다. 어긋남은 판정이 아니라 입력에서 난다 — 그래서 입력 구성 자체를 여기로 올린다.
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveOverToDropZone', () => {
  const VIEW: BacklogView = {
    backlog: [
      { key: 'ATLAS-1', summary: '1', currentStateKey: 'open', assigneeId: null, priority: 1, rank: 'a', version: 0, epicKey: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
      { key: 'ATLAS-2', summary: '2', currentStateKey: 'open', assigneeId: null, priority: 1, rank: 'b', version: 0, epicKey: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
    ],
    sprints: [
      {
        sprint: {
          sprintId: SPRINT_A_ID,
          name: '스프린트 A',
          goal: null,
          status: 'PLANNED',
          startDate: null,
          endDate: null,
          version: 0,
        },
        issues: [
          { key: 'ATLAS-4', summary: '4', currentStateKey: 'open', assigneeId: null, priority: 1, rank: 'a', version: 0, epicKey: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
          { key: 'ATLAS-5', summary: '5', currentStateKey: 'open', assigneeId: null, priority: 1, rank: 'b', version: 0, epicKey: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
        ],
      },
    ],
    truncated: false,
  }

  /** dnd-kit `Over` 중 판정이 읽는 부분만 흉내 낸다 */
  function over(data: Record<string, unknown> | undefined) {
    return { data: { current: data } }
  }

  /** `isZeroMove` 인자 — 실제로 끌어 옮긴 드롭 */
  const MOVED = false
  /** `isZeroMove` 인자 — 집자마자 그대로 놓은 드롭 (T12) */
  const NOT_MOVED = true

  it('칸 droppable 이면 view 에서 그 칸의 순서를 채우고 맨 뒤를 가리킨다', () => {
    const zone = resolveOverToDropZone(
      VIEW,
      over({ context: 'sprint', sprintId: SPRINT_A_ID, orderedKeys: ['ATLAS-4', 'ATLAS-5'] }),
      MOVED,
    )
    expect(zone).toEqual({
      context: 'sprint',
      sprintId: SPRINT_A_ID,
      orderedKeys: ['ATLAS-4', 'ATLAS-5'],
      dropIndex: 2,
    })
  })

  it('카드 droppable 이면 view 에서 대상 칸의 orderedKeys 를 조회해 채운다', () => {
    const zone = resolveOverToDropZone(
      VIEW,
      over({ type: 'card', context: 'sprint', sprintId: SPRINT_A_ID, key: 'ATLAS-5' }),
      MOVED,
    )
    expect(zone).toEqual({
      context: 'sprint',
      sprintId: SPRINT_A_ID,
      orderedKeys: ['ATLAS-4', 'ATLAS-5'],
      dropIndex: 1,
    })
  })

  it('백로그 카드 droppable 이면 backlog 목록을 조회한다', () => {
    const zone = resolveOverToDropZone(
      VIEW,
      over({ type: 'card', context: 'backlog', key: 'ATLAS-1' }),
      MOVED,
    )
    expect(zone).toEqual({
      context: 'backlog',
      sprintId: null,
      orderedKeys: ['ATLAS-1', 'ATLAS-2'],
      dropIndex: 0,
    })
  })

  it('over 가 없으면 null 을 반환한다', () => {
    expect(resolveOverToDropZone(VIEW, null, MOVED)).toBeNull()
    expect(resolveOverToDropZone(VIEW, undefined, MOVED)).toBeNull()
  })

  it('view 가 없으면 카드 droppable 은 판정하지 못하고 null 을 반환한다', () => {
    // 칸 droppable 은 data 에 orderedKeys 를 싣고 오므로 view 없이도 판정된다.
    expect(
      resolveOverToDropZone(
        undefined,
        over({ type: 'card', context: 'backlog', key: 'ATLAS-1' }),
        MOVED,
      ),
    ).toBeNull()
    expect(
      resolveOverToDropZone(undefined, over({ context: 'backlog', orderedKeys: ['ATLAS-1'] }), MOVED),
    ).not.toBeNull()
  })

  it('알 수 없는 data 는 null 을 반환한다', () => {
    expect(resolveOverToDropZone(VIEW, over(undefined), MOVED)).toBeNull()
    expect(resolveOverToDropZone(VIEW, over({ context: 'unknown' }), MOVED)).toBeNull()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // T12 결함 A — 이동 0 판정을 **드롭 판정 단계로** 올린다
  //
  // 종전에는 `use-backlog-drag.ts` 의 `handleDragEnd` 안에서만 걸렀다. 그래서 같은 드롭을
  // 낭독하는 공지 모듈은 이동이 0이었다는 사실을 알 길이 없었고, mutation 은 0건인데
  // 「순서를 변경했습니다.」를 읽었다(실브라우저 실측). 판정을 여기로 올리면 두 소비자가
  // **같은 함수의 같은 반환값**을 보므로 다시 갈라질 수 없다.
  // ───────────────────────────────────────────────────────────────────────────

  it('★이동이 0이면 어떤 over 든 드롭 존이 없다 — 칸 droppable 폴백을 끊는다', () => {
    // 이 입력이 바로 「집자마자 놓기」가 만들던 경로다. `MOVED` 였다면 dropIndex 2 가 잡혀
    // 카드가 맨 뒤로 날아간다 (바로 위 첫 케이스가 그 값을 단언한다).
    const zone = resolveOverToDropZone(
      VIEW,
      over({ context: 'sprint', sprintId: SPRINT_A_ID, orderedKeys: ['ATLAS-4', 'ATLAS-5'] }),
      NOT_MOVED,
    )
    expect(zone).toBeNull()
  })

  it('★이동이 0이면 카드 droppable 위에서도 드롭 존이 없다', () => {
    const zone = resolveOverToDropZone(
      VIEW,
      over({ type: 'card', context: 'backlog', key: 'ATLAS-1' }),
      NOT_MOVED,
    )
    expect(zone).toBeNull()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // F16 결함 — 필터가 rank 계산에 새어 들어갔다
  //
  // 칸 droppable 의 `data.orderedKeys` 는 칸 컴포넌트가 **받은 목록**, 즉 F16 이후로는
  // **필터를 통과한 것만**이다(`BacklogColumn.tsx:94` · `SprintColumn.tsx:74` ←
  // `BacklogBoard.tsx` 의 `display`). 그대로 rank 계산에 쓰면 숨은 카드를 건너뛴 자리에
  // 꽂히고, 그 칸의 다른 카드가 전부 숨으면 `noop-move` 로 아무 일도 일어나지 않는다.
  //
  // 카드 droppable 경로는 처음부터 `view` 를 조회하고 있었다(바로 위 두 케이스). 두 경로 중
  // 하나만 봉합돼 있던 것이 이 결함이다 — **rank 의 진실 출처는 언제나 원본 `view`** 다.
  // ───────────────────────────────────────────────────────────────────────────

  it('★★칸 droppable 의 orderedKeys 가 필터 후 목록이어도 rank 기준은 원본 전량이다', () => {
    // 화면에는 ATLAS-1 만 보이는 상태에서 백로그 칸 빈 영역에 놓았다.
    const zone = resolveOverToDropZone(
      VIEW,
      over({ context: 'backlog', sprintId: null, orderedKeys: ['ATLAS-1'] }),
      MOVED,
    )
    // 「맨 뒤」는 보이는 것의 뒤가 아니라 **원본의 뒤**여야 한다. 필터 후 목록을 쓰면
    // dropIndex 1 · orderedKeys ['ATLAS-1'] 이 잡혀 ATLAS-2 앞에 꽂힌다.
    expect(zone).toEqual({
      context: 'backlog',
      sprintId: null,
      orderedKeys: ['ATLAS-1', 'ATLAS-2'],
      dropIndex: 2,
    })
  })

  it('★★스프린트 칸도 같다 — 필터가 그 칸의 카드를 전부 가려도 원본을 본다', () => {
    // 필터가 스프린트 A 의 카드를 전부 가린 상태(`orderedKeys: []`). 그대로 쓰면 같은 칸
    // 재정렬이 `others.length === 0` 으로 `noop-move` 가 되어 **아무 일도 안 일어난다**.
    const zone = resolveOverToDropZone(
      VIEW,
      over({ context: 'sprint', sprintId: SPRINT_A_ID, orderedKeys: [] }),
      MOVED,
    )
    expect(zone).toEqual({
      context: 'sprint',
      sprintId: SPRINT_A_ID,
      orderedKeys: ['ATLAS-4', 'ATLAS-5'],
      dropIndex: 2,
    })
  })
})
