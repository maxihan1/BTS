// 사이드바 접기 상태 localStorage 영속 훅 단위 테스트 (FR-UX-06 PR11 Task 3)
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useSidebarCollapsed, SIDEBAR_COLLAPSED_STORAGE_KEY } from '../use-sidebar-collapsed'

describe('useSidebarCollapsed', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('T-SC-1: localStorage 미설정 → 기본 펼침(collapsed=false)', () => {
    const { result } = renderHook(() => useSidebarCollapsed())
    expect(result.current.collapsed).toBe(false)
  })

  it('T-SC-2: toggle 호출 시 collapsed가 반전되고 localStorage에 저장된다', () => {
    const { result } = renderHook(() => useSidebarCollapsed())

    act(() => {
      result.current.toggle()
    })

    expect(result.current.collapsed).toBe(true)
    expect(localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY)).toBe('true')

    act(() => {
      result.current.toggle()
    })

    expect(result.current.collapsed).toBe(false)
    expect(localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY)).toBe('false')
  })

  it('T-SC-3: mount 시 localStorage에 저장된 값(true)을 복원한다', () => {
    localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, 'true')

    const { result } = renderHook(() => useSidebarCollapsed())

    expect(result.current.collapsed).toBe(true)
  })

  it('T-SC-4: 잘못된 JSON 저장값 → 펼침(collapsed=false, fail-safe)', () => {
    localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, '{not-valid-json')

    const { result } = renderHook(() => useSidebarCollapsed())

    expect(result.current.collapsed).toBe(false)
  })
})
