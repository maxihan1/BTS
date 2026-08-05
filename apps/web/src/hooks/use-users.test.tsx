// 사용자 조회 훅 단위 테스트 — 50 묶음 청크 (FR-UX-13 F5)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

vi.mock('@/api/users')

import { fetchUsersByIds } from '@/api/users'
import { chunkUserIds, useUsersByIdsChunked } from './use-users'

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return createElement(QueryClientProvider, { client }, children)
}

const ids = (n: number) => Array.from({ length: n }, (_, i) => `id-${i}`)

describe('chunkUserIds', () => {
  it('T2-1: 50개는 묶음 1개다 (백엔드 상한 경계)', () => {
    expect(chunkUserIds(ids(50))).toHaveLength(1)
  })

  it('T2-2: 51개는 50 + 1 두 묶음이다 (초과 시 400 회피)', () => {
    const chunks = chunkUserIds(ids(51))
    expect(chunks.map((c) => c.length)).toEqual([50, 1])
  })

  it('T2-3: 중복 id 는 한 번만 조회한다', () => {
    expect(chunkUserIds(['a', 'a', 'b'])).toEqual([['a', 'b']])
  })

  it('T2-4: 빈 배열은 묶음 0개다', () => {
    expect(chunkUserIds([])).toEqual([])
  })
})

describe('useUsersByIdsChunked', () => {
  beforeEach(() => {
    vi.mocked(fetchUsersByIds).mockReset()
  })

  it('T2-5: 60개 id 는 fetch 를 2회 호출하고 결과를 합친다', async () => {
    vi.mocked(fetchUsersByIds)
      .mockResolvedValueOnce([{ id: 'id-0', username: 'a', displayName: null, email: null }])
      .mockResolvedValueOnce([{ id: 'id-50', username: 'b', displayName: null, email: null }])

    const { result } = renderHook(() => useUsersByIdsChunked(ids(60)), { wrapper })

    await waitFor(() => {
      expect(result.current.data).toHaveLength(2)
    })
    expect(fetchUsersByIds).toHaveBeenCalledTimes(2)
    expect(vi.mocked(fetchUsersByIds).mock.calls[0]?.[0]).toHaveLength(50)
    expect(vi.mocked(fetchUsersByIds).mock.calls[1]?.[0]).toHaveLength(10)
  })

  it('T2-6: 담당자가 0명이면 서버를 부르지 않는다', async () => {
    const { result } = renderHook(() => useUsersByIdsChunked([]), { wrapper })
    await waitFor(() => {
      expect(result.current.data).toEqual([])
    })
    expect(fetchUsersByIds).not.toHaveBeenCalled()
  })

  it('T2-7: 한 묶음이 실패하면 isError 가 true 다 (소비처가 fail-soft 판정에 쓴다)', async () => {
    vi.mocked(fetchUsersByIds).mockRejectedValue(new Error('boom'))
    const { result } = renderHook(() => useUsersByIdsChunked(ids(3)), { wrapper })
    await waitFor(() => {
      expect(result.current.isError).toBe(true)
    })
  })
})
