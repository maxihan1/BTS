// 다이어그램 캔버스 — 초안을 그래프로 그리고 조작을 콜백으로 올린다 (FR-WF-07 D8)
import * as React from 'react'
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Controls,
  Handle,
  Position,
  useNodesState,
  useEdgesState,
} from '@xyflow/react'
import type { Connection, Edge, Node } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { autoLayout, edgeRoutes, startNodeX, START_NODE_ID } from '@/lib/workflow-layout'
import type { LayoutInputState, LayoutInputTransition } from '@/lib/workflow-layout'
import { StatusNode } from './StatusNode'
import { TransitionEdge } from './TransitionEdge'

/** 캔버스가 그리는 상태 — 이름까지 필요해 레이아웃 입력보다 한 필드 넓다. */
interface CanvasState extends LayoutInputState {
  readonly name: string
}

interface WorkflowEditorCanvasProps {
  states: readonly CanvasState[]
  transitions: readonly LayoutInputTransition[]
  /** 잠긴 워크플로우면 배치·연결이 모두 막힌다 (엣지 E12) */
  locked: boolean
  /** 노드를 놓았을 때. **자동 배치 결과는 여기로 오지 않는다** — 사용자가 끈 것만 올린다 */
  onMoveState: (key: string, x: number, y: number) => void
  /** 핸들을 이어 놓았을 때. 부모가 기존 「전환 수정」 다이얼로그를 프리필로 연다 */
  onCreateTransition: (from: string, to: string) => void
  /** 간선을 골랐을 때. 초안 전환에는 id 가 없어 배열 위치가 identity 다 */
  onEditTransition: (transitionIndex: number) => void
}

/**
 * 출발지 없는 전환이 나오는 가상 시작점. mermaid 화면의 `[*]` 와 같은 자리다.
 *
 * 라벨을 글자로 쓰지 않고 원으로 두는 것이 mermaid 와의 시각적 대응이다 — 이름을 붙이면
 * 사용자가 그것을 상태 하나로 읽는다.
 */
function StartNode(): React.JSX.Element {
  return (
    <div
      aria-label={labels.diagram.initialNode}
      className="bg-foreground/70 h-4 w-4 rounded-full"
      role="img"
    >
      {/* 시작점은 오른쪽으로만 나간다. id 규칙은 상태 노드와 같아야 간선이 이 면을 찾는다 */}
      <Handle type="source" id="s-right" position={Position.Right} isConnectable={false} />
    </div>
  )
}

const NODE_TYPES = { status: StatusNode, start: StartNode } as const
const EDGE_TYPES = { transition: TransitionEdge } as const

/**
 * xyflow 기본 스타일시트가 자기 팔레트를 갖고 온다. 그대로 두면 다크에서 배경·간선·컨트롤이
 * BTS 토큰과 어긋나므로 `--xy-*` 를 여기서 덮어쓴다 (NFR N3).
 *
 * 값을 하드코딩하지 않고 전부 토큰을 경유한다 — 라이트/다크 전환이 자동으로 따라온다.
 */
const XY_TOKEN_OVERRIDES: React.CSSProperties = {
  '--xy-background-color': 'var(--background)',
  '--xy-edge-stroke': 'var(--border)',
  '--xy-edge-stroke-selected': 'var(--primary)',
  '--xy-handle-background-color': 'var(--primary)',
  '--xy-handle-border-color': 'var(--background)',
  '--xy-controls-button-background-color': 'var(--card)',
  '--xy-controls-button-background-color-hover': 'var(--muted)',
  '--xy-controls-button-color': 'var(--foreground)',
  '--xy-controls-button-border-color': 'var(--border)',
  '--xy-attribution-background-color': 'transparent',
} as React.CSSProperties

