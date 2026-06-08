// 커스텀 필드 BC TanStack Query 훅 테스트 — RED phase (FR-IS-10 Task 4)
import React from 'react'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import type { CustomField } from '@/api/custom-fields'
import {
  useCustomFields,
  useCreateCustomField,
  useUpdateCustomField,
  useDeleteCustomField,
  CUSTOM_FIELD_KEYS,
} from '../use-custom-fields'

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
const BASE_URL = `/api/v1/projects/${PROJECT_KEY}/custom-fields`

const FIELD_A: CustomField = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  key: 'cf-priority',
  name: '우선순위',
  description: null,
  fieldType: 'SINGLE_SELECT',
  required: false,
  displayOrder: 1,
  options: [
    { value: 'high', label: '높음', displayOrder: 0 },
    { value: 'low', label: '낮음', displayOrder: 1 },
  ],
}

const FIELD_B: CustomField = {
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  key: 'cf-due',
  name: '마감일',
  description: '마감 날짜',
  fieldType: 'DATE',
  required: true,
  displayOrder: 2,
  options: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// 인메모리 store + MSW stateful 핸들러
// ─────────────────────────────────────────────────────────────────────────────

let customFieldStore: Map<string, CustomField> = new Map([
  [FIELD_A.id, FIELD_A],
  [FIELD_B.id, FIELD_B],
])

function resetStore(): void {
  customFieldStore = new Map([
    [FIELD_A.id, { ...FIELD_A }],
    [FIELD_B.id, { ...FIELD_B }],
  ])
}

const listHandler = http.get(BASE_URL, () => {
  const items = Array.from(customFieldStore.values()).sort(
    (a, b) => a.displayOrder - b.displayOrder,
  )
  return HttpResponse.json({ data: items })
})

const createHandler = http.post(BASE_URL, async ({ request }) => {
  const body = (await request.json()) as {
    key: string
    name: string
    fieldType: string
    description?: string
    required?: boolean
    displayOrder?: number
    options?: Array<{ value: string; label: string; displayOrder?: number }>
  }

  const duplicate = Array.from(customFieldStore.values()).find((f) => f.key === body.key)
  if (duplicate !== undefined) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/custom-field-key-duplicate',
        title: 'Custom Field Key Duplicate',
        status: 409,
        detail: '이미 같은 키의 필드가 있습니다.',
        errorCode: 'CUSTOM_FIELD_KEY_DUPLICATE',
        timestamp: new Date().toISOString(),
      },
      { status: 409 },
    )
  }

  const newField: CustomField = {
    id: `dddddddd-dddd-4ddd-8ddd-${Date.now().toString(16).padStart(12, '0')}`,
    projectId: FIELD_A.projectId,
    key: body.key,
    name: body.name,
    description: body.description ?? null,
    fieldType: body.fieldType as CustomField['fieldType'],
    required: body.required ?? false,
    displayOrder: body.displayOrder ?? customFieldStore.size + 1,
    options: (body.options ?? []).map((o, i) => ({
      value: o.value,
      label: o.label,
      displayOrder: o.displayOrder ?? i,
    })),
  }

  customFieldStore.set(newField.id, newField)
  return HttpResponse.json({ data: newField }, { status: 201 })
})

const updateHandler = http.patch(`${BASE_URL}/:fieldId`, async ({ request, params }) => {
  const fieldId = params['fieldId'] as string
  const stored = customFieldStore.get(fieldId)

  if (stored === undefined) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/custom-field-not-found',
        title: 'Custom Field Not Found',
        status: 404,
        detail: `필드를 찾을 수 없습니다: ${fieldId}`,
        errorCode: 'CUSTOM_FIELD_NOT_FOUND',
        timestamp: new Date().toISOString(),
      },
      { status: 404 },
    )
  }

  const body = (await request.json()) as {
    name?: string
    description?: string
    required?: boolean
    displayOrder?: number
  }

  const updated: CustomField = {
    ...stored,
    name: body.name ?? stored.name,
    description: body.description !== undefined ? body.description : stored.description,
    required: body.required ?? stored.required,
    displayOrder: body.displayOrder ?? stored.displayOrder,
  }

  customFieldStore.set(fieldId, updated)
  return HttpResponse.json({ data: updated })
})

const deleteHandler = http.delete(`${BASE_URL}/:fieldId`, ({ params }) => {
  const fieldId = params['fieldId'] as string

  if (!customFieldStore.has(fieldId)) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/custom-field-not-found',
        title: 'Custom Field Not Found',
        status: 404,
        detail: `필드를 찾을 수 없습니다: ${fieldId}`,
        errorCode: 'CUSTOM_FIELD_NOT_FOUND',
        timestamp: new Date().toISOString(),
      },
      { status: 404 },
    )
  }

  customFieldStore.delete(fieldId)
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
// CUSTOM_FIELD_KEYS — queryKey 팩토리
// ─────────────────────────────────────────────────────────────────────────────

