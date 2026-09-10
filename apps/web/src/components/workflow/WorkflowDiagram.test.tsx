// WorkflowDiagram 컴포넌트 단위 테스트 — mermaid 코드 생성 정확성 + debug prop 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { WorkflowView } from './workflow.types'
import { categoryToClass } from './WorkflowDiagram'

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

/**
 * 테스트 픽스처 전용 결정적 전환 UUID 를 만든다.
 * 실제 값은 DB 가 정하지만(`workflow_transitions.id`) 테스트는 재현 가능해야 하므로 순번으로 합성한다.
 * RFC4122 v4 형식(version=4 · variant=8) — Zod `z.string().uuid()` 통과 보장.
 *
 * @param seq 픽스처 안에서 유일한 순번
 * @returns `00000000-0000-4000-8000-` 로 시작하는 UUID 문자열
 */
function txId(seq: number): string {
  return `00000000-0000-4000-8000-${`${seq}`.padStart(12, '0')}`
}

/** software-default: 5 상태, 4 전환 (spec FR-7) */
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
    { key: 't1', name: '진행 시작', fromStateKey: 'open', toStateKey: 'in_progress', id: txId(1), kind: 'NORMAL' },
    { key: 't2', name: '검토 요청', fromStateKey: 'in_progress', toStateKey: 'in_review', id: txId(2), kind: 'NORMAL' },
    { key: 't3', name: '해결 완료', fromStateKey: 'in_review', toStateKey: 'resolved', id: txId(3), kind: 'NORMAL' },
    { key: 't4', name: '닫기', fromStateKey: 'resolved', toStateKey: 'closed', id: txId(4), kind: 'NORMAL' },
  ],
  projectId: null,
}

/** bug-tracking: 5 상태, 4 전환 (spec FR-7) */
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
    { key: 'b1', name: '분류', fromStateKey: 'reported', toStateKey: 'triaged', id: txId(5), kind: 'NORMAL' },
    { key: 'b2', name: '수정 시작', fromStateKey: 'triaged', toStateKey: 'fixing', id: txId(6), kind: 'NORMAL' },
    { key: 'b3', name: '수정 완료', fromStateKey: 'fixing', toStateKey: 'fixed', id: txId(7), kind: 'NORMAL' },
    { key: 'b4', name: '검증 완료', fromStateKey: 'fixed', toStateKey: 'verified', id: txId(8), kind: 'NORMAL' },
  ],
  projectId: null,
}

/** simple: 2 상태, 2 전환 (spec FR-7 §S2) */
const simpleWorkflow: WorkflowView = {
  key: 'simple',
  name: '단순 워크플로우',
  description: '2단계 단순 워크플로우',
  states: [
    { key: 'open', name: '열림', category: 'TODO', displayOrder: 0 },
    { key: 'closed', name: '닫힘', category: 'DONE', displayOrder: 1 },
  ],
  transitions: [
    { key: 's1', name: '닫기', fromStateKey: 'open', toStateKey: 'closed', id: txId(9), kind: 'NORMAL' },
    { key: 's2', name: '다시 열기', fromStateKey: 'closed', toStateKey: 'open', id: txId(10), kind: 'NORMAL' },
  ],
  projectId: null,
}

/** kanban-basic: 4 상태, 3 전환 (spec FR-7) */
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
    { key: 'k1', name: '계획', fromStateKey: 'backlog', toStateKey: 'todo', id: txId(11), kind: 'NORMAL' },
    { key: 'k2', name: '시작', fromStateKey: 'todo', toStateKey: 'doing', id: txId(12), kind: 'NORMAL' },
    { key: 'k3', name: '완료', fromStateKey: 'doing', toStateKey: 'done', id: txId(13), kind: 'NORMAL' },
  ],
  projectId: null,
}

// --- 테스트 ---

