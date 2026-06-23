// 대시보드 조회·생성·수정·삭제 TanStack Query 훅 (FR-DB-01)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  listDashboards,
  getDashboard,
  createDashboard,
  patchDashboard,
  deleteDashboard,
} from '@/api/dashboards'
import type { Dashboard, DashboardPage, CreateDashboardRequest, PatchDashboardRequest } from '@/api/dashboards'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 대시보드 BC queryKey 팩토리 */
export const dashboardKeys = {
  /** 대시보드 목록 queryKey */
  list: () => ['dashboards'] as const,
  /**
   * 대시보드 단건 상세 queryKey.
   *
   * @param id 대시보드 UUID
   */
  detail: (id: string) => ['dashboard', id] as const,
}

// ─────────────────────────────────────────────────────────────────────────────
// 목록 조회 파라미터 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * useDashboards 훅의 선택적 파라미터.
 * 미전달 시 기본값(limit 50, offset 0)이 적용된다.
 */
export interface DashboardListParams {
  /** 페이지 크기 (기본 50) */
  limit?: number
  /** 페이지 오프셋 (기본 0) */
  offset?: number
}

// ─────────────────────────────────────────────────────────────────────────────
// useDashboards — 대시보드 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 접근 가능한 대시보드 목록을 조회한다.
 *
 * GET /api/v1/dashboards?limit=&offset= → DashboardPage (items, total, limit, offset)
 * staleTime 30초 — 빈번한 목록 재조회를 방지한다.
 *
 * @param params 선택적 페이지네이션 파라미터 (기본 limit=50, offset=0)
 */
export function useDashboards(params?: DashboardListParams) {
  const limit = params?.limit ?? 50
  const offset = params?.offset ?? 0

  return useQuery<DashboardPage>({
    queryKey: [...dashboardKeys.list(), { limit, offset }],
    queryFn: () => listDashboards(limit, offset),
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDashboard — 대시보드 단건 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 단건 상세 정보를 조회한다.
 *
 * GET /api/v1/dashboards/{id} → Dashboard
 * staleTime 30초.
 *
 * @param id 대시보드 UUID. undefined이면 쿼리가 비활성화된다.
 */
export function useDashboard(id: string | undefined) {
  return useQuery<Dashboard>({
    queryKey: dashboardKeys.detail(id ?? ''),
    queryFn: () => {
      if (id === undefined) {
        throw new Error('id is required')
      }
      return getDashboard(id)
    },
    staleTime: 30_000,
    enabled: id !== undefined && id.length > 0,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useCreateDashboard — 대시보드 생성 mutation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 새 대시보드를 생성한다.
 *
 * POST /api/v1/dashboards → 201 Dashboard
 *
 * onSuccess → invalidateQueries (invalidate-only, setQueryData 금지)
 * 목록 캐시를 무효화해 refetch를 유도한다.
 */
export function useCreateDashboard() {
  const queryClient = useQueryClient()

  return useMutation<Dashboard, unknown, CreateDashboardRequest>({
    mutationFn: (body) => createDashboard(body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: dashboardKeys.list() })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateDashboard — 대시보드 수정 mutation
// ─────────────────────────────────────────────────────────────────────────────

/** useUpdateDashboard mutation 입력 타입 */
export interface UpdateDashboardInput {
  /** 수정할 대시보드 UUID */
  id: string
  /** 수정 요청 바디 (version 필수 — OCC 낙관적 잠금) */
  body: PatchDashboardRequest
}

/**
 * 대시보드를 부분 수정한다.
 *
 * PATCH /api/v1/dashboards/{id} → Dashboard (version+1 포함)
 *
 * onSuccess → invalidate-only:
 *   - 단건 쿼리 (dashboardKeys.detail(id)) invalidate
 *   - 목록 쿼리 (dashboardKeys.list()) invalidate
 *
 * setQueryData로 부분 응답을 캐시에 머지하지 않는다.
 * 파생 필드 누락으로 화면이 플리커하는 사고를 방지한다
 * (memory: mutation-setquerydata-partial-response-flicker).
 */
export function useUpdateDashboard() {
  const queryClient = useQueryClient()

  return useMutation<Dashboard, unknown, UpdateDashboardInput>({
    mutationFn: ({ id, body }) => patchDashboard(id, body),
    onSuccess: async (_data, { id }) => {
      await queryClient.invalidateQueries({ queryKey: dashboardKeys.detail(id) })
      await queryClient.invalidateQueries({ queryKey: dashboardKeys.list() })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteDashboard — 대시보드 삭제 mutation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드를 삭제한다.
 *
 * DELETE /api/v1/dashboards/{id} → 204 No Content
 *
 * onSuccess → invalidate-only:
 *   - 단건 쿼리 (dashboardKeys.detail(id)) invalidate
 *   - 목록 쿼리 (dashboardKeys.list()) invalidate
 */
export function useDeleteDashboard() {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, string>({
    mutationFn: (id) => deleteDashboard(id),
    onSuccess: async (_data, id) => {
      await queryClient.invalidateQueries({ queryKey: dashboardKeys.detail(id) })
      await queryClient.invalidateQueries({ queryKey: dashboardKeys.list() })
    },
  })
}
