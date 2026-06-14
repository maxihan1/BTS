// 이슈 그래프를 mermaid flowchart LR 코드로 변환하는 순수 함수 helper — FR-LK-02
import type { IssueGraphResponse } from '@/api/issue-graph'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * sanitize ID 접두사.
 * mermaid 노드 ID는 영문으로 시작해야 하고 하이픈이 있으면 파싱이 불안정하다.
 * 보수적 식별자(영문+언더스코어)로 생성한다.
 */
const NODE_ID_PREFIX = 'node_'

/**
 * center 노드에 부여할 mermaid classDef 식별자.
 * WorkflowDiagram 선례(category_todo 등)의 언더스코어 prefix 패턴을 따른다.
 */
const CENTER_CLASS = 'centerNode'

/**
 * center 노드 classDef 스타일 fill — primary 색 20% 투명도.
 * DESIGN.md OKLCH 토큰 기반, WorkflowDiagram category_in_progress 선례 참조.
 */
const CENTER_FILL = 'oklch(from var(--primary) l c h / 0.20)'

/**
 * center 노드 mermaid classDef 선언 라인.
 * flowchart LR 코드 말미에 한 번 삽입된다.
 */
const CENTER_CLASS_DEF =
  `classDef ${CENTER_CLASS} fill:${CENTER_FILL},stroke:var(--primary),stroke-width:2px`

// ─────────────────────────────────────────────────────────────────────────────
// 출력 타입
// ─────────────────────────────────────────────────────────────────────────────

/** generateGraphMermaidCode의 성공 반환 타입 */
export interface GraphMermaidResult {
  /** mermaid flowchart LR 코드 문자열 */
  code: string
  /** sanitizedId → 원래 이슈 키 역매핑 (클릭 내비게이션에 사용) */
  idToKey: Record<string, string>
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 helper — 노드 ID sanitize
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 키를 mermaid 노드 ID로 변환한다.
 * 이슈 키에는 하이픈(ATLAS-1)이 포함돼 mermaid 파싱이 불안정해지므로
 * 인덱스 기반 보수적 식별자(node_0, node_1, ...)로 매핑한다.
 *
 * @param index 노드 순번 (0부터 시작)
 * @returns mermaid 노드 ID (예: "node_0")
 */
function sanitizeNodeId(index: number): string {
  return `${NODE_ID_PREFIX}${index}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * IssueGraphResponse를 mermaid flowchart LR 코드로 변환한다.
 *
 * - 노드 정의: `<sanitizedId>["<이슈 키>"]` — 라벨은 원래 이슈 키, ID는 안전 식별자
 * - center 노드: classDef centerNode(primary 계열)로 강조
 * - 엣지: `<fromId> -->|"<라벨>"| <toId>` — 방향은 백엔드 from/to 그대로 보존
 * - 미지 엣지 type: edgeLabels에 없으면 원문 type을 그대로 사용 (fail-safe)
 * - i18n을 직접 import하지 않고 edgeLabels 인자로 주입받아 순수 함수로 유지한다
 *
 * @param graph 이슈 그래프 응답 (issue-graph.ts의 IssueGraphResponse)
 * @param edgeLabels edge.type(대문자) → 표시 라벨 맵 (component가 주입)
 * @returns mermaid 코드 + idToKey 역매핑, 또는 엣지가 없으면 null
 */
export function generateGraphMermaidCode(
  graph: IssueGraphResponse,
  edgeLabels: Record<string, string>,
): GraphMermaidResult | null {
  // 빈 그래프 — center만 있고 엣지가 없으면 null 반환 (컴포넌트가 빈 상태 표시 신호)
  if (graph.edges.length === 0) {
    return null
  }

  // ── 1단계: 모든 노드를 수집해 키 → sanitized ID 맵을 만든다 ──────────────
  // nodes 배열 순서대로 인덱스를 부여하고, 동일 키는 동일 ID를 유지한다(dedup).
  const keyToId = new Map<string, string>()

  for (const node of graph.nodes) {
    if (!keyToId.has(node.key)) {
      keyToId.set(node.key, sanitizeNodeId(keyToId.size))
    }
  }

  // 엣지에 등장하지만 nodes에 없는 키도 보험으로 등록한다
  for (const edge of graph.edges) {
    if (!keyToId.has(edge.from)) {
      keyToId.set(edge.from, sanitizeNodeId(keyToId.size))
    }
    if (!keyToId.has(edge.to)) {
      keyToId.set(edge.to, sanitizeNodeId(keyToId.size))
    }
  }

  // idToKey 역매핑 구성
  const idToKey: Record<string, string> = {}
  for (const [key, id] of keyToId.entries()) {
    idToKey[id] = key
  }

  // ── 2단계: center 노드 sanitized ID 조회 ───────────────────────────────────
  const centerSanitizedId = keyToId.get(graph.center)
  // center가 nodes에 없는 경우는 정상적이지 않지만, undefined 가드(noUncheckedIndexedAccess)
  if (centerSanitizedId === undefined) {
    return null
  }

  // ── 3단계: mermaid 코드 라인 조립 ────────────────────────────────────────
  const lines: string[] = ['flowchart LR']

  // 노드 정의 — 라벨은 따옴표로 감싸 특수문자 안전 처리
  for (const [key, id] of keyToId.entries()) {
    lines.push(`  ${id}["${key}"]`)
  }

  // 엣지 정의 — from/to 방향을 백엔드 그대로 보존
  for (const edge of graph.edges) {
    const fromId = keyToId.get(edge.from)
    const toId = keyToId.get(edge.to)
    // 위 단계에서 엣지의 from/to를 모두 keyToId에 등록했으므로 undefined는 발생하지 않는다.
    // noUncheckedIndexedAccess 요건상 명시 가드를 추가한다.
    if (fromId === undefined || toId === undefined) {
      continue
    }
    const label = edgeLabels[edge.type] ?? edge.type
    lines.push(`  ${fromId} -->|"${label}"| ${toId}`)
  }

  // classDef 정의 — center 노드 강조 스타일
  lines.push(`  ${CENTER_CLASS_DEF}`)

  // class 할당 — center 노드에 centerNode 클래스 부여
  lines.push(`  class ${centerSanitizedId} ${CENTER_CLASS}`)

  return {
    code: lines.join('\n'),
    idToKey,
  }
}
