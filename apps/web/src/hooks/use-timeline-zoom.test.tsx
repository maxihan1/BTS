// 타임라인 줌 레벨 localStorage 영속 훅 단위 테스트 (FR-TL-03)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act, fireEvent } from '@testing-library/react'
import { ZOOM_PRESETS } from '@/lib/timeline-zoom'
import { useTimelineZoom } from './use-timeline-zoom'

describe('useTimelineZoom', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  // ─── 초기값 ────────────────────────────────────────────────────────────────

  it('T-TZ-1: localStorage 미설정 → 초기값 month', () => {
    const { result } = renderHook(() => useTimelineZoom())
    expect(result.current.zoomLevel).toBe('month')
  })

  it('T-TZ-2: localStorage 사전 설정값(quarter) → 초기값 반영', () => {
    localStorage.setItem('timeline-zoom', 'quarter')
    const { result } = renderHook(() => useTimelineZoom())
    expect(result.current.zoomLevel).toBe('quarter')
  })

  it('T-TZ-3: 잘못된 저장값(xxx) → 초기값 month(parseZoomLevel 폴백)', () => {
    localStorage.setItem('timeline-zoom', 'xxx')
    const { result } = renderHook(() => useTimelineZoom())
    expect(result.current.zoomLevel).toBe('month')
  })

  // ─── setZoom ───────────────────────────────────────────────────────────────

  it('T-TZ-4: setZoom("quarter") → zoomLevel=quarter, localStorage["timeline-zoom"]==="quarter"', () => {
    const { result } = renderHook(() => useTimelineZoom())
    act(() => {
      result.current.setZoom('quarter')
    })
    expect(result.current.zoomLevel).toBe('quarter')
    expect(localStorage.getItem('timeline-zoom')).toBe('quarter')
  })

  // ─── 단축키 ────────────────────────────────────────────────────────────────

  it('T-TZ-5: 단축키 1 → week', () => {
    const { result } = renderHook(() => useTimelineZoom())
    act(() => {
      fireEvent.keyDown(window, { key: '1' })
    })
    expect(result.current.zoomLevel).toBe('week')
  })

  it('T-TZ-6: 단축키 2 → month', () => {
    localStorage.setItem('timeline-zoom', 'week')
    const { result } = renderHook(() => useTimelineZoom())
    act(() => {
      fireEvent.keyDown(window, { key: '2' })
    })
    expect(result.current.zoomLevel).toBe('month')
  })

  it('T-TZ-7: 단축키 3 → quarter', () => {
    const { result } = renderHook(() => useTimelineZoom())
    act(() => {
      fireEvent.keyDown(window, { key: '3' })
    })
    expect(result.current.zoomLevel).toBe('quarter')
  })

  // ─── 입력 요소 포커스 시 단축키 무시 ──────────────────────────────────────

  it('T-TZ-8: input에 keydown → 단축키 무시', () => {
    const { result } = renderHook(() => useTimelineZoom())
    const input = document.createElement('input')
    document.body.appendChild(input)
    try {
      act(() => {
        fireEvent.keyDown(input, { key: '3' })
      })
      expect(result.current.zoomLevel).toBe('month')
    } finally {
      document.body.removeChild(input)
    }
  })

  it('T-TZ-9: textarea에 keydown → 단축키 무시', () => {
    const { result } = renderHook(() => useTimelineZoom())
    const textarea = document.createElement('textarea')
    document.body.appendChild(textarea)
    try {
      act(() => {
        fireEvent.keyDown(textarea, { key: '1' })
      })
      expect(result.current.zoomLevel).toBe('month')
    } finally {
      document.body.removeChild(textarea)
    }
  })

  it('T-TZ-10: contenteditable에 keydown → 단축키 무시', () => {
    const { result } = renderHook(() => useTimelineZoom())
    const div = document.createElement('div')
    div.setAttribute('contenteditable', 'true')
    document.body.appendChild(div)
    try {
      act(() => {
        fireEvent.keyDown(div, { key: '1' })
      })
      expect(result.current.zoomLevel).toBe('month')
    } finally {
      document.body.removeChild(div)
    }
  })

  // ─── 언마운트 클린업 ───────────────────────────────────────────────────────

  it('T-TZ-11: 언마운트 후 keydown이 상태 변경 안 함', () => {
    const { result, unmount } = renderHook(() => useTimelineZoom())
    unmount()
    act(() => {
      fireEvent.keyDown(window, { key: '3' })
    })
    expect(result.current.zoomLevel).toBe('month')
  })

  // ─── C1: localStorage 안전화 ─────────────────────────────────────────────

  it('T-TZ-13: localStorage.getItem throw → 기본값 month(크래시 없음)', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError: blocked')
    })
    try {
      const { result } = renderHook(() => useTimelineZoom())
      expect(result.current.zoomLevel).toBe('month')
    } finally {
      spy.mockRestore()
    }
  })

  it('T-TZ-14: localStorage.setItem throw → setZoom이 throw 없이 상태만 변경', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    try {
      const { result } = renderHook(() => useTimelineZoom())
      act(() => {
        result.current.setZoom('quarter')
      })
      expect(result.current.zoomLevel).toBe('quarter')
    } finally {
      spy.mockRestore()
    }
  })

  // ─── C3: 수식 키 가드 ────────────────────────────────────────────────────

  it('T-TZ-15: ctrlKey+1 → 줌 변경 안 됨(month 유지)', () => {
    const { result } = renderHook(() => useTimelineZoom())
    act(() => {
      fireEvent.keyDown(window, { key: '1', ctrlKey: true })
    })
    expect(result.current.zoomLevel).toBe('month')
  })

  it('T-TZ-16: metaKey+3 → 줌 변경 안 됨(month 유지)', () => {
    const { result } = renderHook(() => useTimelineZoom())
    act(() => {
      fireEvent.keyDown(window, { key: '3', metaKey: true })
    })
    expect(result.current.zoomLevel).toBe('month')
  })

  it('T-TZ-17: altKey+1 → 줌 변경 안 됨(month 유지)', () => {
    const { result } = renderHook(() => useTimelineZoom())
    act(() => {
      fireEvent.keyDown(window, { key: '1', altKey: true })
    })
    expect(result.current.zoomLevel).toBe('month')
  })

  // ─── dayWidth ──────────────────────────────────────────────────────────────

  it('T-TZ-12: dayWidth === ZOOM_PRESETS[zoomLevel].dayWidth', () => {
    const { result } = renderHook(() => useTimelineZoom())
    expect(result.current.dayWidth).toBe(ZOOM_PRESETS['month'].dayWidth)
    act(() => {
      result.current.setZoom('week')
    })
    expect(result.current.dayWidth).toBe(ZOOM_PRESETS['week'].dayWidth)
  })
})
