// 워크플로우 목록 조회 훅 + 상태 옵션 추출 순수 함수 (FR-SR-01 Task 3)
import { useQuery } from '@tanstack/react-query'
import { fetchWorkflows } from '@/api/workflows'
import type { WorkflowView } from '@/api/workflows'

/** 워크플로우 상태 옵션 — 필터 UI 표시용 */
export interface StatusOption {
  /** 상태 식별 키 (백엔드 전송 값) */
  key: string
  /** 사용자에게 표시할 상태 이름 */
  name: string
}

/** 워크플로우 queryKey 상수 — 매직 문자열 방지 */
const WORKFLOW_QUERY_KEY = ['workflows'] as const

/** 캐시 유지 시간 (ms) — 30초 */
const STALE_TIME_MS = 30_000

/**
 * 전체 워크플로우 목록을 조회한다.
 *
 * GET /api/v1/workflows → WorkflowView[]
 * staleTime 30초 — 워크플로우는 자주 변경되지 않아 짧은 캐시로 UX 보호.
 */
export function useWorkflows() {
  return useQuery({
    queryKey: WORKFLOW_QUERY_KEY,
    queryFn: () => fetchWorkflows(),
    staleTime: STALE_TIME_MS,
  })
}

/**
 * 워크플로우 목록에서 상태 옵션 목록을 추출한다.
 *
 * 규칙.
 * - 모든 워크플로우의 states를 flatten한 뒤 key 기준으로 중복을 제거한다.
 * - **첫 등장 항목(key, name, displayOrder)** 을 채택한다(이후 동일 key는 무시).
 * - 최종 정렬은 **(displayOrder asc, key asc) 튜플** 안정 정렬로 결정성을 보장한다.
 *
 * @param workflows 워크플로우 목록 (useWorkflows().data)
 * @returns 중복 제거 후 정렬된 StatusOption 배열
 */
export function extractStatusOptions(workflows: WorkflowView[]): StatusOption[] {
  // key → 첫 등장 상태 맵 (삽입 순서 보존)
  const seen = new Map<string, { key: string; name: string; displayOrder: number }>()

  for (const workflow of workflows) {
    for (const state of workflow.states) {
      if (!seen.has(state.key)) {
        seen.set(state.key, {
          key: state.key,
          name: state.name,
          displayOrder: state.displayOrder,
        })
      }
    }
  }

  return [...seen.values()]
    .sort((a, b) => {
      const orderDiff = a.displayOrder - b.displayOrder
      if (orderDiff !== 0) return orderDiff
      // displayOrder 동일 시 key asc — 결정적 정렬 보장(B4)
      if (a.key < b.key) return -1
      if (a.key > b.key) return 1
      return 0
    })
    .map(({ key, name }) => ({ key, name }))
}
