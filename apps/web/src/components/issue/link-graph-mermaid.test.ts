// mermaid flowchart 코드 생성 순수 함수 단위 테스트 — FR-LK-02 Task 3
import { describe, it, expect } from 'vitest'
import { generateGraphMermaidCode } from './link-graph-mermaid'
import type { IssueGraphResponse } from '@/api/issue-graph'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** 엣지 라벨 맵 — component가 linkGraphStrings에서 생성해 전달하는 형태 */
const EDGE_LABELS: Record<string, string> = {
  BLOCKS: '차단',
  RELATES: '관련',
  DUPLICATES: '중복',
  CLONES: '복제',
  PARENT: '부모',
}

/** center 노드만 있고 엣지가 없는 최소 그래프 */
const EMPTY_GRAPH: IssueGraphResponse = {
  center: 'ATLAS-1',
  depth: 2,
  nodes: [{ key: 'ATLAS-1', summary: '센터 이슈', statusKey: 'TODO', depth: 0 }],
  edges: [],
  truncated: false,
}

/** 두 노드 + 엣지 하나인 기본 그래프 */
const SIMPLE_GRAPH: IssueGraphResponse = {
  center: 'ATLAS-1',
  depth: 2,
  nodes: [
    { key: 'ATLAS-1', summary: '센터 이슈', statusKey: 'TODO', depth: 0 },
    { key: 'ATLAS-42', summary: '연결된 이슈', statusKey: 'IN_PROGRESS', depth: 1 },
  ],
  edges: [{ from: 'ATLAS-1', to: 'ATLAS-42', type: 'BLOCKS' }],
  truncated: false,
}

/** 미지 엣지 타입을 포함하는 그래프 (fail-safe 원문 fallback 검증용) */
const UNKNOWN_TYPE_GRAPH: IssueGraphResponse = {
  center: 'ATLAS-10',
  depth: 1,
  nodes: [
    { key: 'ATLAS-10', summary: '센터', statusKey: 'TODO', depth: 0 },
    { key: 'ATLAS-20', summary: '대상', statusKey: 'DONE', depth: 1 },
  ],
  edges: [{ from: 'ATLAS-10', to: 'ATLAS-20', type: 'FUTURE_TYPE' }],
  truncated: false,
}

/** PARENT 엣지 방향 보존 검증용 (from=부모, to=자식) */
const PARENT_GRAPH: IssueGraphResponse = {
  center: 'ATLAS-5',
  depth: 1,
  nodes: [
    { key: 'ATLAS-3', summary: '부모 이슈', statusKey: 'TODO', depth: 1 },
    { key: 'ATLAS-5', summary: '자식 이슈(center)', statusKey: 'IN_PROGRESS', depth: 0 },
  ],
  edges: [{ from: 'ATLAS-3', to: 'ATLAS-5', type: 'PARENT' }],
  truncated: false,
}

