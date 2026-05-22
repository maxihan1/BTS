// WorkflowDiagram 컴포넌트 단위 테스트 — mermaid 코드 생성 정확성 + debug prop 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { WorkflowView } from './workflow.types'

// mermaid 라이브러리 mock — jsdom 환경에서 실제 SVG 렌더 불가
// 단위 테스트 책임: mermaid 코드 생성 정확성만 검증. 실제 SVG 렌더는 Playwright E2E(Task 6) 담당.
vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    run: vi.fn().mockResolvedValue(undefined),
    render: vi.fn().mockResolvedValue({ svg: '<svg></svg>' }),
  },
}))

// --- 테스트 픽스처 ---

/** software-default: 5 상태, 4 전이 (spec FR-7) */
const softwareDefaultWorkflow: WorkflowView = {
  key: 'software-default',
  name: '소프트웨어 기본 워크플로우',
  description: '표준 소프트웨어 개발 이슈 워크플로우',
  states: [
    { key: 'open', name: '열림', category: 'TODO', displayOrder: 0 },
    { key: 'in_progress', name: '진행 중', category: 'IN_PROGRESS', displayOrder: 1 },
    { key: 'in_review', name: '검토 중', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'resolved', name: '해결됨', category: 'DONE', displayOrder: 3 },
    { key: 'closed', name: '닫힘', category: 'DONE', displayOrder: 4 },
  ],
  transitions: [
    { key: 't1', name: '진행 시작', fromStateKey: 'open', toStateKey: 'in_progress' },
    { key: 't2', name: '검토 요청', fromStateKey: 'in_progress', toStateKey: 'in_review' },
    { key: 't3', name: '해결 완료', fromStateKey: 'in_review', toStateKey: 'resolved' },
    { key: 't4', name: '닫기', fromStateKey: 'resolved', toStateKey: 'closed' },
  ],
}

/** bug-tracking: 5 상태, 4 전이 (spec FR-7) */
const bugTrackingWorkflow: WorkflowView = {
  key: 'bug-tracking',
  name: '버그 추적 워크플로우',
  description: '버그 리포트 전용 워크플로우',
  states: [
    { key: 'reported', name: '신고됨', category: 'TODO', displayOrder: 0 },
    { key: 'triaged', name: '분류됨', category: 'TODO', displayOrder: 1 },
    { key: 'fixing', name: '수정 중', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'fixed', name: '수정됨', category: 'DONE', displayOrder: 3 },
    { key: 'verified', name: '검증됨', category: 'DONE', displayOrder: 4 },
  ],
  transitions: [
    { key: 'b1', name: '분류', fromStateKey: 'reported', toStateKey: 'triaged' },
    { key: 'b2', name: '수정 시작', fromStateKey: 'triaged', toStateKey: 'fixing' },
    { key: 'b3', name: '수정 완료', fromStateKey: 'fixing', toStateKey: 'fixed' },
    { key: 'b4', name: '검증 완료', fromStateKey: 'fixed', toStateKey: 'verified' },
  ],
}

/** simple: 2 상태, 2 전이 (spec FR-7 §S2) */
const simpleWorkflow: WorkflowView = {
  key: 'simple',
  name: '단순 워크플로우',
  description: '2단계 단순 워크플로우',
  states: [
    { key: 'open', name: '열림', category: 'TODO', displayOrder: 0 },
    { key: 'closed', name: '닫힘', category: 'DONE', displayOrder: 1 },
  ],
  transitions: [
    { key: 's1', name: '닫기', fromStateKey: 'open', toStateKey: 'closed' },
    { key: 's2', name: '다시 열기', fromStateKey: 'closed', toStateKey: 'open' },
  ],
}

/** kanban-basic: 4 상태, 3 전이 (spec FR-7) */
const kanbanBasicWorkflow: WorkflowView = {
  key: 'kanban-basic',
  name: '칸반 기본 워크플로우',
  description: '칸반 스타일 기본 워크플로우',
  states: [
    { key: 'backlog', name: '백로그', category: 'TODO', displayOrder: 0 },
    { key: 'todo', name: '할 일', category: 'TODO', displayOrder: 1 },
    { key: 'doing', name: '진행 중', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'done', name: '완료', category: 'DONE', displayOrder: 3 },
  ],
  transitions: [
    { key: 'k1', name: '계획', fromStateKey: 'backlog', toStateKey: 'todo' },
    { key: 'k2', name: '시작', fromStateKey: 'todo', toStateKey: 'doing' },
    { key: 'k3', name: '완료', fromStateKey: 'doing', toStateKey: 'done' },
  ],
}

