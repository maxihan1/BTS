// useProfile 훅 테스트 — query/mutation 4종 + whoami 재조회를 통한 authStore 갱신 검증 (FR-PR-01 D6 Task 5)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { profileHandlers, resetProfileStore } from '@/mocks/profile-handlers'
import { ALICE_PROFILE_FIXTURE } from '@/mocks/profile-fixtures'
import { aliceUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import {
  PROFILE_QUERY_KEY,
  useProfile,
  usePatchProfile,
  useUploadAvatar,
  useDeleteAvatar,
  refreshWhoami,
} from './useProfile'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

const ALICE_TOKEN = mockAccessToken('alice')

beforeEach(() => {
  resetProfileStore()
  server.use(...profileHandlers)
  useAuthStore.getState().setSession({ accessToken: ALICE_TOKEN, user: aliceUser })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────────────────────────────────────
// PROFILE_QUERY_KEY
// ─────────────────────────────────────────────────────────────────────────────

describe('PROFILE_QUERY_KEY', () => {
  it('["profile", "me"] 를 반환한다', () => {
    expect(PROFILE_QUERY_KEY).toEqual(['profile', 'me'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useProfile — 조회 쿼리
// ─────────────────────────────────────────────────────────────────────────────

describe('useProfile', () => {
  it('본인 프로필을 조회한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProfile(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.userId).toBe(ALICE_PROFILE_FIXTURE.userId)
    expect(result.current.data?.displayName).toBe(ALICE_PROFILE_FIXTURE.displayName)
  })

  it('쿼리 키는 ["profile", "me"] 로 등록된다', async () => {
    const { queryClient, wrapper } = createWrapper()
    renderHook(() => useProfile(), { wrapper })

    await waitFor(() =>
      expect(queryClient.getQueryState(['profile', 'me'])).not.toBeUndefined(),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// usePatchProfile — 3-state 부분 수정 mutation
// ─────────────────────────────────────────────────────────────────────────────

describe('usePatchProfile', () => {
  it('성공 시 profile 쿼리를 invalidate하고 whoami를 재조회해 authStore.user를 교체한다', async () => {
    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({ ...aliceUser, displayName: '수정된이름' }),
      ),
    )

    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => usePatchProfile(), { wrapper })

    await act(async () => {
      result.current.mutate({ displayName: '수정된이름' })
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['profile'] }),
    )
    await waitFor(() =>
      expect(useAuthStore.getState().user?.displayName).toBe('수정된이름'),
    )
  })

  it('실패 시 isError가 true가 되고 authStore.user는 변경되지 않는다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => usePatchProfile(), { wrapper })

    await act(async () => {
      // 공백 displayName → 400 PROFILE_VALIDATION_FAILED (profile-handlers.ts 검증 미러)
      result.current.mutate({ displayName: '   ' })
      await waitFor(() => expect(result.current.isError).toBe(true))
    })

    expect(useAuthStore.getState().user?.displayName).toBe(aliceUser.displayName)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUploadAvatar — 아바타 업로드 mutation
// ─────────────────────────────────────────────────────────────────────────────

describe('useUploadAvatar', () => {
  it('성공 시 profile 쿼리를 invalidate하고 whoami를 재조회해 authStore.user(avatarUrl)를 교체한다', async () => {
    const freshAvatarUrl = `/api/v1/users/${ALICE_PROFILE_FIXTURE.userId}/avatar`
    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({ ...aliceUser, avatarUrl: freshAvatarUrl }),
      ),
    )

    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUploadAvatar(), { wrapper })
    const file = new File(['x'], 'avatar.png', { type: 'image/png' })

    await act(async () => {
      result.current.mutate(file)
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['profile'] }),
    )
    await waitFor(() =>
      expect(useAuthStore.getState().user?.avatarUrl).toBe(freshAvatarUrl),
    )
  })

  it('성공 시 authStore.avatarVersion이 1 증가한다 (고정 avatarUrl 캐시버스트 — 회귀 방지)', async () => {
    useAuthStore.setState({ avatarVersion: 0 })
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useUploadAvatar(), { wrapper })
    const file = new File(['x'], 'avatar.png', { type: 'image/png' })

    await act(async () => {
      result.current.mutate(file)
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(useAuthStore.getState().avatarVersion).toBe(1)
  })

  it('whoami 재조회가 실패해도 mutation onSuccess는 reject되지 않고 성공 처리된다', async () => {
    server.use(
      http.get('/api/v1/users/me/whoami', () => new HttpResponse(null, { status: 500 })),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useUploadAvatar(), { wrapper })
    const file = new File(['x'], 'avatar.png', { type: 'image/png' })

    await act(async () => {
      result.current.mutate(file)
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(result.current.isError).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteAvatar — 아바타 삭제 mutation
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteAvatar', () => {
  it('성공 시 profile 쿼리를 invalidate하고 whoami를 재조회해 authStore.user(avatarUrl=null)를 교체한다', async () => {
    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({ ...aliceUser, avatarUrl: null }),
      ),
    )

    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    useAuthStore.getState().setSession({
      accessToken: ALICE_TOKEN,
      user: { ...aliceUser, avatarUrl: '/api/v1/users/00000000-0000-4000-8000-000000000001/avatar' },
    })

    const { result } = renderHook(() => useDeleteAvatar(), { wrapper })

    await act(async () => {
      result.current.mutate()
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['profile'] }),
    )
    await waitFor(() => expect(useAuthStore.getState().user?.avatarUrl).toBeNull())
  })

  it('성공 시 authStore.avatarVersion이 1 증가한다 (고정 avatarUrl 캐시버스트 — 회귀 방지)', async () => {
    useAuthStore.setState({ avatarVersion: 0 })
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useDeleteAvatar(), { wrapper })

    await act(async () => {
      result.current.mutate()
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(useAuthStore.getState().avatarVersion).toBe(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// refreshWhoami — 헬퍼 단독 동작 (accessToken 없으면 스킵)
// ─────────────────────────────────────────────────────────────────────────────

describe('refreshWhoami', () => {
  it('accessToken이 없으면(로그아웃 상태) whoami를 호출하지 않고 authStore.user를 그대로 둔다', async () => {
    useAuthStore.getState().clearSession()

    await refreshWhoami()

    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
  })

  it('accessToken이 있으면 whoami를 조회해 authStore.user를 교체한다', async () => {
    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({ ...aliceUser, displayName: '프레시이름' }),
      ),
    )
    useAuthStore.getState().setSession({
      accessToken: ALICE_TOKEN,
      user: { ...aliceUser, displayName: '오래된이름' },
    })

    await refreshWhoami()

    expect(useAuthStore.getState().user?.displayName).toBe('프레시이름')
  })

  it('whoami 조회가 실패해도 reject되지 않는다 (graceful degradation — PATCH/업로드 자체는 이미 성공)', async () => {
    server.use(
      http.get('/api/v1/users/me/whoami', () => new HttpResponse(null, { status: 500 })),
    )
    useAuthStore.getState().setSession({
      accessToken: ALICE_TOKEN,
      user: { ...aliceUser, displayName: '변경안됨' },
    })

    await expect(refreshWhoami()).resolves.toBeUndefined()
    expect(useAuthStore.getState().user?.displayName).toBe('변경안됨')
  })
})
