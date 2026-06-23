// 대시보드 TanStack Query 훅 통합 테스트 — MSW stateful store 기반 (FR-DB-01 Task 4)
import { describe, it, expect, beforeAll, afterAll, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { setupServer } from 'msw/node'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { dashboardHandlers } from '@/mocks/dashboard-handlers'
import {
  ALICE_OWNER_ID,
  DEFAULT_DASHBOARD,
  OTHER_DASHBOARD,
  resetDashboardStore,
  seedDashboard,
} from '@/mocks/dashboard-fixtures'
import {
  useDashboards,
  useDashboard,
  useCreateDashboard,
  useUpdateDashboard,
  useDeleteDashboard,
  dashboardKeys,
} from './use-dashboards'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 — dashboardHandlers 전용 (stateful store 포함)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...dashboardHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 각 테스트마다 독립된 QueryClient와 Provider 래퍼를 생성한다.
 * retry: false — 실패 시 재시도 없이 즉시 에러 상태로 전환한다.
 */
function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }

  return { queryClient, wrapper: Wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// store 격리 — 각 테스트 전 리셋 + 기본 픽스처 시드
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  resetDashboardStore()
  seedDashboard(DEFAULT_DASHBOARD)
  seedDashboard(OTHER_DASHBOARD)
})

afterEach(() => {
  server.resetHandlers()
})

