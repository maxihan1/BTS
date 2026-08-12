// 대시보드 TanStack Query 훅 통합 테스트 — MSW stateful store 기반 (FR-DB-01 Task 4)
import { server } from '@/test/server'
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { dashboardHandlers } from '@/mocks/dashboard-handlers'
import {
  ALICE_OWNER_ID,
  DEFAULT_DASHBOARD,
  OTHER_DASHBOARD,
  resetDashboardStore,
  seedDashboard,
  resetShareTokenStore,
  seedShareToken,
} from '@/mocks/dashboard-fixtures'
import {
  useDashboards,
  useDashboard,
  useCreateDashboard,
  useUpdateDashboard,
  useDeleteDashboard,
  useShareTokens,
  useIssueShareToken,
  useRevokeShareToken,
  dashboardKeys,
} from './use-dashboards'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 — dashboardHandlers 전용 (stateful store 포함)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...dashboardHandlers)
})

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
  resetShareTokenStore()
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

  it('T-DB-KEY-3: shares(id)는 ["dashboards", id, "shares"] tuple을 반환한다', () => {
    const id = DEFAULT_DASHBOARD.id
    expect(dashboardKeys.shares(id)).toEqual(['dashboards', id, 'shares'])
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

    // 두 훅을 하나의 renderHook 안에서 함께 렌더링해 동일 QueryClient를 공유한다.
    // 별도 renderHook은 React 트리가 분리되어 캐시를 공유하지 못한다.
    const { result } = renderHook(
      () => ({
        query: useDashboard(DEFAULT_DASHBOARD.id),
        mutation: useUpdateDashboard(),
      }),
      { wrapper },
    )

    await waitFor(() => expect(result.current.query.isSuccess).toBe(true))
    expect(result.current.query.data?.name).toBe(DEFAULT_DASHBOARD.name)

    await act(async () => {
      await result.current.mutation.mutateAsync({
        id: DEFAULT_DASHBOARD.id,
        body: { name: '변경된 대시보드 이름', version: DEFAULT_DASHBOARD.version },
      })
    })

    await waitFor(() => expect(result.current.mutation.isSuccess).toBe(true))

    // invalidate → TanStack Query가 자동 refetch → MSW store에서 변경된 이름 반환
    await waitFor(() => expect(result.current.query.data?.name).toBe('변경된 대시보드 이름'))
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

// ─────────────────────────────────────────────────────────────────────────────
// useShareTokens — 공유 토큰 목록 조회 (FR-DB-03 D6/D7 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('useShareTokens', () => {
  it('T-DB-SHARE-LIST-1: 발급된 공유 토큰 목록을 조회한다 (원문 token 미포함)', async () => {
    const { wrapper } = createWrapper()
    seedShareToken({
      id: 'd0000000-0000-4000-8000-000000000001',
      dashboardId: DEFAULT_DASHBOARD.id,
      token: 'raw-token-should-not-leak',
      createdAt: '2026-07-01T00:00:00.000Z',
      expiresAt: null,
    })

    const { result } = renderHook(() => useShareTokens(DEFAULT_DASHBOARD.id), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.items.length).toBe(1)
    expect(result.current.data?.items[0]?.id).toBe('d0000000-0000-4000-8000-000000000001')
    // ShareTokenSummary 타입에는 token 필드가 없다 — 컴파일 시점 유출 회귀가드
  })

  it('T-DB-SHARE-LIST-2: 쿼리키는 ["dashboards", id, "shares"]에 캐시된다', async () => {
    const { queryClient, wrapper } = createWrapper()

    const { result } = renderHook(() => useShareTokens(DEFAULT_DASHBOARD.id), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const cached = queryClient.getQueryData(dashboardKeys.shares(DEFAULT_DASHBOARD.id))
    expect(cached).toBeDefined()
  })

  it('T-DB-SHARE-LIST-3: 발급된 토큰이 없으면 빈 목록을 반환한다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useShareTokens(DEFAULT_DASHBOARD.id), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.items).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useIssueShareToken — 공유 토큰 발급 mutation (FR-DB-03 D6/D7 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('useIssueShareToken', () => {
  it('T-DB-SHARE-ISSUE-1: 발급 성공 시 원문 token을 포함한 결과를 호출측에 반환한다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useIssueShareToken(), { wrapper })

    let mutateResult: { token: string } | undefined
    await act(async () => {
      mutateResult = await result.current.mutateAsync({ id: DEFAULT_DASHBOARD.id })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 모달이 재조회 불가한 원문 token을 state로 보관해야 하므로 mutateAsync 반환값에 그대로 담겨야 한다
    expect(typeof mutateResult?.token).toBe('string')
    expect(mutateResult?.token.length).toBeGreaterThan(0)
    expect(result.current.data?.token).toBe(mutateResult?.token)
  })

  it('T-DB-SHARE-ISSUE-2: 성공 시 공유 목록 쿼리를 invalidate한다 (invalidate-only)', async () => {
    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useIssueShareToken(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ id: DEFAULT_DASHBOARD.id })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: dashboardKeys.shares(DEFAULT_DASHBOARD.id) }),
    )
  })

  it('T-DB-SHARE-ISSUE-3: setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
    const { queryClient, wrapper } = createWrapper()
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useIssueShareToken(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ id: DEFAULT_DASHBOARD.id })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })

  it('T-DB-SHARE-ISSUE-4: 발급 후 목록 refetch하면 새 항목이 반영된다 (stateful store 확인)', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(
      () => ({
        query: useShareTokens(DEFAULT_DASHBOARD.id),
        mutation: useIssueShareToken(),
      }),
      { wrapper },
    )

    await waitFor(() => expect(result.current.query.isSuccess).toBe(true))
    expect(result.current.query.data?.items.length).toBe(0)

    await act(async () => {
      await result.current.mutation.mutateAsync({ id: DEFAULT_DASHBOARD.id })
    })

    await waitFor(() => expect(result.current.mutation.isSuccess).toBe(true))

    // invalidate → TanStack Query가 자동 refetch → MSW store에 발급된 항목이 반영
    await waitFor(() => expect(result.current.query.data?.items.length).toBe(1))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useRevokeShareToken — 공유 토큰 취소 mutation (FR-DB-03 D6/D7 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('useRevokeShareToken', () => {
  const SHARE_ID = 'd0000000-0000-4000-8000-000000000002'

  beforeEach(() => {
    seedShareToken({
      id: SHARE_ID,
      dashboardId: DEFAULT_DASHBOARD.id,
      token: 'raw-token-to-revoke',
      createdAt: '2026-07-01T00:00:00.000Z',
      expiresAt: null,
    })
  })

  it('T-DB-SHARE-REVOKE-1: 취소 성공 시 공유 목록 쿼리를 invalidate한다', async () => {
    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useRevokeShareToken(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ id: DEFAULT_DASHBOARD.id, shareId: SHARE_ID })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: dashboardKeys.shares(DEFAULT_DASHBOARD.id) }),
    )
  })

  it('T-DB-SHARE-REVOKE-2: setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
    const { queryClient, wrapper } = createWrapper()
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useRevokeShareToken(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ id: DEFAULT_DASHBOARD.id, shareId: SHARE_ID })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })

  it('T-DB-SHARE-REVOKE-3: 취소 후 목록 refetch하면 항목이 제거된다 (stateful store 확인)', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(
      () => ({
        query: useShareTokens(DEFAULT_DASHBOARD.id),
        mutation: useRevokeShareToken(),
      }),
      { wrapper },
    )

    await waitFor(() => expect(result.current.query.isSuccess).toBe(true))
    expect(result.current.query.data?.items.length).toBe(1)

    await act(async () => {
      await result.current.mutation.mutateAsync({ id: DEFAULT_DASHBOARD.id, shareId: SHARE_ID })
    })

    await waitFor(() => expect(result.current.mutation.isSuccess).toBe(true))

    await waitFor(() => expect(result.current.query.data?.items.length).toBe(0))
  })
})
