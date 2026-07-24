// 필터 바 공통 라벨 단일 출처 + 이슈/보드 파생 shim 단위 테스트 — FR-UX-06 PR17 Task 1

import { describe, it, expect } from 'vitest'
import { filterBarLabels } from './filter-bar-labels'
import { issueFilterLabels } from './issue-filter-labels'
import { boardFilterLabels } from './board-filter-labels'

describe('filterBarLabels — 공통 단일 출처', () => {
  describe('filter 그룹', () => {
    it('assigneeLabel 키가 존재한다', () => {
      expect(filterBarLabels.filter.assigneeLabel).toBe('담당자')
    })

    it('assigneePlaceholder 키가 존재한다', () => {
      expect(filterBarLabels.filter.assigneePlaceholder).toBe('담당자 검색...')
    })

    it('unassigned 키가 존재한다', () => {
      expect(filterBarLabels.filter.unassigned).toBe('미배정')
    })

    it('labelLabel 키가 존재한다', () => {
      expect(filterBarLabels.filter.labelLabel).toBe('라벨')
    })

    it('labelPlaceholder 키가 존재한다', () => {
      expect(filterBarLabels.filter.labelPlaceholder).toBe('라벨 검색...')
    })

    it('componentLabel 키가 존재한다', () => {
      expect(filterBarLabels.filter.componentLabel).toBe('컴포넌트')
    })

    it('reset 키가 존재한다', () => {
      expect(filterBarLabels.filter.reset).toBe('초기화')
    })

    it('statusLabel 키는 공통 출처에 없다 (이슈 전용)', () => {
      expect('statusLabel' in filterBarLabels.filter).toBe(false)
    })
  })

  describe('chip 그룹', () => {
    it('removeAriaLabel(name)이 "{name} 제거"를 반환한다', () => {
      expect(filterBarLabels.chip.removeAriaLabel('bug')).toBe('bug 제거')
    })
  })

  describe('count 그룹', () => {
    it('applied(n)이 "N개 적용 중"을 반환한다', () => {
      expect(filterBarLabels.count.applied(3)).toBe('3개 적용 중')
    })
  })

  describe('search 그룹', () => {
    it('noResults 키가 존재한다', () => {
      expect(filterBarLabels.search.noResults).toBe('검색 결과 없음')
    })

    it('loading 키가 존재한다', () => {
      expect(filterBarLabels.search.loading).toBe('검색 중...')
    })
  })
})

describe('issueFilterLabels — filterBarLabels 파생 (statusLabel 추가)', () => {
  it('statusLabel이 "상태"이다', () => {
    expect(issueFilterLabels.filter.statusLabel).toBe('상태')
  })

  it('공통 filter 필드를 그대로 보존한다', () => {
    expect(issueFilterLabels.filter.assigneeLabel).toBe(filterBarLabels.filter.assigneeLabel)
    expect(issueFilterLabels.filter.assigneePlaceholder).toBe(filterBarLabels.filter.assigneePlaceholder)
    expect(issueFilterLabels.filter.unassigned).toBe(filterBarLabels.filter.unassigned)
    expect(issueFilterLabels.filter.labelLabel).toBe(filterBarLabels.filter.labelLabel)
    expect(issueFilterLabels.filter.labelPlaceholder).toBe(filterBarLabels.filter.labelPlaceholder)
    expect(issueFilterLabels.filter.componentLabel).toBe(filterBarLabels.filter.componentLabel)
    expect(issueFilterLabels.filter.reset).toBe(filterBarLabels.filter.reset)
  })

  it('chip.removeAriaLabel을 그대로 보존한다', () => {
    expect(issueFilterLabels.chip.removeAriaLabel('bug')).toBe('bug 제거')
  })

  it('count.applied를 그대로 보존한다', () => {
    expect(issueFilterLabels.count.applied(3)).toBe('3개 적용 중')
  })

  it('search 그룹을 그대로 보존한다', () => {
    expect(issueFilterLabels.search.noResults).toBe('검색 결과 없음')
    expect(issueFilterLabels.search.loading).toBe('검색 중...')
  })
})

describe('boardFilterLabels — filterBarLabels 별칭 재export (statusLabel 없음)', () => {
  it('statusLabel 키가 없다', () => {
    expect('statusLabel' in boardFilterLabels.filter).toBe(false)
  })

  it('공통 filter 필드를 그대로 노출한다', () => {
    expect(boardFilterLabels.filter.assigneeLabel).toBe('담당자')
    expect(boardFilterLabels.filter.reset).toBe('초기화')
  })

  it('filterBarLabels와 동일 참조이다', () => {
    expect(boardFilterLabels).toBe(filterBarLabels)
  })
})
