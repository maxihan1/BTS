// Tooltip 프리미티브 단위 테스트 — role="tooltip" 노출 계약 확인
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider } from './tooltip'

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: open 상태에서 role="tooltip" 콘텐츠가 노출된다 (★핵심 계약)
// jsdom에서 hover/pointer 타이밍은 불안정하므로 open을 제어형 prop으로 렌더한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-1: role="tooltip" 노출 (핵심 계약)', () => {
  it('open 상태에서 role="tooltip" 콘텐츠가 텍스트와 함께 노출된다', () => {
    render(
      <TooltipProvider delayDuration={0}>
        <Tooltip open>
          <TooltipTrigger>버튼</TooltipTrigger>
          <TooltipContent>도움말 텍스트</TooltipContent>
        </Tooltip>
      </TooltipProvider>,
    )

    expect(screen.getByRole('tooltip')).toHaveTextContent('도움말 텍스트')
  })

  it('open이 아니면 role="tooltip" 콘텐츠가 존재하지 않는다', () => {
    render(
      <TooltipProvider delayDuration={0}>
        <Tooltip open={false}>
          <TooltipTrigger>버튼</TooltipTrigger>
          <TooltipContent>도움말 텍스트</TooltipContent>
        </Tooltip>
      </TooltipProvider>,
    )

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })
})