/** 초안 상태·전환을 xyflow 노드/간선으로 옮긴다. 좌표 계산은 순수 함수(T2·T3)가 이미 끝냈다. */
function buildGraph(
  states: readonly CanvasState[],
  transitions: readonly LayoutInputTransition[],
  locked: boolean,
): { nodes: Node[]; edges: Edge[]; globals: ReturnType<typeof edgeRoutes>['globals'] } {
  const placed = autoLayout(states)
  // 키로 한 번만 색인한다. `placed` 의 모든 항목은 `states` 에서 나왔으므로 조회는 반드시 맞고,
  // 배열 `find` 를 노드마다 도는 O(n²) 와 「못 찾으면 TODO」 같은 **도달 불가 폴백**을 함께 없앤다.
  // 도달 불가 폴백은 지워도 아무 판정이 red 가 안 되므로, 두면 다음 사람이 그것을 실제 분기로 읽는다.
  const byKey = new Map(states.map((s) => [s.key, s]))
  const routes = edgeRoutes(transitions, placed)

  const nodes: Node[] = placed.flatMap((p) => {
    const state = byKey.get(p.key)
    if (state === undefined) return []
    return [
      {
        id: p.key,
        type: 'status',
        position: { x: p.x, y: p.y },
        draggable: !locked,
        data: { name: state.name, category: state.category, locked },
      },
    ]
  })

  if (routes.hasStartNode) {
    // ★ 자리 계산은 `startNodeX` 가 정본이다. 여기서 따로 셈하면 라벨 겹침 판정이 보는 자리와
    //   실제로 그리는 자리가 갈리고, 그러면 시작 간선의 라벨만 엉뚱한 곳에서 판정된다.
    nodes.unshift({
      id: START_NODE_ID,
      type: 'start',
      position: { x: startNodeX(placed), y: 0 },
      draggable: false,
      selectable: false,
      data: {},
    })
  }

  const edges: Edge[] = routes.edges.map((e) => ({
    id: e.id,
    type: 'transition',
    source: e.source,
    target: e.target,
    // ★ 핸들 id 를 실어야 네 면 중 어느 쪽에 붙을지가 정해진다. 빼면 xyflow 가 기본 핸들
    //   하나만 써서 모든 간선이 오른쪽→왼쪽으로 고정된다 — 세로 이웃이 U 자로 돈다.
    sourceHandle: `s-${e.sourceSide}`,
    targetHandle: `t-${e.targetSide}`,
    data: {
      name: e.label,
      transitionIndex: e.transitionIndex,
      offset: e.offset,
      selfLoop: e.selfLoop,
      labelOffset: e.labelOffset,
    },
  }))

  return { nodes, edges, globals: routes.globals }
}

