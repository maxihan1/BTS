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

/**
 * 워크플로우 queryKey 상수 — 매직 문자열 방지.
 *
 * ★ **export 한다.** `use-workflows-admin` 의 쓰기 훅이 이 키를 무효화해야 편집 결과가
 * 필터 UI 까지 닿는다. 관리 쪽이 자기 키를 따로 만들면 캐시가 둘로 갈려 「편집했는데
 * 목록이 안 바뀐다」가 되고, 그 어긋남은 화면에서만 드러난다.
 */
export const WORKFLOW_QUERY_KEY = ['workflows'] as const

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
 * 워크플로우 목록에서 상태 키 → 표시 이름·카테고리 맵을 추출한다.
 *
 * 이슈 목록 상태 배지가 **원시 키 대신 이름을, 카테고리에 맞는 색을** 쓰기 위한 정본이다.
 * 이슈 응답(`IssueResponse`)에는 `currentStateKey` 만 실려 오고 카테고리가 없어, 화면이
 * 워크플로우에서 되짚는다.
 *
 * ★중복 키 처리는 {@link extractStatusOptions} 와 **같은 규칙**(첫 등장 채택)이다. 다르게
 * 두면 같은 상태가 필터 드롭다운과 목록 배지에서 다른 이름으로 보인다 — 그 어긋남은
 * 화면에서만 드러난다. 판별식이 두 함수의 결과를 맞대 본다.
 *
 * @param workflows 워크플로우 목록 (useWorkflows().data)
 * @returns 상태 키 → `{ name, category }` 맵. 조회 전·실패면 호출 측이 빈 배열을 넘겨 빈 맵
 */
export function extractStatusMeta(
  workflows: WorkflowView[],
): Map<string, { name: string; category: WorkflowView['states'][number]['category'] }> {
  const map = new Map<string, { name: string; category: WorkflowView['states'][number]['category'] }>()

  for (const workflow of workflows) {
    for (const state of workflow.states) {
      if (!map.has(state.key)) {
        map.set(state.key, { name: state.name, category: state.category })
      }
    }
  }

  return map
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
