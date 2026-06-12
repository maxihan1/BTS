// 이슈 템플릿 BC TanStack Query 훅 테스트 — RED phase (FR-TM-01 Task 2)
import React from 'react'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import type { IssueTemplate } from '@/api/issue-templates'
import {
  useIssueTemplates,
  useCreateIssueTemplate,
  useUpdateIssueTemplate,
  useDeleteIssueTemplate,
  ISSUE_TEMPLATE_KEYS,
} from '../use-issue-templates'

// sonner toast spy
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 공통 MSW 응답 데이터
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const BASE_URL = `/api/v1/projects/${PROJECT_KEY}/issue-templates`

const TEMPLATE_A: IssueTemplate = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  issueTypeId: 1,
  name: '버그 리포트 템플릿',
  content: '## 재현 단계\n\n## 예상 결과\n\n## 실제 결과',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const TEMPLATE_B: IssueTemplate = {
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  issueTypeId: 2,
  name: '기능 요청 템플릿',
  content: '## 요청 내용\n\n## 기대 효과',
  createdAt: '2026-01-02T00:00:00Z',
  updatedAt: '2026-01-02T00:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 인메모리 store + MSW stateful 핸들러
// ─────────────────────────────────────────────────────────────────────────────

let issueTemplateStore: Map<string, IssueTemplate> = new Map([
  [TEMPLATE_A.id, TEMPLATE_A],
  [TEMPLATE_B.id, TEMPLATE_B],
])

function resetStore(): void {
  issueTemplateStore = new Map([
    [TEMPLATE_A.id, { ...TEMPLATE_A }],
    [TEMPLATE_B.id, { ...TEMPLATE_B }],
  ])
}

const listHandler = http.get(BASE_URL, () => {
  const items = Array.from(issueTemplateStore.values())
  return HttpResponse.json({ data: items })
})

const createHandler = http.post(BASE_URL, async ({ request }) => {
  const body = (await request.json()) as {
    issueTypeId: number
    name: string
    content: string
  }

  const duplicate = Array.from(issueTemplateStore.values()).find(
    (t) => t.issueTypeId === body.issueTypeId,
  )
  if (duplicate !== undefined) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/issue-template-duplicate',
        title: 'Issue Template Duplicate',
        status: 409,
        detail: '이미 해당 이슈 타입에 템플릿이 있습니다.',
        errorCode: 'ISSUE_TEMPLATE_DUPLICATE',
        timestamp: new Date().toISOString(),
      },
      { status: 409 },
    )
  }

  const newTemplate: IssueTemplate = {
    id: `dddddddd-dddd-4ddd-8ddd-${Date.now().toString(16).padStart(12, '0')}`,
    projectId: TEMPLATE_A.projectId,
    issueTypeId: body.issueTypeId,
    name: body.name,
    content: body.content,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }

  issueTemplateStore.set(newTemplate.id, newTemplate)
  return HttpResponse.json({ data: newTemplate }, { status: 201 })
})

const updateHandler = http.patch(`${BASE_URL}/:templateId`, async ({ request, params }) => {
  const templateId = params['templateId'] as string
  const stored = issueTemplateStore.get(templateId)

  if (stored === undefined) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/issue-template-not-found',
        title: 'Issue Template Not Found',
        status: 404,
        detail: `템플릿을 찾을 수 없습니다: ${templateId}`,
        errorCode: 'ISSUE_TEMPLATE_NOT_FOUND',
        timestamp: new Date().toISOString(),
      },
      { status: 404 },
    )
  }

  const body = (await request.json()) as {
    name?: string
    content?: string
  }

  const updated: IssueTemplate = {
    ...stored,
    name: body.name ?? stored.name,
    content: body.content ?? stored.content,
    updatedAt: new Date().toISOString(),
  }

  issueTemplateStore.set(templateId, updated)
  return HttpResponse.json({ data: updated })
})

const deleteHandler = http.delete(`${BASE_URL}/:templateId`, ({ params }) => {
  const templateId = params['templateId'] as string

  if (!issueTemplateStore.has(templateId)) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/issue-template-not-found',
        title: 'Issue Template Not Found',
        status: 404,
        detail: `템플릿을 찾을 수 없습니다: ${templateId}`,
        errorCode: 'ISSUE_TEMPLATE_NOT_FOUND',
        timestamp: new Date().toISOString(),
      },
      { status: 404 },
    )
  }

  issueTemplateStore.delete(templateId)
  return new HttpResponse(null, { status: 204 })
})

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
// ISSUE_TEMPLATE_KEYS — queryKey 팩토리
// ─────────────────────────────────────────────────────────────────────────────

