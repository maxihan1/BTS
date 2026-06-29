// 타임라인 의존 라인 SVG 오버레이 단위 테스트 — elbow path·hit-path·클릭 강조·해제 (FR-TL-02 D6)
import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { DependencyLine } from '@/lib/timeline-layout'
import { DependencyOverlay } from './DependencyOverlay'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const ROW_HEIGHT = 32

function makeLine(
  blocker: string,
  blocked: string,
  row0: number,
  row1: number,
): DependencyLine {
  return {
    blockerKey: blocker,
    blockedKey: blocked,
    startX: 100,
    startY: row0 * ROW_HEIGHT + ROW_HEIGHT / 2,
    midX: 140,
    endX: 180,
    endY: row1 * ROW_HEIGHT + ROW_HEIGHT / 2,
  }
}

const OVERLAY_PROPS = { axisOffset: 40, width: 400, height: 300 } as const

// ─────────────────────────────────────────────────────────────────────────────
// S5. lines 0개 — 빈 SVG
// ─────────────────────────────────────────────────────────────────────────────

describe('DependencyOverlay — S5 lines 0개', () => {
  it('lines가 빈 배열이어도 SVG를 렌더한다', () => {
    const { container } = render(<DependencyOverlay lines={[]} {...OVERLAY_PROPS} />)
    expect(container.querySelector('svg')).toBeInTheDocument()
  })

  it('lines가 빈 배열이면 aria-label path를 렌더하지 않는다', () => {
    const { container } = render(<DependencyOverlay lines={[]} {...OVERLAY_PROPS} />)
    expect(container.querySelectorAll('path[aria-label]').length).toBe(0)
  })

  it('lines가 빈 배열이면 hit-path(stroke="transparent")를 렌더하지 않는다', () => {
    const { container } = render(<DependencyOverlay lines={[]} {...OVERLAY_PROPS} />)
    expect(container.querySelectorAll('path[stroke="transparent"]').length).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. elbow path 렌더 (직각 경로·hit-path·aria-label)
// ─────────────────────────────────────────────────────────────────────────────

describe('DependencyOverlay — S1 elbow path 렌더', () => {
  const lines = [makeLine('BTS-2', 'BTS-3', 1, 2), makeLine('BTS-1', 'BTS-4', 0, 3)]

  it('N개 lines → N개 visible path(aria-label 있음)가 렌더된다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    expect(container.querySelectorAll('path[aria-label]').length).toBe(2)
  })

  it('각 elbow path의 d 속성에 H와 V가 포함된다 (직각 경로)', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const paths = container.querySelectorAll('path[aria-label]')
    paths.forEach((p) => {
      const d = p.getAttribute('d') ?? ''
      expect(d).toContain('H')
      expect(d).toContain('V')
    })
  })

  it('각 라인에 hit-path(stroke="transparent")가 동반된다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    expect(container.querySelectorAll('path[stroke="transparent"]').length).toBe(lines.length)
  })

  it('각 라인에 올바른 aria-label이 존재한다 (NFR3)', () => {
    render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    expect(screen.getByLabelText('BTS-2가 BTS-3을 차단')).toBeInTheDocument()
    expect(screen.getByLabelText('BTS-1가 BTS-4을 차단')).toBeInTheDocument()
  })

  it('path d가 axisOffset을 y에 더한 값으로 시작한다 (M x,y+axisOffset)', () => {
    const line = makeLine('BTS-2', 'BTS-3', 1, 2)
    const { container } = render(<DependencyOverlay lines={[line]} {...OVERLAY_PROPS} />)
    const path = container.querySelector('path[aria-label]')
    const d = path?.getAttribute('d') ?? ''
    // startY + axisOffset = (1*32 + 16) + 40 = 88
    expect(d).toMatch(/^M 100,88/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. hit-path 클릭 → 선택 강조
// ─────────────────────────────────────────────────────────────────────────────

describe('DependencyOverlay — S2 hit-path 클릭 강조', () => {
  const lines = [makeLine('BTS-2', 'BTS-3', 1, 2), makeLine('BTS-1', 'BTS-4', 0, 3)]

  it('hit-path 클릭 시 해당 visible path가 data-selected="true"가 된다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)

    expect(container.querySelectorAll('path[data-selected="true"]').length).toBe(1)
  })

  it('선택 후 나머지 visible path가 data-dimmed="true"가 된다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)

    expect(container.querySelectorAll('path[data-dimmed="true"]').length).toBeGreaterThan(0)
  })

  it('선택된 path는 data-dimmed 속성이 없다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)

    const selected = container.querySelector('path[data-selected="true"]')
    expect(selected).not.toHaveAttribute('data-dimmed')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 클릭 해제
// ─────────────────────────────────────────────────────────────────────────────

describe('DependencyOverlay — S3 클릭 해제', () => {
  const lines = [makeLine('BTS-2', 'BTS-3', 1, 2), makeLine('BTS-1', 'BTS-4', 0, 3)]

  it('S3a: 선택된 hit-path 재클릭 시 선택이 해제된다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)
    expect(container.querySelectorAll('path[data-selected="true"]').length).toBe(1)

    fireEvent.click(first)
    expect(container.querySelectorAll('path[data-selected="true"]').length).toBe(0)
  })

  it('S3b: 배경 rect(data-testid="dep-overlay-bg") 클릭 시 선택이 해제된다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)
    expect(container.querySelectorAll('path[data-selected="true"]').length).toBe(1)

    const bgRect = container.querySelector('[data-testid="dep-overlay-bg"]')
    if (bgRect === null) throw new Error('배경 rect 없음')
    fireEvent.click(bgRect)

    expect(container.querySelectorAll('path[data-selected="true"]').length).toBe(0)
  })

  it('S3c: 선택 해제 후 data-dimmed path도 사라진다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)
    expect(container.querySelectorAll('path[data-dimmed="true"]').length).toBeGreaterThan(0)

    const bgRect = container.querySelector('[data-testid="dep-overlay-bg"]')
    if (bgRect === null) throw new Error('배경 rect 없음')
    fireEvent.click(bgRect)

    expect(container.querySelectorAll('path[data-dimmed="true"]').length).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 배경 rect pointer-events 조건부 — Gantt 막대 클릭 통과 버그 수정
// ─────────────────────────────────────────────────────────────────────────────

describe('DependencyOverlay — S6 배경 rect pointer-events 조건부', () => {
  const lines = [makeLine('BTS-2', 'BTS-3', 1, 2)]

  // jsdom은 pointer-events CSS 속성을 클릭 차단에 강제하지 않으므로
  // style 속성 값 자체를 직접 단언한다.
  // 실 브라우저에서 Gantt 막대 클릭이 통과하는지는 E2E에 위임한다.

  it('S6a: 선택 없을 때(초기) 배경 rect의 pointerEvents가 none이다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const bgRect = container.querySelector('[data-testid="dep-overlay-bg"]')
    if (bgRect === null) throw new Error('배경 rect 없음')
    expect((bgRect as HTMLElement).style.pointerEvents).toBe('none')
  })

  it('S6b: hit-path 클릭으로 라인 선택 후 배경 rect의 pointerEvents가 all이 된다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPath = container.querySelector('path[stroke="transparent"]')
    if (hitPath === null) throw new Error('hit-path 없음')

    fireEvent.click(hitPath)

    const bgRect = container.querySelector('[data-testid="dep-overlay-bg"]')
    if (bgRect === null) throw new Error('배경 rect 없음')
    expect((bgRect as HTMLElement).style.pointerEvents).toBe('all')
  })

  it('S6c: 배경 클릭으로 선택 해제 후 배경 rect의 pointerEvents가 다시 none이 된다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPath = container.querySelector('path[stroke="transparent"]')
    if (hitPath === null) throw new Error('hit-path 없음')

    fireEvent.click(hitPath)

    const bgRect = container.querySelector('[data-testid="dep-overlay-bg"]')
    if (bgRect === null) throw new Error('배경 rect 없음')
    fireEvent.click(bgRect)

    expect((bgRect as HTMLElement).style.pointerEvents).toBe('none')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C-a. 선택 라인 화살촉 색 일치 (CONCERN-a fix)
// ─────────────────────────────────────────────────────────────────────────────

describe('DependencyOverlay — C-a 선택 라인 화살촉 색 일치', () => {
  const lines = [makeLine('BTS-2', 'BTS-3', 1, 2), makeLine('BTS-1', 'BTS-4', 0, 3)]

  /**
   * C-a: 비선택 상태의 visible path는 기본 marker(fill-muted-foreground)를 사용한다.
   * RED: 현재 모든 path가 기본 MARKER_ID를 사용함 — 이 테스트는 통과한다.
   */
  it('C-a-1: 비선택 path는 기본 marker(dep-arrow-end)를 사용하고 selected marker를 쓰지 않는다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const visiblePaths = container.querySelectorAll('path[aria-label]')
    expect(visiblePaths.length).toBeGreaterThan(0)
    visiblePaths.forEach((p) => {
      const markerEnd = p.getAttribute('marker-end') ?? ''
      expect(markerEnd).toContain('dep-arrow-end')
      expect(markerEnd).not.toContain('dep-arrow-end-selected')
    })
  })

  /**
   * C-a-2: hit-path 클릭 시 선택된 path의 markerEnd가 selected marker를 가리켜야 한다.
   * RED: 현재 선택 path도 기본 MARKER_ID를 사용 → 'dep-arrow-end-selected' 미포함 → 실패.
   * GREEN: selected marker 추가 + 선택 path에 적용 후 통과.
   */
  it('C-a-2: hit-path 클릭 시 선택 path의 markerEnd가 dep-arrow-end-selected를 가리킨다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)

    const selectedPath = container.querySelector('path[data-selected="true"]')
    expect(selectedPath).not.toBeNull()
    expect(selectedPath?.getAttribute('marker-end')).toContain('dep-arrow-end-selected')
  })

  /**
   * C-a-3: dimmed(비선택) path는 기본 marker를 유지해야 한다.
   * RED: 이미 기본 marker 사용 → 통과하지만, selected marker 도입 후 dimmed에 번지지 않도록 보장.
   */
  it('C-a-3: dimmed path는 기본 marker(dep-arrow-end)를 유지하고 selected marker를 쓰지 않는다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)

    const dimmedPaths = container.querySelectorAll('path[data-dimmed="true"]')
    expect(dimmedPaths.length).toBeGreaterThan(0)
    dimmedPaths.forEach((p) => {
      const markerEnd = p.getAttribute('marker-end') ?? ''
      expect(markerEnd).toContain('dep-arrow-end')
      expect(markerEnd).not.toContain('dep-arrow-end-selected')
    })
  })

  /**
   * C-a-4: 선택 해제 후 모든 path가 기본 marker로 돌아간다.
   */
  it('C-a-4: 선택 해제 후 모든 path가 기본 marker로 돌아간다', () => {
    const { container } = render(<DependencyOverlay lines={lines} {...OVERLAY_PROPS} />)
    const hitPaths = container.querySelectorAll('path[stroke="transparent"]')
    const first = hitPaths[0]
    if (first === undefined) throw new Error('hit-path 없음')

    fireEvent.click(first)
    fireEvent.click(first)  // 재클릭 → 선택 해제

    const visiblePaths = container.querySelectorAll('path[aria-label]')
    visiblePaths.forEach((p) => {
      const markerEnd = p.getAttribute('marker-end') ?? ''
      expect(markerEnd).not.toContain('dep-arrow-end-selected')
    })
  })
})
