// 전환 간선 판정 — 이름 라벨 · 빈 이름이면 라벨 없음
import * as React from 'react'
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Position, ReactFlowProvider, useStoreApi } from '@xyflow/react'
import type { EdgeProps } from '@xyflow/react'
import { TransitionEdge } from '../TransitionEdge'
import type { TransitionEdgeType } from '../TransitionEdge'

/**
 * `EdgeLabelRenderer` 는 스토어의 `domNode` 안에서 `.react-flow__edgelabel-renderer` 를 찾아
 * 그리로 포털을 연다. 평소에는 `<ReactFlow>` 가 그 DOM 을 만들지만, 여기서는 **진짜 스토어에
 * 진짜 host 를 꽂아** 같은 경로를 열어 준다.
 *
 * ★ `@xyflow/react` 를 mock 하지 않는 이유. 목이 `EdgeLabelRenderer` 를 삼키면 라벨이 무조건
 * 보여 「빈 이름이면 안 그린다」 판정이 통째로 공허해진다.
 */
function EdgeHost({ children }: { children: React.ReactNode }): React.JSX.Element {
  const store = useStoreApi()
  const hostRef = React.useRef<HTMLDivElement>(null)
  const [ready, setReady] = React.useState(false)

  React.useEffect(() => {
    const host = hostRef.current
    if (host === null) return
    store.setState({ domNode: host })
    setReady(true)
  }, [store])

  return (
    <div ref={hostRef}>
      <div className="react-flow__edgelabel-renderer" />
      <svg>{ready ? children : null}</svg>
    </div>
  )
}

function edgeProps(name: string): EdgeProps<TransitionEdgeType> {
  return {
    id: 'e1',
    source: 'todo',
    target: 'done',
    sourceX: 0,
    sourceY: 0,
    sourcePosition: Position.Right,
    targetX: 200,
    targetY: 120,
    targetPosition: Position.Left,
    data: { name },
  }
}

function renderEdge(name: string) {
  const { container } = render(
    <ReactFlowProvider>
      <EdgeHost>
        <TransitionEdge {...edgeProps(name)} />
      </EdgeHost>
    </ReactFlowProvider>,
  )
  const host = container.querySelector('.react-flow__edgelabel-renderer')
  if (host === null) throw new Error('라벨 포털 host 를 찾지 못했다')
  return { host, container }
}

describe('TransitionEdge', () => {
  it('전환 이름을 라벨로 그린다', () => {
    const { host, container } = renderEdge('검토 요청')

    expect(screen.getByText('검토 요청')).toBeInTheDocument()
    // 포털 밖(SVG 안)에 그리면 다른 노드에 가려 안 보인다 — host 안에 들어갔는지까지 본다
    expect(host.textContent).toBe('검토 요청')
    // 간선 경로도 함께 나온다 — 라벨만 있고 선이 없으면 그림이 아니다
    expect(container.querySelector('path.react-flow__edge-path')).not.toBeNull()
  })

  it('이름이 빈 문자열이면 라벨을 그리지 않는다', () => {
    const { host, container } = renderEdge('')

    // 빈 라벨 상자가 뜨면 간선을 가리고 「이름 없는 무엇」이 하나 더 있는 것처럼 보인다
    expect(host.childElementCount).toBe(0)
    expect(container.querySelector('path.react-flow__edge-path')).not.toBeNull()
  })
})
