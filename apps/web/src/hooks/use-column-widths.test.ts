// 컬럼 폭 영속 훅 판별식 — 손상 저장값 폴백 · 하한/상한 클램프 · 초기화
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import {
  useColumnWidths,
  MIN_COLUMN_WIDTH,
  MAX_COLUMN_WIDTH,
} from './use-column-widths'

const STORAGE_KEY = 'test-column-widths'
const DEFAULTS: Readonly<Record<string, number>> = { key: 92, summary: 280, status: 128 }

beforeEach(() => {
  window.localStorage.clear()
})

describe('useColumnWidths — 초기값', () => {
  it('T-1: 저장값이 없으면 기본 폭을 그대로 쓴다', () => {
    const { result } = renderHook(() => useColumnWidths(STORAGE_KEY, DEFAULTS))

    expect(result.current.widths).toEqual(DEFAULTS)
  })

  it('T-2: 저장값이 있으면 기본 폭 위에 덮어쓴다 — 저장에 없는 키는 기본값이 남는다', () => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ summary: 400 }))

    const { result } = renderHook(() => useColumnWidths(STORAGE_KEY, DEFAULTS))

    expect(result.current.widths).toEqual({ ...DEFAULTS, summary: 400 })
  })
})

describe('useColumnWidths — 손상 저장값 폴백 (fail-safe)', () => {
  it.each([
    ['JSON 아님', 'not-json'],
    ['배열', '[1,2,3]'],
    ['숫자 아닌 값', '{"summary":"wide"}'],
    ['NaN·Infinity', '{"summary":null}'],
    ['모르는 컬럼 키', '{"unknown-column":200}'],
    ['하한 미만', `{"summary":${MIN_COLUMN_WIDTH - 1}}`],
    ['상한 초과', `{"summary":${MAX_COLUMN_WIDTH + 1}}`],
  ])('T-3: %s 이면 통째로 버리고 기본 폭으로 돌아간다', (_label, raw) => {
    // ★부분 필터링이 아니라 **전체 무효** 처리다 — use-column-visibility 의 EC2 와 같은 처방.
    // 스키마가 바뀐 옛 저장값을 반쯤 살려 두면 화면이 설명 불가능한 상태가 된다.
    window.localStorage.setItem(STORAGE_KEY, raw)

    const { result } = renderHook(() => useColumnWidths(STORAGE_KEY, DEFAULTS))

    expect(result.current.widths).toEqual(DEFAULTS)
  })
})

describe('useColumnWidths — setWidth', () => {
  it('T-4: 폭을 바꾸면 상태와 localStorage 에 함께 남는다', () => {
    const { result } = renderHook(() => useColumnWidths(STORAGE_KEY, DEFAULTS))

    act(() => result.current.setWidth('summary', 420))

    expect(result.current.widths.summary).toBe(420)
    expect(JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? '{}')).toEqual({ summary: 420 })
  })

  it('T-5: 하한·상한으로 클램프한다 — 0 폭 컬럼은 되돌릴 방법이 없어진다', () => {
    const { result } = renderHook(() => useColumnWidths(STORAGE_KEY, DEFAULTS))

    act(() => result.current.setWidth('summary', 4))
    expect(result.current.widths.summary).toBe(MIN_COLUMN_WIDTH)

    act(() => result.current.setWidth('summary', 99_999))
    expect(result.current.widths.summary).toBe(MAX_COLUMN_WIDTH)
  })

  it('T-6: 기본 폭에 없는 컬럼 키는 무시한다', () => {
    const { result } = renderHook(() => useColumnWidths(STORAGE_KEY, DEFAULTS))

    act(() => result.current.setWidth('unknown-column', 200))

    expect(result.current.widths).toEqual(DEFAULTS)
  })

  it('T-7: 소수점 폭은 정수로 반올림한다 — 드래그 delta 가 소수로 들어온다', () => {
    const { result } = renderHook(() => useColumnWidths(STORAGE_KEY, DEFAULTS))

    act(() => result.current.setWidth('summary', 301.6))

    expect(result.current.widths.summary).toBe(302)
  })
})

describe('useColumnWidths — reset', () => {
  it('T-8: 초기화하면 기본 폭으로 돌아가고 저장값도 지운다', () => {
    const { result } = renderHook(() => useColumnWidths(STORAGE_KEY, DEFAULTS))
    act(() => result.current.setWidth('summary', 420))

    act(() => result.current.reset())

    expect(result.current.widths).toEqual(DEFAULTS)
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull()
  })
})
