// URL search ↔ IssueFilterParams 매핑 순수 함수 단위 테스트 (FR-SR-01 D6)
import { describe, it, expect } from 'vitest'
import {
  UNASSIGNED,
  toArray,
  searchToIssueFilter,
  issueFilterToSearch,
  isEmptyIssueFilter,
  normalizeIssueFilter,
} from './issue-filter'

// ─────────────────────────────────────────────────────────────────────────────
// toArray
// ─────────────────────────────────────────────────────────────────────────────
describe('toArray', () => {
  it('undefined를 빈 배열로 정규화한다', () => {
    expect(toArray(undefined)).toEqual([])
  })

  it('단일 문자열을 1-요소 배열로 정규화한다', () => {
    expect(toArray('open')).toEqual(['open'])
  })

  it('배열은 그대로 반환한다', () => {
    expect(toArray(['a', 'b'])).toEqual(['a', 'b'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// searchToIssueFilter
// ─────────────────────────────────────────────────────────────────────────────
describe('searchToIssueFilter', () => {
  it('완전한 search 객체를 올바르게 변환한다', () => {
    const result = searchToIssueFilter({
      status: ['open'],
      assignee: ['a1', UNASSIGNED],
      label: ['bug'],
      component: ['c1'],
    })
    expect(result).toEqual({
      statusKeys: ['open'],
      assigneeIds: ['a1'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: ['c1'],
    })
  })

  it('unassigned 센티널이 없으면 includeUnassigned=false', () => {
    const result = searchToIssueFilter({ assignee: ['a1'] })
    expect(result.includeUnassigned).toBe(false)
    expect(result.assigneeIds).toEqual(['a1'])
  })

  it('assignee=unassigned 만 있으면 assigneeIds=[] + includeUnassigned=true', () => {
    const result = searchToIssueFilter({ assignee: UNASSIGNED })
    expect(result.assigneeIds).toEqual([])
    expect(result.includeUnassigned).toBe(true)
  })

  it('단일 문자열 status도 배열로 정규화한다', () => {
    const result = searchToIssueFilter({ status: 'open' })
    expect(result.statusKeys).toEqual(['open'])
  })

  it('단일 문자열 label도 배열로 정규화한다', () => {
    const result = searchToIssueFilter({ label: 'bug' })
    expect(result.labels).toEqual(['bug'])
  })

  it('undefined 필드는 빈 배열로 처리한다', () => {
    const result = searchToIssueFilter({})
    expect(result).toEqual({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
  })

  it('component 단일 문자열도 배열로 정규화한다', () => {
    const result = searchToIssueFilter({ component: 'c1' })
    expect(result.componentIds).toEqual(['c1'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// issueFilterToSearch
// ─────────────────────────────────────────────────────────────────────────────
describe('issueFilterToSearch', () => {
  it('완전한 필터를 올바른 search 객체로 변환한다', () => {
    const result = issueFilterToSearch({
      statusKeys: ['open'],
      assigneeIds: ['a1'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: ['c1'],
    })
    expect(result.status).toEqual(['open'])
    expect(result.assignee).toEqual(['a1', UNASSIGNED])
    expect(result.label).toEqual(['bug'])
    expect(result.component).toEqual(['c1'])
  })

  it('includeUnassigned=true이면 assignee 배열 끝에 unassigned를 추가한다', () => {
    const result = issueFilterToSearch({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: true,
      labels: [],
      componentIds: [],
    })
    expect(result.assignee).toEqual([UNASSIGNED])
  })

  it('빈 statusKeys는 status 키를 생략한다', () => {
    const result = issueFilterToSearch({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    expect('status' in result).toBe(false)
  })

  it('빈 labels는 label 키를 생략한다', () => {
    const result = issueFilterToSearch({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    expect('label' in result).toBe(false)
  })

  it('빈 componentIds는 component 키를 생략한다', () => {
    const result = issueFilterToSearch({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    expect('component' in result).toBe(false)
  })

  it('빈 assigneeIds + includeUnassigned=false는 assignee 키를 생략한다', () => {
    const result = issueFilterToSearch({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    expect('assignee' in result).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// isEmptyIssueFilter
// ─────────────────────────────────────────────────────────────────────────────
describe('isEmptyIssueFilter', () => {
  it('모든 배열이 비어 있고 includeUnassigned=false이면 true를 반환한다', () => {
    expect(
      isEmptyIssueFilter({
        statusKeys: [],
        assigneeIds: [],
        includeUnassigned: false,
        labels: [],
        componentIds: [],
      }),
    ).toBe(true)
  })

  it('statusKeys에 값이 있으면 false를 반환한다', () => {
    expect(
      isEmptyIssueFilter({
        statusKeys: ['open'],
        assigneeIds: [],
        includeUnassigned: false,
        labels: [],
        componentIds: [],
      }),
    ).toBe(false)
  })

  it('includeUnassigned=true이면 false를 반환한다', () => {
    expect(
      isEmptyIssueFilter({
        statusKeys: [],
        assigneeIds: [],
        includeUnassigned: true,
        labels: [],
        componentIds: [],
      }),
    ).toBe(false)
  })

  it('labels에 값이 있으면 false를 반환한다', () => {
    expect(
      isEmptyIssueFilter({
        statusKeys: [],
        assigneeIds: [],
        includeUnassigned: false,
        labels: ['bug'],
        componentIds: [],
      }),
    ).toBe(false)
  })

  it('componentIds에 값이 있으면 false를 반환한다', () => {
    expect(
      isEmptyIssueFilter({
        statusKeys: [],
        assigneeIds: [],
        includeUnassigned: false,
        labels: [],
        componentIds: ['c1'],
      }),
    ).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// normalizeIssueFilter — B1 queryKey 안정성
// ─────────────────────────────────────────────────────────────────────────────
describe('normalizeIssueFilter', () => {
  it('키 순서만 다른 동일 assigneeIds → 동일한 정규화 결과', () => {
    const a = normalizeIssueFilter({
      statusKeys: [],
      assigneeIds: ['a2', 'a1'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    const b = normalizeIssueFilter({
      statusKeys: [],
      assigneeIds: ['a1', 'a2'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    expect(a).toEqual(b)
    expect(a.assigneeIds).toEqual(['a1', 'a2'])
  })

  it('키 순서만 다른 동일 statusKeys → 동일한 정규화 결과', () => {
    const a = normalizeIssueFilter({
      statusKeys: ['done', 'open'],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    const b = normalizeIssueFilter({
      statusKeys: ['open', 'done'],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
    expect(a).toEqual(b)
    expect(a.statusKeys).toEqual(['done', 'open'])
  })

  it('키 순서만 다른 동일 labels → 동일한 정규화 결과', () => {
    const a = normalizeIssueFilter({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: ['feat', 'bug'],
      componentIds: [],
    })
    const b = normalizeIssueFilter({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: ['bug', 'feat'],
      componentIds: [],
    })
    expect(a).toEqual(b)
    expect(a.labels).toEqual(['bug', 'feat'])
  })

  it('키 순서만 다른 동일 componentIds → 동일한 정규화 결과', () => {
    const a = normalizeIssueFilter({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: ['c2', 'c1'],
    })
    const b = normalizeIssueFilter({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: ['c1', 'c2'],
    })
    expect(a).toEqual(b)
    expect(a.componentIds).toEqual(['c1', 'c2'])
  })

  it('원본 배열을 변이하지 않는다 (부수효과 0)', () => {
    const original = {
      statusKeys: ['done', 'open'],
      assigneeIds: ['a2', 'a1'],
      includeUnassigned: false,
      labels: ['feat', 'bug'],
      componentIds: ['c2', 'c1'],
    }
    const originalStatusCopy = [...original.statusKeys]
    const originalAssigneeCopy = [...original.assigneeIds]
    const originalLabelsCopy = [...original.labels]
    const originalComponentsCopy = [...original.componentIds]

    normalizeIssueFilter(original)

    expect(original.statusKeys).toEqual(originalStatusCopy)
    expect(original.assigneeIds).toEqual(originalAssigneeCopy)
    expect(original.labels).toEqual(originalLabelsCopy)
    expect(original.componentIds).toEqual(originalComponentsCopy)
  })

  it('includeUnassigned는 그대로 보존한다', () => {
    const result = normalizeIssueFilter({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: true,
      labels: [],
      componentIds: [],
    })
    expect(result.includeUnassigned).toBe(true)
  })
})
