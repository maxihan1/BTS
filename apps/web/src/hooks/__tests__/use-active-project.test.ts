// 활성 프로젝트 localStorage 영속 스토어 단위 테스트 — FR-UX-07 Task 2 (RED)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useActiveProject, ACTIVE_PROJECT_STORAGE_KEY } from '../use-active-project'

describe('useActiveProject — localStorage 영속 스토어', () => {
  beforeEach(() => {
    localStorage.clear()
    // zustand 스토어는 모듈 전역 싱글턴이라 테스트 간 상태가 유지된다 — 매 테스트 전 리셋
    // (use-sidebar-collapsed.test.ts 선례)
    useActiveProject.setState({ activeProjectKey: null })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('T-AP-1: localStorage 미설정 → null (해소는 호출자가 폴백으로 처리)', () => {
    const { result } = renderHook(() => useActiveProject())
    expect(result.current.activeProjectKey).toBeNull()
  })

  it('T-AP-2: setActiveProject 호출 시 상태와 localStorage 가 함께 갱신된다', () => {
    const { result } = renderHook(() => useActiveProject())

    act(() => {
      result.current.setActiveProject('INFRA')
    })

    expect(result.current.activeProjectKey).toBe('INFRA')
    expect(localStorage.getItem(ACTIVE_PROJECT_STORAGE_KEY)).toBe(JSON.stringify('INFRA'))
  })

  it('T-AP-3: 모듈 초기화 시 저장값을 복원한다 (실제 초기화 경로)', async () => {
    localStorage.setItem(ACTIVE_PROJECT_STORAGE_KEY, JSON.stringify('ATLAS'))

    // 싱글턴이라 모듈을 재로드해야 초기화 코드가 다시 탄다
    vi.resetModules()
    const fresh = await import('../use-active-project')

    expect(fresh.useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('T-AP-4 (E5): 문자열이 아닌 저장값 → null (fail-safe)', async () => {
    localStorage.setItem(ACTIVE_PROJECT_STORAGE_KEY, JSON.stringify(42))

    vi.resetModules()
    const fresh = await import('../use-active-project')

    expect(fresh.useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('T-AP-5 (E5): 잘못된 JSON → null (fail-safe)', async () => {
    localStorage.setItem(ACTIVE_PROJECT_STORAGE_KEY, '{not-valid-json')

    vi.resetModules()
    const fresh = await import('../use-active-project')

    expect(fresh.useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('T-AP-6 (E5): 빈 문자열 저장값 → null (빈 스코프가 백엔드로 새지 않게)', async () => {
    localStorage.setItem(ACTIVE_PROJECT_STORAGE_KEY, JSON.stringify(''))

    vi.resetModules()
    const fresh = await import('../use-active-project')

    expect(fresh.useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('T-AP-7 (NFR2): localStorage 읽기가 throw 해도 앱이 죽지 않고 null 로 폴백한다', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })

    vi.resetModules()
    const fresh = await import('../use-active-project')

    expect(fresh.useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('T-AP-8 (NFR2): localStorage 쓰기가 throw 해도 메모리 상태는 정상 갱신된다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    const { result } = renderHook(() => useActiveProject())

    act(() => {
      result.current.setActiveProject('INFRA')
    })

    // 영속은 실패해도 이번 세션 동안의 활성 프로젝트는 유지돼야 한다
    expect(result.current.activeProjectKey).toBe('INFRA')
  })

  it('T-AP-9 (E7): 같은 값을 다시 설정하면 localStorage write 를 생략한다', () => {
    const { result } = renderHook(() => useActiveProject())

    act(() => {
      result.current.setActiveProject('INFRA')
    })

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')

    act(() => {
      result.current.setActiveProject('INFRA')
    })

    expect(setItemSpy).not.toHaveBeenCalled()
    expect(result.current.activeProjectKey).toBe('INFRA')
  })

  it('T-AP-10: 서로 다른 훅 인스턴스가 상태를 공유한다 (ShellLayout·라우트 어댑터)', () => {
    const { result: instanceA } = renderHook(() => useActiveProject())
    const { result: instanceB } = renderHook(() => useActiveProject())

    act(() => {
      instanceA.current.setActiveProject('ATLAS')
    })

    expect(instanceB.current.activeProjectKey).toBe('ATLAS')
  })
})