// --- 테스트 ---

describe('generateMermaidCode', () => {
  // generateMermaidCode를 직접 import해서 단위 검증
  // WorkflowDiagram.tsx 가 존재하지 않으면 이 import에서 RED 실패
  let generateMermaidCode: (workflow: WorkflowView) => string

  beforeEach(async () => {
    const mod = await import('./WorkflowDiagram')
    generateMermaidCode = mod.generateMermaidCode
  })

  it('T3-1: workflow prop 주면 stateDiagram-v2 + 5 노드 + 4 엣지 포함 코드 생성', () => {
    const code = generateMermaidCode(softwareDefaultWorkflow)

    // stateDiagram-v2 헤더 포함
    expect(code).toContain('stateDiagram-v2')

    // 5 노드 키 포함
    for (const state of softwareDefaultWorkflow.states) {
      expect(code).toContain(state.key)
    }

    // 4 전이(엣지) — "fromKey --> toKey : label" 패턴
    for (const transition of softwareDefaultWorkflow.transitions) {
      expect(code).toContain(transition.fromStateKey)
      expect(code).toContain(transition.toStateKey)
    }

    // 시작 노드 — displayOrder 최소(0) 상태가 [*] 에서 시작
    expect(code).toContain('[*] --> open')

    // 종료 노드 — DONE 카테고리 상태 → [*]
    expect(code).toContain('closed --> [*]')
  })

  it('T3-2: 4 표준 워크플로우 snapshot — 노드/엣지 수 일치 검증', () => {
    // software-default: 5 상태, 4 전이
    const sdCode = generateMermaidCode(softwareDefaultWorkflow)
    expect(sdCode).toMatchSnapshot('software-default')
    // 노드 수 검증 (상태 키가 각각 등장하는지)
    expect(softwareDefaultWorkflow.states.every((s) => sdCode.includes(s.key))).toBe(true)
    expect(softwareDefaultWorkflow.transitions.every((t) => sdCode.includes(t.fromStateKey))).toBe(true)

    // bug-tracking: 5 상태, 4 전이
    const btCode = generateMermaidCode(bugTrackingWorkflow)
    expect(btCode).toMatchSnapshot('bug-tracking')
    expect(bugTrackingWorkflow.states.every((s) => btCode.includes(s.key))).toBe(true)

    // simple: 2 상태, 2 전이
    const simCode = generateMermaidCode(simpleWorkflow)
    expect(simCode).toMatchSnapshot('simple')
    expect(simpleWorkflow.states.every((s) => simCode.includes(s.key))).toBe(true)

    // kanban-basic: 4 상태, 3 전이
    const kbCode = generateMermaidCode(kanbanBasicWorkflow)
    expect(kbCode).toMatchSnapshot('kanban-basic')
    expect(kanbanBasicWorkflow.states.every((s) => kbCode.includes(s.key))).toBe(true)
  })
})

describe('WorkflowDiagram', () => {
  it('T3-3: debug=true 이면 <details> + <pre> 안에 mermaid source 노출', async () => {
    const { WorkflowDiagram } = await import('./WorkflowDiagram')

    render(<WorkflowDiagram workflow={softwareDefaultWorkflow} debug={true} />)

    // <details> 존재 검증
    const details = document.querySelector('details')
    expect(details).toBeInTheDocument()

    // <pre> 안에 mermaid 코드 포함
    const pre = document.querySelector('details pre')
    expect(pre).toBeInTheDocument()
    expect(pre?.textContent).toContain('stateDiagram-v2')
  })

  it('T3-EC1: states가 빈 배열이면 placeholder 텍스트 렌더', async () => {
    const { WorkflowDiagram } = await import('./WorkflowDiagram')

    const emptyWorkflow: WorkflowView = {
      key: 'empty',
      name: '빈 워크플로우',
      description: '상태 없음',
      states: [],
      transitions: [],
    }

    render(<WorkflowDiagram workflow={emptyWorkflow} />)

    expect(screen.getByText('이 워크플로우에는 상태가 없습니다')).toBeInTheDocument()
  })
})
