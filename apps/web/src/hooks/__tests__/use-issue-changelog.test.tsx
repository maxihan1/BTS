// 이슈 변경 이력 조회 훅 단위 테스트 — RED phase (FR-HS-02 Task-F1)
import React from 'react'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ApiError } from '@/api/client'
import { useIssueChangelog, CHANGELOG_KEYS } from '../use-issue-changelog'

// ─────────────────────────────────────────────────────────────────────────────
// fetchIssueChangelog 모킹 — fetch 함수 직접 mock
// ─────────────────────────────────────────────────────────────────────────────
vi.mock('@/api/changelog', () => ({
  fetchIssueChangelog: vi.fn(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — Spring Page<ChangeGroupResponse>
// ─────────────────────────────────────────────────────────────────────────────
const pageFixture = {
  content: [
    {
      actorId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
      actorName: 'Alice',
      createdAt: '2026-06-10T10:00:00Z',
      items: [
        {
          field: 'summary',
          fromValue: '이전 제목',
          toValue: '새 제목',
          fromLabel: null,
          toLabel: null,
        },
      ],
    },
  ],
  totalElements: 1,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return { client, wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// CHANGELOG_KEYS — queryKey 팩토리 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('CHANGELOG_KEYS', () => {
  it('T-CLK-1: issueKey와 page를 포함하는 queryKey를 반환한다', () => {
    const key = CHANGELOG_KEYS.list('ATLAS-1', 0)
    expect(key).toContain('ATLAS-1')
    expect(key).toContain(0)
  })

  it('T-CLK-2: 동일 인자에 대해 동일한 배열을 반환한다', () => {
    expect(CHANGELOG_KEYS.list('ATLAS-1', 0)).toEqual(CHANGELOG_KEYS.list('ATLAS-1', 0))
  })

  it('T-CLK-3: page가 다르면 다른 queryKey를 반환한다', () => {
    expect(CHANGELOG_KEYS.list('ATLAS-1', 0)).not.toEqual(CHANGELOG_KEYS.list('ATLAS-1', 1))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useIssueChangelog — 성공
// ─────────────────────────────────────────────────────────────────────────────
describe('useIssueChangelog — 성공', () => {
  beforeEach(async () => {
    const { fetchIssueChangelog } = await import('@/api/changelog')
    vi.mocked(fetchIssueChangelog).mockClear()
  })

  it('T-CLH-1a: fetchIssueChangelog를 위임해 page 데이터를 반환한다', async () => {
    const { fetchIssueChangelog } = await import('@/api/changelog')
    vi.mocked(fetchIssueChangelog).mockResolvedValueOnce(pageFixture)

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueChangelog('ATLAS-1', 0), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.content).toHaveLength(1)
    expect(result.current.data?.content[0]?.actorName).toBe('Alice')
  })

  it('T-CLH-1b: fetchIssueChangelog에 issueKey와 page를 전달한다', async () => {
    const { fetchIssueChangelog } = await import('@/api/changelog')
    vi.mocked(fetchIssueChangelog).mockResolvedValueOnce(pageFixture)

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueChangelog('ATLAS-5', 2), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchIssueChangelog).toHaveBeenCalledWith('ATLAS-5', 2, expect.any(Number))
  })

  it('T-CLH-1c: 초기 로딩 중 isPending이 true다', async () => {
    const { fetchIssueChangelog } = await import('@/api/changelog')
    // never resolves
    vi.mocked(fetchIssueChangelog).mockImplementation(() => new Promise(() => undefined))

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueChangelog('ATLAS-1', 0), { wrapper })

    expect(result.current.isPending).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useIssueChangelog — 에러
// ─────────────────────────────────────────────────────────────────────────────
describe('useIssueChangelog — 에러', () => {
  it('T-CLH-2a: fetchIssueChangelog가 404를 throw하면 isError가 true다', async () => {
    const { fetchIssueChangelog } = await import('@/api/changelog')
    vi.mocked(fetchIssueChangelog).mockRejectedValueOnce(new ApiError(404, { message: 'Not Found' }))

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueChangelog('ATLAS-99', 0), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.error).toBeInstanceOf(ApiError)
    if (!(result.current.error instanceof ApiError)) throw new Error('type guard missed')
    expect(result.current.error.status).toBe(404)
  })
})
