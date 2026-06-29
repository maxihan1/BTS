// 타임라인 줌 레벨 localStorage 영속 훅 (FR-TL-03)
import { useState, useEffect, useCallback } from 'react'
import { type ZoomLevel, parseZoomLevel, ZOOM_PRESETS } from '@/lib/timeline-zoom'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** localStorage 저장 키 */
const STORAGE_KEY = 'timeline-zoom' as const

/**
 * 키보드 단축키 → 줌 레벨 매핑.
 *
 * | key | zoomLevel |
 * |-----|-----------|
 * | 1   | week      |
 * | 2   | month     |
 * | 3   | quarter   |
 */
const KEY_ZOOM_MAP: Readonly<Record<string, ZoomLevel>> = {
  '1': 'week',
  '2': 'month',
  '3': 'quarter',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이벤트 타깃이 텍스트 편집 가능한 요소인지 판별한다.
 *
 * - `HTMLInputElement` / `HTMLTextAreaElement`: 기본 입력 요소
 * - `isContentEditable`: 실제 브라우저에서 부모 상속까지 포함
 * - `getAttribute('contenteditable') === 'true'`: jsdom 환경 대응 폴백
 *
 * @param target keydown 이벤트의 event.target
 * @returns 편집 가능 요소이면 true
 */
function isEditableTarget(target: EventTarget | null): boolean {
  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
    return true
  }
  if (target instanceof HTMLElement) {
    return target.isContentEditable || target.getAttribute('contenteditable') === 'true'
  }
  return false
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useTimelineZoom 반환 타입 */
interface TimelineZoomResult {
  /** 현재 줌 레벨 */
  zoomLevel: ZoomLevel
  /** 줌 레벨 변경 함수 — localStorage 동기화 포함 */
  setZoom: (level: ZoomLevel) => void
  /** 현재 줌 레벨의 일(day) 단위 열 폭(px) */
  dayWidth: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 줌 레벨을 localStorage에 영속하고 키보드 단축키를 처리하는 훅.
 *
 * - 초기값: localStorage `timeline-zoom` 파싱 (미설정 또는 잘못된 값 → 'month')
 * - `setZoom(level)`: 상태 및 localStorage 동시 갱신
 * - 단축키: 1=week, 2=month, 3=quarter
 *   (input · textarea · contenteditable에 이벤트가 발생한 경우 무시)
 * - 언마운트 시 keydown 리스너 자동 해제
 *
 * @returns { zoomLevel, setZoom, dayWidth }
 */
export function useTimelineZoom(): TimelineZoomResult {
  const [zoomLevel, setZoomState] = useState<ZoomLevel>(() =>
    parseZoomLevel(localStorage.getItem(STORAGE_KEY)),
  )

  const setZoom = useCallback((level: ZoomLevel): void => {
    setZoomState(level)
    localStorage.setItem(STORAGE_KEY, level)
  }, [])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent): void => {
      if (isEditableTarget(event.target)) return

      const level = KEY_ZOOM_MAP[event.key]
      if (level !== undefined) {
        setZoom(level)
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [setZoom])

  return {
    zoomLevel,
    setZoom,
    dayWidth: ZOOM_PRESETS[zoomLevel].dayWidth,
  }
}
