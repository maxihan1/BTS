// 상태 노드 판정 — 이름 · 카테고리 색 · hover 핸들(J4) · 잠금(E12)
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ReactFlowProvider } from '@xyflow/react'
import { StatusNode } from '../StatusNode'
import type { StatusNodeData } from '../StatusNode'

/**
 * `Handle` 은 `useHandleConfig()` 로 컨텍스트를 요구하고 없으면 **throw** 한다.
 * `ReactFlowProvider` 가 그 provider 를 품고 있으므로 그것으로 감싸면 진짜 `Handle` 이 뜬다 —
 * `@xyflow/react` 를 mock 하면 핸들 클래스가 통째로 사라져 아래 판정이 공허해진다.
 */
function renderNode(overrides: Partial<StatusNodeData> = {}) {
  const data: StatusNodeData = { name: '할 일', category: 'TODO', locked: false, ...overrides }
  const { container } = render(
    <ReactFlowProvider>
      <StatusNode data={data} />
    </ReactFlowProvider>,
  )
  const root = container.firstElementChild
  if (!(root instanceof HTMLElement)) throw new Error('노드 루트를 찾지 못했다')
  return { root, handles: Array.from(root.querySelectorAll('.react-flow__handle')) }
}

describe('StatusNode', () => {
  it('상태 이름을 그린다', () => {
    renderNode({ name: '검토 중' })
    expect(screen.getByText('검토 중')).toBeInTheDocument()
  })

  it('카테고리별로 다른 클래스를 준다', () => {
    const todo = renderNode({ category: 'TODO' }).root.className
    const inProgress = renderNode({ category: 'IN_PROGRESS' }).root.className
    const done = renderNode({ category: 'DONE' }).root.className

    expect(new Set([todo, inProgress, done]).size).toBe(3)

    /*
     * ★ 값까지 못박는다. `WorkflowDiagram.tsx` 의 mermaid `classDef category_*` 3행과 **같은 색**이라야
     * 읽기 전용 `/workflows/{key}` 와 편집기 `/admin/workflows/{key}` 가 같은 제품처럼 보인다.
     * 「서로 다르기만 하면 통과」로 두면 색이 갈라져도 초록이다.
     */
    expect(todo).toContain('bg-muted')
    expect(todo).toContain('border-border')
    expect(inProgress).toContain('bg-primary/15')
    expect(inProgress).toContain('border-primary')
    expect(done).toContain('bg-success/15')
    expect(done).toContain('border-success')
  })

  it('hover 하면 연결 핸들이 나타난다', () => {
    const { root, handles } = renderNode()

    expect(handles).toHaveLength(2)
    /*
     * jsdom 은 Tailwind CSS 를 적용하지 않으므로 `userEvent.hover` 로는 opacity 가 변하지 않는다.
     * 그래서 **발화 조건 전부**를 판정한다 — `group-hover:` 는 조상에 `group` 이 있어야만 걸리므로
     * 둘 중 하나라도 빠지면 핸들은 영원히 안 보인다(그리고 이 판정이 red 가 된다).
     *
     * 상태(useState)로 조건부 렌더하지 않는 이유는 컴포넌트 KDoc 에 있다 —
     * 핸들 DOM 이 사라지면 간선이 통째로 안 그려진다.
     */
    expect(root.classList.contains('group')).toBe(true)
    for (const handle of handles) {
      expect(root.contains(handle)).toBe(true)
      expect(handle.className).toContain('opacity-0')
      expect(handle.className).toContain('group-hover:opacity-100')
    }
  })

  it('잠긴 워크플로우면 핸들이 나타나지 않는다', () => {
    const { handles } = renderNode({ locked: true })

    // DOM 에는 남는다 — 지우면 xyflow 가 간선 끝을 못 잡아 잠긴 워크플로우의 간선이 통째로 사라진다
    expect(handles).toHaveLength(2)
    for (const handle of handles) {
      expect(handle.className).not.toContain('group-hover:opacity-100')
      // xyflow 가 `isConnectable` 을 그대로 클래스로 내린다 — 연결을 시작할 수 없다는 뜻
      expect(handle.classList.contains('connectable')).toBe(false)
    }
  })
})
