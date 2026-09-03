// 401 인터셉터 동작 검증 — refresh 자동 호출, race lock, refresh 실패 처리
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { apiFetch } from './client'
import { useAuthStore } from '@/auth/authStore'
import { useLoginPromptStore } from '@/auth/loginPromptStore'

beforeEach(() => {
  // 각 테스트 전 store 초기화
  useAuthStore.setState({ accessToken: null, user: null })
  useLoginPromptStore.getState().reset()
  // sessionStorage 정리
  sessionStorage.clear()
})

describe('401 인터셉터', () => {
  it('401 응답 시 /refresh 자동 호출 후 원래 요청을 retry하여 성공한다', async () => {
    const refreshCallCount = { count: 0 }
    const protectedCallCount = { count: 0 }

    // 첫 번째 호출은 401, retry는 200
    server.use(
      http.get('/api/v1/protected', () => {
        protectedCallCount.count++
        if (protectedCallCount.count === 1) {
          return new HttpResponse(JSON.stringify({ error: 'Unauthorized' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        return HttpResponse.json({ data: 'ok' })
      }),
      http.post('/api/v1/auth/refresh', () => {
        refreshCallCount.count++
        return HttpResponse.json({
          access_token: 'new-access-token',
          token_type: 'Bearer',
          expires_in: 900,
        })
      }),
    )

    useAuthStore.setState({ accessToken: 'old-token', user: null })

    const res = await apiFetch('/api/v1/protected')

    expect(res.ok).toBe(true)
    expect(refreshCallCount.count).toBe(1)
    expect(protectedCallCount.count).toBe(2)

    // store에 새 토큰이 저장되어야 한다
    expect(useAuthStore.getState().accessToken).toBe('new-access-token')
  })

  it('4개 요청이 동시에 401을 받아도 /refresh는 1회만 호출된다 (race lock)', async () => {
    const refreshCallCount = { count: 0 }

    server.use(
      http.get('/api/v1/resource', () => {
        return new HttpResponse(JSON.stringify({ error: 'Unauthorized' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        })
      }),
      http.post('/api/v1/auth/refresh', async () => {
        refreshCallCount.count++
        // 약간의 지연으로 동시성 시뮬레이션
        await new Promise<void>((resolve) => setTimeout(resolve, 10))
        return HttpResponse.json({
          access_token: 'new-access-token',
          token_type: 'Bearer',
          expires_in: 900,
        })
      }),
    )

    useAuthStore.setState({ accessToken: 'old-token', user: null })

    // 4개 요청 동시 발사 — 모두 401을 받을 것이고 동일한 refreshPromise를 공유해야 한다
    await Promise.allSettled([
      apiFetch('/api/v1/resource'),
      apiFetch('/api/v1/resource'),
      apiFetch('/api/v1/resource'),
      apiFetch('/api/v1/resource'),
    ])

    expect(refreshCallCount.count).toBe(1)
  })

  it('refresh가 401을 반환하면 clearSession()을 호출하고 reject한다', async () => {
    server.use(
      http.get('/api/v1/protected', () => {
        return new HttpResponse(JSON.stringify({ error: 'Unauthorized' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        })
      }),
      http.post('/api/v1/auth/refresh', () => {
        return new HttpResponse(JSON.stringify({ error: 'Refresh token expired' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        })
      }),
    )

    useAuthStore.setState({ accessToken: 'old-token', user: { username: 'u', email: 'e@e.com', authMethod: 'local', userId: '1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false } })

    const clearSessionSpy = vi.spyOn(useAuthStore.getState(), 'clearSession')

    await expect(apiFetch('/api/v1/protected')).rejects.toThrow()

    // store가 비워져야 한다
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()

    clearSessionSpy.mockRestore()
  })
})

describe('세션 만료 프롬프트 — refresh 실패 시 로그인 모달 트리거', () => {
  /** refresh 가 401 을 돌려주는 표준 배선 */
  function stubExpiredRefresh() {
    server.use(
      http.get('/api/v1/protected', () =>
        new HttpResponse(JSON.stringify({ error: 'Unauthorized' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
      http.post('/api/v1/auth/refresh', () =>
        new HttpResponse(JSON.stringify({ error: 'Refresh token expired' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
  }

  it('세션이 있던 요청의 refresh 가 실패하면 sessionExpired 가 true 가 된다', async () => {
    stubExpiredRefresh()
    useAuthStore.setState({ accessToken: 'old-token', user: null })

    await expect(apiFetch('/api/v1/protected')).rejects.toThrow()

    expect(useLoginPromptStore.getState().sessionExpired).toBe(true)
  })

  it('세션이 없던 요청의 refresh 가 실패하면 sessionExpired 는 false 로 남는다 (공개 라우트 방어)', async () => {
    // dashboards/shared/$token 은 staticData:{requireAuth:false} 인 미인증 공개 라우트다.
    // 세션이 애초에 없던 401 로 공개 페이지 위에 로그인 모달을 씌우면 안 된다.
    stubExpiredRefresh()
    useAuthStore.setState({ accessToken: null, user: null })

    await expect(apiFetch('/api/v1/protected')).rejects.toThrow()

    expect(useLoginPromptStore.getState().sessionExpired).toBe(false)
  })
})