describe('categoryToClass — 카테고리 → mermaid classDef 식별자 변환 (D3 언더스코어 prefix)', () => {
  it("'TODO' → 'category_todo'", () => {
    expect(categoryToClass('TODO')).toBe('category_todo')
  })
  it("'IN_PROGRESS' → 'category_in_progress'", () => {
    expect(categoryToClass('IN_PROGRESS')).toBe('category_in_progress')
  })
  it("'DONE' → 'category_done'", () => {
    expect(categoryToClass('DONE')).toBe('category_done')
  })
})

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

    // 4 전환(엣지) — "fromKey --> toKey : label" 패턴
    // fromStateKey 는 GLOBAL·INITIAL 에서 null 이므로 명시적 null 체크로 좁힌다(이 픽스처는 전부 NORMAL).
    for (const transition of softwareDefaultWorkflow.transitions) {
      const from = transition.fromStateKey
      expect(from).not.toBeNull()
      if (from !== null) {
        expect(code).toContain(from)
      }
      expect(code).toContain(transition.toStateKey)
    }

    // 시작 노드 — displayOrder 최소(0) 상태가 [*] 에서 시작
    expect(code).toContain('[*] --> open')

    // 종료 노드 — DONE 카테고리 상태 → [*]
    expect(code).toContain('closed --> [*]')
  })

  it('T3-2: 4 표준 워크플로우 snapshot — 노드/엣지 수 일치 검증', () => {
    // software-default: 5 상태, 4 전환
    const sdCode = generateMermaidCode(softwareDefaultWorkflow)
    expect(sdCode).toMatchSnapshot('software-default')
    // 노드 수 검증 (상태 키가 각각 등장하는지)
    expect(softwareDefaultWorkflow.states.every((s) => sdCode.includes(s.key))).toBe(true)
    expect(softwareDefaultWorkflow.transitions.every((t) => t.fromStateKey !== null && sdCode.includes(t.fromStateKey))).toBe(true)

    // bug-tracking: 5 상태, 4 전환
    const btCode = generateMermaidCode(bugTrackingWorkflow)
    expect(btCode).toMatchSnapshot('bug-tracking')
    expect(bugTrackingWorkflow.states.every((s) => btCode.includes(s.key))).toBe(true)

    // simple: 2 상태, 2 전환
    const simCode = generateMermaidCode(simpleWorkflow)
    expect(simCode).toMatchSnapshot('simple')
    expect(simpleWorkflow.states.every((s) => simCode.includes(s.key))).toBe(true)

    // kanban-basic: 4 상태, 3 전환
    const kbCode = generateMermaidCode(kanbanBasicWorkflow)
    expect(kbCode).toMatchSnapshot('kanban-basic')
    expect(kanbanBasicWorkflow.states.every((s) => kbCode.includes(s.key))).toBe(true)
  })
})

