// 다이어그램 캔버스의 전환 간선 — 곡선 경로와 이름 라벨만 그린다 (FR-WF-07 D8)
import * as React from 'react'
import { BaseEdge, EdgeLabelRenderer, getBezierPath } from '@xyflow/react'
import type { Edge, EdgeProps } from '@xyflow/react'

/** 간선 하나가 캔버스에서 받는 데이터. 종류(GLOBAL/INITIAL) 판정은 캔버스가 이미 끝냈다. */
interface TransitionEdgeData extends Record<string, unknown> {
  /** 전환 이름. 빈 문자열이면 라벨 상자를 그리지 않는다 */
  name: string
}

/** 캔버스가 등록하는 간선 타입 — `edgeTypes={{ transition: TransitionEdge }}` */
type TransitionEdgeType = Edge<TransitionEdgeData, 'transition'>

interface TransitionEdgeLabelProps {
  name: string
  /** 캔버스 좌표계 기준 라벨 중심 */
  x: number
  y: number
}

/**
 * 간선 위에 얹는 이름 상자.
 *
 * **이름이 비면 아무것도 그리지 않는다.** 빈 라벨 상자가 뜨면 간선을 가리기만 하고
 * 사용자에게는 「이름 없는 무엇」이 하나 더 있는 것처럼 보인다.
 *
 * SVG 가 아니라 HTML 이라 배경·라운드에 BTS 토큰을 그대로 쓸 수 있다. 대신 간선의 SVG
 * 바깥(`EdgeLabelRenderer` 포털)에 그려지므로 좌표를 직접 받아 translate 한다.
 */
function TransitionEdgeLabel({ name, x, y }: TransitionEdgeLabelProps): React.JSX.Element | null {
  if (name === '') return null

  return (
    <div
      // 좌표는 간선마다 달라 클래스로 못 뽑는다 — 토큰이 아니라 위치라서 인라인 style 이 유일한 길이다
      style={{ transform: `translate(-50%, -50%) translate(${x}px, ${y}px)` }}
      className="bg-card text-foreground ring-foreground/10 pointer-events-none absolute rounded-md px-1.5 py-0.5 text-xs ring-1"
    >
      {name}
    </div>
  )
}

/**
 * 캔버스 위 전환 간선.
 *
 * **순수 프레젠테이션이다.** self-loop 곡률·다중 전환 offset 같은 경로 계산은
 * `lib/workflow-layout.ts`, 어느 전환을 어느 노드에 잇는지는 캔버스의 몫이다.
 * 여기서는 xyflow 가 넘겨준 양 끝 좌표로 베지에 경로 하나를 그린다.
 *
 * ### 선 색을 여기서 주지 않는 이유
 * `.react-flow__edge-path` 의 `stroke` 는 레이어 밖 규칙이라 Tailwind 유틸리티
 * (`@layer utilities`)가 **진다**. 여기에 `stroke-*` 를 적으면 안 먹는데 적혀 있는
 * 거짓 코드가 된다 — 선 색 정본은 캔버스의 `--xy-edge-stroke` 덮어쓰기다.
 */
function TransitionEdge({
  id,
  sourceX,
  sourceY,
  sourcePosition,
  targetX,
  targetY,
  targetPosition,
  markerEnd,
  data,
}: EdgeProps<TransitionEdgeType>): React.JSX.Element {
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  })

  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} />
      <EdgeLabelRenderer>
        <TransitionEdgeLabel name={data?.name ?? ''} x={labelX} y={labelY} />
      </EdgeLabelRenderer>
    </>
  )
}

export { TransitionEdge }
export type { TransitionEdgeData, TransitionEdgeType }
