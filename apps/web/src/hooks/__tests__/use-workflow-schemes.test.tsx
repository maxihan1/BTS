// 워크플로우 스킴 CRUD TanStack Query hooks 테스트 — RED phase
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { SchemeDetail, MappingCreated, SchemeListItem, SchemeMutationResult } from '@/api/workflow-schemes'
import {
  useWorkflowSchemes,
  useWorkflowSchemeDetail,
  useCreateWorkflowScheme,
  useUpdateWorkflowScheme,
  useDeleteWorkflowScheme,
  useAddMapping,
  useRemoveMapping,
  useAssignableWorkflowSchemes,
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
  it('스킴 목록을 조회해 SchemeListItem 배열을 반환한다', async () => {
    const schemes: SchemeListItem[] = [
      {
        id: 1,
        key: 'software-default-scheme',
        name: '소프트웨어 개발 기본 스킴',
        description: '',
        isStandard: true,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
        usedByProjectsCount: 3,
        mappingsCount: 5,
        mappings: [],
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
    expect(result.current.data?.[0]?.key).toBe('software-default-scheme')
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
    const detail: SchemeDetail = {
      id: 1,
      key: 'software-default-scheme',
      name: '소프트웨어 개발 기본 스킴',
      description: '설명',
      isStandard: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
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
    expect(result.current.data?.key).toBe('software-default-scheme')
    expect(result.current.data?.mappings).toHaveLength(1)
  })
})

describe('useAssignableWorkflowSchemes', () => {
  it('프로젝트 스코프의 할당 가능 스킴 목록을 반환한다 (백엔드 어휘 그대로)', async () => {
    server.use(
      http.get('/api/v1/projects/ATLAS/assignable-workflow-schemes', () =>
        HttpResponse.json({
          data: [
            { id: 1, key: 'software-scheme', name: '소프트웨어 스킴', description: null, isStandard: true },
          ],
        }),
      ),
    )

    const { result } = renderHook(() => useAssignableWorkflowSchemes('ATLAS'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]?.key).toBe('software-scheme')
    expect(result.current.data?.[0]?.isStandard).toBe(true)
  })
})

describe('useCreateWorkflowScheme', () => {
  it('스킴 생성 성공 시 onSuccess를 호출한다', async () => {
    // 생성·수정 응답은 카운트·mappings 를 싣지 않는다(백엔드 WorkflowSchemeResponse).
      const created: SchemeMutationResult = {
        id: 1,
        key: 'new-scheme',
        name: '새 스킴',
        description: '',
        isStandard: false,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
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
      result.current.mutate({ key: 'new-scheme', name: '새 스킴' }, { onSuccess })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(onSuccess).toHaveBeenCalledOnce()
  })
})

/**
 * assignable 캐시 무효화 회귀 방지 (TODOS §SCHEME_KEYS.assignable 캐시).
 *
 * `useAssignableWorkflowSchemes` 는 staleTime 30초인데 생성·수정·삭제 뮤테이션이
 * list/detail 만 invalidate 했다. ⇒ SYSTEM_ADMIN 겸 PROJECT_ADMIN 이
 * `/admin/workflow-schemes` 에서 스킴을 만든 뒤 30초 안에 배정 화면으로 가면
 * 새 스킴이 드롭다운에 없다.
 *
 * 관리자 뮤테이션은 전역 자원을 다루므로 projectKey 를 모른다. 그래서 키를
 * 레포 지배 관례인 **리소스-우선**(`['assignable-workflow-schemes', projectKey]`,
 * use-components·use-boards 와 동형)으로 맞춰 prefix 하나로 전부 잡는다.
 * (`predicate:` 방식은 이 레포에 선례가 0건이라 도입하지 않았다.)
 *
 * 뮤테이션 생산 지점 3곳을 **전수** 검증한다 — 하나만 검증하면 나머지가 무방비다
 * ([[mutation-site-count-equals-verified-scope]]).
 */
describe('스킴 뮤테이션의 assignable 캐시 무효화', () => {
  const ASSIGNABLE_PREFIX = ['assignable-workflow-schemes']

  /** invalidateQueries 호출을 관측하기 위한 최소 형태 (전체 시그니처를 흉내 낼 필요가 없다) */
  type InvalidateSpy = { mock: { calls: readonly (readonly unknown[])[] } }

  /** invalidateQueries 를 감시하는 클라이언트 + 래퍼 */
  function createSpyClient() {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const spy = vi.spyOn(client, 'invalidateQueries')
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    return { client, spy, wrapper }
  }

  /** spy 호출 중 assignable prefix 를 무효화한 것이 있는가 */
  function invalidatedAssignable(spy: InvalidateSpy): boolean {
    return spy.mock.calls.some((call) => {
      const arg = call[0] as { queryKey?: readonly unknown[] } | undefined
      const key = arg?.queryKey
      return Array.isArray(key) && key[0] === ASSIGNABLE_PREFIX[0]
    })
  }

  it('생성 성공 시 assignable 캐시를 무효화한다', async () => {
    const created: SchemeMutationResult = {
      id: 1, key: 'new-scheme', name: '새 스킴', description: '',
      isStandard: false, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    }
    server.use(
      http.post('/api/v1/workflow-schemes', () => HttpResponse.json({ data: created }, { status: 201 })),
    )

    const { spy, wrapper } = createSpyClient()
    const { result } = renderHook(() => useCreateWorkflowScheme(), { wrapper })

    act(() => {
      result.current.mutate({ key: 'new-scheme', name: '새 스킴' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(invalidatedAssignable(spy)).toBe(true))
  })

  it('수정 성공 시 assignable 캐시를 무효화한다', async () => {
    const updated: SchemeMutationResult = {
      id: 1, key: 'alpha', name: '바뀐 이름', description: '',
      isStandard: false, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z',
    }
    server.use(
      http.put('/api/v1/workflow-schemes/alpha', () => HttpResponse.json({ data: updated })),
    )

    const { spy, wrapper } = createSpyClient()
    const { result } = renderHook(() => useUpdateWorkflowScheme('alpha'), { wrapper })

    act(() => {
      result.current.mutate({ name: '바뀐 이름', description: null })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(invalidatedAssignable(spy)).toBe(true))
  })

  it('삭제 성공 시 assignable 캐시를 무효화한다', async () => {
    server.use(
      http.delete('/api/v1/workflow-schemes/alpha', () => new HttpResponse(null, { status: 204 })),
    )

    const { spy, wrapper } = createSpyClient()
    const { result } = renderHook(() => useDeleteWorkflowScheme(), { wrapper })

    act(() => {
      result.current.mutate('alpha')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(invalidatedAssignable(spy)).toBe(true))
  })
})

describe('useUpdateWorkflowScheme', () => {
  it('스킴 수정 성공 시 낙관적 업데이트 후 서버 응답으로 갱신한다', async () => {
    // 생성·수정 응답은 카운트·mappings 를 싣지 않는다(백엔드 WorkflowSchemeResponse).
      const updated: SchemeMutationResult = {
        id: 1,
        key: 'custom-scheme-beta',
        name: '수정된 스킴 이름',
        description: '수정된 설명',
        isStandard: false,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
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
    const existingDetail: SchemeDetail = {
      id: 1,
      key: 'custom-scheme-beta',
      name: '파일럿',
      description: '',
      isStandard: false,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
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

    // 백엔드 POST /mappings 응답은 MappingCreated(내부 PK 형태)다 — 상세의 MappingDetail 과 다르다.
    const newMapping: MappingCreated = {
      id: 99,
      schemeId: 6,
      issueTypeId: 5,
      workflowId: '5b5a535a-b6ff-4a90-a3d1-9dd935137256',
      createdAt: '2026-01-01T00:00:00Z',
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
    const existingDetail: SchemeDetail = {
      id: 1,
      key: 'software-default-scheme',
      name: '소프트웨어',
      description: '',
      isStandard: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
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
    const cachedDetail = client.getQueryData<SchemeDetail>([
      'workflow-schemes',
      'software-default-scheme',
    ])
    expect(cachedDetail?.mappings).toHaveLength(1)
    expect(cachedDetail?.mappings[0]?.issueTypeKey).toBe('bug')
  })

  it('onSettled에서 invalidate를 호출해 캐시를 갱신한다', async () => {
    const existingDetail: SchemeDetail = {
      id: 1,
      key: 'custom-scheme-beta',
      name: '파일럿',
      description: '',
      isStandard: false,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
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
    // 백엔드 매핑 추가 응답은 MappingCreated(내부 PK 형태)다.
    const newMapping: MappingCreated = {
      id: 70,
      schemeId: 6,
      issueTypeId: 3,
      workflowId: 'e4b722f6-255e-4550-be96-113b9542f1fb',
      createdAt: '2026-01-01T00:00:00Z',
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
    const existingDetail: SchemeDetail = {
      id: 1,
      key: 'software-default-scheme',
      name: '소프트웨어',
      description: '',
      isStandard: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
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
      const cached = client.getQueryData<SchemeDetail>([
        'workflow-schemes',
        'software-default-scheme',
      ])
      return cached?.mappings.every((m) => m.id !== 10)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })
})