describe('CUSTOM_FIELD_KEYS', () => {
  it('list 키는 projectKey를 포함한다', () => {
    const key = CUSTOM_FIELD_KEYS.list(PROJECT_KEY)
    expect(key).toContain(PROJECT_KEY)
  })

  it('list 키는 동일 projectKey에 대해 동일한 배열을 반환한다', () => {
    expect(CUSTOM_FIELD_KEYS.list(PROJECT_KEY)).toEqual(CUSTOM_FIELD_KEYS.list(PROJECT_KEY))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCustomFields — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useCustomFields', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler)
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
    const { result } = renderHook(() => useCustomFields(PROJECT_KEY, { enabled: false }), {
      wrapper,
    })

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('enabled: true 옵션 전달 시 fetch가 발생한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCustomFields(PROJECT_KEY, { enabled: true }), {
      wrapper,
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toBeDefined()
  })

  it('options 미전달 시 기본 enabled=true로 fetch가 발생한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCustomFields(PROJECT_KEY), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toBeDefined()
  })

  it('커스텀 필드 목록을 조회해 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCustomFields(PROJECT_KEY), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(2)
    expect(result.current.data?.[0]?.key).toBe('cf-priority')
  })

  it('초기 로딩 상태에서 isPending이 true다', () => {
    server.use(
      http.get(BASE_URL, async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ data: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCustomFields(PROJECT_KEY), { wrapper })

    expect(result.current.isPending).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateCustomField — 생성 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateCustomField', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler, createHandler)
    vi.mocked(toast.error).mockClear()
  })

  it('생성 성공 시 목록 쿼리가 invalidate되어 refetch 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useCustomFields(PROJECT_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const initialCount =
      (client.getQueryData<CustomField[]>(CUSTOM_FIELD_KEYS.list(PROJECT_KEY)) ?? []).length

    const { result } = renderHook(() => useCreateCustomField(PROJECT_KEY), { wrapper })

    await act(async () => {
      result.current.mutate({ key: 'cf-new', name: '새 필드', fieldType: 'SHORT_TEXT' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached =
        client.getQueryData<CustomField[]>(CUSTOM_FIELD_KEYS.list(PROJECT_KEY)) ?? []
      return cached.length > initialCount
    })
  })

  it('키 중복(409) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useCreateCustomField(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate({ key: 'cf-priority', name: '중복키', fieldType: 'SHORT_TEXT' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })

  it('silent:true 옵션 시 키 중복(409) 에러에서도 toast.error가 호출되지 않는다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useCreateCustomField(PROJECT_KEY, { silent: true }), {
      wrapper,
    })
    await act(async () => {
      result.current.mutate({ key: 'cf-priority', name: '중복키', fieldType: 'SHORT_TEXT' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateCustomField — 수정 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateCustomField', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler, updateHandler)
    vi.mocked(toast.error).mockClear()
  })

  it('수정 성공 시 목록 쿼리가 invalidate되어 갱신된 이름이 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useCustomFields(PROJECT_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useUpdateCustomField(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate({ fieldId: FIELD_A.id, input: { name: '변경된 이름' } })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached =
        client.getQueryData<CustomField[]>(CUSTOM_FIELD_KEYS.list(PROJECT_KEY)) ?? []
      return cached.some((f) => f.name === '변경된 이름')
    })
  })

  it('존재하지 않는 fieldId 수정(404) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useUpdateCustomField(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate({
        fieldId: '00000000-0000-4000-8000-000000000000',
        input: { name: '없음' },
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })

  it('silent:true 옵션 시 수정 404 에러에서도 toast.error가 호출되지 않는다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useUpdateCustomField(PROJECT_KEY, { silent: true }), {
      wrapper,
    })
    await act(async () => {
      result.current.mutate({
        fieldId: '00000000-0000-4000-8000-000000000000',
        input: { name: '없음' },
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteCustomField — 삭제 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteCustomField', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler, deleteHandler)
    vi.mocked(toast.error).mockClear()
  })

  it('삭제 성공 시 목록에서 해당 필드가 사라진다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useCustomFields(PROJECT_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useDeleteCustomField(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate(FIELD_B.id)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached =
        client.getQueryData<CustomField[]>(CUSTOM_FIELD_KEYS.list(PROJECT_KEY)) ?? []
      return !cached.some((f) => f.id === FIELD_B.id)
    })
  })

  it('존재하지 않는 fieldId 삭제(404) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDeleteCustomField(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate('00000000-dead-4000-8000-000000000000')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})
