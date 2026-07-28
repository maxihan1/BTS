// 경로 파라미터 → 활성 프로젝트 기록기 테스트 — FR-UX-07 Task 5 (RED)
//
// 이 훅이 없으면 S4 가 성립하지 않는다 — /projects/INFRA/board 를 보다가 사이드바 "이슈"를
// 눌러도 INFRA 가 아니라 이전 저장값이 열린다. plan 독립 리뷰 BLOCKER B1.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useActiveProject } from '../use-active-project'
import { useTrackActiveProject } from '../use-track-active-project'

/** useParams 반환값 — 테스트마다 갈아끼운다 */
let mockParams: Record<string, string | undefined> = {}

vi.mock('@tanstack/react-router', () => ({
  useParams: () => mockParams,
}))

describe('useTrackActiveProject — 경로 파라미터 기록기 (B1)', () => {
  beforeEach(() => {
    localStorage.clear()
    useActiveProject.setState({ activeProjectKey: null })
    mockParams = {}
  })

  it('T-TR-1 (S4): /projects/$projectKey/* 경로의 키를 저장값에 기록한다', () => {
    mockParams = { projectKey: 'INFRA' }

    renderHook(() => useTrackActiveProject(true))

    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
  })

  it('T-TR-2: 경로 파라미터가 없으면 저장값을 건드리지 않는다 (/issues 등)', () => {
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    mockParams = {}

    renderHook(() => useTrackActiveProject(true))

    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('T-TR-3: 미인증이면 기록하지 않는다 (ShellLayout 비인증 분기)', () => {
    mockParams = { projectKey: 'INFRA' }

    renderHook(() => useTrackActiveProject(false))

    expect(useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('T-TR-4 (E7): 같은 프로젝트 재방문 시 localStorage write 를 생략한다', () => {
    mockParams = { projectKey: 'INFRA' }
    const { rerender } = renderHook(() => useTrackActiveProject(true))

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')
    rerender()

    expect(setItemSpy).not.toHaveBeenCalled()
    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
    setItemSpy.mockRestore()
  })

  it('T-TR-5: 경로 파라미터가 문자열이 아니면 무시한다 (방어)', () => {
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    mockParams = { projectKey: undefined }

    renderHook(() => useTrackActiveProject(true))

    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('T-TR-6: 빈 문자열 경로 파라미터는 무시한다 (빈 스코프 방지)', () => {
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    mockParams = { projectKey: '' }

    renderHook(() => useTrackActiveProject(true))

    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })
})
