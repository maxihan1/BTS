// URL search params ↔ BoardCardFilterParams 매핑 유틸 테스트 (FR-BD-02, FR-UX-01)
import { describe, it, expect } from 'vitest'
import type { BoardDetail } from '../api/boards'
import {
  searchBoardCards,
  searchToFilter,
  filterToSearch,
  isEmptyFilter,
  queryStringToSearch,
  type BoardFilterSearch,
} from './board-filter'
import { buildBoardFilterQuery, type BoardCardFilterParams } from '../api/boards'

describe('searchToFilter', () => {
  it('assignee 배열에서 unassigned 센티널을 분리해 includeUnassigned=true로 변환한다', () => {
    const result = searchToFilter({
      assignee: ['a1', 'unassigned'],
      label: ['bug'],
      component: ['c1'],
    })
    expect(result).toEqual<BoardCardFilterParams>({
      assigneeIds: ['a1'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: ['c1'],
    })
  })

  it('단일 문자열 값도 배열로 정규화한다', () => {
    const result = searchToFilter({ assignee: 'a1' })
    expect(result.assigneeIds).toEqual(['a1'])
    expect(result.includeUnassigned).toBe(false)
    expect(result.labels).toEqual([])
    expect(result.componentIds).toEqual([])
  })

  it('undefined 필드는 빈 배열로 정규화한다', () => {
    const result = searchToFilter({})
    expect(result).toEqual<BoardCardFilterParams>({
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
  })

  it('assignee=unassigned 단독이면 assigneeIds=[], includeUnassigned=true', () => {
    const result = searchToFilter({ assignee: 'unassigned' })
    expect(result.assigneeIds).toEqual([])
    expect(result.includeUnassigned).toBe(true)
  })
})

describe('filterToSearch', () => {
  it('assigneeIds와 includeUnassigned=true를 합쳐 assignee 배열로 변환한다', () => {
    const result = filterToSearch({
      assigneeIds: ['a1'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: [],
    })
    expect(result).toEqual<BoardFilterSearch>({
      assignee: ['a1', 'unassigned'],
      label: ['bug'],
    })
  })

  it('빈 배열/false 필드의 키는 생략한다', () => {
    const result = filterToSearch({
      assigneeIds: ['a1'],
      includeUnassigned: false,
      labels: [],
      componentIds: ['c1'],
    })
    expect(result).toEqual<BoardFilterSearch>({
      assignee: ['a1'],
      component: ['c1'],
    })
    // label 키 자체가 없어야 한다
    expect('label' in result).toBe(false)
  })

  it('빈 필터이면 {}를 반환한다 (모든 키 생략)', () => {
    const result = filterToSearch({
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    expect(result).toEqual({})
    expect(Object.keys(result)).toHaveLength(0)
  })

  it('includeUnassigned=true 단독이면 assignee:[unassigned]만 반환한다', () => {
    const result = filterToSearch({
      assigneeIds: [],
      includeUnassigned: true,
      labels: [],
      componentIds: [],
    })
    expect(result).toEqual<BoardFilterSearch>({ assignee: ['unassigned'] })
  })
})

describe('isEmptyFilter', () => {
  it('모든 배열이 비고 includeUnassigned=false이면 true를 반환한다', () => {
    expect(
      isEmptyFilter({
        assigneeIds: [],
        includeUnassigned: false,
        labels: [],
        componentIds: [],
      }),
    ).toBe(true)
  })

  it('assigneeIds에 값이 있으면 false를 반환한다', () => {
    expect(
      isEmptyFilter({
        assigneeIds: ['a1'],
        includeUnassigned: false,
        labels: [],
        componentIds: [],
      }),
    ).toBe(false)
  })

  it('includeUnassigned=true이면 false를 반환한다', () => {
    expect(
      isEmptyFilter({
        assigneeIds: [],
        includeUnassigned: true,
        labels: [],
        componentIds: [],
      }),
    ).toBe(false)
  })

  it('labels에 값이 있으면 false를 반환한다', () => {
    expect(
      isEmptyFilter({
        assigneeIds: [],
        includeUnassigned: false,
        labels: ['bug'],
        componentIds: [],
      }),
    ).toBe(false)
  })
})

describe('round-trip', () => {
  it('searchToFilter(filterToSearch(f))가 원본 필터와 동등하다', () => {
    const original: BoardCardFilterParams = {
      assigneeIds: ['a1', 'a2'],
      includeUnassigned: true,
      labels: ['bug', 'feature'],
      componentIds: ['c1'],
    }
    const roundTripped = searchToFilter(filterToSearch(original))
    expect(roundTripped).toEqual(original)
  })

  it('빈 필터는 round-trip 후에도 빈 필터다', () => {
    const empty: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    expect(searchToFilter(filterToSearch(empty))).toEqual(empty)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// queryStringToSearch (FR-UX-01) — 퀵필터 저장 query 문자열 → URL search 역변환
// ─────────────────────────────────────────────────────────────────────────────

describe('queryStringToSearch', () => {
  it('assignee/label/component 파라미터를 배열로 파싱한다', () => {
    const result = queryStringToSearch('assignee=a1&assignee=unassigned&label=bug&component=c1')
    expect(result).toEqual<BoardFilterSearch>({
      assignee: ['a1', 'unassigned'],
      label: ['bug'],
      component: ['c1'],
    })
  })

  it('+ 기호가 공백으로 복원된다 (x-www-form-urlencoded 인코딩 계약, 리뷰 B1 함정)', () => {
    const result = queryStringToSearch('label=my+bug')
    expect(result.label).toEqual(['my bug'])
  })

  it('빈 문자열이면 빈 객체를 반환한다 (필드 키 생략)', () => {
    const result = queryStringToSearch('')
    expect(result).toEqual({})
    expect(Object.keys(result)).toHaveLength(0)
  })

  it('일부 필드만 있으면 나머지 키는 생략한다', () => {
    const result = queryStringToSearch('assignee=a1')
    expect(result).toEqual<BoardFilterSearch>({ assignee: ['a1'] })
    expect('label' in result).toBe(false)
    expect('component' in result).toBe(false)
  })

  it('unassigned 센티널이 assignee 배열에 그대로 보존된다', () => {
    const result = queryStringToSearch('assignee=unassigned')
    expect(result.assignee).toEqual(['unassigned'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// round-trip — buildBoardFilterQuery → queryStringToSearch → searchToFilter (FR-UX-01)
// 퀵필터 저장(serialize) → 적용(deserialize) 경로의 프론트 절반을 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('round-trip — buildBoardFilterQuery → queryStringToSearch → searchToFilter', () => {
  it('공백 포함 라벨("my bug")을 포함한 필터가 왕복 후 원본과 동등하다', () => {
    const original: BoardCardFilterParams = {
      assigneeIds: ['a1a1a1a1-b2b2-4c3c-8d4d-e5e5e5e5e5e5'],
      includeUnassigned: true,
      labels: ['my bug', 'feature'],
      componentIds: ['c1c1c1c1-d2d2-4e3e-8f4f-a5a5a5a5a5a5'],
    }
    const qs = buildBoardFilterQuery(original)
    const stripped = qs.startsWith('?') ? qs.slice(1) : qs
    const roundTripped = searchToFilter(queryStringToSearch(stripped))
    expect(roundTripped).toEqual(original)
  })

  it('빈 필터는 왕복 후에도 빈 필터다', () => {
    const empty: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    const qs = buildBoardFilterQuery(empty)
    expect(qs).toBe('')
    const roundTripped = searchToFilter(queryStringToSearch(qs))
    expect(roundTripped).toEqual(empty)
  })

  it('assigneeIds만 있는 필터가 왕복 후 동등하다', () => {
    const original: BoardCardFilterParams = {
      assigneeIds: ['a1a1a1a1-b2b2-4c3c-8d4d-e5e5e5e5e5e5', 'a2a2a2a2-b2b2-4c3c-8d4d-e5e5e5e5e5e5'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    const qs = buildBoardFilterQuery(original)
    const stripped = qs.startsWith('?') ? qs.slice(1) : qs
    const roundTripped = searchToFilter(queryStringToSearch(stripped))
    expect(roundTripped).toEqual(original)
  })
})

describe('searchBoardCards — 보드 텍스트 검색 (JB-B-4)', () => {
  const card = (issueKey: string, summary: string) =>
    ({ issueKey, summary }) as BoardDetail['columns'][number]['cards'][number]
  const board = {
    columns: [
      { columnId: 'c1', cards: [card('ATLAS-1', '로그인 버그'), card('ATLAS-2', '결제 화면')] },
      { columnId: 'c2', cards: [card('ATLAS-12', '로그아웃 이동')] },
    ],
  } as unknown as BoardDetail

  it('요약에 검색어가 든 카드만 남긴다 — 컬럼은 그대로', () => {
    const result = searchBoardCards(board, '로그')
    expect(result.columns.map((c) => c.cards.map((x) => x.issueKey))).toEqual([['ATLAS-1'], ['ATLAS-12']])
  })

  it('이슈 키로도 찾고 대소문자 · 앞뒤 공백을 가리지 않는다', () => {
    const result = searchBoardCards(board, '  atlas-2 ')
    expect(result.columns.map((c) => c.cards.map((x) => x.issueKey))).toEqual([['ATLAS-2'], []])
  })

  it('검색어가 비면 원본 참조를 그대로 돌려준다 (하위 memo 보호)', () => {
    expect(searchBoardCards(board, '   ')).toBe(board)
  })
})
