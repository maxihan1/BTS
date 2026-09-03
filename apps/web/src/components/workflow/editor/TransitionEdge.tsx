// 다이어그램 캔버스의 전환 간선 — 곡선 경로와 이름 라벨만 그린다 (FR-WF-07 D8)
import * as React from 'react'
import { BaseEdge, EdgeLabelRenderer, getBezierPath } from '@xyflow/react'
import type { Edge, EdgeProps } from '@xyflow/react'

/** 간선 하나가 캔버스에서 받는 데이터. 종류(GLOBAL/INITIAL) 판정은 캔버스가 이미 끝냈다. */
interface TransitionEdgeData extends Record<string, unknown> {
  /** 전환 이름. 빈 문자열이면 라벨 상자를 그리지 않는다 */
  name: string
  /** 중심선에서 밀어낼 거리(px). `lib/workflow-layout.ts` 가 같은 상태쌍 간선을 벌린 결과다 */
  offset?: number
  /** 출발과 도착이 같은 전환인가. 참이면 베지에가 아니라 호를 그린다 */
  selfLoop?: boolean
  /**
   * 라벨을 중점에서 추가로 밀어낼 벡터(px). `lib/workflow-layout.ts` 가 **다른 상태쌍**의
   * 간선끼리 중점이 한 자리에 모인 것을 풀어 준 결과다 — `offset` 과 다른 문제를 푼다.
   */
  labelOffset?: { x: number; y: number }
}

/**
 * self-loop 고리의 최소 반지름(px).
 *
 * `offset` 이 0 에 가까우면 고리가 노드 뒤로 숨는다 — 한 개뿐인 self-loop 은 벌릴 이웃이 없어
 * offset 이 0 으로 나오므로, 그 경우에도 보이게 하한을 둔다.
 */
const SELF_LOOP_MIN_RADIUS_PX = 16

/**
 * self-loop 고리의 최대 반지름(px). 노드 높이(`min-h-11` = 44px)와 같다.
 *
 * **상한이 없으면 고리가 이웃 행을 삼킨다** (부채 170). 반지름 60 은 지름 120 으로
 * 행 간격(`ROW_GAP_PX` = 120)과 정확히 같아, 아래 행 노드에 원이 걸쳤다.
 * 상한은 `lib/workflow-layout.ts` 의 `selfLoopOffset` 에도 있다 — 여기 것은 마지막 방어선이다.
 */
const SELF_LOOP_MAX_RADIUS_PX = 44

/** 라벨을 고리 바깥으로 더 밀어낼 여백(px). 라벨 상자가 고리 선에 닿지 않을 만큼이다. */
const SELF_LOOP_LABEL_GAP_PX = 10

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
 * self-loop 고리의 반지름. 하한과 상한 사이로 접는다.
 *
 * @param offset `lib/workflow-layout.ts` 의 `selfLoopOffset` 이 준 크기
 */
function selfLoopRadius(offset: number): number {
  return Math.min(
    Math.max(Math.abs(offset), SELF_LOOP_MIN_RADIUS_PX),
    SELF_LOOP_MAX_RADIUS_PX,
  )
}

/**
 * self-loop 경로. **출발 핸들의 오른쪽 바깥에 붙은 온전한 원**을 직접 그린다.
 *
 * `getBezierPath` 는 출발·도착이 같은 노드면 `sourceY === targetY` 인 거의 직선을 내놓아
 * 곡선이 노드 몸통 뒤로 완전히 숨는다. 그래서 이 경우만 경로를 직접 만든다.
 *
 * ### 반원 둘로 나눠 그리는 이유 (부채 170)
 * 전에는 시작점과 끝점을 1px 어긋나게 둔 큰 호 하나(`A r,r 0 1 1 x-1,y-1`)였다. 그러면 원의
 * 중심이 **호 플래그가 정하는 대로** 놓여 시계 방향으로 아래를 향했고, 고리가 아래 행 노드를
 * 관통했다. 중심을 `(x + r, y)` 로 **못박으면** 원이 출발 핸들 오른쪽에만 존재한다 —
 * 노드는 핸들의 왼쪽에 있으므로 어느 이웃도 뚫지 않는다.
 *
 * @param x 출발 핸들 X · @param y 출발 핸들 Y · @param r 고리 반지름(px)
 */