describe('ISSUE_TEMPLATE_KEYS', () => {
  it('list 키는 projectKey를 포함한다', () => {
    const key = ISSUE_TEMPLATE_KEYS.list(PROJECT_KEY)
    expect(key).toContain(PROJECT_KEY)
  })

  it('list 키는 동일 projectKey에 대해 동일한 배열을 반환한다', () => {
    expect(ISSUE_TEMPLATE_KEYS.list(PROJECT_KEY)).toEqual(ISSUE_TEMPLATE_KEYS.list(PROJECT_KEY))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useIssueTemplates — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useIssueTemplates', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler)
  })

  it('이슈 템플릿 목록을 조회해 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueTemplates(PROJECT_KEY), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(2)
    expect(result.current.data?.[0]?.name).toBe(TEMPLATE_A.name)
  })

  it('초기 로딩 상태에서 isPending이 true다', () => {
    server.use(
      http.get(BASE_URL, async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ data: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueTemplates(PROJECT_KEY), { wrapper })

    expect(result.current.isPending).toBe(true)
  })

  it('enabled: false 옵션 전달 시 쿼리가 idle 상태가 되고 fetch가 발생하지 않는다', async () => {
    const fetchSpy = vi.fn()
    server.use(
      http.get(BASE_URL, () => {
        fetchSpy()
        return HttpResponse.json({ data: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(
      () => useIssueTemplates(PROJECT_KEY, { enabled: false }),
      { wrapper },
    )

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateIssueTemplate — 생성 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateIssueTemplate', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler, createHandler)
    vi.mocked(toast.error).mockClear()
  })

  it('생성 성공 시 목록 쿼리가 invalidate되어 refetch 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useIssueTemplates(PROJECT_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const initialCount =
      (client.getQueryData<IssueTemplate[]>(ISSUE_TEMPLATE_KEYS.list(PROJECT_KEY)) ?? []).length

    const { result } = renderHook(() => useCreateIssueTemplate(PROJECT_KEY), { wrapper })

    await act(async () => {
      result.current.mutate({
        issueTypeId: 3,
        name: '새 템플릿',
        content: '## 새 내용',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached =
        client.getQueryData<IssueTemplate[]>(ISSUE_TEMPLATE_KEYS.list(PROJECT_KEY)) ?? []
      return cached.length > initialCount
    })
  })

  it('issueTypeId 중복(409) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useCreateIssueTemplate(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate({
        issueTypeId: TEMPLATE_A.issueTypeId,
        name: '중복 타입',
        content: '내용',
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })

  it('silent:true 옵션 시 중복(409) 에러에서도 toast.error가 호출되지 않는다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(
      () => useCreateIssueTemplate(PROJECT_KEY, { silent: true }),
      { wrapper },
    )
    await act(async () => {
      result.current.mutate({
        issueTypeId: TEMPLATE_A.issueTypeId,
        name: '중복 타입',
        content: '내용',
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateIssueTemplate — 수정 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateIssueTemplate', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler, updateHandler)
    vi.mocked(toast.error).mockClear()
  })

  it('수정 성공 시 목록 쿼리가 invalidate되어 갱신된 이름이 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useIssueTemplates(PROJECT_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useUpdateIssueTemplate(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate({ templateId: TEMPLATE_A.id, input: { name: '변경된 이름' } })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached =
        client.getQueryData<IssueTemplate[]>(ISSUE_TEMPLATE_KEYS.list(PROJECT_KEY)) ?? []
      return cached.some((t) => t.name === '변경된 이름')
    })
  })

  it('존재하지 않는 templateId 수정(404) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useUpdateIssueTemplate(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate({
        templateId: '00000000-0000-4000-8000-000000000000',
        input: { name: '없음' },
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })

  it('silent:true 옵션 시 수정 404 에러에서도 toast.error가 호출되지 않는다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(
      () => useUpdateIssueTemplate(PROJECT_KEY, { silent: true }),
      { wrapper },
    )
    await act(async () => {
      result.current.mutate({
        templateId: '00000000-0000-4000-8000-000000000000',
        input: { name: '없음' },
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteIssueTemplate — 삭제 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteIssueTemplate', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler, deleteHandler)
    vi.mocked(toast.error).mockClear()
  })

  it('삭제 성공 시 목록에서 해당 템플릿이 사라진다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useIssueTemplates(PROJECT_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useDeleteIssueTemplate(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate(TEMPLATE_B.id)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached =
        client.getQueryData<IssueTemplate[]>(ISSUE_TEMPLATE_KEYS.list(PROJECT_KEY)) ?? []
      return !cached.some((t) => t.id === TEMPLATE_B.id)
    })
  })

  it('존재하지 않는 templateId 삭제(404) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDeleteIssueTemplate(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate('00000000-dead-4000-8000-000000000000')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})
