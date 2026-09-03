// 다이어그램 캔버스의 상태 노드 — 이름과 카테고리 색만 그린다 (FR-WF-07 D8)
import * as React from 'react'
import { Handle, Position } from '@xyflow/react'
import type { Node } from '@xyflow/react'
import type { StateCategory } from '@/api/workflows-admin.types'
import { cn } from '@/lib/utils'

/** 노드 하나가 캔버스에서 받는 데이터. 좌표는 xyflow 가 들고 있으므로 여기 없다. */
interface StatusNodeData extends Record<string, unknown> {
  /** 상태 이름 — 화면에 그대로 보인다 */
  name: string
  category: StateCategory
  /**
   * 잠긴 워크플로우(엣지 E12)면 연결 핸들이 **끝까지 드러나지 않는다.**
   * 전환을 새로 잇는 조작이 없으므로 어포던스도 주지 않는다.
   */
  locked: boolean
}

/** 캔버스가 등록하는 노드 타입 — `nodeTypes={{ status: StatusNode }}` */
type StatusNodeType = Node<StatusNodeData, 'status'>

/**
 * 카테고리 → 배경·테두리 토큰.
 *
 * ★ **`WorkflowDiagram.tsx` 의 `classDef category_*` 3행과 같은 색이다.**
 * 같은 워크플로우를 읽기 전용 `/workflows/{key}` 와 편집기 `/admin/workflows/{key}` 에서
 * 번갈아 볼 때 같은 제품처럼 보여야 한다 — 한쪽만 고치지 마라.
 * 저쪽은 mermaid classDef **이름**(`category_todo`)을 만들고 이쪽은 Tailwind **클래스**를
 * 만든다. 목적지가 달라 합칠 수 없으니, `StateCategory` 가 늘면 **두 곳 모두** 고친다.
 *
 * 배열 `find` 가 아니라 `Record` 인 이유. 카테고리가 늘면 여기서 컴파일이 깨져야 한다.
 */
const CATEGORY_CLASS: Record<StateCategory, string> = {
  TODO: 'bg-muted border-border',
  IN_PROGRESS: 'bg-primary/15 border-primary',
  DONE: 'bg-success/15 border-success',
}

/**
 * 핸들 공통 모양. 크기·색은 레이어 밖의 `.react-flow__handle` 규칙이 이기므로
 * (Tailwind v4 유틸리티는 `@layer utilities` 라 레이어 없는 규칙에 진다) 여기서는
 * **opacity 만** 다룬다. 크기·색 정본은 캔버스의 `--xy-handle-*` 덮어쓰기다.
 */
const HANDLE_BASE_CLASS = 'opacity-0 transition-opacity'

/**
 * hover 하면 드러난다 (J4 — "Nodes appear on statuses … when you hover over a status").
 *
 * ★ **터치 기기에서는 발화하지 않는다(N5).** Tailwind v4.3 의 `group-hover:` 는
 * `@media (hover: hover)` 로 감싸져 컴파일된다(실측). 그래서 터치에서는 6px 짜리
 * 「보이는데 못 누르는」 표적이 아예 나타나지 않고, 전환을 만드는 길은 전환 탭의
 * 「전환 추가」 다이얼로그 하나로 남는다.
 */
const HANDLE_HOVER_CLASS = 'group-hover:opacity-100'

/**
 * 선택된 노드에 얹는 링.
 *
 * **카테고리 색과 다른 축이라야 한다** (부채 169). 카테고리는 배경·테두리 *색*으로 말하므로
 * 선택도 색으로 말하면 「선택된 진행 중」과 「선택 안 된 완료」가 화면에서 헷갈린다.
 * 링은 테두리 바깥에 따로 그려져 색과 겹치지 않는다.
 *
 * `ring-offset` 을 주지 않는 이유. 캔버스 배경이 노드마다 달라(격자·간선이 지나간다)
 * 오프셋 색을 하나로 못 고른다 — 붙은 링이 어느 배경에서나 읽힌다.
 */
const SELECTED_CLASS = 'ring-2 ring-primary'

interface StatusNodeProps {
  data: StatusNodeData
  /**
   * xyflow 가 넘기는 선택 상태.
   *
   * ★ **DOM 의 `.selected` 클래스에 기대지 않는다.** xyflow 는 래퍼(`.react-flow__node`)에
   * 그 클래스를 붙이지만 저장소에 그것을 그리는 CSS 규칙이 없다 — 커스텀 노드가 기본 스타일을
   * 걷어내면서 함께 사라졌다. 프롭으로 받아 여기서 그려야 판정이 이 컴포넌트 안에 선다.
   */
  selected?: boolean
}

/**
 * 캔버스 위 상태 노드.
 *
 * **순수 프레젠테이션이다.** 좌표 계산은 `lib/workflow-layout.ts`, 상태 관리는 캔버스의 몫이다.
 *
 * ### 핸들을 잠금 여부와 무관하게 항상 그리는 이유
 * xyflow 의 `getEdgePosition` 은 노드의 `handleBounds` 로 간선 양 끝을 잡는다 —
 * 핸들 DOM 이 없으면 **간선이 통째로 안 그려진다**(실측 `@xyflow/system` `getEdgePosition`).
 * 잠긴 워크플로우도 「보기는 된다」(E12)여야 하므로, 잠금은 `isConnectable=false` 와
 * hover 클래스 제거로만 표현하고 DOM 에서는 지우지 않는다.
 */
function StatusNode({ data, selected = false }: StatusNodeProps): React.JSX.Element {
  const connectable = !data.locked
  const handleClass = cn(HANDLE_BASE_CLASS, connectable && HANDLE_HOVER_CLASS)

  return (
    <div
      className={cn(
        'group relative flex min-h-11 min-w-36 max-w-52 items-center justify-center rounded-lg border px-3 py-2',
        CATEGORY_CLASS[data.category],
        selected && SELECTED_CLASS,
      )}
    >
      <Handle type="target" position={Position.Left} isConnectable={connectable} className={handleClass} />
      <span className="text-foreground truncate text-sm font-medium">{data.name}</span>
      <Handle type="source" position={Position.Right} isConnectable={connectable} className={handleClass} />
    </div>
  )
}

export { StatusNode }
export type { StatusNodeData, StatusNodeProps, StatusNodeType }
