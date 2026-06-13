// 이슈 링크 API 함수 및 TanStack Query 훅 단위 테스트 — FR-LK-01 Task-3
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  fetchIssueLinks,
  createLink,
  deleteLink,
  setParent,
  clearParent,
  useIssueLinks,
  useCreateLink,
  useDeleteLink,
  useSetParent,
  issueLinksKey,
} from './issue-links'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const linkFixture = {
  id: 1,
  linkType: 'BLOCKS',
  direction: 'OUTWARD' as const,
  label: 'blocks',
  otherIssue: {
    key: 'ATLAS-2',
    summary: '두 번째 이슈',
    statusKey: 'open',
  },
}

const linkListFixture = {
  outward: [linkFixture],
  inward: [],
}

const parentResponseFixture = {
  key: 'ATLAS-1',
  parent: {
    key: 'ATLAS-10',
    summary: '부모 이슈 요약',
  },
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
// T-LK-1. fetchIssueLinks — GET /links 200 언랩
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchIssueLinks — GET /links 200 언랩', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/:key/links', () =>
        HttpResponse.json({ data: linkListFixture }),
      ),
    )
  })

  it('T-LK-1a: outward 배열을 반환한다', async () => {
    const result = await fetchIssueLinks('ATLAS-1')
    expect(result.outward).toHaveLength(1)
    expect(result.outward[0]?.id).toBe(1)
  })

  it('T-LK-1b: linkType이 대문자(BLOCKS)로 파싱된다', async () => {
    const result = await fetchIssueLinks('ATLAS-1')
    expect(result.outward[0]?.linkType).toBe('BLOCKS')
  })

  it('T-LK-1c: otherIssue 필드가 정합하게 파싱된다', async () => {
    const result = await fetchIssueLinks('ATLAS-1')
    const link = result.outward[0]
    expect(link?.otherIssue.key).toBe('ATLAS-2')
    expect(link?.otherIssue.summary).toBe('두 번째 이슈')
    expect(link?.otherIssue.statusKey).toBe('open')
  })

  it('T-LK-1d: inward 배열이 빈 배열로 파싱된다', async () => {
    const result = await fetchIssueLinks('ATLAS-1')
    expect(result.inward).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-LK-2. createLink — POST /links 201 언랩 + 소문자 linkType 전송
// ─────────────────────────────────────────────────────────────────────────────

describe('createLink — POST /links 201 언랩', () => {
  it('T-LK-2a: 201 응답 시 IssueLinkResponse를 반환한다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/links', () =>
        HttpResponse.json({ data: linkFixture }, { status: 201 }),
      ),
    )
    const result = await createLink('ATLAS-1', { targetKey: 'ATLAS-2', linkType: 'blocks' })
    expect(result.id).toBe(1)
    expect(result.linkType).toBe('BLOCKS')
  })

  it('T-LK-2b: 요청 body에 linkType이 소문자로 전송된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/issues/:key/links', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: linkFixture }, { status: 201 })
      }),
    )
    await createLink('ATLAS-1', { targetKey: 'ATLAS-2', linkType: 'blocks' })
    expect((capturedBody as Record<string, unknown>)['linkType']).toBe('blocks')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-LK-3. deleteLink — DELETE /links/{linkId} 204
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteLink — DELETE /links/{linkId} 204', () => {
  it('T-LK-3a: 204 응답 시 undefined를 반환한다', async () => {
    server.use(
      http.delete('/api/v1/issues/:key/links/:linkId', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    const result = await deleteLink('ATLAS-1', 1)
    expect(result).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-LK-4. setParent / clearParent — PATCH /parent 200
// ─────────────────────────────────────────────────────────────────────────────

describe('setParent — PATCH /parent 200', () => {
  it('T-LK-4a: 성공 시 parent 객체가 포함된 응답을 반환한다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/parent', () =>
        HttpResponse.json({ data: parentResponseFixture }),
      ),
    )
    const result = await setParent('ATLAS-1', 'ATLAS-10')
    expect(result.key).toBe('ATLAS-1')
    expect(result.parent?.key).toBe('ATLAS-10')
    expect(result.parent?.summary).toBe('부모 이슈 요약')
  })
})

describe('clearParent — PATCH /parent 200 (parent 키 생략)', () => {
  it('T-LK-4b: 부모 해제 시 parent가 nullish로 파싱된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/parent', () =>
        // 백엔드 @JsonInclude(NON_NULL) — parent 키 자체 생략
        HttpResponse.json({ data: { key: 'ATLAS-1' } }),
      ),
    )
    const result = await clearParent('ATLAS-1')
    expect(result.key).toBe('ATLAS-1')
    // parent 키 생략 → undefined 또는 null
    expect(result.parent == null).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-LK-5. 에러 매핑 — 4xx 시 ApiError throw
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchIssueLinks — 404 ISSUE_NOT_FOUND', () => {
  it('T-LK-5a: 404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/links', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_NOT_FOUND', message: '이슈를 찾을 수 없습니다' },
          { status: 404 },
        ),
      ),
    )
    await expect(fetchIssueLinks('NO-EXIST')).rejects.toBeInstanceOf(ApiError)
    await expect(fetchIssueLinks('NO-EXIST')).rejects.toMatchObject({ status: 404 })
  })
})

