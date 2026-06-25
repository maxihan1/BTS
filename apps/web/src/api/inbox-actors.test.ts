// useActorNames 훅 단위 테스트 — actorUserId → 표시 이름 Map 변환 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { InboxItem } from '@/api/inbox'
import type { UserSummary } from '@/api/users'

// fetchUsersByIds 모킹 — users.ts 의존 격리
vi.mock('@/api/users', () => ({
  fetchUsersByIds: vi.fn(),
}))

import { fetchUsersByIds } from '@/api/users'
import { useActorNames } from '@/api/inbox-actors'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트용 InboxItem 기본값 — 필수 필드만 채운 최소 픽스처 */
function makeItem(overrides: Partial<InboxItem>): InboxItem {
  return {
    id: '00000000-0000-4000-a000-000000000001',
    eventType: 'ISSUE_MENTIONED',
    issueKey: null,
    title: '테스트 알림',
    body: null,
    actorUserId: null,
    readAt: null,
    archivedAt: null,
    createdAt: '2026-06-25T00:00:00Z',
    ...overrides,
  }
}

/** 테스트용 UserSummary 픽스처 */
function makeUser(overrides: Partial<UserSummary>): UserSummary {
  return {
    id: '00000000-0000-4000-a000-000000000010',
    username: 'alice',
    displayName: 'Alice',
    email: 'alice@example.com',
    ...overrides,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — QueryClient 래퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

const mockFetchUsersByIds = vi.mocked(fetchUsersByIds)

beforeEach(() => {
  vi.clearAllMocks()
})

describe('useActorNames', () => {
  it('고유 non-null actorUserId로 fetchUsersByIds를 1회 호출한다', async () => {
    const actorId = '00000000-0000-4000-a000-000000000010'
    const items = [
      makeItem({ actorUserId: actorId }),
      makeItem({ actorUserId: actorId }), // 중복 — 1회만 조회해야 함
    ]
    const users: UserSummary[] = [makeUser({ id: actorId })]
    mockFetchUsersByIds.mockResolvedValueOnce(users)

    const { result } = renderHook(() => useActorNames(items), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(mockFetchUsersByIds).toHaveBeenCalledTimes(1)
    expect(mockFetchUsersByIds).toHaveBeenCalledWith([actorId])
  })

  it('displayName이 있으면 displayName을 Map에 반환한다', async () => {
    const actorId = '00000000-0000-4000-a000-000000000010'
    const items = [makeItem({ actorUserId: actorId })]
    mockFetchUsersByIds.mockResolvedValueOnce([
      makeUser({ id: actorId, displayName: 'Alice Kim', username: 'alice' }),
    ])

    const { result } = renderHook(() => useActorNames(items), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.get(actorId)).toBe('Alice Kim')
  })

  it('displayName이 null이면 username을 폴백으로 사용한다', async () => {
    const actorId = '00000000-0000-4000-a000-000000000010'
    const items = [makeItem({ actorUserId: actorId })]
    mockFetchUsersByIds.mockResolvedValueOnce([
      makeUser({ id: actorId, displayName: null, username: 'alice' }),
    ])

    const { result } = renderHook(() => useActorNames(items), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.get(actorId)).toBe('alice')
  })

  it('actorUserId가 전부 null이면 fetchUsersByIds를 호출하지 않는다', async () => {
    const items = [
      makeItem({ actorUserId: null }),
      makeItem({ actorUserId: null }),
    ]

    const { result } = renderHook(() => useActorNames(items), {
      wrapper: makeWrapper(),
    })

    // enabled:false인 경우 쿼리가 실행되지 않으므로 isSuccess가 아닌 isPending=false+data=undefined 상태 확인
    // fetchUsersByIds 호출이 없어야 한다는 것이 핵심
    await waitFor(() => expect(result.current.fetchStatus).toBe('idle'))

    expect(mockFetchUsersByIds).not.toHaveBeenCalled()
    expect(result.current.data).toBeUndefined()
  })

  it('items가 빈 배열이면 fetchUsersByIds를 호출하지 않는다', async () => {
    const { result } = renderHook(() => useActorNames([]), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.fetchStatus).toBe('idle'))

    expect(mockFetchUsersByIds).not.toHaveBeenCalled()
  })

  it('미존재 id는 Map에 포함되지 않는다', async () => {
    const existingId = '00000000-0000-4000-a000-000000000010'
    const missingId = '00000000-0000-4000-a000-000000000099'
    const items = [
      makeItem({ actorUserId: existingId }),
      makeItem({ actorUserId: missingId }),
    ]
    // 백엔드가 existingId만 응답 (missingId는 미존재이므로 제외)
    mockFetchUsersByIds.mockResolvedValueOnce([
      makeUser({ id: existingId }),
    ])

    const { result } = renderHook(() => useActorNames(items), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.has(existingId)).toBe(true)
    expect(result.current.data?.has(missingId)).toBe(false)
  })

  it('다수의 고유 actorUserId는 정렬된 순서로 queryKey를 구성한다', async () => {
    // queryKey 안정성 검증: 같은 id 집합이면 순서와 무관하게 동일 캐시 히트
    const idA = '00000000-0000-4000-a000-000000000001'
    const idB = '00000000-0000-4000-a000-000000000002'
    const items = [
      makeItem({ actorUserId: idB }), // B가 먼저 등장
      makeItem({ actorUserId: idA }),
    ]
    mockFetchUsersByIds.mockResolvedValueOnce([
      makeUser({ id: idA, displayName: 'A', username: 'userA' }),
      makeUser({ id: idB, displayName: 'B', username: 'userB' }),
    ])

    const { result } = renderHook(() => useActorNames(items), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // fetchUsersByIds는 정렬된 id 배열로 1회 호출
    expect(mockFetchUsersByIds).toHaveBeenCalledTimes(1)
    const calledWith = mockFetchUsersByIds.mock.calls[0]?.[0]
    expect(calledWith).toEqual([idA, idB]) // 정렬됨

    expect(result.current.data?.get(idA)).toBe('A')
    expect(result.current.data?.get(idB)).toBe('B')
  })
})
