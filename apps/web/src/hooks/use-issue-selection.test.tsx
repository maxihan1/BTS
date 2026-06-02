// 이슈 다중 선택 상태 훅 단위 테스트 — 페이지 교차 누적 선택 동작 검증
import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useIssueSelection } from './use-issue-selection'

describe('useIssueSelection', () => {
  it('T-IS-SEL-1: 초기 상태는 빈 선택이다', () => {
    const { result } = renderHook(() => useIssueSelection())
    expect(result.current.selectedKeys).toEqual([])
    expect(result.current.count).toBe(0)
    expect(result.current.isSelected('ISSUE-1')).toBe(false)
  })

  it('T-IS-SEL-2: toggle로 키를 추가할 수 있다', () => {
    const { result } = renderHook(() => useIssueSelection())
    act(() => {
      result.current.toggle('ISSUE-1')
    })
    expect(result.current.isSelected('ISSUE-1')).toBe(true)
    expect(result.current.count).toBe(1)
  })

  it('T-IS-SEL-3: 이미 선택된 키를 toggle하면 제거된다', () => {
    const { result } = renderHook(() => useIssueSelection())
    act(() => {
      result.current.toggle('ISSUE-1')
    })
    act(() => {
      result.current.toggle('ISSUE-1')
    })
    expect(result.current.isSelected('ISSUE-1')).toBe(false)
    expect(result.current.count).toBe(0)
  })

  it('T-IS-SEL-4: selectAllOnPage가 현재 페이지 키를 모두 추가한다', () => {
    const { result } = renderHook(() => useIssueSelection())
    act(() => {
      result.current.selectAllOnPage(['ISSUE-1', 'ISSUE-2', 'ISSUE-3'])
    })
    expect(result.current.isSelected('ISSUE-1')).toBe(true)
    expect(result.current.isSelected('ISSUE-2')).toBe(true)
    expect(result.current.isSelected('ISSUE-3')).toBe(true)
    expect(result.current.count).toBe(3)
  })

  it('T-IS-SEL-5: selectAllOnPage가 기존 선택을 유지하면서 추가한다 (페이지 교차 누적)', () => {
    const { result } = renderHook(() => useIssueSelection())
    // 1페이지 선택
    act(() => {
      result.current.selectAllOnPage(['ISSUE-1', 'ISSUE-2'])
    })
    // 2페이지 선택 추가
    act(() => {
      result.current.selectAllOnPage(['ISSUE-3', 'ISSUE-4'])
    })
    expect(result.current.isSelected('ISSUE-1')).toBe(true)
    expect(result.current.isSelected('ISSUE-2')).toBe(true)
    expect(result.current.isSelected('ISSUE-3')).toBe(true)
    expect(result.current.isSelected('ISSUE-4')).toBe(true)
    expect(result.current.count).toBe(4)
  })

  it('T-IS-SEL-6: clearPageSelection이 현재 페이지 키만 제거하고 다른 페이지 선택은 유지한다', () => {
    const { result } = renderHook(() => useIssueSelection())
    // 두 페이지 모두 선택
    act(() => {
      result.current.selectAllOnPage(['ISSUE-1', 'ISSUE-2'])
    })
    act(() => {
      result.current.selectAllOnPage(['ISSUE-3', 'ISSUE-4'])
    })
    // 1페이지만 해제
    act(() => {
      result.current.clearPageSelection(['ISSUE-1', 'ISSUE-2'])
    })
    expect(result.current.isSelected('ISSUE-1')).toBe(false)
    expect(result.current.isSelected('ISSUE-2')).toBe(false)
    expect(result.current.isSelected('ISSUE-3')).toBe(true)
    expect(result.current.isSelected('ISSUE-4')).toBe(true)
    expect(result.current.count).toBe(2)
  })

  it('T-IS-SEL-7: clearAll이 전체 선택을 해제한다', () => {
    const { result } = renderHook(() => useIssueSelection())
    act(() => {
      result.current.selectAllOnPage(['ISSUE-1', 'ISSUE-2', 'ISSUE-3'])
    })
    act(() => {
      result.current.clearAll()
    })
    expect(result.current.count).toBe(0)
    expect(result.current.selectedKeys).toEqual([])
  })

  it('T-IS-SEL-8: selectedKeys는 선택된 키 배열을 반환한다', () => {
    const { result } = renderHook(() => useIssueSelection())
    act(() => {
      result.current.toggle('ISSUE-2')
    })
    act(() => {
      result.current.toggle('ISSUE-1')
    })
    expect(result.current.selectedKeys).toContain('ISSUE-1')
    expect(result.current.selectedKeys).toContain('ISSUE-2')
    expect(result.current.selectedKeys).toHaveLength(2)
  })

  it('T-IS-SEL-9: 페이지 이동 시뮬레이션 — A페이지 선택 후 B페이지 추가해도 A 유지', () => {
    const { result } = renderHook(() => useIssueSelection())
    const pageA = ['PROJ-1', 'PROJ-2', 'PROJ-3']
    const pageB = ['PROJ-4', 'PROJ-5', 'PROJ-6']

    // A페이지 선택
    act(() => {
      result.current.selectAllOnPage(pageA)
    })
    // B페이지로 이동 후 B페이지 선택
    act(() => {
      result.current.selectAllOnPage(pageB)
    })

    // A페이지 선택 여전히 유지
    for (const key of pageA) {
      expect(result.current.isSelected(key)).toBe(true)
    }
    // B페이지도 선택됨
    for (const key of pageB) {
      expect(result.current.isSelected(key)).toBe(true)
    }
    expect(result.current.count).toBe(6)
  })
})
