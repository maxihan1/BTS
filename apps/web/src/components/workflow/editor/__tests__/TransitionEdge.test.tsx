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

/** 경로 문자열에서 좌표쌍을 모두 뽑는다. `A rx,ry rot large sweep x,y` 의 끝 두 수만 좌표다. */
function pointsOf(path: string): { x: number; y: number }[] {
  const points: { x: number; y: number }[] = []
  const move = /M (-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/g
  const arc = /A [\d.]+,[\d.]+ \d+ \d+ \d+ (-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/g

  for (const source of [move, arc]) {
    for (const match of path.matchAll(source)) {
      points.push({ x: Number(match[1]), y: Number(match[2]) })
    }
  }
  if (points.length === 0) throw new Error(`경로에서 좌표를 못 뽑았다: ${path}`)
  return points
}

/** 경로의 첫 `A` 명령이 쓴 반지름. */
function radiusOf(path: string): number {
  const found = /A (\d+(?:\.\d+)?),/.exec(path)
  if (found === null) throw new Error(`경로에 호가 없다: ${path}`)
  return Number(found[1])
}

describe('self-loop 자리 (부채 170)', () => {
  it('고리가 출발 핸들의 오른쪽 바깥에만 있다', () => {
    /*
     * 2026-09-03 실측. 시계 방향 큰 호가 출발점에서 아래로 돌아 `In Progress` 와 `In Review`
     * 를 관통했다 — 선이 뚫고 지난 노드가 그 전환과 관계있는 것처럼 읽힌다.
     * 출발 핸들은 노드 오른쪽 중앙이므로, 고리가 그 x 보다 왼쪽으로 가지 않으면 노드를 안 뚫는다.
     */
    const loop = pathOf(renderEdge('재작업', { selfLoop: true, offset: 40 }).container)

    // edgeProps 의 sourceX 는 0 이다 — 음수 x 가 하나라도 있으면 노드 쪽으로 넘어간 것이다
    for (const point of pointsOf(loop)) {
      expect(point.x).toBeGreaterThanOrEqual(0)
    }
  })

  it('고리 반지름이 노드 높이를 넘지 않는다', () => {
    // 반지름 60 은 지름 120 으로 행 간격(120)과 정확히 같아 아래 행 노드에 걸쳤다
    const loop = pathOf(renderEdge('재작업', { selfLoop: true, offset: 200 }).container)

    // min-h-11 = 44px. 그보다 큰 고리는 이웃 행까지 뻗는다
    expect(radiusOf(loop)).toBeLessThanOrEqual(44)
  })

  it('라벨이 고리 바깥에 붙는다', () => {
    /*
     * 「재작업」이 캔버스 위쪽 빈 자리에 홀로 떠 어느 전환의 이름인지 알 수 없었다.
     * 고리의 가장 바깥 점보다 멀리 두면 고리에 가리지 않고, 고리에 붙어 있어 주인이 읽힌다.
     */
    const { container, host } = renderEdge('재작업', { selfLoop: true, offset: 40 })
    const radius = radiusOf(pathOf(container))
    const transform = labelTransformOf(host)

    const shifted = /translate\((-?\d+(?:\.\d+)?)px, (-?\d+(?:\.\d+)?)px\)/.exec(transform)
    if (shifted === null) throw new Error(`라벨 좌표를 못 읽었다: ${transform}`)

    // 고리는 sourceX(0) 에서 오른쪽으로 지름만큼 뻗는다. 라벨은 그 바깥이다.
    expect(Number(shifted[1])).toBeGreaterThan(radius * 2)
    // 세로로는 출발 핸들 높이를 크게 벗어나지 않는다 — 위로 달아나면 주인을 잃는다
    expect(Math.abs(Number(shifted[2]))).toBeLessThanOrEqual(radius)
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
