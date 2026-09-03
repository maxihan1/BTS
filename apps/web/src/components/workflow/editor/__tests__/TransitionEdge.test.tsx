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

function edgeProps(
  name: string,
  extra: Partial<TransitionEdgeType['data']> = {},
): EdgeProps<TransitionEdgeType> {
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
    data: { name, ...extra },
  }
}

function renderEdge(name: string, extra: Partial<TransitionEdgeType['data']> = {}) {
  const { container } = render(
    <ReactFlowProvider>
      <EdgeHost>
        <TransitionEdge {...edgeProps(name, extra)} />
      </EdgeHost>
    </ReactFlowProvider>,
  )
  const host = container.querySelector('.react-flow__edgelabel-renderer')
  if (host === null) throw new Error('라벨 포털 host 를 찾지 못했다')
  return { host, container }
}

/** 간선 경로의 `d` 속성. 경로 형태가 실제로 갈리는지 재는 유일한 관찰점이다. */
function pathOf(container: HTMLElement): string {
  const d = container.querySelector('path.react-flow__edge-path')?.getAttribute('d')
  if (d === null || d === undefined) throw new Error('간선 경로를 찾지 못했다')
  return d
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

/**
 * ★ 게이트 2 리뷰가 잡은 BLOCKER 의 회귀 가드.
 *
 * `lib/workflow-layout.ts` 가 `offset`·`selfLoop` 을 계산해 캔버스가 `data` 로 실었는데
 * **이 컴포넌트가 그것을 읽지 않아** F10(self-loop)·F12(다중 전환 벌림)가 화면에 없었다.
 * 순수 함수 테스트는 offset **값**만 재므로 계속 초록이었고, 목 픽스처에 중복 쌍도 self-loop 도
 * 없어 눈확인으로도 관찰이 불가능했다.
 *
 * 그래서 판정을 **경로의 `d` 속성**에 건다 — 픽셀 없이 「형태가 실제로 갈리는가」를 잰다.
 */
describe('경로 형태 (F10 · F12)', () => {
  it('offset 이 다르면 경로가 다르다 — 같은 쌍 간선이 겹치지 않는다', () => {
    const straight = pathOf(renderEdge('A', { offset: 0 }).container)
    const bentLeft = pathOf(renderEdge('B', { offset: 40 }).container)
    const bentRight = pathOf(renderEdge('C', { offset: -40 }).container)

    expect(bentLeft).not.toBe(straight)
    expect(bentRight).not.toBe(straight)
    // 좌우로 대칭 분산하므로 둘도 서로 달라야 한다.
    expect(bentLeft).not.toBe(bentRight)
  })

  it('self-loop 은 호를 그린다 — 베지에가 아니다', () => {
    const loop = pathOf(renderEdge('되돌리기', { selfLoop: true, offset: 40 }).container)

    // 호 명령(A)이 있어야 노드 뒤로 숨지 않는다. 베지에(C/Q)만 있으면 직선에 가깝다.
    expect(loop).toContain('A ')
    expect(loop).not.toBe(pathOf(renderEdge('되돌리기', { offset: 40 }).container))
  })

  it('self-loop 은 offset 이 0 이어도 보이는 반지름을 갖는다', () => {
    // 한 개뿐인 self-loop 은 벌릴 이웃이 없어 offset 이 0 이다. 하한이 없으면 노드에 가린다.
    const loop = pathOf(renderEdge('되돌리기', { selfLoop: true, offset: 0 }).container)

    const radius = Number(/A (\d+(?:\.\d+)?)/.exec(loop)?.[1] ?? '0')
    expect(radius).toBeGreaterThan(0)
  })
})

/** 라벨 상자의 `translate(...)` 문자열. 라벨이 실제로 어디 놓였는지 재는 유일한 관찰점이다. */
function labelTransformOf(host: Element): string {
  const box = host.firstElementChild
  if (!(box instanceof HTMLElement)) throw new Error('라벨 상자를 찾지 못했다')
  return box.style.transform
}

describe('라벨 자리 (부채 168)', () => {
  it('labelOffset 을 받으면 라벨이 그만큼 밀린다', () => {
    // ★ lib 이 겹침을 풀어도 컴포넌트가 그 값을 안 쓰면 화면은 그대로다. 그 사이를 재는 판정이다.
    const plain = labelTransformOf(renderEdge('검토 요청').host)
    const shifted = labelTransformOf(renderEdge('검토 요청', { labelOffset: { x: 40, y: -24 } }).host)

    expect(shifted).not.toBe(plain)
  })

  it('labelOffset 이 없으면 라벨은 간선 중점 그대로다', () => {
    // 굽힐 이유가 없는 라벨까지 밀면 어느 간선의 이름인지 흐려진다
    const plain = labelTransformOf(renderEdge('검토 요청').host)
    const zero = labelTransformOf(renderEdge('검토 요청', { labelOffset: { x: 0, y: 0 } }).host)

    expect(zero).toBe(plain)
  })
})
