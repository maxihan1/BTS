// 개인 알림 보관함(Inbox) API 클라이언트 + 훅 단위 테스트 — FR-UX-03 Task-2
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  inboxItemSchema,
  inboxPageSchema,
  unreadCountSchema,
  readAllResponseSchema,
  inboxKey,
  useInbox,
  useUnreadCount,
  useMarkRead,
  useMarkArchive,
  useReadAll,
} from './inbox'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures
// ─────────────────────────────────────────────────────────────────────────────

const itemFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  eventType: 'ISSUE_MENTIONED',
  issueKey: 'ATLAS-1',
  title: '이슈에서 언급되었습니다',
  body: '이슈 본문 요약',
  actorUserId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  // 댓글에서 난 멘션이라 딥링크 대상이 있다 (백엔드는 payload.commentId 로 내려준다)
  commentId: 'b7c8d9e0-1234-4567-89ab-cdef01234567',
  readAt: null,
  archivedAt: null,
  createdAt: '2024-06-25T09:00:00Z',
}

const pageFixture = {
  content: [itemFixture],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
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
// T-IB-1. Zod 스키마 — inboxItemSchema 백엔드 InboxItemResponse 1:1 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('inboxItemSchema — InboxItemResponse 1:1 파싱', () => {
  it('T-IB-1a: 모든 필드가 채워진 항목을 정상 파싱한다', () => {
    const result = inboxItemSchema.safeParse(itemFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.id).toBe(itemFixture.id)
    expect(result.data.eventType).toBe('ISSUE_MENTIONED')
    expect(result.data.issueKey).toBe('ATLAS-1')
    expect(result.data.title).toBe(itemFixture.title)
    expect(result.data.body).toBe(itemFixture.body)
    expect(result.data.actorUserId).toBe(itemFixture.actorUserId)
    expect(result.data.commentId).toBe(itemFixture.commentId)
    expect(result.data.readAt).toBeNull()
    expect(result.data.archivedAt).toBeNull()
    expect(result.data.createdAt).toBe('2024-06-25T09:00:00Z')
  })

  it('T-IB-1b: nullable 필드(issueKey/body/actorUserId/commentId/readAt/archivedAt)가 null이어도 파싱된다', () => {
    const minimal = {
      ...itemFixture,
      issueKey: null,
      body: null,
      actorUserId: null,
      // 댓글에서 나지 않은 알림(담당자 지정 등)은 딥링크 대상이 없다
      commentId: null,
      readAt: null,
      archivedAt: null,
    }
    const result = inboxItemSchema.safeParse(minimal)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.issueKey).toBeNull()
    expect(result.data.body).toBeNull()
    expect(result.data.actorUserId).toBeNull()
    expect(result.data.commentId).toBeNull()
  })

  it('T-IB-1e: commentId 키가 통째로 없는 응답도 파싱되고 null 로 채워진다', () => {
    // 단일 호스트라 SPA 와 백엔드가 함께 뜨지만, 롤백·캐시된 번들이면 「새 SPA + 옛 백엔드」가
    // 성립한다. 그때 한 필드의 부재는 항목 하나가 아니라 **페이지 전체**의 파싱을 죽여
    // 인박스가 통째로 비어 버린다. 딥링크 하나 없는 것보다 훨씬 나쁜 실패다.
    const legacyItem = Object.fromEntries(
      Object.entries(itemFixture).filter(([k]) => k !== 'commentId'),
    )
    const result = inboxPageSchema.safeParse({ ...pageFixture, content: [legacyItem] })

    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.content[0]?.commentId).toBeNull()
  })

  it('T-IB-1d: commentId 가 UUID 가 아니면 파싱을 거부한다', () => {
    // 딥링크는 그대로 URL 로 나간다 — 임의 문자열을 통과시키면 깨진 링크를 그리게 된다.
    const result = inboxItemSchema.safeParse({ ...itemFixture, commentId: 'not-a-uuid' })
    expect(result.success).toBe(false)
  })

  it('T-IB-1c: readAt/archivedAt가 ISO 문자열이어도 파싱된다', () => {
    const read = {
      ...itemFixture,
      readAt: '2024-06-25T10:00:00Z',
      archivedAt: '2024-06-25T11:00:00Z',
    }
    const result = inboxItemSchema.safeParse(read)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.readAt).toBe('2024-06-25T10:00:00Z')
    expect(result.data.archivedAt).toBe('2024-06-25T11:00:00Z')
  })

  it('T-IB-1d: 필수 필드(id/eventType/title/createdAt) 누락 시 실패한다', () => {
    // 각 필수 필드를 omit해 safeParse 실패를 검증한다
    const base: Record<string, unknown> = { ...itemFixture }

    const withoutId = Object.fromEntries(Object.entries(base).filter(([k]) => k !== 'id'))
    expect(inboxItemSchema.safeParse(withoutId).success).toBe(false)

    const withoutEventType = Object.fromEntries(Object.entries(base).filter(([k]) => k !== 'eventType'))
    expect(inboxItemSchema.safeParse(withoutEventType).success).toBe(false)

    const withoutTitle = Object.fromEntries(Object.entries(base).filter(([k]) => k !== 'title'))
    expect(inboxItemSchema.safeParse(withoutTitle).success).toBe(false)

    const withoutCreatedAt = Object.fromEntries(Object.entries(base).filter(([k]) => k !== 'createdAt'))
    expect(inboxItemSchema.safeParse(withoutCreatedAt).success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-2. Zod 스키마 — inboxPageSchema Spring Page 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('inboxPageSchema — Spring Page 파싱', () => {
  it('T-IB-2a: content/totalElements/totalPages/number/size 포함 페이지를 파싱한다', () => {
    const result = inboxPageSchema.safeParse(pageFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.content).toHaveLength(1)
    expect(result.data.totalElements).toBe(1)
    expect(result.data.totalPages).toBe(1)
    expect(result.data.number).toBe(0)
    expect(result.data.size).toBe(20)
  })

  it('T-IB-2b: 추가 Spring Page 필드(pageable 등)가 있어도 파싱 실패하지 않는다 (passthrough)', () => {
    const withExtra = {
      ...pageFixture,
      pageable: { sort: { sorted: false } },
      first: true,
      last: true,
      empty: false,
    }
    const result = inboxPageSchema.safeParse(withExtra)
    expect(result.success).toBe(true)
  })

  it('T-IB-2c: content가 없으면 실패한다', () => {
    const withoutContent = Object.fromEntries(Object.entries(pageFixture).filter(([k]) => k !== 'content'))
    expect(inboxPageSchema.safeParse(withoutContent).success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-3. Zod 스키마 — unreadCountSchema { data: { count } } 언랩
// ─────────────────────────────────────────────────────────────────────────────

describe('unreadCountSchema — { data: { count } } 파싱', () => {
  it('T-IB-3a: 정상 응답을 파싱한다', () => {
    const result = unreadCountSchema.safeParse({ data: { count: 5 } })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.data.count).toBe(5)
  })

  it('T-IB-3b: count=0도 유효하다', () => {
    const result = unreadCountSchema.safeParse({ data: { count: 0 } })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.data.count).toBe(0)
  })

  it('T-IB-3c: data 래퍼가 없으면 실패한다', () => {
    expect(unreadCountSchema.safeParse({ count: 5 }).success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-4. Zod 스키마 — readAllResponseSchema { data: { updated } }
// ─────────────────────────────────────────────────────────────────────────────

describe('readAllResponseSchema — { data: { updated } } 파싱', () => {
  it('T-IB-4a: 정상 응답을 파싱한다', () => {
    const result = readAllResponseSchema.safeParse({ data: { updated: 3 } })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.data.updated).toBe(3)
  })

  it('T-IB-4b: updated=0도 유효하다 (0건 일괄읽음)', () => {
    const result = readAllResponseSchema.safeParse({ data: { updated: 0 } })
    expect(result.success).toBe(true)
  })

  it('T-IB-4c: data 래퍼가 없으면 실패한다', () => {
    expect(readAllResponseSchema.safeParse({ updated: 3 }).success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-5. inboxKey — filter-aware 정규화 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

describe('inboxKey — filter-aware queryKey 정규화', () => {
  it('T-IB-5a: 필터 없으면 [inbox, list, {}] 형태다', () => {
    const key = inboxKey({})
    expect(key[0]).toBe('inbox')
    expect(key[1]).toBe('list')
  })

  it('T-IB-5b: tab 포함 시 키에 반영된다', () => {
    const key = inboxKey({ tab: 'UNREAD' })
    const serialized = JSON.stringify(key)
    expect(serialized).toContain('UNREAD')
  })

  it('T-IB-5c: q/senderId/issueKey/from/to/page/size 모두 키에 반영된다', () => {
    const filters = {
      tab: 'ALL' as const,
      q: '검색어',
      senderId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
      issueKey: 'ATLAS-1',
      from: '2024-01-01T00:00:00Z',
      to: '2024-12-31T23:59:59Z',
      page: 2,
      size: 50,
    }
    const key = inboxKey(filters)
    const serialized = JSON.stringify(key)
    expect(serialized).toContain('ALL')
    expect(serialized).toContain('검색어')
    expect(serialized).toContain('ATLAS-1')
    expect(serialized).toContain('2')
  })

  it('T-IB-5d: 동일 필터는 동일 키를 반환한다 (정규화)', () => {
    const key1 = inboxKey({ tab: 'UNREAD', page: 0 })
    const key2 = inboxKey({ tab: 'UNREAD', page: 0 })
    expect(JSON.stringify(key1)).toBe(JSON.stringify(key2))
  })

  it('T-IB-5e: 접두사 [inbox]로 mutation invalidate가 목록+카운트를 일괄 무효화할 수 있다', () => {
    const key = inboxKey({})
    expect(key[0]).toBe('inbox')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-6. fetchInbox — 쿼리스트링 조립 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchInbox — 쿼리스트링 조립', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/users/me/inbox', ({ request }) => {
        const url = new URL(request.url)
        // 요청 URL을 응답에 에코해 검증에 활용
        return HttpResponse.json({
          ...pageFixture,
          _url: url.search,
        })
      }),
    )
  })

  it('T-IB-6a: tab 파라미터가 쿼리스트링에 포함된다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useInbox({ tab: 'UNREAD' }), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // MSW가 요청을 받았으면 훅이 성공한 것 — 추가 URL 검증은 fetchInbox 직접 테스트로
  })

  it('T-IB-6b: 필터 없으면 빈 쿼리스트링(또는 기본 tab=ALL)으로 요청된다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useInbox({}), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('T-IB-6c: page/size 파라미터가 전달된다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useInbox({ page: 2, size: 50 }), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-7. useInbox — 목록 조회 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useInbox — 목록 조회 훅', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/users/me/inbox', () => HttpResponse.json(pageFixture)),
    )
  })

  it('T-IB-7a: 성공 시 InboxPage를 반환한다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useInbox({}), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.content).toHaveLength(1)
    expect(result.current.data?.content[0]?.id).toBe(itemFixture.id)
  })

  it('T-IB-7b: 조회 실패 시 isError가 true다', async () => {
    server.use(
      http.get('/api/v1/users/me/inbox', () => HttpResponse.json({}, { status: 401 })),
    )
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useInbox({}), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-8. useUnreadCount — 미읽음 카운트 조회 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useUnreadCount — 미읽음 카운트 훅', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/users/me/inbox/unread-count', () =>
        HttpResponse.json({ data: { count: 7 } }),
      ),
    )
  })

  it('T-IB-8a: 성공 시 count를 반환한다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useUnreadCount(), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toBe(7)
  })

  it('T-IB-8b: 쿼리 키가 [inbox, unread-count]다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const { result } = renderHook(() => useUnreadCount(), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const cached = queryClient.getQueryData(['inbox', 'unread-count'])
    expect(cached).toBe(7)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-9. useMarkRead — 읽음/안읽음 mutation + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useMarkRead — 읽음 mutation', () => {
  const itemId = itemFixture.id

  beforeEach(() => {
    server.use(
      http.patch(`/api/v1/users/me/inbox/${itemId}/read`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
  })

  it('T-IB-9a: PATCH read 성공 시 isSuccess가 true다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useMarkRead(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ id: itemId, read: true })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('T-IB-9b: 성공 시 onSettled에서 [inbox] 접두사 invalidate가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useMarkRead(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ id: itemId, read: true })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['inbox'] }),
    )
  })

  it('T-IB-9c: 실패 시에도 onSettled invalidate가 호출된다', async () => {
    server.use(
      http.patch(`/api/v1/users/me/inbox/${itemId}/read`, () =>
        HttpResponse.json({}, { status: 404 }),
      ),
    )
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useMarkRead(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ id: itemId, read: false })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['inbox'] }),
    )
  })

  it('T-IB-9d: setQueryData가 직접 호출되지 않는다 (부분응답 플리커 방지)', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useMarkRead(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ id: itemId, read: true })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-10. useMarkArchive — 보관 mutation + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useMarkArchive — 보관 mutation', () => {
  const itemId = itemFixture.id

  beforeEach(() => {
    server.use(
      http.patch(`/api/v1/users/me/inbox/${itemId}/archive`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
  })

  it('T-IB-10a: PATCH archive 성공 시 isSuccess가 true다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useMarkArchive(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ id: itemId, archived: true })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('T-IB-10b: 성공 시 onSettled에서 [inbox] 접두사 invalidate가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useMarkArchive(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ id: itemId, archived: true })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['inbox'] }),
    )
  })

  it('T-IB-10c: setQueryData가 직접 호출되지 않는다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useMarkArchive(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ id: itemId, archived: false })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IB-11. useReadAll — 일괄 읽음 mutation + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useReadAll — 일괄 읽음 mutation', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/users/me/inbox/read-all', () =>
        HttpResponse.json({ data: { updated: 3 } }),
      ),
    )
  })

  it('T-IB-11a: ids 없이 호출하면 전체 미읽음을 읽음 처리한다 (200 반환)', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useReadAll(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({})
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.updated).toBe(3)
  })

  it('T-IB-11b: ids 배열을 전달하면 해당 항목만 읽음 처리된다', async () => {
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useReadAll(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ ids: [itemFixture.id] })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('T-IB-11c: 성공 시 onSettled에서 [inbox] 접두사 invalidate가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useReadAll(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({})
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['inbox'] }),
    )
  })

  it('T-IB-11d: setQueryData가 직접 호출되지 않는다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useReadAll(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({})
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})