describe('generateMermaidCode — 출발 상태가 없는 전환 (GLOBAL·INITIAL)', () => {
  let generateMermaidCode: (workflow: WorkflowView) => string

  beforeEach(async () => {
    const mod = await import('./WorkflowDiagram')
    generateMermaidCode = mod.generateMermaidCode
  })

  /** V207 ⑨ 백필과 같은 모양 — INITIAL 1건 + NORMAL 1건 */
  const withInitial: WorkflowView = {
    key: 'with-initial',
    name: 'INITIAL 포함 워크플로우',
    description: '이슈 생성 진입 전환을 가진 워크플로우',
    states: [
      { key: 'open', name: '열림', category: 'TODO', displayOrder: 1 },
      { key: 'done', name: '완료', category: 'DONE', displayOrder: 2 },
    ],
    transitions: [
      { key: 'INITIAL__open', name: '이슈 생성', fromStateKey: null, toStateKey: 'open', id: txId(101), kind: 'INITIAL' },
      { key: 'open__done', name: '완료 처리', fromStateKey: 'open', toStateKey: 'done', id: txId(102), kind: 'NORMAL' },
    ],
      projectId: null,
  }

  /** 어느 상태에서나 쓸 수 있는 전환(GLOBAL) 1건 */
  const withGlobal: WorkflowView = {
    key: 'with-global',
    name: 'GLOBAL 포함 워크플로우',
    description: '어느 상태에서나 쓸 수 있는 전환을 가진 워크플로우',
    states: [
      { key: 'open', name: '열림', category: 'TODO', displayOrder: 1 },
      { key: 'closed', name: '닫힘', category: 'DONE', displayOrder: 2 },
    ],
    transitions: [
      { key: 'GLOBAL__closed', name: '강제 종료', fromStateKey: null, toStateKey: 'closed', id: txId(111), kind: 'GLOBAL' },
      { key: 'open__closed', name: '종료', fromStateKey: 'open', toStateKey: 'closed', id: txId(112), kind: 'NORMAL' },
    ],
      projectId: null,
  }

  /**
   * T3-N1. 회귀 방지 핵심 — fromStateKey 가 null 인 전환이 문자열 'null' 노드로 새지 않는다.
   * `${transition.fromStateKey}` 템플릿 보간이 null 을 그대로 찍던 결함의 재현 단언이다.
   */
  it('T3-N1: INITIAL 전환이 있어도 mermaid 코드에 문자열 null 이 없다', () => {
    expect(generateMermaidCode(withInitial)).not.toContain('null')
  })

  it('T3-N2: GLOBAL 전환이 있어도 mermaid 코드에 문자열 null 이 없다', () => {
    expect(generateMermaidCode(withGlobal)).not.toContain('null')
  })

  /** T3-N3. INITIAL 은 mermaid 표준 시작 표기 `[*] --> to` 로 그린다 (ADR — Jira 도 Create 노드를 별도로 그린다). */
  it('T3-N3: INITIAL 전환은 [*] --> to : name 으로 그려진다', () => {
    expect(generateMermaidCode(withInitial)).toContain('[*] --> open : 이슈 생성')
  })

  /**
   * T3-N4. INITIAL 이 데이터로 오면 displayOrder 기반 합성 시작 엣지를 겹쳐 그리지 않는다.
   * 합성 엣지는 INITIAL 이 없던 시절의 대역이었으므로, 둘을 함께 찍으면 시작 화살표가 중복된다.
   */
  it('T3-N4: INITIAL 이 있으면 시작 엣지가 정확히 INITIAL 수만큼만 생성된다', () => {
    const startEdges = generateMermaidCode(withInitial)
      .split('\n')
      .filter((line) => line.trim().startsWith('[*] -->'))
    expect(startEdges).toHaveLength(1)
  })

  /** T3-N5. INITIAL 이 없는 워크플로우는 종전대로 displayOrder 최소 상태로 합성 시작 엣지를 낸다. */
  it('T3-N5: INITIAL 이 없으면 displayOrder 최소 상태로 합성 시작 엣지를 유지한다', () => {
    expect(generateMermaidCode(withGlobal)).toContain('[*] --> open')
  })

  /** T3-N6. GLOBAL 은 「어느 상태에서나」를 뜻하는 공용 의사 노드에서 출발하는 엣지로 그린다. */
  it('T3-N6: GLOBAL 전환은 공용 의사 노드에서 출발하는 엣지로 그려진다', () => {
    const code = generateMermaidCode(withGlobal)
    expect(code).toContain('state "어디서나" as any_state')
    expect(code).toContain('any_state --> closed : 강제 종료')
  })

  /** T3-N7. GLOBAL 이 여러 건이어도 의사 노드 선언은 1회뿐이다 (mermaid 중복 선언 방지). */
  it('T3-N7: GLOBAL 이 2건이어도 의사 노드 선언은 1회다', () => {
    const twoGlobals: WorkflowView = {
      ...withGlobal,
      transitions: [
        ...withGlobal.transitions,
        { key: 'GLOBAL__open', name: '강제 재개', fromStateKey: null, toStateKey: 'open', id: txId(113), kind: 'GLOBAL' },
      ],
    }
    const declarations = generateMermaidCode(twoGlobals)
      .split('\n')
      .filter((line) => line.includes('as any_state'))
    expect(declarations).toHaveLength(1)
  })

  /** T3-N8. GLOBAL 이 없으면 의사 노드를 선언하지 않는다 — 빈 노드가 다이어그램에 남지 않아야 한다. */
  it('T3-N8: GLOBAL 이 없으면 의사 노드를 선언하지 않는다', () => {
    expect(generateMermaidCode(withInitial)).not.toContain('any_state')
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
          projectId: null,
    }

    render(<WorkflowDiagram workflow={emptyWorkflow} />)

    expect(screen.getByText('이 워크플로우에는 상태가 없습니다')).toBeInTheDocument()
  })
})