describe('createLink — 422 LINK_SELF_REFERENCE', () => {
  it('T-LK-5b: 422 응답 시 ApiError(422)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/links', () =>
        HttpResponse.json(
          { errorCode: 'LINK_SELF_REFERENCE', message: '자기 자신에게 링크할 수 없습니다' },
          { status: 422 },
        ),
      ),
    )
    await expect(
      createLink('ATLAS-1', { targetKey: 'ATLAS-1', linkType: 'blocks' }),
    ).rejects.toBeInstanceOf(ApiError)
    await expect(
      createLink('ATLAS-1', { targetKey: 'ATLAS-1', linkType: 'blocks' }),
    ).rejects.toMatchObject({ status: 422 })
  })
})

describe('createLink — 409 DUPLICATE_LINK', () => {
  it('T-LK-5c: 409 응답 시 ApiError(409)를 throw하고 errorCode 추출 가능하다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/links', () =>
        HttpResponse.json(
          { errorCode: 'DUPLICATE_LINK', message: '중복 링크입니다' },
          { status: 409 },
        ),
      ),
    )
    let caught: unknown = null
    try {
      await createLink('ATLAS-1', { targetKey: 'ATLAS-2', linkType: 'blocks' })
    } catch (e) {
      caught = e
    }
    expect(caught).toBeInstanceOf(ApiError)
    const err = caught as ApiError
    expect(err.status).toBe(409)
    const errorCode = (err.body as Record<string, unknown>)['errorCode']
    expect(errorCode).toBe('DUPLICATE_LINK')
  })
})

describe('deleteLink — 404 LINK_NOT_FOUND', () => {
  it('T-LK-5d: 404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/issues/:key/links/:linkId', () =>
        HttpResponse.json(
          { errorCode: 'LINK_NOT_FOUND', message: '링크를 찾을 수 없습니다' },
          { status: 404 },
        ),
      ),
    )
    await expect(deleteLink('ATLAS-1', 999)).rejects.toBeInstanceOf(ApiError)
    await expect(deleteLink('ATLAS-1', 999)).rejects.toMatchObject({ status: 404 })
  })
})

describe('setParent — 400 VALIDATION_FAILED', () => {
  it('T-LK-5e: 400 응답 시 ApiError(400)를 throw한다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/parent', () =>
        HttpResponse.json(
          { errorCode: 'VALIDATION_FAILED', message: '유효하지 않은 요청입니다' },
          { status: 400 },
        ),
      ),
    )
    await expect(setParent('ATLAS-1', 'INVALID')).rejects.toBeInstanceOf(ApiError)
    await expect(setParent('ATLAS-1', 'INVALID')).rejects.toMatchObject({ status: 400 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-LK-6. useIssueLinks — queryKey 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('useIssueLinks — queryKey 분리', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/:key/links', () =>
        HttpResponse.json({ data: linkListFixture }),
      ),
    )
  })

  it('T-LK-6a: issueLinksKey(key)가 issueQueryKey와 다른 배열이다', () => {
    const linksKey = issueLinksKey('ATLAS-1')
    // issueQueryKey = ['issue', 'ATLAS-1'], linksKey는 달라야 한다
    expect(linksKey).not.toEqual(['issue', 'ATLAS-1'])
    expect(Array.isArray(linksKey)).toBe(true)
  })

  it('T-LK-6b: useIssueLinks가 성공적으로 링크 목록을 반환한다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueLinks('ATLAS-1'), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.outward).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-LK-7. useCreateLink — onSettled에서 issueLinksKey invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateLink — onSettled invalidate', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:key/links', () =>
        HttpResponse.json({ data: linkFixture }, { status: 201 }),
      ),
    )
  })

  it('T-LK-7a: 성공 후 issueLinksKey로 invalidateQueries가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useCreateLink('ATLAS-1'), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ targetKey: 'ATLAS-2', linkType: 'blocks' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueLinksKey('ATLAS-1') })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-LK-8. useDeleteLink — onSettled에서 issueLinksKey invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteLink — onSettled invalidate', () => {
  beforeEach(() => {
    server.use(
      http.delete('/api/v1/issues/:key/links/:linkId', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
  })

  it('T-LK-8a: 성공 후 issueLinksKey로 invalidateQueries가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useDeleteLink('ATLAS-1'), { wrapper: Wrapper })
    act(() => {
      result.current.mutate(1)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueLinksKey('ATLAS-1') })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-LK-9. useSetParent — issueLinksKey + issueQueryKey 양쪽 invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useSetParent — 양쪽 invalidate', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/parent', () =>
        HttpResponse.json({ data: parentResponseFixture }),
      ),
    )
  })

  it('T-LK-9a: 성공 후 issueLinksKey로 invalidateQueries가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useSetParent('ATLAS-1'), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ parentKey: 'ATLAS-10' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueLinksKey('ATLAS-1') })
  })

  it('T-LK-9b: 성공 후 issueQueryKey([issue, key])로도 invalidateQueries가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useSetParent('ATLAS-1'), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ parentKey: 'ATLAS-10' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })
})