function CanvasInner({
  states,
  transitions,
  locked,
  onMoveState,
  onCreateTransition,
  onEditTransition,
}: WorkflowEditorCanvasProps): React.JSX.Element {
  const { nodes, edges, globals } = React.useMemo(
    () => buildGraph(states, transitions, locked),
    [states, transitions, locked],
  )

  /**
   * xyflow 가 자기 상태를 갖게 한다.
   *
   * ★ **`nodes`/`edges` 를 controlled 로 주면서 `onNodesChange` 를 안 주면 변경이 전부 버려진다.**
   * 스토어는 `hasDefaultNodes` 가 거짓이면 `onNodesChange?.(changes)` 를 부르고 끝내는데 그것이
   * undefined 이기 때문이다. 결과가 둘이었다 — ① 노드를 끌면 커서만 가고 노드는 제자리에 있다가
   * `onNodeDragStop` 뒤 리렌더에서 점프한다 ② `select` 변경도 같은 경로라 선택이 영영 안 걸려
   * `--xy-edge-stroke-selected` 가 도달 불가 토큰이 된다.
   *
   * E2E 는 `mouse.up` **뒤에** 좌표를 재므로 ①을 못 잡는다 — 게이트 2 리뷰가 코드로 잡았다.
   *
   * 초안이 바뀌면(상태 추가·삭제·다른 탭 편집) 계산 결과를 다시 seed 한다. 드래그 중 좌표는
   * 로컬에만 있고, 놓는 순간 `onNodeDragStop` 이 초안으로 올린다.
   */
  const [liveNodes, setLiveNodes, handleNodesChange] = useNodesState(nodes)
  const [liveEdges, setLiveEdges, handleEdgesChange] = useEdgesState(edges)

  React.useEffect(() => {
    /*
     * ★ **선택 상태를 살려서 갈아끼운다.** `nodes` 는 초안이 바뀔 때마다 새로 만들어지는데
     *   그 결과에는 `selected` 가 없다 — 그대로 덮으면 **노드를 끌어 놓는 순간 선택 링이
     *   사라진다.** 드래그는 `onNodeDragStop → onMoveState` 로 초안을 바꾸므로 이 효과가
     *   반드시 뒤따르고, 그래서 「고른 것을 화면이 알려 준다」(부채 169)가 편집을 시작하는
     *   순간 무너졌다. 선택은 xyflow 가 들고 있는 화면 상태이지 초안의 일부가 아니므로,
     *   초안이 바뀌었다고 잃을 이유가 없다.
     */
    setLiveNodes((previous) => {
      const selected = new Set(previous.filter((node) => node.selected).map((node) => node.id))
      if (selected.size === 0) return nodes
      return nodes.map((node) => (selected.has(node.id) ? { ...node, selected: true } : node))
    })
  }, [nodes, setLiveNodes])

  React.useEffect(() => {
    setLiveEdges(edges)
  }, [edges, setLiveEdges])

  const handleConnect = React.useCallback(
    (connection: Connection) => {
      // 시작점에서 끄는 것은 전환 생성이 아니다 — 그 자리는 INITIAL 전환 편집이 맡는다.
      if (connection.source === null || connection.target === null) return
      if (connection.source === START_NODE_ID) return
      onCreateTransition(connection.source, connection.target)
    },
    [onCreateTransition],
  )

  const handleEdgeClick = React.useCallback(
    (_event: React.MouseEvent, edge: Edge) => {
      const index = edge.data?.transitionIndex
      if (typeof index === 'number') onEditTransition(index)
    },
    [onEditTransition],
  )

  if (states.length === 0) {
    // 빈 캔버스를 그대로 두지 않는다. 「상태가 없다」와 「그릴 것이 없다」는 다른 사실이라
    // 상태 패널의 문구를 돌려 쓰지 않는다 (F16).
    return <EmptyState title={labels.diagram.empty} />
  }

  return (
    <div aria-label={labels.diagram.canvasLabel} className="relative h-[32rem] w-full" style={XY_TOKEN_OVERRIDES}>
      <ReactFlow
        nodes={liveNodes}
        edges={liveEdges}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        nodeTypes={NODE_TYPES}
        edgeTypes={EDGE_TYPES}
        nodesConnectable={!locked}
        elementsSelectable
        fitView
        onNodeDragStop={(_e, node) => {
          onMoveState(node.id, node.position.x, node.position.y)
        }}
        onConnect={handleConnect}
        onEdgeClick={handleEdgeClick}
      >
        <Background />
        <Controls showInteractive={false} aria-label={labels.diagram.fitView} />
      </ReactFlow>

      {globals.length > 0 ? (
        // 전역 전환은 간선으로 그리지 않는다 — 모든 노드에서 선을 뽑으면 화면을 못 읽는다 (F11 · E6).
        <aside
          aria-label={labels.diagram.globalPanel}
          className="bg-card ring-foreground/10 absolute top-2 left-2 rounded-md px-2 py-1.5 text-xs ring-1"
        >
          <p className="text-muted-foreground mb-1 font-medium">{labels.diagram.globalPanel}</p>
          <ul>
            {globals.map((g) => (
              <li key={g.transitionIndex}>
                <Button
                  variant="link"
                  size="sm"
                  className="h-auto justify-start p-0 text-xs"
                  onClick={() => {
                    onEditTransition(g.transitionIndex)
                  }}
                >
                  {g.name} → {g.to}
                </Button>
              </li>
            ))}
          </ul>
        </aside>
      ) : null}

      {locked ? <p className="text-muted-foreground mt-1 text-xs">{labels.diagram.lockedHint}</p> : null}
    </div>
  )
}

/**
 * 다이어그램 탭의 캔버스.
 *
 * `ReactFlowProvider` 로 감싸는 이유. xyflow 훅이 컨텍스트를 요구하고, 탭 전환으로 마운트가
 * 오갈 때 프로바이더가 밖에 있으면 뷰포트 상태가 탭 밖까지 새어 나간다.
 */
function WorkflowEditorCanvas(props: WorkflowEditorCanvasProps): React.JSX.Element {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  )
}

export { WorkflowEditorCanvas }
export type { WorkflowEditorCanvasProps, CanvasState }
