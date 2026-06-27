// 즐겨찾기 API 클라이언트 + React Query 훅 단위 테스트 — FR-UX-02 Task-1
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  fetchFavorites,
  addFavorite,
  removeFavorite,
  useFavorites,
  useAddFavorite,
  useRemoveFavorite,
  favoritesKey,
  extractFavoriteErrorCode,
  FAVORITE_ERROR_CODES,
  FAVORITE_TARGET_TYPES,
} from './favorites'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const FAVORITE_ID = '550e8400-e29b-41d4-a716-446655440000'
const TARGET_ID = 'PROJ-1'

/** 즐겨찾기 단건 픽스처 */
const favoriteFixture = {
  id: FAVORITE_ID,
  targetType: 'ISSUE' as const,
  targetId: TARGET_ID,
  createdAt: '2024-01-15T10:00:00Z',
}

/** 즐겨찾기 목록 픽스처 */
const favoriteListFixture = {
  items: [favoriteFixture],
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
  return { queryClient, Wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-1. favoriteResponseSchema / favoriteListResponseSchema 언랩 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchFavorites — GET /favorites 200 DataResponse 언랩', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/favorites', () =>
        HttpResponse.json({ data: favoriteListFixture }),
      ),
    )
  })

  it('T-UX-1a: items 배열을 반환한다', async () => {
    const result = await fetchFavorites()
    expect(result).toHaveLength(1)
    expect(result[0]?.id).toBe(FAVORITE_ID)
    expect(result[0]?.targetType).toBe('ISSUE')
    expect(result[0]?.targetId).toBe(TARGET_ID)
    expect(result[0]?.createdAt).toBe('2024-01-15T10:00:00Z')
  })

  it('T-UX-1b: items가 빈 배열이면 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/favorites', () =>
        HttpResponse.json({ data: { items: [] } }),
      ),
    )
    const result = await fetchFavorites()
    expect(result).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-2. fetchFavorites — targetType 필터 쿼리파라미터
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchFavorites — targetType 필터 쿼리파라미터', () => {
  it('T-UX-2a: targetType 없이 호출 시 쿼리파라미터 없이 GET 한다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/favorites', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: { items: [] } })
      }),
    )
    await fetchFavorites()
    expect(capturedUrl).not.toBeNull()
    expect(capturedUrl).not.toContain('targetType')
  })

  it('T-UX-2b: targetType 있으면 쿼리파라미터로 전달된다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/favorites', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: { items: [] } })
      }),
    )
    await fetchFavorites('ISSUE')
    expect(capturedUrl).not.toBeNull()
    expect(capturedUrl).toContain('targetType=ISSUE')
  })

  it('T-UX-2c: targetType=DASHBOARD 도 전달된다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/favorites', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: { items: [] } })
      }),
    )
    await fetchFavorites('DASHBOARD')
    expect(capturedUrl).not.toBeNull()
    expect(capturedUrl).toContain('targetType=DASHBOARD')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-3. addFavorite — POST /favorites body(targetType+targetId) 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('addFavorite — POST /favorites 201/200 성공', () => {
  it('T-UX-3a: 201 응답 시 body에 targetType+targetId 포함, 단건 favorite 반환', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/favorites', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: favoriteFixture }, { status: 201 })
      }),
    )
    const result = await addFavorite('ISSUE', TARGET_ID)
    expect(result.id).toBe(FAVORITE_ID)
    expect(result.targetType).toBe('ISSUE')
    expect(result.targetId).toBe(TARGET_ID)
    const body = capturedBody as Record<string, unknown>
    expect(body['targetType']).toBe('ISSUE')
    expect(body['targetId']).toBe(TARGET_ID)
  })

  it('T-UX-3b: 200 응답(멱등) 도 정상적으로 반환한다', async () => {
    server.use(
      http.post('/api/v1/favorites', async () =>
        HttpResponse.json({ data: favoriteFixture }, { status: 200 }),
      ),
    )
    const result = await addFavorite('ISSUE', TARGET_ID)
    expect(result.id).toBe(FAVORITE_ID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-4. removeFavorite — DELETE /favorites?targetType=&targetId= 204
// ─────────────────────────────────────────────────────────────────────────────

describe('removeFavorite — DELETE /favorites 204', () => {
  it('T-UX-4a: 204 응답 시 undefined를 반환하고 쿼리파라미터에 targetType+targetId가 포함된다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.delete('/api/v1/favorites', ({ request }) => {
        capturedUrl = request.url
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const result = await removeFavorite('ISSUE', TARGET_ID)
    expect(result).toBeUndefined()
    expect(capturedUrl).not.toBeNull()
    expect(capturedUrl).toContain('targetType=ISSUE')
    expect(capturedUrl).toContain(`targetId=${TARGET_ID}`)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-5. 에러 매핑 — 400 시 ApiError throw + extractFavoriteErrorCode
// ─────────────────────────────────────────────────────────────────────────────

describe('addFavorite — 400 NOTIF_FAV_INVALID → ApiError throw', () => {
  it('T-UX-5a: 400 응답 시 ApiError(400)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/favorites', () =>
        HttpResponse.json(
          { errorCode: 'NOTIF_FAV_INVALID', message: '잘못된 즐겨찾기 요청' },
          { status: 400 },
        ),
      ),
    )
    await expect(addFavorite('ISSUE', 'INVALID')).rejects.toBeInstanceOf(ApiError)
    await expect(addFavorite('ISSUE', 'INVALID')).rejects.toMatchObject({ status: 400 })
  })
})

describe('fetchFavorites — 401 미인증 → ApiError throw', () => {
  it('T-UX-5b: 401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/favorites', () =>
        HttpResponse.json({ errorCode: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(fetchFavorites()).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-6. favoritesKey — 쿼리 키 형태 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('favoritesKey — 쿼리 키 형태', () => {
  it('T-UX-6a: targetType 없으면 ["favorites"] prefix 키다', () => {
    const key = favoritesKey()
    expect(key[0]).toBe('favorites')
  })

  it('T-UX-6b: targetType 있으면 ["favorites", "ISSUE"] 형태다', () => {
    const key = favoritesKey('ISSUE')
    expect(key).toEqual(['favorites', 'ISSUE'])
  })

  it('T-UX-6c: 타입별 키가 서로 다르다', () => {
    expect(favoritesKey('ISSUE')).not.toEqual(favoritesKey('DASHBOARD'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-7. useFavorites — 쿼리 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useFavorites — 쿼리 훅 성공', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/favorites', () =>
        HttpResponse.json({ data: favoriteListFixture }),
      ),
    )
  })

  it('T-UX-7a: 전체 즐겨찾기 목록을 반환한다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useFavorites(), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]?.targetType).toBe('ISSUE')
  })

  it('T-UX-7b: targetType 필터로 호출하면 해당 타입만 조회된다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useFavorites('ISSUE'), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-8. useAddFavorite — mutation + 접두사 invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useAddFavorite — onSettled 접두사 invalidate', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/favorites', async () =>
        HttpResponse.json({ data: favoriteFixture }, { status: 201 }),
      ),
    )
  })

  it('T-UX-8a: 성공 후 ["favorites"] 접두사로 invalidateQueries가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useAddFavorite(), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ targetType: 'ISSUE', targetId: TARGET_ID })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['favorites'] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-9. useRemoveFavorite — mutation + 접두사 invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useRemoveFavorite — onSettled 접두사 invalidate', () => {
  beforeEach(() => {
    server.use(
      http.delete('/api/v1/favorites', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
  })

  it('T-UX-9a: 성공 후 ["favorites"] 접두사로 invalidateQueries가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useRemoveFavorite(), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ targetType: 'ISSUE', targetId: TARGET_ID })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['favorites'] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-10. extractFavoriteErrorCode — ApiError에서 errorCode 추출
// ─────────────────────────────────────────────────────────────────────────────

describe('extractFavoriteErrorCode — 에러 코드 추출', () => {
  it('T-UX-10a: ApiError에서 NOTIF_FAV_INVALID를 추출한다', () => {
    const err = new ApiError(400, { errorCode: 'NOTIF_FAV_INVALID', message: '잘못된 요청' })
    expect(extractFavoriteErrorCode(err)).toBe('NOTIF_FAV_INVALID')
  })

  it('T-UX-10b: ApiError가 아니면 null을 반환한다', () => {
    expect(extractFavoriteErrorCode(new Error('일반 에러'))).toBeNull()
    expect(extractFavoriteErrorCode('문자열')).toBeNull()
  })

  it('T-UX-10c: body에 errorCode가 없으면 null을 반환한다', () => {
    const err = new ApiError(500, { message: 'errorCode 없음' })
    expect(extractFavoriteErrorCode(err)).toBeNull()
  })

  it('T-UX-10d: FAVORITE_ERROR_CODES 상수에 NOTIF_FAV_INVALID가 포함된다', () => {
    expect(FAVORITE_ERROR_CODES.NOTIF_FAV_INVALID).toBe('NOTIF_FAV_INVALID')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-UX-11. FAVORITE_TARGET_TYPES 상수 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('FAVORITE_TARGET_TYPES — 상수', () => {
  it('T-UX-11a: ISSUE, DASHBOARD, PROJECT를 포함한다', () => {
    expect(FAVORITE_TARGET_TYPES.ISSUE).toBe('ISSUE')
    expect(FAVORITE_TARGET_TYPES.DASHBOARD).toBe('DASHBOARD')
    expect(FAVORITE_TARGET_TYPES.PROJECT).toBe('PROJECT')
  })

  // Task-2: FILTER 타입 추가 (FR-SR-03 D6/D7)
  it('T-UX-11b: FILTER를 포함한다', () => {
    expect(FAVORITE_TARGET_TYPES.FILTER).toBe('FILTER')
  })
})
