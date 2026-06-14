// 이슈 링크 그래프 BC MSW 핸들러 — GET /api/v1/issues/:key/graph (FR-LK-02)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 이슈 키 상수 (E2E 참조용)
//
// E2E 시나리오 토글 관례.
//   - 이슈 키별 고정 응답 방식: ATLAS-1(일반)/ATLAS-4(빈)/ATLAS-5(truncated)
//   - ATLAS-4, ATLAS-5는 issue-handlers.ts에 fixture가 존재(이슈 상세 정상 렌더)
//   - localStorage 플래그가 필요 없어 가장 단순하고 격리가 명확하다
//   - msw-derived-behavior-shared-store-e2e 교훈: 파생 응답은 단일 스토어에서 읽는다
// ─────────────────────────────────────────────────────────────────────────────

/** 일반 그래프 시나리오 이슈 키 (depth별 노드 수 변화 검증) */
export const GRAPH_NORMAL_KEY = 'ATLAS-1'

/** 빈 그래프 시나리오 이슈 키 — 엣지 0개 → emptyState 렌더 */
export const GRAPH_EMPTY_KEY = 'ATLAS-4'

/** truncated 시나리오 이슈 키 — truncated=true + 노드/엣지 있음 */
export const GRAPH_TRUNCATED_KEY = 'ATLAS-5'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 (a) — 일반 그래프 응답 (ATLAS-1)
//
// depth에 따라 노드/엣지 수를 다르게 반환해 depth 변경 재조회를 검증한다.
//   depth=1: center(ATLAS-1) + ATLAS-2 = 2노드, 1엣지
//   depth≥2: center(ATLAS-1) + ATLAS-2 + ATLAS-3 = 3노드, 2엣지
// ─────────────────────────────────────────────────────────────────────────────

function buildNormalGraphResponse(center: string, depth: number) {
  const nodes =
    depth === 1
      ? [
          { key: center, summary: `${center} 이슈`, statusKey: 'open', depth: 0 },
          { key: 'ATLAS-2', summary: 'ATLAS-2 이슈', statusKey: 'in_progress', depth: 1 },
        ]
      : [
          { key: center, summary: `${center} 이슈`, statusKey: 'open', depth: 0 },
          { key: 'ATLAS-2', summary: 'ATLAS-2 이슈', statusKey: 'in_progress', depth: 1 },
          { key: 'ATLAS-3', summary: 'ATLAS-3 이슈', statusKey: 'done', depth: 2 },
        ]

  const edges =
    depth === 1
      ? [{ from: center, to: 'ATLAS-2', type: 'BLOCKS' }]
      : [
          { from: center, to: 'ATLAS-2', type: 'BLOCKS' },
          { from: 'ATLAS-2', to: 'ATLAS-3', type: 'RELATES' },
        ]

  return {
    center,
    depth,
    nodes,
    edges,
    truncated: false,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 (b) — 빈 그래프 응답 (ATLAS-4)
//
// 엣지가 0개인 경우.
// LinkGraph: generateGraphMermaidCode → null → EmptyState 컴포넌트 렌더.
// ─────────────────────────────────────────────────────────────────────────────

function buildEmptyGraphResponse(center: string, depth: number) {
  return {
    center,
    depth,
    nodes: [{ key: center, summary: `${center} 이슈`, statusKey: 'open', depth: 0 }],
    edges: [] as Array<{ from: string; to: string; type: string }>,
    truncated: false,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 (c) — truncated 응답 (ATLAS-5)
//
// truncated=true이고 엣지 있음.
// LinkGraph: truncatedNotice + GraphRenderer 동시 렌더.
// ─────────────────────────────────────────────────────────────────────────────

function buildTruncatedGraphResponse(center: string, depth: number) {
  return {
    center,
    depth,
    nodes: [
      { key: center, summary: `${center} 이슈`, statusKey: 'open', depth: 0 },
      { key: 'ATLAS-2', summary: 'ATLAS-2 이슈', statusKey: 'in_progress', depth: 1 },
    ],
    edges: [{ from: center, to: 'ATLAS-2', type: 'DUPLICATES' }],
    truncated: true,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/issues/:key/graph — 이슈 링크 그래프 조회.
 *
 * depth 쿼리 파라미터를 반영한다 (1~3 정수, 기본값 2).
 * 이슈 키별 고정 시나리오를 반환한다.
 *
 *   ATLAS-1 → 일반 그래프 (depth 반영)
 *   ATLAS-4 → 빈 그래프 (엣지 0)
 *   ATLAS-5 → truncated=true 그래프
 *   기타 → 일반 그래프 fallback
 *
 * 성공 → 200 { data: IssueGraphResponse }
 * depth 비정수/범위 밖 → 400 INVALID_DEPTH
 */
const getIssueGraphHandler = http.get(
  '/api/v1/issues/:key/graph',
  ({ params, request }) => {
    const key = params['key'] as string
    const url = new URL(request.url)
    const depthParam = url.searchParams.get('depth')
    const depth = depthParam !== null ? parseInt(depthParam, 10) : 2

    if (isNaN(depth) || depth < 1 || depth > 3) {
      return HttpResponse.json(
        { errorCode: 'INVALID_DEPTH', message: 'depth는 1~3 정수여야 합니다.' },
        { status: 400 },
      )
    }

    if (key === GRAPH_EMPTY_KEY) {
      return HttpResponse.json({ data: buildEmptyGraphResponse(key, depth) })
    }

    if (key === GRAPH_TRUNCATED_KEY) {
      return HttpResponse.json({ data: buildTruncatedGraphResponse(key, depth) })
    }

    // ATLAS-1 등 기본: 일반 그래프
    return HttpResponse.json({ data: buildNormalGraphResponse(key, depth) })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 그래프 BC MSW 핸들러 배열 */
export const issueGraphHandlers = [getIssueGraphHandler]
