// FR-PM-02 useProjectPermissions 훅 단위 테스트 — RED phase
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import { server } from '@/test/server'
import { projectPermissionHandlers } from '@/mocks/project-permission-handlers'
import { aliceUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { useProjectPermissions } from './use-project-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client }, children)
  return { client, wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// useProjectPermissions
// ─────────────────────────────────────────────────────────────────────────────

describe('useProjectPermissions', () => {
  beforeEach(() => {
    // 핸들러가 Authorization 토큰을 검증하므로 alice 세션을 미리 설정한다
    useAuthStore.getState().setSession({ accessToken: mockAccessToken('alice'), user: aliceUser })
    server.use(...projectPermissionHandlers)
  })

  afterEach(() => {
    useAuthStore.getState().clearSession()
  })

  it('projectKey를 전달하면 권한 데이터를 반환한다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjectPermissions('ATLAS'), {
      wrapper,
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.projectKey).toBe('ATLAS')
    expect(result.current.data?.permissions.CREATE).toBe(true)
  })

  it('초기 로딩 중에는 isLoading이 true다', () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', async () => {
        await new Promise(() => {
          // 응답을 보류해 로딩 상태를 유지한다
        })
        return HttpResponse.json({})
      }),
    )

    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjectPermissions('ATLAS'), {
      wrapper,
    })

    expect(result.current.isLoading || result.current.isPending).toBe(true)
  })

  it('서버가 오류를 반환하면 isError가 true다', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ error: 'internal_error' }, { status: 500 }),
      ),
    )

    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjectPermissions('ATLAS'), {
      wrapper,
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })

  it('projectKey가 빈 문자열이면 쿼리가 비활성화(enabled:false)돼 호출하지 않는다', () => {
    let fetchCalled = false

    server.use(
      http.get('/api/v1/users/me/project-permissions', () => {
        fetchCalled = true
        return HttpResponse.json({})
      }),
    )

    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjectPermissions(''), {
      wrapper,
    })

    // enabled:false 상태 — 로딩도 아니고 데이터도 없다
    expect(result.current.isPending).toBe(true)
    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchCalled).toBe(false)
  })

  it('staleTime 내 두 번째 훅 호출은 서버 재요청 없이 캐시를 재사용한다', async () => {
    let fetchCount = 0

    server.use(
      http.get('/api/v1/users/me/project-permissions', () => {
        fetchCount++
        return HttpResponse.json({
          projectKey: 'ATLAS',
          permissions: { CREATE: true },
        })
      }),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) =>
      createElement(QueryClientProvider, { client }, children)

    const hook1 = renderHook(() => useProjectPermissions('ATLAS'), { wrapper })
    await waitFor(() => expect(hook1.result.current.isSuccess).toBe(true))

    // 같은 QueryClient로 두 번째 훅 — staleTime 내이므로 재요청 없음
    const hook2 = renderHook(() => useProjectPermissions('ATLAS'), { wrapper })
    await waitFor(() => expect(hook2.result.current.isSuccess).toBe(true))

    expect(fetchCount).toBe(1)
  })
})
