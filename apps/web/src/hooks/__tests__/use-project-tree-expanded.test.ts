// 프로젝트 트리 펼침 집합 localStorage 영속 스토어 단위 테스트 — FR-UX-08 PR-A Task 2 (RED)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  useProjectTreeExpanded,
  PROJECT_TREE_EXPANDED_STORAGE_KEY,
} from '../use-project-tree-expanded'

describe('useProjectTreeExpanded — 펼침 집합 localStorage 영속 스토어 (FR5)', () => {
  beforeEach(() => {
    localStorage.clear()
    // 모듈 전역 zustand 싱글턴 — 테스트 간 누출 차단 (ProjectTree.test.tsx:100-102 선례).
    // ★ 이 리셋이 없으면 "나머지는 접힘" 계열 단언이 순서 의존이 되어
    //   거짓 실패와 거짓 통과가 둘 다 가능하다 (plan 리뷰 BLOCKER-2).
    useProjectTreeExpanded.setState({ expandedKeys: new Set<string>() })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('T-PE-1: 초기 상태는 빈 Set 이다', () => {
    const { result } = renderHook(() => useProjectTreeExpanded())
    expect(result.current.expandedKeys.size).toBe(0)
  })

  it('T-PE-2: toggle 로 펼치면 Set 에 들어가고 localStorage 에 배열로 저장된다', () => {
    const { result } = renderHook(() => useProjectTreeExpanded())

    act(() => {
      result.current.toggle('ATLAS')
    })

    expect(result.current.expandedKeys.has('ATLAS')).toBe(true)
    expect(localStorage.getItem(PROJECT_TREE_EXPANDED_STORAGE_KEY)).toBe(JSON.stringify(['ATLAS']))
  })

  it('T-PE-3: toggle 을 다시 부르면 접히고 저장값에서도 빠진다', () => {
    const { result } = renderHook(() => useProjectTreeExpanded())

    act(() => {
      result.current.toggle('ATLAS')
    })
    act(() => {
      result.current.toggle('ATLAS')
    })

    expect(result.current.expandedKeys.has('ATLAS')).toBe(false)
    expect(localStorage.getItem(PROJECT_TREE_EXPANDED_STORAGE_KEY)).toBe(JSON.stringify([]))
  })

  it('T-PE-4 (FR6 핵심 계약): expand 는 더하기만 하고 기존 원소를 제거하지 않는다', () => {
    const { result } = renderHook(() => useProjectTreeExpanded())

    act(() => {
      result.current.expand('INFRA')
    })
    act(() => {
      result.current.expand('ATLAS')
    })

    // ★ 이것이 FR-UX-06 PR12 FR5(덮어쓰기) 정정의 판별식이다.
    //   덮어쓰기가 남아 있으면 INFRA 가 사라진다.
    expect(result.current.expandedKeys.has('INFRA')).toBe(true)
    expect(result.current.expandedKeys.has('ATLAS')).toBe(true)
    expect(result.current.expandedKeys.size).toBe(2)
  })

  it('T-PE-5 (E5): 이미 펼쳐진 키에 expand 하면 localStorage write 를 생략한다', () => {
    const { result } = renderHook(() => useProjectTreeExpanded())

    act(() => {
      result.current.expand('ATLAS')
    })

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')

    act(() => {
      result.current.expand('ATLAS')
    })

    expect(setItemSpy).not.toHaveBeenCalled()
    expect(result.current.expandedKeys.has('ATLAS')).toBe(true)
  })

  it('T-PE-6: 빈 문자열 키는 펼침 집합에 넣지 않는다', () => {
    const { result } = renderHook(() => useProjectTreeExpanded())

    act(() => {
      result.current.expand('')
    })

    expect(result.current.expandedKeys.size).toBe(0)
  })

  it('T-PE-7: 모듈 초기화 시 저장값을 복원한다 (S4 — 새로고침 후 펼침 유지)', async () => {
    localStorage.setItem(
      PROJECT_TREE_EXPANDED_STORAGE_KEY,
      JSON.stringify(['ATLAS', 'INFRA']),
    )

    vi.resetModules()
    const fresh = await import('../use-project-tree-expanded')

    const restored = fresh.useProjectTreeExpanded.getState().expandedKeys
    expect(restored.has('ATLAS')).toBe(true)
    expect(restored.has('INFRA')).toBe(true)
    expect(restored.size).toBe(2)
  })

  it('T-PE-8 (NFR2): 저장값이 배열이 아니면 빈 Set 으로 폴백한다', async () => {
    localStorage.setItem(PROJECT_TREE_EXPANDED_STORAGE_KEY, JSON.stringify({ ATLAS: true }))

    vi.resetModules()
    const fresh = await import('../use-project-tree-expanded')

    expect(fresh.useProjectTreeExpanded.getState().expandedKeys.size).toBe(0)
  })

  it('T-PE-9 (NFR2): 저장값 원소가 문자열이 아니면 빈 Set 으로 폴백한다', async () => {
    localStorage.setItem(PROJECT_TREE_EXPANDED_STORAGE_KEY, JSON.stringify(['ATLAS', 7]))

    vi.resetModules()
    const fresh = await import('../use-project-tree-expanded')

    expect(fresh.useProjectTreeExpanded.getState().expandedKeys.size).toBe(0)
  })

  it('T-PE-10 (NFR2): 잘못된 JSON → 빈 Set (fail-safe)', async () => {
    localStorage.setItem(PROJECT_TREE_EXPANDED_STORAGE_KEY, '{not-valid-json')

    vi.resetModules()
    const fresh = await import('../use-project-tree-expanded')

    expect(fresh.useProjectTreeExpanded.getState().expandedKeys.size).toBe(0)
  })

  it('T-PE-11 (NFR2): localStorage 읽기가 throw 해도 빈 Set 으로 폴백한다', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })

    vi.resetModules()
    const fresh = await import('../use-project-tree-expanded')

    expect(fresh.useProjectTreeExpanded.getState().expandedKeys.size).toBe(0)
  })

  it('T-PE-12 (NFR2): localStorage 쓰기가 throw 해도 메모리 상태는 정상 갱신된다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })

    const { result } = renderHook(() => useProjectTreeExpanded())

    act(() => {
      result.current.expand('ATLAS')
    })

    expect(result.current.expandedKeys.has('ATLAS')).toBe(true)
  })

  it('T-PE-13 (NFR7): 상한이 없다 — 프로젝트를 많이 펼쳐도 전부 유지된다', () => {
    const { result } = renderHook(() => useProjectTreeExpanded())

    act(() => {
      for (let i = 0; i < 20; i += 1) {
        result.current.expand(`P${String(i)}`)
      }
    })

    expect(result.current.expandedKeys.size).toBe(20)
  })

  it('T-PE-14: 서로 다른 훅 인스턴스가 상태를 공유한다', () => {
    const { result: instanceA } = renderHook(() => useProjectTreeExpanded())
    const { result: instanceB } = renderHook(() => useProjectTreeExpanded())

    act(() => {
      instanceA.current.expand('ATLAS')
    })

    expect(instanceB.current.expandedKeys.has('ATLAS')).toBe(true)
  })
})
