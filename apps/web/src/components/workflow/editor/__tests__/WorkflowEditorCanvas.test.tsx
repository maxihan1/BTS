// 캔버스 판정 — 빈 상태(F16) · 전역 전환 패널(F11) · 잠금 안내(E12)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { WorkflowEditorCanvas } from '../WorkflowEditorCanvas'
import type { CanvasState } from '../WorkflowEditorCanvas'
import type { LayoutInputTransition } from '@/lib/workflow-layout'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'

/**
 * ★ 노드·간선의 **렌더**는 여기서 재지 않는다.
 *
 * jsdom 이 SVG 측정 API(`getBBox`·레이아웃 width)를 구현하지 않아 xyflow 는 뷰포트 크기를 0
 * 으로 보고 노드를 그리지 않는다. 픽셀 검증은 Playwright(`e2e/workflow-diagram.spec.ts`)의
 * 몫이고, 좌표·간선 계산 자체는 순수 함수 `lib/workflow-layout.ts` 가 이미 판정한다(NFR N1).
 *
 * 이 파일이 재는 것은 **캔버스 밖에서 렌더되는 것** — 빈 상태·전역 패널·잠금 안내다.
 */
const STATES: CanvasState[] = [
  { key: 'open', name: '열림', category: 'TODO', displayOrder: 1, layoutX: null, layoutY: null },
  { key: 'done', name: '완료', category: 'DONE', displayOrder: 2, layoutX: null, layoutY: null },
]

function noop(): void {}

function renderCanvas(overrides: Partial<React.ComponentProps<typeof WorkflowEditorCanvas>> = {}) {
  const props = {
    states: STATES,
    transitions: [] as LayoutInputTransition[],
    locked: false,
    onMoveState: noop,
    onCreateTransition: noop,
    onEditTransition: noop,
    ...overrides,
  }
  return render(<WorkflowEditorCanvas {...props} />)
}

describe('빈 상태 (F16)', () => {
  it('상태가 없으면 빈 상태를 그린다', () => {
    renderCanvas({ states: [] })

    expect(screen.getByText(labels.diagram.empty)).toBeInTheDocument()
  })

  it('빈 상태 문구가 상태 패널의 것을 돌려 쓰지 않는다', () => {
    // 「상태가 하나도 없다」와 「캔버스에 그릴 것이 없다」는 사용자에게 다른 사실이다.
    expect(labels.diagram.empty).not.toBe(labels.statusPanel.empty)
  })

  it('상태가 있으면 캔버스를 그린다', () => {
    renderCanvas()

    expect(screen.getByLabelText(labels.diagram.canvasLabel)).toBeInTheDocument()
    expect(screen.queryByText(labels.diagram.empty)).not.toBeInTheDocument()
  })
})

describe('전역 전환 패널 (F11 · E6)', () => {
  const GLOBAL_TRANSITIONS: LayoutInputTransition[] = [
    { from: null, to: 'done', name: '강제 완료', kind: 'GLOBAL' },
  ]

  it('전역 전환은 패널로 나온다', () => {
    renderCanvas({ transitions: GLOBAL_TRANSITIONS })

    const panel = screen.getByLabelText(labels.diagram.globalPanel)
    expect(panel).toHaveTextContent('강제 완료')
  })

  it('전역 전환이 없으면 패널을 그리지 않는다', () => {
    renderCanvas()

    expect(screen.queryByLabelText(labels.diagram.globalPanel)).not.toBeInTheDocument()
  })

  it('패널 항목을 누르면 그 전환의 편집을 요청한다', async () => {
    const onEditTransition = vi.fn()
    renderCanvas({ transitions: GLOBAL_TRANSITIONS, onEditTransition })

    await userEvent.click(screen.getByRole('button', { name: /강제 완료/ }))

    // 초안 전환에는 id 가 없어 배열 위치가 유일한 identity 다.
    expect(onEditTransition).toHaveBeenCalledWith(0)
  })
})

describe('잠금 (E12)', () => {
  it('잠기면 안내를 보여준다', () => {
    renderCanvas({ locked: true })

    expect(screen.getByText(labels.diagram.lockedHint)).toBeInTheDocument()
  })

  it('잠기지 않으면 안내가 없다', () => {
    renderCanvas()

    expect(screen.queryByText(labels.diagram.lockedHint)).not.toBeInTheDocument()
  })
})
