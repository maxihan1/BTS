// 최근 프로젝트 MRU localStorage 영속 스토어 단위 테스트 — FR-UX-08 PR-A Task 1 (RED)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useRecentProjects, RECENT_PROJECTS_STORAGE_KEY, MAX_RECENT_PROJECTS } from '../use-recent-projects'

describe('useRecentProjects — MRU 상한 5 localStorage 영속 스토어 (FR1)', () => {
  beforeEach(() => {
    localStorage.clear()
    // zustand 스토어는 모듈 전역 싱글턴이라 테스트 간 상태가 유지된다 — 매 테스트 전 리셋
    // (use-active-project.test.ts·use-sidebar-collapsed.test.ts 선례)
    useRecentProjects.setState({ recentProjectKeys: [] })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('T-RP-1: 초기 상태는 빈 목록이다', () => {
    const { result } = renderHook(() => useRecentProjects())
    expect(result.current.recentProjectKeys).toEqual([])
  })

  it('T-RP-2: push 하면 목록 맨 앞에 들어가고 localStorage 에 함께 저장된다', () => {
    const { result } = renderHook(() => useRecentProjects())

    act(() => {
      result.current.pushRecentProject('ATLAS')
    })

    expect(result.current.recentProjectKeys).toEqual(['ATLAS'])
    expect(localStorage.getItem(RECENT_PROJECTS_STORAGE_KEY)).toBe(JSON.stringify(['ATLAS']))
  })

  it('T-RP-3: 나중에 push 한 것이 앞에 온다 (MRU 정렬)', () => {
    const { result } = renderHook(() => useRecentProjects())

    act(() => {
      result.current.pushRecentProject('ATLAS')
      result.current.pushRecentProject('INFRA')
    })

    expect(result.current.recentProjectKeys).toEqual(['INFRA', 'ATLAS'])
  })

  it('T-RP-4: 이미 목록에 있는 키를 push 하면 맨 앞으로 이동하고 중복되지 않는다', () => {
    const { result } = renderHook(() => useRecentProjects())

    act(() => {
      result.current.pushRecentProject('ATLAS')
      result.current.pushRecentProject('INFRA')
      result.current.pushRecentProject('ZETA')
      result.current.pushRecentProject('ATLAS')
    })

    expect(result.current.recentProjectKeys).toEqual(['ATLAS', 'ZETA', 'INFRA'])
  })

  it('T-RP-5 (E5): 이미 맨 앞인 키를 다시 push 하면 localStorage write 를 생략한다', () => {
    const { result } = renderHook(() => useRecentProjects())

    act(() => {
      result.current.pushRecentProject('ATLAS')
    })

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')

    act(() => {
      result.current.pushRecentProject('ATLAS')
    })

    expect(setItemSpy).not.toHaveBeenCalled()
    expect(result.current.recentProjectKeys).toEqual(['ATLAS'])
  })

  it('T-RP-6 (E6): 상한을 넘으면 가장 오래된 항목을 축출하고 길이가 정확히 상한이다', () => {
    const { result } = renderHook(() => useRecentProjects())

    act(() => {
      // 상한 + 1 개를 순서대로 넣는다 — P0 가 가장 오래된 항목이 된다
      for (let i = 0; i <= MAX_RECENT_PROJECTS; i += 1) {
        result.current.pushRecentProject(`P${String(i)}`)
      }
    })

    expect(result.current.recentProjectKeys).toHaveLength(MAX_RECENT_PROJECTS)
    expect(result.current.recentProjectKeys).not.toContain('P0')
    // 가장 최근 것이 맨 앞
    expect(result.current.recentProjectKeys[0]).toBe(`P${String(MAX_RECENT_PROJECTS)}`)
  })

  it('T-RP-7: 모듈 초기화 시 저장값을 복원한다 (실제 초기화 경로)', async () => {
    localStorage.setItem(RECENT_PROJECTS_STORAGE_KEY, JSON.stringify(['INFRA', 'ATLAS']))

    // 싱글턴이라 모듈을 재로드해야 초기화 코드가 다시 탄다
    vi.resetModules()
    const fresh = await import('../use-recent-projects')

    expect(fresh.useRecentProjects.getState().recentProjectKeys).toEqual(['INFRA', 'ATLAS'])
  })

  it('T-RP-8 (E4): 저장값이 배열이 아니면 빈 목록으로 폴백한다', async () => {
    localStorage.setItem(RECENT_PROJECTS_STORAGE_KEY, JSON.stringify('ATLAS'))

    vi.resetModules()
    const fresh = await import('../use-recent-projects')

    expect(fresh.useRecentProjects.getState().recentProjectKeys).toEqual([])
  })

  it('T-RP-9 (E4): 저장값 원소가 문자열이 아니면 빈 목록으로 폴백한다', async () => {
    localStorage.setItem(RECENT_PROJECTS_STORAGE_KEY, JSON.stringify(['ATLAS', 42]))

    vi.resetModules()
    const fresh = await import('../use-recent-projects')

    expect(fresh.useRecentProjects.getState().recentProjectKeys).toEqual([])
  })

  it('T-RP-10 (E4): 잘못된 JSON → 빈 목록 (fail-safe)', async () => {
    localStorage.setItem(RECENT_PROJECTS_STORAGE_KEY, '{not-valid-json')

    vi.resetModules()
    const fresh = await import('../use-recent-projects')

    expect(fresh.useRecentProjects.getState().recentProjectKeys).toEqual([])
  })

  it('T-RP-11 (E4): 저장값이 상한을 넘겨 변조돼도 상한까지만 복원한다', async () => {
    localStorage.setItem(
      RECENT_PROJECTS_STORAGE_KEY,
      JSON.stringify(['A', 'B', 'C', 'D', 'E', 'F', 'G']),
    )

    vi.resetModules()
    const fresh = await import('../use-recent-projects')

    expect(fresh.useRecentProjects.getState().recentProjectKeys).toHaveLength(MAX_RECENT_PROJECTS)
  })

  it('T-RP-12 (NFR2): localStorage 읽기가 throw 해도 빈 목록으로 폴백한다', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })

    vi.resetModules()
    const fresh = await import('../use-recent-projects')

    expect(fresh.useRecentProjects.getState().recentProjectKeys).toEqual([])
  })

  it('T-RP-13 (NFR2): localStorage 쓰기가 throw 해도 메모리 상태는 정상 갱신된다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    const { result } = renderHook(() => useRecentProjects())

    act(() => {
      result.current.pushRecentProject('ATLAS')
    })

    // 영속은 실패해도 이번 세션 동안의 최근 목록은 유지돼야 한다
    expect(result.current.recentProjectKeys).toEqual(['ATLAS'])
  })

  it('T-RP-14: 빈 문자열 키는 기록하지 않는다 (빈 스코프가 새지 않게)', () => {
    const { result } = renderHook(() => useRecentProjects())

    act(() => {
      result.current.pushRecentProject('')
    })

    expect(result.current.recentProjectKeys).toEqual([])
  })

  it('T-RP-15: 서로 다른 훅 인스턴스가 상태를 공유한다 (ShellLayout·스위처)', () => {
    const { result: instanceA } = renderHook(() => useRecentProjects())
    const { result: instanceB } = renderHook(() => useRecentProjects())

    act(() => {
      instanceA.current.pushRecentProject('ATLAS')
    })

    expect(instanceB.current.recentProjectKeys).toEqual(['ATLAS'])
  })
})
