// 워크플로우 FSM 다이어그램 컴포넌트 (mermaid stateDiagram-v2 + 카테고리별 색상)
/* eslint-disable react-refresh/only-export-components -- generateMermaidCode/categoryToClass는 단위 테스트용 named export (spec FR-1 명시) */
import { useEffect, useRef, useState } from 'react'
import type { JSX } from 'react'
import type { WorkflowView, WorkflowStateView, StateCategory } from './workflow.types'

// --- 카테고리 → mermaid classDef 이름 매핑 ---

/**
 * StateCategory를 mermaid classDef 식별자로 변환한다.
 * switch의 모든 case를 명시하고 default에서 never 타입 가드로 미래 enum 확장을 안전하게 감지한다.
 *
 * D3 결정. 언더스코어 prefix 채택 사유 — mermaid v11 stateDiagram-v2 의 classDef 식별자 정규식
 * `[a-zA-Z][a-zA-Z0-9_-]*` 허용 + state id (예. simple 의 `done`) 와 토큰 충돌 회피.
 * 일부 mermaid 버전의 하이픈 비일관 처리 (issue #5263 등) 위험 회피.
 *
 * @param category 상태 카테고리 (TODO / IN_PROGRESS / DONE)
 * @returns mermaid classDef 이름 문자열 (예. 'category_todo')
 */
export function categoryToClass(category: StateCategory): string {
  switch (category) {
    case 'TODO':
      return 'category_todo'
    case 'IN_PROGRESS':
      return 'category_in_progress'
    case 'DONE':
      return 'category_done'
    default: {
      // 미래에 StateCategory에 새 값이 추가되면 TypeScript 컴파일 에러로 감지된다.
      const _exhaustive: never = category
      return _exhaustive
    }
  }
}

// --- mermaid 코드 생성 helper ---

/**
 * WorkflowView 데이터로부터 mermaid stateDiagram-v2 코드 문자열을 생성한다.
 *
 * 노드 ID = WorkflowStateView.key (영문만 사용 — EC-5: 한글/특수문자 key는 mermaid 직렬화 불안정).
 * 표시 라벨 = WorkflowStateView.name (한글 허용 — mermaid 전환 라벨은 따옴표 없이도 처리).
 * displayOrder 최솟값 state → 시작 [*], category === 'DONE' state → 종료 [*].
 *
 * @param workflow WorkflowView — states + transitions
 * @returns mermaid stateDiagram-v2 코드 문자열. states가 비어있으면 빈 문자열 반환.
 */
export function generateMermaidCode(workflow: WorkflowView): string {
  const { states, transitions } = workflow

  if (states.length === 0) {
    return ''
  }

  const lines: string[] = ['stateDiagram-v2']

  // 시작 노드: displayOrder가 가장 낮은 state — noUncheckedIndexedAccess 대응으로 undefined 가드
  const sortedByOrder = [...states].sort((a, b) => a.displayOrder - b.displayOrder)
  const initialState: WorkflowStateView | undefined = sortedByOrder[0]
  if (initialState !== undefined) {
    lines.push(`  [*] --> ${initialState.key}`)
  }

  // 전환 라인 — "from --> to : label"
  // mermaid stateDiagram-v2 에서 라벨이 있는 전환: "from --> to : label"
  for (const transition of transitions) {
    lines.push(`  ${transition.fromStateKey} --> ${transition.toStateKey} : ${transition.name}`)
  }

  // 종료 노드: DONE 카테고리의 state
  const doneStates = states.filter((s) => s.category === 'DONE')
  for (const doneState of doneStates) {
    lines.push(`  ${doneState.key} --> [*]`)
  }

  // classDef 정의 — DESIGN.md 디자인 토큰 기반 (하드코딩 색상 금지)
  //   TODO        → --muted / --border
  //   IN_PROGRESS → --primary (투명도 15%)
  //   DONE        → --success (투명도 15%)
  // 세 카테고리 모두 토큰을 참조하므로 라이트/다크 테마가 자동으로 따라온다.
  lines.push(`  classDef category_todo fill:var(--muted),stroke:var(--border)`)
  lines.push(`  classDef category_in_progress fill:oklch(from var(--primary) l c h / 0.15),stroke:var(--primary)`)
  lines.push(`  classDef category_done fill:oklch(from var(--success) l c h / 0.15),stroke:var(--success)`)

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
    // workflow.key + code + states.length 변경 시 재렌더 (FR-5)
    // states.length 포함: states가 빈 배열 → 비어있지 않음으로 변경될 때 재실행 보장
  }, [workflow.key, workflow.states.length, code])

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
