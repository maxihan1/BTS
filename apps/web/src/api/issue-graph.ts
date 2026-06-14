// 이슈 링크 그래프 API 클라이언트 및 lazy TanStack Query 훅 — FR-LK-02
import { z } from 'zod'
import { useQuery } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 언랩
// ─────────────────────────────────────────────────────────────────────────────

/**
 * backend 공통 응답 래퍼 `{ data: T }` 파싱 스키마.
 * issue-links.ts:16, issue-versions.ts:32와 동일 패턴으로 로컬 재정의.
 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 GET /api/v1/issues/{key}/graph 응답과 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 그래프 노드 단건 스키마.
 * 백엔드 IssueGraphNodeDto 미러.
 */
export const issueGraphNodeSchema = z.object({
  /** 이슈 키 (예: "ATLAS-1") */
  key: z.string().min(1),
  /** 이슈 요약 */
  summary: z.string(),
  /** 이슈 상태 키 */
  statusKey: z.string(),
  /** 중심으로부터의 거리 (0 = center) */
  depth: z.number().int().min(0),
})

/**
 * 그래프 엣지 단건 스키마.
 * 백엔드 IssueGraphEdgeDto 미러.
 * type은 대문자 5종 (BLOCKS/RELATES/DUPLICATES/CLONES/PARENT).
 */
export const issueGraphEdgeSchema = z.object({
  /** 출발 이슈 키 */
  from: z.string().min(1),
  /** 도착 이슈 키 */
  to: z.string().min(1),
  /** 엣지 유형 (대문자) — "BLOCKS" | "RELATES" | "DUPLICATES" | "CLONES" | "PARENT" */
  type: z.string().min(1),
})

/**
 * 이슈 그래프 전체 응답 스키마.
 * 백엔드 IssueGraphResponse.data 미러.
 * truncated=true이면 NODE_CAP(100)으로 잘린 상태.
 */
export const issueGraphResponseSchema = z.object({
  /** 그래프의 중심 이슈 키 */
  center: z.string().min(1),
  /** 조회 depth (1~3) */
  depth: z.number().int().min(1).max(3),
  /** 그래프 노드 목록 */
  nodes: z.array(issueGraphNodeSchema),
  /** 그래프 엣지 목록 */
  edges: z.array(issueGraphEdgeSchema),
  /**
   * NODE_CAP(100) 초과로 잘린 경우 true.
   * 화면에서 경고 배너를 표시할 때 사용.
   */
  truncated: z.boolean(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 도출
// ─────────────────────────────────────────────────────────────────────────────

/** 그래프 노드 응답 타입 */
export type IssueGraphNode = z.infer<typeof issueGraphNodeSchema>

/** 그래프 엣지 응답 타입 */
export type IssueGraphEdge = z.infer<typeof issueGraphEdgeSchema>

/** 이슈 그래프 전체 응답 타입 */
export type IssueGraphResponse = z.infer<typeof issueGraphResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — 백엔드 IssueGraphErrorCode 열거 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 그래프 BC errorCode 상수.
 * 호출 측(그래프 컴포넌트)이 switch/if 분기에서 사용한다.
 * (error-key drift 방지 — PR #106 교훈, 공유 util 경유)
 */
export const ISSUE_GRAPH_ERROR_CODES = {
  /** 이슈를 찾을 수 없음 */
  ISSUE_NOT_FOUND: 'ISSUE_NOT_FOUND',
  /** depth가 비정수이거나 범위(1~3) 밖 */
  INVALID_DEPTH: 'INVALID_DEPTH',
} as const

/** 이슈 그래프 BC errorCode 유니온 타입 */
export type IssueGraphErrorCode = (typeof ISSUE_GRAPH_ERROR_CODES)[keyof typeof ISSUE_GRAPH_ERROR_CODES]

// ─────────────────────────────────────────────────────────────────────────────
// 엣지 타입 상수 — 대문자 5종 (helper·component 공유용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 그래프 엣지 타입 상수 (대문자).
 * 백엔드 EdgeType 열거 미러. 그래프 렌더링 컴포넌트에서 색상/아이콘 분기에 사용한다.
 */
export const ISSUE_GRAPH_EDGE_TYPES = {
  /** A가 B를 차단함 */
  BLOCKS: 'BLOCKS',
  /** A와 B가 관련됨 */
  RELATES: 'RELATES',
  /** A가 B를 복제함 */
  DUPLICATES: 'DUPLICATES',
  /** A가 B를 클론함 */
  CLONES: 'CLONES',
  /** A가 B의 부모임 */
  PARENT: 'PARENT',
} as const

/** 엣지 타입 유니온 타입 */
export type IssueGraphEdgeType = (typeof ISSUE_GRAPH_EDGE_TYPES)[keyof typeof ISSUE_GRAPH_EDGE_TYPES]

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 그래프 쿼리 키.
 * depth를 키에 포함해 depth 변경 시 별도 캐시 엔트리로 관리한다.
 *
 * @param key 이슈 키 (예: "ATLAS-1")
 * @param depth 그래프 탐색 깊이 (1~3)
 */
export const issueGraphKey = (
  key: string,
  depth: number,
): [string, string, number] => ['issue-graph', key, depth]

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 그래프를 조회한다.
 * GET /api/v1/issues/{key}/graph?depth={depth} → `{ data: IssueGraphResponse }` 언랩
 *
 * @param key 이슈 키 (예: "ATLAS-1")
 * @param depth 그래프 탐색 깊이 (1~3, 기본값 2)
 * @throws ApiError — 400 INVALID_DEPTH(비정수/범위밖), 404 ISSUE_NOT_FOUND
 */
export async function fetchIssueGraph(
  key: string,
  depth: number,
): Promise<IssueGraphResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/graph?depth=${depth}`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueGraphResponseSchema).parse(raw)
  return wrapped.data
}

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 그래프 lazy 조회 훅.
 * enabled=false이면 쿼리를 실행하지 않는다 — 그래프 패널을 펼칠 때만 조회.
 *
 * 사용 예.
 * ```tsx
 * const [open, setOpen] = useState(false)
 * const { data, isLoading } = useIssueGraph(issueKey, 2, open)
 * ```
 *
 * @param key 이슈 키 (예: "ATLAS-1")
 * @param depth 그래프 탐색 깊이 (1~3, 기본값 2)
 * @param enabled true이면 조회 실행, false이면 idle 유지
 */
export function useIssueGraph(key: string, depth: number, enabled: boolean) {
  return useQuery({
    queryKey: issueGraphKey(key, depth),
    queryFn: () => fetchIssueGraph(key, depth),
    enabled,
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 이슈 그래프 BC errorCode를 추출한다.
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 * (선례: issue-links.ts extractLinkErrorCode 동일 패턴)
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractGraphErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