function selfLoopPath(x: number, y: number, r: number): string {
  const far = x + r * 2
  // (x,y) → 위로 반원 → (far,y) → 아래로 반원 → (x,y). 중심은 항상 (x + r, y) 다.
  return `M ${x},${y} A ${r},${r} 0 1 1 ${far},${y} A ${r},${r} 0 1 1 ${x},${y}`
}

/**
 * 같은 상태쌍에 몰린 간선을 중심선에서 밀어낸 베지에 경로.
 *
 * 제어점을 법선 방향으로 `offset` 만큼 옮긴다. `offset` 이 0 이면 직선 베지에와 같다.
 *
 * @returns `[path, labelX, labelY]` — 라벨도 같은 만큼 밀어야 선 위에 얹힌다
 */
function offsetPath(
  sourceX: number,
  sourceY: number,
  targetX: number,
  targetY: number,
  offset: number,
): [string, number, number] {
  const midX = (sourceX + targetX) / 2
  const midY = (sourceY + targetY) / 2
  const dx = targetX - sourceX
  const dy = targetY - sourceY
  const length = Math.hypot(dx, dy) || 1
  // 법선 = 진행 방향을 90도 돌린 단위 벡터. 부호가 좌우를 가른다.
  const normalX = -(dy / length) * offset
  const normalY = (dx / length) * offset
  const controlX = midX + normalX
  const controlY = midY + normalY

  // 2차 베지에라 곡선 중점은 제어점의 절반만큼만 밀린다 — 라벨을 그 자리에 둔다.
  return [
    `M ${sourceX},${sourceY} Q ${controlX},${controlY} ${targetX},${targetY}`,
    midX + normalX / 2,
    midY + normalY / 2,
  ]
}

/**
 * 캔버스 위 전환 간선.
 *
 * **경로 형태의 결정은 `lib/workflow-layout.ts` 가 한다** — 이 컴포넌트는 그 결과(`offset`·
 * `selfLoop`)를 SVG path 로 옮기기만 한다. 어느 전환을 어느 노드에 잇는지는 캔버스의 몫이다.
 *
 * ### 세 갈래인 이유 (F10 · F12)
 * - `selfLoop` — 출발과 도착이 같다. 베지에는 거의 직선이 돼 노드 뒤에 숨으므로 호를 직접 그린다
 * - `offset !== 0` — 같은 상태쌍에 간선이 여럿이다. 겹치면 사용자가 어느 것을 고르는지 알 수 없다
 * - 그 밖 — 홑간선. `getBezierPath` 기본값이 가장 자연스럽다
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
  const offset = typeof data?.offset === 'number' ? data.offset : 0
  const selfLoop = data?.selfLoop === true
  const labelShift = data?.labelOffset ?? { x: 0, y: 0 }

  let path: string
  let labelX: number
  let labelY: number

  if (selfLoop) {
    const radius = selfLoopRadius(offset)
    path = selfLoopPath(sourceX, sourceY, radius)
    // 고리의 가장 바깥 점 너머에 둔다 — 고리 안이나 위쪽 빈 자리에 두면 주인을 알 수 없다
    labelX = sourceX + radius * 2 + SELF_LOOP_LABEL_GAP_PX
    labelY = sourceY
  } else if (offset !== 0) {
    ;[path, labelX, labelY] = offsetPath(sourceX, sourceY, targetX, targetY, offset)
  } else {
    ;[path, labelX, labelY] = getBezierPath({
      sourceX,
      sourceY,
      sourcePosition,
      targetX,
      targetY,
      targetPosition,
    })
  }

  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} />
      <EdgeLabelRenderer>
        <TransitionEdgeLabel
          name={data?.name ?? ''}
          x={labelX + labelShift.x}
          y={labelY + labelShift.y}
        />
      </EdgeLabelRenderer>
    </>
  )
}

export { TransitionEdge }
export type { TransitionEdgeData, TransitionEdgeType }
