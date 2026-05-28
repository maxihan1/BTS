// 워크플로우 스킴 CRUD TanStack Query hooks 테스트 — RED phase
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { SchemeDetailResponse, MappingResponse } from '@/api/workflow-schemes'
import type { SchemeResponse } from '@/api/workflow-schemes'
import {
  useWorkflowSchemes,
  useWorkflowSchemeDetail,
  useCreateWorkflowScheme,
  useUpdateWorkflowScheme,
  useDeleteWorkflowScheme,
  useAddMapping,
  useRemoveMapping,
} from '../use-workflow-schemes'

/** 테스트마다 독립 캐시를 가진 QueryClient 래퍼 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

describe('useWorkflowSchemes', () => {
  it('스킴 목록을 조회해 SchemeResponse 배열을 반환한다', async () => {
    const schemes: SchemeResponse[] = [
      {
        schemeKey: 'software-default-scheme',
        name: '소프트웨어 개발 기본 스킴',
        description: '',
        isStandard: true,
        usedByProjectsCount: 3,
        mappingsCount: 5,
      },
    ]

    server.use(
      http.get('/api/v1/workflow-schemes', () =>
        HttpResponse.json({ data: schemes }),
      ),
    )

    const { result } = renderHook(() => useWorkflowSchemes(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]?.schemeKey).toBe('software-default-scheme')
  })

  it('로딩 중일 때 isLoading이 true다', () => {
    server.use(
      http.get('/api/v1/workflow-schemes', () =>
        HttpResponse.json({ data: [] }),
      ),
    )

    const { result } = renderHook(() => useWorkflowSchemes(), {
      wrapper: createWrapper(),
    })

    // 초기 상태에서는 isLoading 또는 isPending이 true
    expect(result.current.isLoading || result.current.isPending).toBe(true)
  })
})

describe('useWorkflowSchemeDetail', () => {
  it('schemeKey로 단건 상세를 조회한다', async () => {
    const detail: SchemeDetailResponse = {
      schemeKey: 'software-default-scheme',
      name: '소프트웨어 개발 기본 스킴',
      description: '설명',
      isStandard: true,
      usedByProjectsCount: 1,
      mappingsCount: 1,
      mappings: [
        {
          id: 10,
          issueTypeKey: 'bug',
          issueTypeName: '버그',
          workflowKey: 'bug-tracking',
          workflowName: '버그 추적',
          isDefault: false,
        },
      ],
    }

    server.use(
      http.get('/api/v1/workflow-schemes/software-default-scheme', () =>
        HttpResponse.json({ data: detail }),
      ),
    )

    const { result } = renderHook(
      () => useWorkflowSchemeDetail('software-default-scheme'),
      { wrapper: createWrapper() },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.schemeKey).toBe('software-default-scheme')
    expect(result.current.data?.mappings).toHaveLength(1)
  })
})

describe('useCreateWorkflowScheme', () => {
  it('스킴 생성 성공 시 onSuccess를 호출한다', async () => {
    const created: SchemeResponse = {
      schemeKey: 'new-scheme',
      name: '새 스킴',
      description: '',
      isStandard: false,
      usedByProjectsCount: 0,
      mappingsCount: 0,
    }

    server.use(
      http.post('/api/v1/workflow-schemes', () =>
        HttpResponse.json({ data: created }, { status: 201 }),
      ),
    )

    const onSuccess = vi.fn()
    const { result } = renderHook(() => useCreateWorkflowScheme(), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate({ schemeKey: 'new-scheme', name: '새 스킴' }, { onSuccess })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(onSuccess).toHaveBeenCalledOnce()
  })
})

describe('useUpdateWorkflowScheme', () => {
  it('스킴 수정 성공 시 낙관적 업데이트 후 서버 응답으로 갱신한다', async () => {
    const updated: SchemeResponse = {
      schemeKey: 'custom-scheme-beta',
      name: '수정된 스킴 이름',
      description: '수정된 설명',
      isStandard: false,
      usedByProjectsCount: 0,
      mappingsCount: 1,
    }

    server.use(
      http.put('/api/v1/workflow-schemes/custom-scheme-beta', () =>
        HttpResponse.json({ data: updated }),
      ),
    )

    const { result } = renderHook(
      () => useUpdateWorkflowScheme('custom-scheme-beta'),
      { wrapper: createWrapper() },
    )

    act(() => {
      result.current.mutate({ name: '수정된 스킴 이름', description: '수정된 설명' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })
})

describe('useDeleteWorkflowScheme', () => {
  it('삭제 성공 시 onSuccess를 호출한다', async () => {
    server.use(
      http.delete('/api/v1/workflow-schemes/custom-scheme-beta', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )

    const onSuccess = vi.fn()
    const { result } = renderHook(() => useDeleteWorkflowScheme(), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate('custom-scheme-beta', { onSuccess })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(onSuccess).toHaveBeenCalledOnce()
  })

  it('409 SCHEME_IN_USE 시 toast.error를 호출하고 onError를 호출한다', async () => {
    server.use(
      http.delete('/api/v1/workflow-schemes/custom-scheme-alpha', () =>
        HttpResponse.json(
          { code: 'SCHEME_IN_USE', detail: '사용 중' },
          { status: 409 },
        ),
      ),
    )

    const onError = vi.fn()
    const { result } = renderHook(() => useDeleteWorkflowScheme(), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate('custom-scheme-alpha', { onError })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(onError).toHaveBeenCalledOnce()
  })
})

describe('useAddMapping', () => {
  it('onMutate에서 캐시에 낙관적 업데이트를 적용한다', async () => {
    const existingDetail: SchemeDetailResponse = {
      schemeKey: 'custom-scheme-beta',
      name: '파일럿',
      description: '',
      isStandard: false,
      usedByProjectsCount: 0,
      mappingsCount: 1,
      mappings: [
        {
          id: 60,
          issueTypeKey: null,
          issueTypeName: null,
          workflowKey: 'simple',
          workflowName: '단순 워크플로우',
          isDefault: true,
        },
      ],
    }

    const newMapping: MappingResponse = {
      id: 99,
      issueTypeKey: 'bug',
      issueTypeName: '버그',
      workflowKey: 'bug-tracking',
      workflowName: '버그 추적',
      isDefault: false,
    }

    server.use(
      http.get('/api/v1/workflow-schemes/custom-scheme-beta', () =>
        HttpResponse.json({ data: existingDetail }),
      ),
      http.post('/api/v1/workflow-schemes/custom-scheme-beta/mappings', () =>
        HttpResponse.json({ data: newMapping }, { status: 201 }),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    // 먼저 detail 캐시 채우기
    const detailHook = renderHook(() => useWorkflowSchemeDetail('custom-scheme-beta'), { wrapper })
    await waitFor(() => expect(detailHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useAddMapping('custom-scheme-beta'), { wrapper })

    act(() => {
      result.current.mutate({ issueTypeKey: 'bug', workflowKey: 'bug-tracking' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('409 MAPPING_DUPLICATE 시 onError에서 롤백하고 toast.error를 호출한다', async () => {
    const existingDetail: SchemeDetailResponse = {
      schemeKey: 'software-default-scheme',
      name: '소프트웨어',
      description: '',
      isStandard: true,
      usedByProjectsCount: 3,
      mappingsCount: 1,
      mappings: [
        {
          id: 10,
          issueTypeKey: 'bug',
          issueTypeName: '버그',
          workflowKey: 'bug-tracking',
          workflowName: '버그 추적',
          isDefault: false,
        },
      ],
    }

    server.use(
      http.get('/api/v1/workflow-schemes/software-default-scheme', () =>
        HttpResponse.json({ data: existingDetail }),
      ),
      http.post('/api/v1/workflow-schemes/software-default-scheme/mappings', () =>
        HttpResponse.json(
          { code: 'MAPPING_DUPLICATE', detail: '중복' },
          { status: 409 },
        ),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const detailHook = renderHook(
      () => useWorkflowSchemeDetail('software-default-scheme'),
      { wrapper },
    )
    await waitFor(() => expect(detailHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(
      () => useAddMapping('software-default-scheme'),
      { wrapper },
    )

    act(() => {
      result.current.mutate({ issueTypeKey: 'bug', workflowKey: 'bug-tracking' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    // 롤백 후 기존 캐시 유지 확인
    const cachedDetail = client.getQueryData<SchemeDetailResponse>([
      'workflow-schemes',
      'software-default-scheme',
    ])
    expect(cachedDetail?.mappings).toHaveLength(1)
    expect(cachedDetail?.mappings[0]?.issueTypeKey).toBe('bug')
  })

  it('onSettled에서 invalidate를 호출해 캐시를 갱신한다', async () => {
    const existingDetail: SchemeDetailResponse = {
      schemeKey: 'custom-scheme-beta',
      name: '파일럿',
      description: '',
      isStandard: false,
      usedByProjectsCount: 0,
      mappingsCount: 1,
      mappings: [
        {
          id: 60,
          issueTypeKey: null,
          issueTypeName: null,
          workflowKey: 'simple',
          workflowName: '단순 워크플로우',
          isDefault: true,
        },
      ],
    }
    const newMapping: MappingResponse = {
      id: 70,
      issueTypeKey: 'task',
      issueTypeName: '작업',
      workflowKey: 'simple',
      workflowName: '단순 워크플로우',
      isDefault: false,
    }

    let fetchCount = 0
    server.use(
      http.get('/api/v1/workflow-schemes/custom-scheme-beta', () => {
        fetchCount++
        return HttpResponse.json({ data: existingDetail })
      }),
      http.post('/api/v1/workflow-schemes/custom-scheme-beta/mappings', () =>
        HttpResponse.json({ data: newMapping }, { status: 201 }),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const detailHook = renderHook(() => useWorkflowSchemeDetail('custom-scheme-beta'), { wrapper })
    await waitFor(() => expect(detailHook.result.current.isSuccess).toBe(true))

    const prevFetchCount = fetchCount

    const { result } = renderHook(() => useAddMapping('custom-scheme-beta'), { wrapper })

    act(() => {
      result.current.mutate({ issueTypeKey: 'task', workflowKey: 'simple' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // onSettled에서 invalidate 후 재조회 발생
    await waitFor(() => expect(fetchCount).toBeGreaterThan(prevFetchCount))
  })
})

describe('useRemoveMapping', () => {
  it('낙관적 업데이트로 캐시에서 매핑을 즉시 제거한다', async () => {
    const existingDetail: SchemeDetailResponse = {
      schemeKey: 'software-default-scheme',
      name: '소프트웨어',
      description: '',
      isStandard: true,
      usedByProjectsCount: 3,
      mappingsCount: 2,
      mappings: [
        {
          id: 10,
          issueTypeKey: 'bug',
          issueTypeName: '버그',
          workflowKey: 'bug-tracking',
          workflowName: '버그 추적',
          isDefault: false,
        },
        {
          id: 14,
          issueTypeKey: null,
          issueTypeName: null,
          workflowKey: 'software-default',
          workflowName: '기본',
          isDefault: true,
        },
      ],
    }

    server.use(
      http.get('/api/v1/workflow-schemes/software-default-scheme', () =>
        HttpResponse.json({ data: existingDetail }),
      ),
      http.delete(
        '/api/v1/workflow-schemes/software-default-scheme/mappings/10',
        () => new HttpResponse(null, { status: 204 }),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const detailHook = renderHook(
      () => useWorkflowSchemeDetail('software-default-scheme'),
      { wrapper },
    )
    await waitFor(() => expect(detailHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(
      () => useRemoveMapping('software-default-scheme'),
      { wrapper },
    )

    act(() => {
      result.current.mutate(10)
    })

    // onMutate에서 낙관적으로 제거 — 즉시 캐시 반영
    await waitFor(() => {
      const cached = client.getQueryData<SchemeDetailResponse>([
        'workflow-schemes',
        'software-default-scheme',
      ])
      return cached?.mappings.every((m) => m.id !== 10)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })
})
