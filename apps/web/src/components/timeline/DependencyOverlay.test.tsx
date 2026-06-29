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
