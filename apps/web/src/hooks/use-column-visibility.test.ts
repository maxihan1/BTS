// 컬럼 표시 상태 localStorage 영속 훅 단위 테스트 (FR-UX-06 Phase 5 PR18 Task 3)
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useColumnVisibility } from './use-column-visibility'

const STORAGE_KEY = 'bts.test.issue-table.columns'
const ALL_COLUMN_KEYS = ['key', 'summary', 'status', 'assignee', 'priority', 'updatedAt']
const REQUIRED_KEYS = ['key', 'summary']
const DEFAULT_VISIBLE = ['key', 'summary', 'status', 'assignee']

describe('useColumnVisibility', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('T-CV-1: localStorage 미설정 시 초기값은 defaultVisible이다', () => {
    const { result } = renderHook(() =>
      useColumnVisibility(STORAGE_KEY, ALL_COLUMN_KEYS, REQUIRED_KEYS, DEFAULT_VISIBLE),
    )

    expect(result.current.visible).toEqual(DEFAULT_VISIBLE)
    expect(result.current.isVisible('key')).toBe(true)
    expect(result.current.isVisible('priority')).toBe(false)
  })

  it('T-CV-2: toggle 호출 시 선택 컬럼(비필수)이 켜지고 다시 호출하면 꺼진다', () => {
    const { result } = renderHook(() =>
      useColumnVisibility(STORAGE_KEY, ALL_COLUMN_KEYS, REQUIRED_KEYS, DEFAULT_VISIBLE),
    )

    act(() => {
      result.current.toggle('priority')
    })
    expect(result.current.isVisible('priority')).toBe(true)

    act(() => {
      result.current.toggle('priority')
    })
    expect(result.current.isVisible('priority')).toBe(false)
  })

  it('T-CV-3: 토글 결과가 localStorage에 저장되고 재마운트 시 복원된다', () => {
    const first = renderHook(() =>
      useColumnVisibility(STORAGE_KEY, ALL_COLUMN_KEYS, REQUIRED_KEYS, DEFAULT_VISIBLE),
    )

    act(() => {
      first.result.current.toggle('priority')
    })
    expect(first.result.current.isVisible('priority')).toBe(true)
    first.unmount()

    // 재마운트(같은 storageKey) — 새 컴포넌트 인스턴스가 저장된 값을 복원해야 한다
    const second = renderHook(() =>
      useColumnVisibility(STORAGE_KEY, ALL_COLUMN_KEYS, REQUIRED_KEYS, DEFAULT_VISIBLE),
    )
    expect(second.result.current.isVisible('priority')).toBe(true)
    expect(second.result.current.isVisible('status')).toBe(true)
  })

  it('T-CV-4: localStorage 값이 손상된 JSON이면 기본값으로 안전 복구한다 (EC2)', () => {
    localStorage.setItem(STORAGE_KEY, '{not-valid-json')

    const { result } = renderHook(() =>
      useColumnVisibility(STORAGE_KEY, ALL_COLUMN_KEYS, REQUIRED_KEYS, DEFAULT_VISIBLE),
    )

    expect(result.current.visible).toEqual(DEFAULT_VISIBLE)
  })

  it('T-CV-5: localStorage 값에 미지의 컬럼 키가 포함되면 기본값으로 안전 복구한다 (EC2)', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(['key', 'summary', 'ghostColumn']))

    const { result } = renderHook(() =>
      useColumnVisibility(STORAGE_KEY, ALL_COLUMN_KEYS, REQUIRED_KEYS, DEFAULT_VISIBLE),
    )

    expect(result.current.visible).toEqual(DEFAULT_VISIBLE)
    expect(result.current.isVisible('ghostColumn')).toBe(false)
  })

  it('T-CV-6: 필수 컬럼은 toggle을 호출해도 항상 표시 상태를 유지한다', () => {
    const { result } = renderHook(() =>
      useColumnVisibility(STORAGE_KEY, ALL_COLUMN_KEYS, REQUIRED_KEYS, DEFAULT_VISIBLE),
    )

    act(() => {
      result.current.toggle('key')
    })
    expect(result.current.isVisible('key')).toBe(true)

    act(() => {
      result.current.toggle('summary')
    })
    expect(result.current.isVisible('summary')).toBe(true)
  })

  it('T-CV-7: localStorage에 저장된 유효한 부분집합이 필수 컬럼을 빠뜨려도 항상 포함된다', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(['status']))

    const { result } = renderHook(() =>
      useColumnVisibility(STORAGE_KEY, ALL_COLUMN_KEYS, REQUIRED_KEYS, DEFAULT_VISIBLE),
    )

    expect(result.current.isVisible('key')).toBe(true)
    expect(result.current.isVisible('summary')).toBe(true)
    expect(result.current.isVisible('status')).toBe(true)
  })
})
