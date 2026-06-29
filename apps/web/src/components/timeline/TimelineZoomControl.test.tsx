// 타임라인 줌 컨트롤 컴포넌트 단위 테스트 — 세그먼트 버튼 + −/+ 확대축소 (FR-TL-03 Task 4)
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { timelineLabels } from '@/i18n/timeline-labels'
import { nextZoomIn, nextZoomOut } from '@/lib/timeline-zoom'
import { TimelineZoomControl } from './TimelineZoomControl'

// ─────────────────────────────────────────────────────────────────────────────
// S1. 세그먼트 버튼 렌더 (주/월/분기)
// ─────────────────────────────────────────────────────────────────────────────

describe('TimelineZoomControl — S1 세그먼트 버튼 렌더', () => {
  const onZoomChange = vi.fn()

  afterEach(() => { onZoomChange.mockClear() })

  it('S1-a: 주/월/분기 세그먼트 버튼 3개가 렌더된다', () => {
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.week })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: timelineLabels.zoom.month })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: timelineLabels.zoom.quarter })).toBeInTheDocument()
  })

  it('S1-b: 현재 레벨 버튼은 aria-pressed=true, 나머지는 false', () => {
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.week })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: timelineLabels.zoom.month })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: timelineLabels.zoom.quarter })).toHaveAttribute('aria-pressed', 'false')
  })

  it('S1-c: zoomLevel=week 이면 주 버튼만 aria-pressed=true', () => {
    render(<TimelineZoomControl zoomLevel="week" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.week })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: timelineLabels.zoom.month })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: timelineLabels.zoom.quarter })).toHaveAttribute('aria-pressed', 'false')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 세그먼트 버튼 클릭 → onZoomChange 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('TimelineZoomControl — S2 세그먼트 버튼 클릭', () => {
  const onZoomChange = vi.fn()

  afterEach(() => { onZoomChange.mockClear() })

  it('S2-a: 주 버튼 클릭 시 onZoomChange("week") 호출', async () => {
    const user = userEvent.setup()
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    await user.click(screen.getByRole('button', { name: timelineLabels.zoom.week }))
    expect(onZoomChange).toHaveBeenCalledOnce()
    expect(onZoomChange).toHaveBeenCalledWith('week')
  })

  it('S2-b: 분기 버튼 클릭 시 onZoomChange("quarter") 호출', async () => {
    const user = userEvent.setup()
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    await user.click(screen.getByRole('button', { name: timelineLabels.zoom.quarter }))
    expect(onZoomChange).toHaveBeenCalledOnce()
    expect(onZoomChange).toHaveBeenCalledWith('quarter')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. +/− 버튼 클릭 → nextZoomIn / nextZoomOut 위임
// ─────────────────────────────────────────────────────────────────────────────

describe('TimelineZoomControl — S3 확대/축소 버튼 클릭', () => {
  const onZoomChange = vi.fn()

  afterEach(() => { onZoomChange.mockClear() })

  it('S3-a: + 버튼 클릭 시 onZoomChange(nextZoomIn(current)) 호출', async () => {
    const user = userEvent.setup()
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    await user.click(screen.getByRole('button', { name: timelineLabels.zoom.zoomInAriaLabel }))
    expect(onZoomChange).toHaveBeenCalledOnce()
    expect(onZoomChange).toHaveBeenCalledWith(nextZoomIn('month'))
  })

  it('S3-b: − 버튼 클릭 시 onZoomChange(nextZoomOut(current)) 호출', async () => {
    const user = userEvent.setup()
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    await user.click(screen.getByRole('button', { name: timelineLabels.zoom.zoomOutAriaLabel }))
    expect(onZoomChange).toHaveBeenCalledOnce()
    expect(onZoomChange).toHaveBeenCalledWith(nextZoomOut('month'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 끝단 disabled — 최대확대/최대축소
// ─────────────────────────────────────────────────────────────────────────────

describe('TimelineZoomControl — S4 끝단 disabled', () => {
  const onZoomChange = vi.fn()

  afterEach(() => { onZoomChange.mockClear() })

  it('S4-a: zoomLevel=week(최대 확대) 시 + 버튼이 disabled', () => {
    render(<TimelineZoomControl zoomLevel="week" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.zoomInAriaLabel })).toBeDisabled()
  })

  it('S4-b: zoomLevel=week 시 − 버튼은 enabled', () => {
    render(<TimelineZoomControl zoomLevel="week" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.zoomOutAriaLabel })).toBeEnabled()
  })

  it('S4-c: zoomLevel=quarter(최대 축소) 시 − 버튼이 disabled', () => {
    render(<TimelineZoomControl zoomLevel="quarter" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.zoomOutAriaLabel })).toBeDisabled()
  })

  it('S4-d: zoomLevel=quarter 시 + 버튼은 enabled', () => {
    render(<TimelineZoomControl zoomLevel="quarter" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.zoomInAriaLabel })).toBeEnabled()
  })

  it('S4-e: zoomLevel=month(중간) 시 +/− 버튼 모두 enabled', () => {
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.zoomInAriaLabel })).toBeEnabled()
    expect(screen.getByRole('button', { name: timelineLabels.zoom.zoomOutAriaLabel })).toBeEnabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 접근성 — role=group, aria-label, aria-keyshortcuts (D3)
// ─────────────────────────────────────────────────────────────────────────────

describe('TimelineZoomControl — S5 접근성', () => {
  const onZoomChange = vi.fn()

  it('S5-a: 컨테이너에 role=group + aria-label(groupAriaLabel)이 있다', () => {
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    expect(
      screen.getByRole('group', { name: timelineLabels.zoom.groupAriaLabel }),
    ).toBeInTheDocument()
  })

  it('S5-b: + 버튼에 aria-label=zoomInAriaLabel이 있다', () => {
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    expect(
      screen.getByRole('button', { name: timelineLabels.zoom.zoomInAriaLabel }),
    ).toHaveAttribute('aria-label', timelineLabels.zoom.zoomInAriaLabel)
  })

  it('S5-c: − 버튼에 aria-label=zoomOutAriaLabel이 있다', () => {
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    expect(
      screen.getByRole('button', { name: timelineLabels.zoom.zoomOutAriaLabel }),
    ).toHaveAttribute('aria-label', timelineLabels.zoom.zoomOutAriaLabel)
  })

  it('S5-d: 세그먼트 버튼에 aria-keyshortcuts 속성이 있다 (D3 discoverability)', () => {
    render(<TimelineZoomControl zoomLevel="month" onZoomChange={onZoomChange} />)
    expect(screen.getByRole('button', { name: timelineLabels.zoom.week })).toHaveAttribute('aria-keyshortcuts', '1')
    expect(screen.getByRole('button', { name: timelineLabels.zoom.month })).toHaveAttribute('aria-keyshortcuts', '2')
    expect(screen.getByRole('button', { name: timelineLabels.zoom.quarter })).toHaveAttribute('aria-keyshortcuts', '3')
  })
})
