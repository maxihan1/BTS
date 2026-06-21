// URL search params ↔ BoardCardFilterParams 매핑 유틸 테스트 (FR-BD-02)
import { describe, it, expect } from 'vitest'
import {
  searchToFilter,
  filterToSearch,
  isEmptyFilter,
  type BoardFilterSearch,
} from './board-filter'
import type { BoardCardFilterParams } from '../api/boards'

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
