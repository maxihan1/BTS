// 워크플로우 FSM 다이어그램 컴포넌트 (mermaid stateDiagram-v2 + 카테고리별 색상)
import { useEffect, useRef, useState } from 'react'
import type { WorkflowView, WorkflowStateView, StateCategory } from './workflow.types'

// --- 카테고리 → mermaid classDef 이름 매핑 ---

/**
 * StateCategory를 mermaid classDef 식별자로 변환한다.
 * @param category 상태 카테고리 (TODO / IN_PROGRESS / DONE)
 * @returns mermaid classDef 이름 문자열
 */
export function categoryToClass(category: StateCategory): string {
  switch (category) {
    case 'TODO':
      return 'todo'
    case 'IN_PROGRESS':
      return 'in_progress'
    case 'DONE':
      return 'done'
  }
}

// --- mermaid 코드 생성 helper ---

/**
 * WorkflowView 데이터로부터 mermaid stateDiagram-v2 코드 문자열을 생성한다.
 * 노드 ID = WorkflowStateView.key (영문), 전이 라벨 = WorkflowTransitionView.name.
 * displayOrder 최솟값 state → 시작 [*], category === 'DONE' state → 종료 [*].
 * @param workflow WorkflowView — states + transitions
 * @returns mermaid stateDiagram-v2 코드 문자열
 */
export function generateMermaidCode(workflow: WorkflowView): string {
  const { states, transitions } = workflow

  if (states.length === 0) {
    return ''
  }

  const lines: string[] = ['stateDiagram-v2']

  // 시작 노드: displayOrder가 가장 낮은 state
  const sortedByOrder = [...states].sort((a, b) => a.displayOrder - b.displayOrder)
  const firstState = sortedByOrder[0]
  if (firstState !== undefined) {
    lines.push(`  [*] --> ${firstState.key}`)
  }

  // 전이 라인 — "from --> to : label"
  // mermaid stateDiagram-v2 에서 라벨이 있는 전이: "from --> to : label"
  for (const transition of transitions) {
    lines.push(`  ${transition.fromStateKey} --> ${transition.toStateKey} : ${transition.name}`)
  }

  // 종료 노드: DONE 카테고리의 state
  const doneStates = states.filter((s) => s.category === 'DONE')
  for (const doneState of doneStates) {
    lines.push(`  ${doneState.key} --> [*]`)
  }

  // classDef 정의 — DESIGN.md OKLCH 토큰 기반
  // TODO: bg-muted/text-muted-foreground → --muted 토큰
  // IN_PROGRESS: bg-primary/10 → --primary 토큰 (투명도 15%)
  // DONE: bg-emerald-500/10 → --success 계열 (emerald)
  lines.push(`  classDef todo fill:var(--muted),stroke:var(--border)`)
  lines.push(`  classDef in_progress fill:oklch(from var(--primary) l c h / 0.15),stroke:var(--primary)`)
  lines.push(`  classDef done fill:oklch(0.94 0.05 160 / 0.15),stroke:oklch(0.5 0.12 160)`)

  // class 할당 — 각 state에 카테고리 클래스 부여
  for (const state of states) {
    lines.push(`  class ${state.key} ${categoryToClass(state.category)}`)
  }

  return lines.join('\n')
}

// --- 컴포넌트 prop 타입 ---

export interface WorkflowDiagramProps {
  workflow: WorkflowView
  debug?: boolean
}

// --- WorkflowDiagram 컴포넌트 ---

/**
 * 워크플로우 FSM을 mermaid stateDiagram-v2로 시각화하는 컴포넌트.
 * mermaid를 동적 import(useEffect)로 로드해 번들 초기 로드 크기를 줄인다.
 * debug=true 시 <details> 안에 mermaid 소스 코드를 노출한다.
 */
export function WorkflowDiagram({ workflow, debug = false }: WorkflowDiagramProps): JSX.Element {
  const containerRef = useRef<HTMLDivElement>(null)
  const [renderError, setRenderError] = useState<boolean>(false)

  const code = generateMermaidCode(workflow)

  useEffect(() => {
    // states가 없으면 렌더 시도하지 않음 (EC-1)
    if (workflow.states.length === 0) {
      return
    }

    if (!containerRef.current) {
      return
    }

    let cancelled = false

    async function renderDiagram() {
      try {
        // 동적 import — Vite chunk 분리로 초기 번들 크기 절약 (NFR-2)
        const mermaid = (await import('mermaid')).default

        mermaid.initialize({
          startOnLoad: false,
          theme: 'base',
          themeVariables: {
            // 라이트 모드 전용 (DESIGN.md NFR-4)
            background: '#ffffff',
          },
        })

        if (cancelled || !containerRef.current) {
          return
        }

        const id = `mermaid-${workflow.key}-${Date.now()}`
        const { svg } = await mermaid.render(id, code)

        if (cancelled || !containerRef.current) {
          return
        }

        containerRef.current.innerHTML = svg
        setRenderError(false)
      } catch (err) {
        if (!cancelled) {
          console.error('[WorkflowDiagram] mermaid 렌더 실패:', err)
          setRenderError(true)
        }
      }
    }

    void renderDiagram()

    return () => {
      cancelled = true
    }
    // workflow.key + code 변경 시 재렌더 (FR-5)
  }, [workflow.key, code])

  // EC-1: 빈 워크플로우
  if (workflow.states.length === 0) {
    return (
      <div className="text-muted-foreground text-sm p-4">
        이 워크플로우에는 상태가 없습니다
      </div>
    )
  }

  // EC-3: mermaid 렌더 실패 fallback
  if (renderError) {
    return (
      <div role="alert" className="text-destructive text-sm p-4">
        다이어그램 렌더 실패
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {/* 다이어그램 컨테이너 — aria-label로 접근성 부여 (NFR-3) */}
      <div
        ref={containerRef}
        aria-label={`${workflow.name} 다이어그램`}
        className="overflow-x-auto"
      />
      {/* debug 모드: mermaid 소스 코드 노출 (FR-6, spec S3) */}
      {debug && (
        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground">Mermaid source</summary>
          <pre className="mt-1 rounded bg-muted p-2 overflow-x-auto whitespace-pre-wrap">{code}</pre>
        </details>
      )}
    </div>
  )
}
