// 사이드바 접기 상태 localStorage 영속 훅 단위 테스트 (FR-UX-06 PR11 Task 3/8)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useSidebarCollapsed, SIDEBAR_COLLAPSED_STORAGE_KEY } from '../use-sidebar-collapsed'

describe('useSidebarCollapsed', () => {
  beforeEach(() => {
    localStorage.clear()
    // zustand 스토어는 모듈 전역 싱글턴이라 테스트 간 상태가 유지된다 — 매 테스트 전 리셋
    useSidebarCollapsed.setState({ collapsed: false })
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

  it('T-SC-3: 스토어 초기화 시 localStorage에 저장된 값(true)을 복원한다', async () => {
    localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, 'true')

    // zustand 스토어는 모듈 로드 시 1회만 localStorage를 읽는 싱글턴이라, 복원 로직을
    // 검증하려면 모듈을 재로드해 초기화 코드를 다시 태워야 한다(setState 시뮬레이션이 아닌
    // 실제 초기화 경로 검증).
    vi.resetModules()
    const fresh = await import('../use-sidebar-collapsed')

    expect(fresh.useSidebarCollapsed.getState().collapsed).toBe(true)
  })

  it('T-SC-4: 잘못된 JSON 저장값 → 펼침(collapsed=false, fail-safe)', async () => {
    localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, '{not-valid-json')

    vi.resetModules()
    const fresh = await import('../use-sidebar-collapsed')

    expect(fresh.useSidebarCollapsed.getState().collapsed).toBe(false)
  })

  it('T-SC-5: 서로 다른 훅 인스턴스(예: Sidebar·TopBar)가 collapsed 상태를 공유한다', () => {
    const { result: instanceA } = renderHook(() => useSidebarCollapsed())
    const { result: instanceB } = renderHook(() => useSidebarCollapsed())

    act(() => {
      instanceA.current.toggle()
    })

    expect(instanceB.current.collapsed).toBe(true)
  })
})
