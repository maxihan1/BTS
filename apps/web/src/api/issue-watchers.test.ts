// 이슈 워처 API 클라이언트 + TanStack Query 훅 단위 테스트 — FR-WT-01 Task-1
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  fetchWatchers,
  addWatcher,
  removeWatcher,
  useWatchers,
  useAddWatcher,
  useRemoveWatcher,
  issueWatchersKey,
  extractWatcherErrorCode,
  ISSUE_WATCHER_ERROR_CODES,
} from './issue-watchers'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ISSUE_KEY = 'ATLAS-1'
const USER_UUID = '550e8400-e29b-41d4-a716-446655440000'

const watcherFixture = {
  userId: USER_UUID,
  displayName: '홍길동',
}

const watcherListFixture = {
  watchers: [watcherFixture],
  count: 1,
  isWatching: true,
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
// T-WT-1. fetchWatchers — GET /watchers 200 DataResponse 언랩
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchWatchers — GET /watchers 200 DataResponse 언랩', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/:key/watchers', () =>
        HttpResponse.json({ data: watcherListFixture }),
      ),
    )
  })

  it('T-WT-1a: watchers 배열을 반환한다', async () => {
    const result = await fetchWatchers(ISSUE_KEY)
    expect(result.watchers).toHaveLength(1)
    expect(result.watchers[0]?.userId).toBe(USER_UUID)
    expect(result.watchers[0]?.displayName).toBe('홍길동')
  })

  it('T-WT-1b: count와 isWatching 필드가 파싱된다', async () => {
    const result = await fetchWatchers(ISSUE_KEY)
    expect(result.count).toBe(1)
    expect(result.isWatching).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WT-2. addWatcher — POST /watchers 201 본문 없음
// ─────────────────────────────────────────────────────────────────────────────

describe('addWatcher — POST /watchers 201 self 추가 (userId 없음)', () => {
  it('T-WT-2a: userId 없이 호출 시 body 없이 POST되고 undefined를 반환한다', async () => {
    let capturedBody: string | null = null
    server.use(
      http.post('/api/v1/issues/:key/watchers', async ({ request }) => {
        capturedBody = await request.text()
        return new HttpResponse(null, { status: 201 })
      }),
    )
    const result = await addWatcher(ISSUE_KEY)
    expect(result).toBeUndefined()
    // body가 없거나 빈 문자열이어야 한다 (self 추가)
    expect(capturedBody === '' || capturedBody === null).toBe(true)
  })
})

describe('addWatcher — POST /watchers 201 타인 추가 (userId 있음)', () => {
  it('T-WT-2b: userId 명시 시 body에 포함해서 POST되고 undefined를 반환한다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/issues/:key/watchers', async ({ request }) => {
        capturedBody = await request.json()
        return new HttpResponse(null, { status: 201 })
      }),
    )
    const result = await addWatcher(ISSUE_KEY, USER_UUID)
    expect(result).toBeUndefined()
    expect((capturedBody as Record<string, unknown>)['userId']).toBe(USER_UUID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WT-3. removeWatcher — DELETE /watchers/{userId} 204
// ─────────────────────────────────────────────────────────────────────────────

describe('removeWatcher — DELETE /watchers/{userId} 204', () => {
  it('T-WT-3a: 204 응답 시 undefined를 반환하고 경로에 userId가 포함된다', async () => {
    let capturedPath: string | null = null
    server.use(
      http.delete('/api/v1/issues/:key/watchers/:userId', ({ params }) => {
        capturedPath = params['userId'] as string
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const result = await removeWatcher(ISSUE_KEY, USER_UUID)
    expect(result).toBeUndefined()
    expect(capturedPath).toBe(USER_UUID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WT-4. 에러 매핑 — 4xx 시 ApiError throw
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchWatchers — 404 ISSUE_NOT_FOUND', () => {
  it('T-WT-4a: 404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/watchers', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_NOT_FOUND', message: '이슈를 찾을 수 없습니다' },
          { status: 404 },
        ),
      ),
    )
    await expect(fetchWatchers('NO-EXIST')).rejects.toBeInstanceOf(ApiError)
    await expect(fetchWatchers('NO-EXIST')).rejects.toMatchObject({ status: 404 })
  })
})

describe('addWatcher — 422 ISSUE_WATCHER_USER_NOT_FOUND', () => {
  it('T-WT-4b: 422 응답 시 ApiError(422)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/watchers', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_WATCHER_USER_NOT_FOUND', message: '사용자를 찾을 수 없습니다' },
          { status: 422 },
        ),
      ),
    )
    await expect(addWatcher(ISSUE_KEY, 'nonexistent-uuid')).rejects.toBeInstanceOf(ApiError)
    await expect(addWatcher(ISSUE_KEY, 'nonexistent-uuid')).rejects.toMatchObject({ status: 422 })
  })
})

describe('removeWatcher — 403 ISSUE_ACCESS_DENIED', () => {
  it('T-WT-4c: 403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/issues/:key/watchers/:userId', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_ACCESS_DENIED', message: '권한이 없습니다' },
          { status: 403 },
        ),
      ),
    )
    await expect(removeWatcher(ISSUE_KEY, USER_UUID)).rejects.toBeInstanceOf(ApiError)
    await expect(removeWatcher(ISSUE_KEY, USER_UUID)).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WT-5. issueWatchersKey — 형태 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('issueWatchersKey — 쿼리 키 형태', () => {
  it('T-WT-5a: issueWatchersKey(key)가 올바른 배열 형태다', () => {
    const key = issueWatchersKey(ISSUE_KEY)
    expect(key).toEqual(['issue-watchers', ISSUE_KEY, 'list'])
  })

  it('T-WT-5b: issueWatchersKey(key)가 issueQueryKey와 다르다', () => {
    const key = issueWatchersKey(ISSUE_KEY)
    expect(key).not.toEqual(['issue', ISSUE_KEY])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WT-6. useWatchers — 쿼리 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useWatchers — 쿼리 훅 성공', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/:key/watchers', () =>
        HttpResponse.json({ data: watcherListFixture }),
      ),
    )
  })

  it('T-WT-6a: 성공적으로 워처 목록을 반환한다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useWatchers(ISSUE_KEY), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.watchers).toHaveLength(1)
    expect(result.current.data?.isWatching).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WT-7. useAddWatcher — onSettled invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useAddWatcher — onSettled invalidateQueries', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:key/watchers', () =>
        new HttpResponse(null, { status: 201 }),
      ),
    )
  })

  it('T-WT-7a: 성공 후 issueWatchersKey로 invalidateQueries가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useAddWatcher(ISSUE_KEY), { wrapper: Wrapper })
    act(() => {
      result.current.mutate(undefined)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueWatchersKey(ISSUE_KEY) })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WT-8. useRemoveWatcher — onSettled invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useRemoveWatcher — onSettled invalidateQueries', () => {
  beforeEach(() => {
    server.use(
      http.delete('/api/v1/issues/:key/watchers/:userId', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
  })

  it('T-WT-8a: 성공 후 issueWatchersKey로 invalidateQueries가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useRemoveWatcher(ISSUE_KEY), { wrapper: Wrapper })
    act(() => {
      result.current.mutate(USER_UUID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueWatchersKey(ISSUE_KEY) })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WT-9. extractWatcherErrorCode — ApiError에서 errorCode 추출
// ─────────────────────────────────────────────────────────────────────────────

describe('extractWatcherErrorCode — 에러 코드 추출', () => {
  it('T-WT-9a: ApiError에서 errorCode를 추출한다', () => {
    const err = new ApiError(404, { errorCode: 'ISSUE_NOT_FOUND', message: '이슈 없음' })
    expect(extractWatcherErrorCode(err)).toBe('ISSUE_NOT_FOUND')
  })

  it('T-WT-9b: ApiError가 아니면 null을 반환한다', () => {
    expect(extractWatcherErrorCode(new Error('일반 에러'))).toBeNull()
    expect(extractWatcherErrorCode('문자열')).toBeNull()
  })

  it('T-WT-9c: body에 errorCode가 없으면 null을 반환한다', () => {
    const err = new ApiError(500, { message: 'errorCode 없음' })
    expect(extractWatcherErrorCode(err)).toBeNull()
  })

  it('T-WT-9d: ISSUE_WATCHER_ERROR_CODES 상수에 실제 백엔드 에러코드가 포함된다', () => {
    expect(ISSUE_WATCHER_ERROR_CODES.ISSUE_NOT_FOUND).toBe('ISSUE_NOT_FOUND')
    expect(ISSUE_WATCHER_ERROR_CODES.ISSUE_ACCESS_DENIED).toBe('ISSUE_ACCESS_DENIED')
    expect(ISSUE_WATCHER_ERROR_CODES.ISSUE_WATCHER_USER_NOT_FOUND).toBe('ISSUE_WATCHER_USER_NOT_FOUND')
    expect(ISSUE_WATCHER_ERROR_CODES.ISSUE_WATCHER_VALIDATION_FAILED).toBe('ISSUE_WATCHER_VALIDATION_FAILED')
  })
})
