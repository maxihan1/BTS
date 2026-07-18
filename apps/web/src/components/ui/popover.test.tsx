// Popover 프리미티브 단위 테스트 — 트리거 클릭 시 콘텐츠 노출 계약 + ScrollArea 렌더 스모크
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import { Popover, PopoverTrigger, PopoverContent } from './popover'
import { ScrollArea, ScrollBar } from './scroll-area'

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: 트리거 클릭 시 콘텐츠가 열린다 (★핵심 계약)
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-1: 트리거 클릭 시 콘텐츠 열림', () => {
  it('트리거를 클릭하면 콘텐츠 텍스트가 노출된다', async () => {
    const user = userEvent.setup()
    render(
      <Popover>
        <PopoverTrigger>열기</PopoverTrigger>
        <PopoverContent>팝오버 내용</PopoverContent>
      </Popover>,
    )

    expect(screen.queryByText('팝오버 내용')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '열기' }))

    expect(await screen.findByText('팝오버 내용')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-2: ScrollArea 렌더 스모크
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-2: ScrollArea 렌더 스모크', () => {
  it('ScrollArea + ScrollBar가 children과 함께 정상 렌더된다', () => {
    render(
      <ScrollArea className="h-40">
        <div>스크롤 콘텐츠</div>
        <ScrollBar />
      </ScrollArea>,
    )

    expect(screen.getByText('스크롤 콘텐츠')).toBeInTheDocument()
  })
})
