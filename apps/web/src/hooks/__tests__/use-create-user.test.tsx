// useCreateUser 훅 테스트 — 성공 / 409 USERNAME_TAKEN 에러 시나리오 검증
import { renderHook, waitFor, act } from '@testing-library/react'
import { describe, it, expect, beforeEach } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useCreateUser } from '../use-create-user'
import { ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  return {
    wrapper: ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  }
}

const VALID_PAYLOAD = { username: 'newuser', displayName: '새 사용자' }

const CREATED_RESPONSE = {
  id: '11111111-0000-0000-0000-000000000001',
  username: 'newuser',
  temporaryPassword: 'TmpPass123!',
}

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 세팅 — createUser X-XSRF-TOKEN 검증
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-xsrf-token; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateUser
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateUser', () => {
  /**
   * T8-H-1. 성공 시 isSuccess가 true, data에 { id, username, temporaryPassword } 포함.
   */
  it('T8-H-1: 성공 시 isSuccess true, data.temporaryPassword 존재', async () => {
    server.use(
      http.post('/api/v1/users', () =>
        HttpResponse.json(CREATED_RESPONSE, { status: 201 }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCreateUser(), { wrapper })

    await act(async () => {
      result.current.mutate(VALID_PAYLOAD)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.isError).toBe(false)
    expect(result.current.data?.temporaryPassword).toBe(CREATED_RESPONSE.temporaryPassword)
    expect(result.current.data?.id).toBe(CREATED_RESPONSE.id)
  })

  /**
   * T8-H-2. 409 USERNAME_TAKEN → isError true, error가 ApiError(409), body.code === 'USERNAME_TAKEN'.
   */
  it('T8-H-2: 409 USERNAME_TAKEN → isError true, ApiError(409), code USERNAME_TAKEN', async () => {
    server.use(
      http.post('/api/v1/users', () =>
        HttpResponse.json({ code: 'USERNAME_TAKEN' }, { status: 409 }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCreateUser(), { wrapper })

    await act(async () => {
      result.current.mutate(VALID_PAYLOAD)
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.isSuccess).toBe(false)
    const err = result.current.error
    expect(err).toBeInstanceOf(ApiError)
    if (!(err instanceof ApiError)) throw new Error('type guard missed')
    expect(err.status).toBe(409)
    expect((err.body as { code?: string } | null)?.code).toBe('USERNAME_TAKEN')
  })

  /**
   * T8-H-3. 초기 상태에서 isPending false, data undefined.
   */
  it('T8-H-3: 초기 상태 isPending false, data undefined', () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCreateUser(), { wrapper })

    expect(result.current.isPending).toBe(false)
    expect(result.current.data).toBeUndefined()
    expect(result.current.isError).toBe(false)
  })

  /**
   * T8-H-4. reset 호출 후 isError false로 초기화.
   */
  it('T8-H-4: reset 후 isError false 초기화', async () => {
    server.use(
      http.post('/api/v1/users', () =>
        HttpResponse.json({ code: 'USERNAME_TAKEN' }, { status: 409 }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCreateUser(), { wrapper })

    await act(async () => {
      result.current.mutate(VALID_PAYLOAD)
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    act(() => {
      result.current.reset()
    })

    await waitFor(() => {
      expect(result.current.isError).toBe(false)
      expect(result.current.error).toBeNull()
    })
  })
})
