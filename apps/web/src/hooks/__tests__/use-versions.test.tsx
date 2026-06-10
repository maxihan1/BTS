// 버전 BC TanStack Query 훅 테스트 — RED phase (FR-VR-01 Task 4)
import React from 'react'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import { versionHandlers, resetVersionStore } from '@/mocks/version-handlers'
import type { Version } from '@/api/versions.types'
import {
  useVersions,
  useCreateVersion,
  useUpdateVersion,
  useChangeVersionDates,
  useDeleteVersion,
  useChangeVersionStatus,
  VERSION_KEYS,
} from '../use-versions'

// sonner toast spy
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return { client, wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// VERSION_KEYS — queryKey 팩토리 정확성
// ─────────────────────────────────────────────────────────────────────────────

describe('VERSION_KEYS', () => {
  it('list 키는 projectKey를 포함한다', () => {
    const key = VERSION_KEYS.list('ATLAS')
    expect(key).toContain('ATLAS')
  })

  it('list 키는 동일 projectKey에 대해 동일한 배열을 반환한다', () => {
    expect(VERSION_KEYS.list('ATLAS')).toEqual(VERSION_KEYS.list('ATLAS'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useVersions — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useVersions', () => {
  beforeEach(() => {
    resetVersionStore()
    server.use(...versionHandlers)
  })

  it('버전 목록을 조회해 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/ATLAS/versions', () =>
        HttpResponse.json({
          data: [
            {
              id: '11111111-1111-4111-8111-111111111111',
              projectId: '22222222-2222-4222-8222-222222222222',
              name: 'v1.0.0',
              description: null,
              startDate: null,
              releaseDate: null,
            },
          ],
        }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useVersions('ATLAS'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toBeDefined()
    expect(result.current.data?.length).toBeGreaterThanOrEqual(1)
  })

  it('버전이 없는 프로젝트는 빈 배열을 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useVersions('EMPTY'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([])
  })

  it('초기 로딩 상태에서 isPending이 true다', () => {
    server.use(
      http.get('/api/v1/projects/ATLAS/versions', async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ data: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useVersions('ATLAS'), { wrapper })

    expect(result.current.isPending).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateVersion — 생성 + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateVersion', () => {
  beforeEach(() => {
    resetVersionStore()
    server.use(...versionHandlers)
  })

  it('생성 성공 시 목록 쿼리가 invalidate되어 refetch 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    // 초기 목록 캐시 채우기
    const listHook = renderHook(() => useVersions('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const initialCount =
      (client.getQueryData<Version[]>(VERSION_KEYS.list('ATLAS')) ?? []).length

    const { result } = renderHook(() => useCreateVersion('ATLAS'), { wrapper })

    await act(async () => {
      result.current.mutate({ name: 'v2.0.0' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // invalidate 후 refetch — 목록이 늘어나야 한다
    await waitFor(() => {
      const cached = client.getQueryData<Version[]>(VERSION_KEYS.list('ATLAS')) ?? []
      return cached.length > initialCount
    })
  })

  it('이름 중복(409) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    // 먼저 같은 이름으로 하나 만든다
    const { result: first } = renderHook(() => useCreateVersion('ATLAS'), { wrapper })
    await act(async () => {
      first.current.mutate({ name: '중복버전' })
    })
    await waitFor(() => expect(first.current.isSuccess).toBe(true))

    // 두 번째 같은 이름 시도 — 409
    const { result: second } = renderHook(() => useCreateVersion('ATLAS'), { wrapper })
    await act(async () => {
      second.current.mutate({ name: '중복버전' })
    })

    await waitFor(() => expect(second.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateVersion — 수정 + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateVersion', () => {
  beforeEach(() => {
    resetVersionStore()
    server.use(...versionHandlers)
  })

  it('수정 성공 시 목록 쿼리가 invalidate되어 갱신된 이름이 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    // 사전 버전 생성
    const { result: create } = renderHook(() => useCreateVersion('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: 'v1.0.0' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    // 목록 조회로 id 획득
    const listHook = renderHook(() => useVersions('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useUpdateVersion('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({ id: created!.id, input: { name: 'v1.0.1' } })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached = client.getQueryData<Version[]>(VERSION_KEYS.list('ATLAS')) ?? []
      return cached.some((v) => v.name === 'v1.0.1')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useChangeVersionDates — 날짜 변경 + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useChangeVersionDates', () => {
  beforeEach(() => {
    resetVersionStore()
    server.use(...versionHandlers)
    vi.mocked(toast.error).mockClear()
  })

  it('날짜 변경 성공 시 목록 쿼리가 invalidate된다', async () => {
    const { client, wrapper } = createWrapper()

    // 사전 버전 생성
    const { result: create } = renderHook(() => useCreateVersion('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: 'v1.0.0' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    const listHook = renderHook(() => useVersions('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useChangeVersionDates('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({
        id: created!.id,
        startDate: '2026-01-01',
        releaseDate: '2026-06-30',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // invalidate 후 refetch — queryKey가 캐시에 있어야 한다
    expect(
      client.getQueryState(VERSION_KEYS.list('ATLAS')),
    ).toBeDefined()
  })

  it('날짜를 null로 전달해 해제할 수 있다', async () => {
    const { client, wrapper } = createWrapper()

    // 사전 버전 생성 (startDate 포함)
    const { result: create } = renderHook(() => useCreateVersion('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: 'v1.0.0', startDate: '2026-01-01' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    const listHook = renderHook(() => useVersions('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useChangeVersionDates('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({ id: created!.id, startDate: null, releaseDate: null })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached = client.getQueryData<Version[]>(VERSION_KEYS.list('ATLAS')) ?? []
      return cached.some((v) => v.startDate === null)
    })
  })

  it('존재하지 않는 id 날짜 변경(404) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useChangeVersionDates('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({
        id: '00000000-dead-4000-8000-000000000000',
        startDate: '2026-01-01',
        releaseDate: null,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useChangeVersionStatus — 상태 전이 + invalidate (FR-VR-02 Task 5 RED)
// ─────────────────────────────────────────────────────────────────────────────

describe('useChangeVersionStatus', () => {
  beforeEach(() => {
    resetVersionStore()
    server.use(...versionHandlers)
    vi.mocked(toast.error).mockClear()
  })

  it('상태 전이 성공 시 목록 쿼리가 invalidate된다', async () => {
    const { client, wrapper } = createWrapper()

    // 사전 버전 생성
    const { result: create } = renderHook(() => useCreateVersion('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: 'v1.0.0' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    const listHook = renderHook(() => useVersions('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useChangeVersionStatus('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({ id: created!.id, status: 'RELEASED' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // invalidate 후 queryKey가 캐시에 있어야 한다
    expect(client.getQueryState(VERSION_KEYS.list('ATLAS'))).toBeDefined()
  })

  it('불허 전이(409) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    server.use(
      http.patch('/api/v1/projects/ATLAS/versions/:id/status', async () =>
        HttpResponse.json(
          { errorCode: 'VERSION_TRANSITION_NOT_ALLOWED' },
          { status: 409 },
        ),
      ),
    )

    const { result } = renderHook(() => useChangeVersionStatus('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({ id: '11111111-1111-4111-8111-111111111111', status: 'RELEASED' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteVersion — 삭제 + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteVersion', () => {
  beforeEach(() => {
    resetVersionStore()
    server.use(...versionHandlers)
  })

  it('삭제 성공 시 목록에서 해당 버전이 사라진다', async () => {
    const { client, wrapper } = createWrapper()

    // 사전 버전 생성
    const { result: create } = renderHook(() => useCreateVersion('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: '삭제할버전' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    const listHook = renderHook(() => useVersions('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useDeleteVersion('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate(created!.id)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached = client.getQueryData<Version[]>(VERSION_KEYS.list('ATLAS')) ?? []
      return !cached.some((v) => v.id === created!.id)
    })
  })

  it('존재하지 않는 id 삭제(404) 시 toast.error가 호출된다', async () => {
    vi.mocked(toast.error).mockClear()
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDeleteVersion('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate('00000000-dead-4000-8000-000000000000')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})