// ─────────────────────────────────────────────────────────────────────────────
// dashboardKeys — queryKey 팩토리 구조 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardKeys', () => {
  it('T-DB-KEY-1: list()는 ["dashboards"] tuple을 반환한다', () => {
    expect(dashboardKeys.list()).toEqual(['dashboards'])
  })

  it('T-DB-KEY-2: detail(id)는 ["dashboard", id] tuple을 반환한다', () => {
    const id = DEFAULT_DASHBOARD.id
    expect(dashboardKeys.detail(id)).toEqual(['dashboard', id])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDashboards — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useDashboards', () => {
  it('T-DB-LIST-1: 파라미터 없이 호출하면 기본 limit/offset으로 대시보드 목록을 반환한다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDashboards(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // alice 소유 + ORG 가시성인 bob 대시보드 둘 다 포함
    expect(result.current.data).toBeDefined()
    expect(result.current.data?.items.length).toBeGreaterThanOrEqual(1)
    expect(result.current.data?.total).toBeGreaterThanOrEqual(1)
  })

  it('T-DB-LIST-2: 목록 응답에는 alice 소유 대시보드(DEFAULT_DASHBOARD)가 포함된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDashboards(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const ids = result.current.data?.items.map((d) => d.id) ?? []
    expect(ids).toContain(DEFAULT_DASHBOARD.id)
  })

  it('T-DB-LIST-3: params를 전달하면 limit/offset이 적용된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDashboards({ limit: 1, offset: 0 }), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // limit=1이므로 최대 1건
    expect(result.current.data?.items.length).toBeLessThanOrEqual(1)
    expect(result.current.data?.limit).toBe(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDashboard — 단건 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useDashboard', () => {
  it('T-DB-DETAIL-1: 유효한 id를 전달하면 단건 대시보드를 반환한다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDashboard(DEFAULT_DASHBOARD.id), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.id).toBe(DEFAULT_DASHBOARD.id)
    expect(result.current.data?.name).toBe(DEFAULT_DASHBOARD.name)
    expect(result.current.data?.ownerId).toBe(ALICE_OWNER_ID)
  })

  it('T-DB-DETAIL-2: id가 undefined이면 쿼리가 비활성화(idle)된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDashboard(undefined), { wrapper })

    // 50ms 대기 후도 idle 상태여야 한다
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(result.current.fetchStatus).toBe('idle')
  })

  it('T-DB-DETAIL-3: 존재하지 않는 id이면 에러 상태가 된다', async () => {
    const { wrapper } = createWrapper()

    const nonExistentId = '00000000-0000-4000-8000-999999999999'
    const { result } = renderHook(() => useDashboard(nonExistentId), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateDashboard — 생성 mutation
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateDashboard', () => {
  it('T-DB-CREATE-1: mutate 성공 시 새 대시보드를 생성하고 목록 쿼리를 invalidate한다', async () => {
    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useCreateDashboard(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({
        name: '새 대시보드',
        visibility: 'PRIVATE',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 생성된 대시보드 응답에 name이 포함되어야 한다
    expect(result.current.data?.name).toBe('새 대시보드')

    // invalidate-only 확인 — 목록 queryKey로 invalidateQueries가 호출되어야 한다
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: dashboardKeys.list() }),
    )
  })

  it('T-DB-CREATE-2: setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
    const { queryClient, wrapper } = createWrapper()
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useCreateDashboard(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({
        name: '테스트 대시보드',
        visibility: 'PRIVATE',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // setQueryData는 절대 호출되어선 안 된다 (부분응답 플리커 방지)
    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateDashboard — 수정 mutation
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateDashboard', () => {
  it('T-DB-UPDATE-1: PATCH 성공 시 단건·목록 쿼리 모두 invalidate한다', async () => {
    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateDashboard(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({
        id: DEFAULT_DASHBOARD.id,
        body: { name: '수정된 이름', version: DEFAULT_DASHBOARD.version },
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 단건 queryKey invalidate 확인
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: dashboardKeys.detail(DEFAULT_DASHBOARD.id) }),
    )
    // 목록 queryKey invalidate 확인
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: dashboardKeys.list() }),
    )
  })

  it('T-DB-UPDATE-2: PATCH 후 refetch하면 수정된 이름이 반영된다 (stateful store 확인)', async () => {
    const { wrapper } = createWrapper()

    // 먼저 단건 조회로 캐시를 채운다
    const { result: queryResult } = renderHook(() => useDashboard(DEFAULT_DASHBOARD.id), {
      wrapper,
    })
    await waitFor(() => expect(queryResult.current.isSuccess).toBe(true))

    // mutation 훅
    const { result: mutationResult } = renderHook(() => useUpdateDashboard(), { wrapper })

    await act(async () => {
      await mutationResult.current.mutateAsync({
        id: DEFAULT_DASHBOARD.id,
        body: { name: '변경된 대시보드 이름', version: DEFAULT_DASHBOARD.version },
      })
    })

    await waitFor(() => expect(mutationResult.current.isSuccess).toBe(true))

    // invalidate 후 refetch — MSW store가 변이되었으므로 새 이름이 반환되어야 한다
    await waitFor(() => expect(queryResult.current.data?.name).toBe('변경된 대시보드 이름'))
  })

  it('T-DB-UPDATE-3: setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
    const { queryClient, wrapper } = createWrapper()
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useUpdateDashboard(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({
        id: DEFAULT_DASHBOARD.id,
        body: { name: '수정', version: DEFAULT_DASHBOARD.version },
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteDashboard — 삭제 mutation
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteDashboard', () => {
  it('T-DB-DELETE-1: DELETE 성공 시 단건·목록 쿼리 모두 invalidate한다', async () => {
    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useDeleteDashboard(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync(DEFAULT_DASHBOARD.id)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 단건 queryKey invalidate 확인
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: dashboardKeys.detail(DEFAULT_DASHBOARD.id) }),
    )
    // 목록 queryKey invalidate 확인
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: dashboardKeys.list() }),
    )
  })

  it('T-DB-DELETE-2: 삭제 후 목록 refetch 시 삭제된 항목이 제외된다 (stateful store 확인)', async () => {
    const { wrapper } = createWrapper()

    // 먼저 목록 조회로 캐시를 채운다
    const { result: listResult } = renderHook(() => useDashboards(), { wrapper })
    await waitFor(() => expect(listResult.current.isSuccess).toBe(true))
    const initialCount = listResult.current.data?.items.length ?? 0

    // 삭제 mutation
    const { result: mutationResult } = renderHook(() => useDeleteDashboard(), { wrapper })

    await act(async () => {
      await mutationResult.current.mutateAsync(DEFAULT_DASHBOARD.id)
    })

    await waitFor(() => expect(mutationResult.current.isSuccess).toBe(true))

    // invalidate 후 refetch — 삭제된 항목이 제외되어야 한다
    await waitFor(() => {
      const currentCount = listResult.current.data?.items.length ?? initialCount
      return currentCount < initialCount
    })

    const ids = listResult.current.data?.items.map((d) => d.id) ?? []
    expect(ids).not.toContain(DEFAULT_DASHBOARD.id)
  })

  it('T-DB-DELETE-3: setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
    const { queryClient, wrapper } = createWrapper()
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useDeleteDashboard(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync(DEFAULT_DASHBOARD.id)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})
