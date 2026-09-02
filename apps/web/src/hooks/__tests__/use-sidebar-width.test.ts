// 사이드바 폭 localStorage 영속 훅 단위 테스트 (Jira 패리티 J6)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  useSidebarWidth,
  SIDEBAR_WIDTH_STORAGE_KEY,
  MIN_SIDEBAR_WIDTH,
  MAX_SIDEBAR_WIDTH,
  DEFAULT_SIDEBAR_WIDTH,
} from '../use-sidebar-width'

describe('useSidebarWidth', () => {
  beforeEach(() => {
    localStorage.clear()
    // zustand 스토어는 모듈 전역 싱글턴이라 테스트 간 상태가 유지된다 — 매 테스트 전 리셋
    useSidebarWidth.setState({ width: DEFAULT_SIDEBAR_WIDTH })
  })

  it('T-SW-1: localStorage 미설정 → 기본 폭', () => {
    const { result } = renderHook(() => useSidebarWidth())
    expect(result.current.width).toBe(DEFAULT_SIDEBAR_WIDTH)
  })

  it('T-SW-2: 상수 3종이 서로 모순되지 않는다', () => {
    // 기본값이 범위 밖이면 첫 방문자가 clamp 된 폭을 보고, 저장하지 않았는데도 값이 바뀐다.
    expect(MIN_SIDEBAR_WIDTH).toBeLessThan(MAX_SIDEBAR_WIDTH)
    expect(DEFAULT_SIDEBAR_WIDTH).toBeGreaterThanOrEqual(MIN_SIDEBAR_WIDTH)
    expect(DEFAULT_SIDEBAR_WIDTH).toBeLessThanOrEqual(MAX_SIDEBAR_WIDTH)
  })

  it('T-SW-3: commitWidth 는 스토어와 localStorage 를 함께 갱신한다', () => {
    const { result } = renderHook(() => useSidebarWidth())

    act(() => {
      result.current.commitWidth(320)
    })

    expect(result.current.width).toBe(320)
    expect(localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY)).toBe('320')
  })

  it('T-SW-4: setWidth 는 스토어만 갱신하고 localStorage 를 건드리지 않는다', () => {
    // ★드래그 중 60fps 로 localStorage 를 두들기지 않기 위한 분리다. 이 단언이 사라지면
    //   포인터 이동마다 쓰기가 일어나도 아무도 모른다.
    const { result } = renderHook(() => useSidebarWidth())

    act(() => {
      result.current.setWidth(300)
    })

    expect(result.current.width).toBe(300)
    expect(localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY)).toBeNull()
  })

  it('T-SW-5: 범위 밖 입력을 clamp 한다 (setWidth·commitWidth 양쪽)', () => {
    const { result } = renderHook(() => useSidebarWidth())

    act(() => {
      result.current.setWidth(MAX_SIDEBAR_WIDTH + 500)
    })
    expect(result.current.width).toBe(MAX_SIDEBAR_WIDTH)

    act(() => {
      result.current.setWidth(MIN_SIDEBAR_WIDTH - 500)
    })
    expect(result.current.width).toBe(MIN_SIDEBAR_WIDTH)

    act(() => {
      result.current.commitWidth(MAX_SIDEBAR_WIDTH + 1)
    })
    expect(result.current.width).toBe(MAX_SIDEBAR_WIDTH)
    expect(localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY)).toBe(String(MAX_SIDEBAR_WIDTH))
  })

  it('T-SW-6: 유한수가 아닌 입력은 무시한다 (NaN 이 저장돼 폭이 사라지는 것을 막는다)', () => {
    const { result } = renderHook(() => useSidebarWidth())

    act(() => {
      result.current.setWidth(Number.NaN)
    })
    expect(result.current.width).toBe(DEFAULT_SIDEBAR_WIDTH)

    act(() => {
      result.current.commitWidth(Number.POSITIVE_INFINITY)
    })
    expect(result.current.width).toBe(DEFAULT_SIDEBAR_WIDTH)
    expect(localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY)).toBeNull()
  })

  it('T-SW-7: 스토어 초기화 시 저장된 폭을 복원한다', async () => {
    localStorage.setItem(SIDEBAR_WIDTH_STORAGE_KEY, '400')

    // zustand 스토어는 모듈 로드 시 1회만 localStorage 를 읽는 싱글턴이라, 복원 로직을
    // 검증하려면 모듈을 재로드해 초기화 코드를 다시 태워야 한다.
    vi.resetModules()
    const fresh = await import('../use-sidebar-width')

    expect(fresh.useSidebarWidth.getState().width).toBe(400)
  })

  it('T-SW-8: 잘못된 JSON 저장값 → 기본 폭 (fail-safe)', async () => {
    localStorage.setItem(SIDEBAR_WIDTH_STORAGE_KEY, '{not-valid-json')

    vi.resetModules()
    const fresh = await import('../use-sidebar-width')

    expect(fresh.useSidebarWidth.getState().width).toBe(DEFAULT_SIDEBAR_WIDTH)
  })

  it('T-SW-9: 숫자가 아닌 저장값 → 기본 폭', async () => {
    localStorage.setItem(SIDEBAR_WIDTH_STORAGE_KEY, '"wide"')

    vi.resetModules()
    const fresh = await import('../use-sidebar-width')

    expect(fresh.useSidebarWidth.getState().width).toBe(DEFAULT_SIDEBAR_WIDTH)
  })

  it('T-SW-10: 범위 밖 저장값도 복원 시점에 clamp 한다', async () => {
    // ★저장 시 clamp 하니까 읽을 때는 안 해도 된다는 판단이 함정이다. 상수를 좁히면
    //   이미 저장된 값이 새 범위 밖이 되고, 그 값은 아무도 다시 저장하지 않는다.
    localStorage.setItem(SIDEBAR_WIDTH_STORAGE_KEY, String(MAX_SIDEBAR_WIDTH + 1000))

    vi.resetModules()
    const fresh = await import('../use-sidebar-width')

    expect(fresh.useSidebarWidth.getState().width).toBe(MAX_SIDEBAR_WIDTH)
  })

  it('T-SW-11: 서로 다른 훅 인스턴스가 폭 상태를 공유한다', () => {
    const { result: instanceA } = renderHook(() => useSidebarWidth())
    const { result: instanceB } = renderHook(() => useSidebarWidth())

    act(() => {
      instanceA.current.commitWidth(360)
    })

    expect(instanceB.current.width).toBe(360)
  })
})
