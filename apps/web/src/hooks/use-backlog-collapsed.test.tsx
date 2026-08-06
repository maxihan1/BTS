// 백로그 섹션 접힘 상태 localStorage 영속 훅 단위 테스트 (FR-UX-13 F15 Task 5 — T-CL-1 · E6 · E7)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  useBacklogCollapsed,
  useBacklogCollapsedStore,
  backlogCollapsedStorageKey,
  EPIC_PANEL_SECTION_ID,
} from './use-backlog-collapsed'

const PROJECT = 'PROJ'
const OTHER_PROJECT = 'OTHER'

/** 저장된 접힘 id 목록을 그대로 읽는다(타입 미확정 — 파싱 결과를 단언용으로만 쓴다) */
function readStoredIds(projectKey: string): unknown {
  const raw = localStorage.getItem(backlogCollapsedStorageKey(projectKey))
  if (raw === null) return null
  return JSON.parse(raw) as unknown
}

describe('useBacklogCollapsed', () => {
  beforeEach(() => {
    localStorage.clear()
    // zustand 스토어는 모듈 전역 싱글턴이라 테스트 간 상태가 누출된다 — 매 테스트 전 리셋
    useBacklogCollapsedStore.setState({ byProject: {} })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('T-CL-1a: 저장값이 없으면 모든 섹션이 펼침이다', () => {
    const { result } = renderHook(() => useBacklogCollapsed(PROJECT))

    expect(result.current.isCollapsed('backlog')).toBe(false)
    expect(result.current.isCollapsed('sprint-1')).toBe(false)
  })

  it('T-CL-1b: toggle 하면 접히고 프로젝트별 키로 localStorage에 저장된다', () => {
    const { result } = renderHook(() => useBacklogCollapsed(PROJECT))

    act(() => {
      result.current.toggle('sprint-7')
    })

    expect(result.current.isCollapsed('sprint-7')).toBe(true)
    expect(localStorage.getItem('bts.backlog.collapsed.PROJ')).toBe(JSON.stringify(['sprint-7']))

    act(() => {
      result.current.toggle('sprint-7')
    })

    expect(result.current.isCollapsed('sprint-7')).toBe(false)
    expect(readStoredIds(PROJECT)).toEqual([])
  })

  it('T-CL-1c: 재마운트해도 접힘이 유지된다', () => {
    const first = renderHook(() => useBacklogCollapsed(PROJECT))
    act(() => {
      first.result.current.toggle('backlog')
    })
    first.unmount()

    const second = renderHook(() => useBacklogCollapsed(PROJECT))

    expect(second.result.current.isCollapsed('backlog')).toBe(true)
  })

  it('T-CL-1d: 새로고침(모듈 재로드) 후에도 localStorage에서 접힘을 복원한다', async () => {
    localStorage.setItem(backlogCollapsedStorageKey(PROJECT), JSON.stringify(['backlog']))

    // 스토어는 모듈 전역 싱글턴이라, 메모리 상태가 아니라 localStorage 복원 경로를
    // 검증하려면 모듈을 재로드해 스토어를 비운 채로 다시 태워야 한다(가짜 통과 차단).
    vi.resetModules()
    const fresh = await import('./use-backlog-collapsed')
    const { result } = renderHook(() => fresh.useBacklogCollapsed(PROJECT))

    expect(result.current.isCollapsed('backlog')).toBe(true)
  })

  it('T-CL-1e: localStorage 읽기가 예외를 던져도 전부 펼침으로 렌더한다 (E6)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked')
    })

    const { result } = renderHook(() => useBacklogCollapsed(PROJECT))

    expect(result.current.isCollapsed('backlog')).toBe(false)
    expect(result.current.isCollapsed('sprint-1')).toBe(false)
  })

  it('T-CL-1f: localStorage 쓰기가 예외를 던져도 메모리 상태는 접힘으로 유지된다 (E6)', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })

    const { result } = renderHook(() => useBacklogCollapsed(PROJECT))

    act(() => {
      result.current.toggle('backlog')
    })

    expect(result.current.isCollapsed('backlog')).toBe(true)
  })

  it('T-CL-1g: 깨진 JSON 저장값이면 전부 펼침이다', () => {
    localStorage.setItem(backlogCollapsedStorageKey(PROJECT), '{not-valid-json')

    const { result } = renderHook(() => useBacklogCollapsed(PROJECT))

    expect(result.current.isCollapsed('backlog')).toBe(false)
  })

  it('T-CL-1h: 배열이 아니거나 문자열이 아닌 원소가 섞인 저장값이면 전부 펼침이다', () => {
    localStorage.setItem(backlogCollapsedStorageKey(PROJECT), JSON.stringify({ backlog: true }))
    const object = renderHook(() => useBacklogCollapsed(PROJECT))
    expect(object.result.current.isCollapsed('backlog')).toBe(false)
    object.unmount()

    localStorage.setItem(backlogCollapsedStorageKey(PROJECT), JSON.stringify(['backlog', 7]))
    useBacklogCollapsedStore.setState({ byProject: {} })
    const mixed = renderHook(() => useBacklogCollapsed(PROJECT))
    expect(mixed.result.current.isCollapsed('backlog')).toBe(false)
  })

  it('T-CL-1i: 접힘 상태는 프로젝트별로 분리된다', () => {
    const proj = renderHook(() => useBacklogCollapsed(PROJECT))
    const other = renderHook(() => useBacklogCollapsed(OTHER_PROJECT))

    act(() => {
      proj.result.current.toggle('backlog')
    })

    expect(proj.result.current.isCollapsed('backlog')).toBe(true)
    expect(other.result.current.isCollapsed('backlog')).toBe(false)
    expect(readStoredIds(OTHER_PROJECT)).toBeNull()
  })

  it('T-CL-1j: 같은 프로젝트의 두 훅 인스턴스가 접힘 상태를 공유한다', () => {
    const sprintSection = renderHook(() => useBacklogCollapsed(PROJECT))
    const backlogSection = renderHook(() => useBacklogCollapsed(PROJECT))

    act(() => {
      sprintSection.result.current.toggle('sprint-3')
    })

    expect(backlogSection.result.current.isCollapsed('sprint-3')).toBe(true)
  })

  it('E7: 이제 없는 sprint id가 저장돼 있어도 무시하고 동작하며 정리하지 않는다', () => {
    localStorage.setItem(
      backlogCollapsedStorageKey(PROJECT),
      JSON.stringify(['sprint-999', 'backlog']),
    )

    const { result } = renderHook(() => useBacklogCollapsed(PROJECT))

    expect(result.current.isCollapsed('backlog')).toBe(true)
    expect(result.current.isCollapsed('sprint-1')).toBe(false)

    act(() => {
      result.current.toggle('sprint-1')
    })

    expect(result.current.isCollapsed('sprint-1')).toBe(true)
    // 사라진 스프린트 id는 그대로 남는다 — 정리는 하지 않는다(E7)
    expect(readStoredIds(PROJECT)).toEqual(['sprint-999', 'backlog', 'sprint-1'])
  })

  // ── FR-UX-13 F16 Task 4 — 에픽 패널 접기 키 추가 ──────────────────────────
  //
  // 패널은 droppable 이 아니지만 접힘 영속 요구가 백로그·스프린트 섹션과 완전히 같다.
  // 새 훅·새 localStorage 키를 만들면 프로젝트별 분리와 fail-safe 폴백(E6)이 두 벌로
  // 갈리므로 **같은 스토어의 섹션 id 를 하나 추가**하는 것으로 끝낸다.

  it('F16-4a: 에픽 패널 키는 섹션 id 공간에서 백로그·스프린트와 충돌하지 않는다', () => {
    expect(EPIC_PANEL_SECTION_ID).not.toBe('backlog')
    expect(EPIC_PANEL_SECTION_ID.startsWith('sprint-')).toBe(false)
    // 비-공허 짝 — 키가 빈 문자열이면 위 두 단언이 조용히 통과한다
    expect(EPIC_PANEL_SECTION_ID.length).toBeGreaterThan(0)
  })

  it('F16-4b: 에픽 패널 접힘도 같은 프로젝트별 키에 저장되고 프로젝트마다 독립이다', () => {
    const proj = renderHook(() => useBacklogCollapsed(PROJECT))
    const other = renderHook(() => useBacklogCollapsed(OTHER_PROJECT))

    act(() => {
      proj.result.current.toggle(EPIC_PANEL_SECTION_ID)
    })

    expect(proj.result.current.isCollapsed(EPIC_PANEL_SECTION_ID)).toBe(true)
    expect(other.result.current.isCollapsed(EPIC_PANEL_SECTION_ID)).toBe(false)
    expect(readStoredIds(PROJECT)).toEqual([EPIC_PANEL_SECTION_ID])
    expect(readStoredIds(OTHER_PROJECT)).toBeNull()
  })

  it('F16-4c: 에픽 패널을 접어도 백로그·스프린트 섹션 접힘은 그대로다', () => {
    const { result } = renderHook(() => useBacklogCollapsed(PROJECT))

    act(() => {
      result.current.toggle(EPIC_PANEL_SECTION_ID)
    })

    expect(result.current.isCollapsed('backlog')).toBe(false)
    expect(result.current.isCollapsed('sprint-1')).toBe(false)
  })
})