/** 동일 노드가 복수 엣지에서 재등장하는 그래프 (dedup 검증용) */
const MULTI_EDGE_GRAPH: IssueGraphResponse = {
  center: 'PROJ-1',
  depth: 2,
  nodes: [
    { key: 'PROJ-1', summary: 'center', statusKey: 'TODO', depth: 0 },
    { key: 'PROJ-2', summary: 'node2', statusKey: 'TODO', depth: 1 },
    { key: 'PROJ-3', summary: 'node3', statusKey: 'TODO', depth: 1 },
  ],
  edges: [
    { from: 'PROJ-1', to: 'PROJ-2', type: 'BLOCKS' },
    { from: 'PROJ-1', to: 'PROJ-3', type: 'RELATES' },
  ],
  truncated: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 스위트
// ─────────────────────────────────────────────────────────────────────────────

describe('generateGraphMermaidCode', () => {
  describe('빈 그래프 처리', () => {
    it('edges가 비어있으면 null을 반환한다', () => {
      const result = generateGraphMermaidCode(EMPTY_GRAPH, EDGE_LABELS)
      expect(result).toBeNull()
    })

    it('nodes가 center 1개뿐이어도 null을 반환한다', () => {
      const singleNode: IssueGraphResponse = {
        ...EMPTY_GRAPH,
        nodes: [{ key: 'ATLAS-1', summary: '유일 노드', statusKey: 'TODO', depth: 0 }],
        edges: [],
      }
      const result = generateGraphMermaidCode(singleNode, EDGE_LABELS)
      expect(result).toBeNull()
    })
  })

  describe('출력 구조 — flowchart LR', () => {
    it('flowchart LR로 시작한다', () => {
      const result = generateGraphMermaidCode(SIMPLE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      expect(result!.code).toMatch(/^flowchart LR/)
    })
  })

  describe('노드 정의', () => {
    it('노드 라벨이 원래 이슈 키와 일치한다', () => {
      const result = generateGraphMermaidCode(SIMPLE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      // 노드 라벨은 따옴표로 감싼 원래 키: ["ATLAS-1"]
      expect(result!.code).toContain('["ATLAS-1"]')
      expect(result!.code).toContain('["ATLAS-42"]')
    })

    it('sanitize된 노드 ID에 하이픈이 없다', () => {
      const result = generateGraphMermaidCode(SIMPLE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      const lines = result!.code.split('\n')
      // 노드 정의 라인: `  <id>["..."]` — id에 하이픈 없음
      const nodeDefLines = lines.filter((l) => l.includes('["'))
      for (const line of nodeDefLines) {
        // 노드 ID 부분([ 이전)만 추출해 하이픈 검사
        const match = /^\s*(\S+)\[/.exec(line)
        if (match !== undefined && match !== null) {
          const nodeId = match[1]
          if (nodeId !== undefined) {
            expect(nodeId).not.toContain('-')
          }
        }
      }
    })
  })

  describe('center 노드 강조', () => {
    it('classDef centerNode 라인이 포함된다', () => {
      const result = generateGraphMermaidCode(SIMPLE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      expect(result!.code).toContain('classDef centerNode')
    })

    it('class <centerSanitizedId> centerNode 라인이 포함된다', () => {
      const result = generateGraphMermaidCode(SIMPLE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      // ATLAS-1의 sanitize ID가 centerNode class를 받아야 함
      const { code, idToKey } = result!
      const centerId = Object.entries(idToKey).find(([, key]) => key === 'ATLAS-1')?.[0]
      expect(centerId).toBeDefined()
      expect(code).toContain(`class ${centerId!} centerNode`)
    })
  })

  describe('엣지 렌더링', () => {
    it('엣지를 from -->|"라벨"| to 형식으로 렌더링한다', () => {
      const result = generateGraphMermaidCode(SIMPLE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      // 엣지 라인에 edgeLabels 주입 라벨 사용
      expect(result!.code).toContain('-->|"차단"|')
    })

    it('PARENT 엣지의 from/to 방향이 그대로 보존된다', () => {
      const result = generateGraphMermaidCode(PARENT_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      const { code, idToKey } = result!
      const fromId = Object.entries(idToKey).find(([, k]) => k === 'ATLAS-3')?.[0]
      const toId = Object.entries(idToKey).find(([, k]) => k === 'ATLAS-5')?.[0]
      expect(fromId).toBeDefined()
      expect(toId).toBeDefined()
      // from → to 순서 검증
      const edgeLine = code.split('\n').find((l) => l.includes('-->|"부모"|'))
      expect(edgeLine).toBeDefined()
      expect(edgeLine!.indexOf(fromId!)).toBeLessThan(edgeLine!.indexOf(toId!))
    })

    it('미지 엣지 type은 edgeLabels 대신 원문 type을 fallback으로 사용한다', () => {
      const result = generateGraphMermaidCode(UNKNOWN_TYPE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      // FUTURE_TYPE은 EDGE_LABELS에 없으므로 원문 그대로
      expect(result!.code).toContain('-->|"FUTURE_TYPE"|')
    })
  })

  describe('idToKey 역매핑', () => {
    it('sanitizedId → 원래 이슈 키 역매핑이 정확하다', () => {
      const result = generateGraphMermaidCode(SIMPLE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      const { idToKey } = result!
      const keys = Object.values(idToKey)
      expect(keys).toContain('ATLAS-1')
      expect(keys).toContain('ATLAS-42')
    })

    it('동일 키는 동일 sanitized ID를 가진다 (dedup)', () => {
      const result = generateGraphMermaidCode(MULTI_EDGE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      const { idToKey } = result!
      // PROJ-1은 두 엣지에 from으로 등장하지만 idToKey에서 한 번만 존재해야 함
      const proj1Entries = Object.entries(idToKey).filter(([, k]) => k === 'PROJ-1')
      expect(proj1Entries).toHaveLength(1)
    })

    it('모든 노드가 idToKey에 포함된다', () => {
      const result = generateGraphMermaidCode(MULTI_EDGE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      const { idToKey } = result!
      const keys = Object.values(idToKey)
      expect(keys).toContain('PROJ-1')
      expect(keys).toContain('PROJ-2')
      expect(keys).toContain('PROJ-3')
    })
  })

  describe('sanitize ID 형식', () => {
    it('sanitize ID는 영문+언더스코어만 포함한다', () => {
      const result = generateGraphMermaidCode(SIMPLE_GRAPH, EDGE_LABELS)
      expect(result).not.toBeNull()
      const ids = Object.keys(result!.idToKey)
      for (const id of ids) {
        expect(id).toMatch(/^[a-zA-Z_][a-zA-Z0-9_]*$/)
      }
    })
  })
})
