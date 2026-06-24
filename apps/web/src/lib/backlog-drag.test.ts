// backlog-drag 순수 함수 단위 테스트 — 시나리오 판정·이웃 계산 (FR-BL-01/02 D6/D7)
import { describe, it, expect } from 'vitest'
import {
  resolveBacklogDropAction,
  computeNeighbors,
} from './backlog-drag'
import type { NeighborResult } from './backlog-drag'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 키 목록 (rank 순) */
const BACKLOG_KEYS = ['ATLAS-1', 'ATLAS-2', 'ATLAS-3']
const SPRINT_A_KEYS = ['ATLAS-4', 'ATLAS-5']
const SPRINT_B_KEYS = ['ATLAS-6', 'ATLAS-7']
const SPRINT_A_ID = 'sprint-a-uuid'
const SPRINT_B_ID = 'sprint-b-uuid'

// ─────────────────────────────────────────────────────────────────────────────
// computeNeighbors — 이웃 계산
// ─────────────────────────────────────────────────────────────────────────────

describe('computeNeighbors', () => {
  it('목록 중간에 삽입 시 이전·다음 키를 반환한다', () => {
    // ATLAS-1, [ATLAS-2], ATLAS-3 사이에 드롭 (인덱스 1)
    const result: NeighborResult = computeNeighbors(BACKLOG_KEYS, 1)
    expect(result).toEqual<NeighborResult>({
      previousIssueKey: 'ATLAS-1',
      nextIssueKey: 'ATLAS-3',
    })
  })

  it('목록 맨 앞에 삽입 시 previousIssueKey=undefined를 반환한다', () => {
    const result = computeNeighbors(BACKLOG_KEYS, 0)
    expect(result).toEqual<NeighborResult>({
      previousIssueKey: undefined,
      nextIssueKey: 'ATLAS-1',
    })
  })

  it('목록 맨 뒤에 삽입 시 nextIssueKey=undefined를 반환한다', () => {
    // 3개 목록의 끝(인덱스 3)
    const result = computeNeighbors(BACKLOG_KEYS, 3)
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
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-2',
        fromContext: 'backlog',
        fromSprintId: null,
        toContext: 'backlog',
        toSprintId: null,
        targetKeys: BACKLOG_KEYS,
        dropIndex: 2,
      })
      expect(action.kind).toBe('rerank')
      if (action.kind === 'rerank') {
        expect(action.rerank).toEqual<NeighborResult>({
          previousIssueKey: 'ATLAS-2',
          nextIssueKey: 'ATLAS-3',
        })
        expect(action.sprintId).toBeUndefined()
      }
    })

    it('E1: 대상 칸이 비어 이웃 없으면 rerank를 생략한다', () => {
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
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-4',
        fromContext: 'sprint',
        fromSprintId: SPRINT_A_ID,
        toContext: 'sprint',
        toSprintId: SPRINT_A_ID,
        targetKeys: SPRINT_A_KEYS,
        dropIndex: 1,
      })
      expect(action.kind).toBe('rerank')
      if (action.kind === 'rerank') {
        expect(action.sprintId).toBeUndefined()
        expect(action.rerank).toEqual<NeighborResult>({
          previousIssueKey: 'ATLAS-4',
          nextIssueKey: 'ATLAS-5',
        })
      }
    })
  })

  // S2 백로그→스프린트Y
  describe('S2: 백로그 → 스프린트 (할당)', () => {
    it('assign + rerank 액션을 반환한다', () => {
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
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-4',
        fromContext: 'sprint',
        fromSprintId: SPRINT_A_ID,
        toContext: 'backlog',
        toSprintId: null,
        targetKeys: BACKLOG_KEYS,
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
    it('백로그→백로그 같은 위치이면 noop을 반환한다', () => {
      // ATLAS-2는 인덱스 1 → 드롭 위치도 1~2 (자기 앞뒤)이면 제자리
      const action = resolveBacklogDropAction({
        issueKey: 'ATLAS-2',
        fromContext: 'backlog',
        fromSprintId: null,
        toContext: 'backlog',
        toSprintId: null,
        targetKeys: BACKLOG_KEYS,
        dropIndex: 1, // 바로 자기 자리
      })
      expect(action.kind).toBe('noop')
    })
  })
})
