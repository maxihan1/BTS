// 필드 권한 규칙 + 그룹 TanStack Query 훅 테스트 — RED phase (FR-PM-07 Task 4)
import React from 'react'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import type { FieldPermissionResponse } from '@/api/field-permissions'
import type { GroupResponse } from '@/api/groups'
import {
  useFieldPermissions,
  useCreateFieldPermission,
  useDeleteFieldPermission,
  FIELD_PERMISSION_KEYS,
} from '../use-field-permissions'
import { useGroups, GROUP_KEYS } from '../use-groups'

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
const BASE_URL = `/api/v1/projects/${PROJECT_KEY}/field-permissions`
const GROUPS_URL = '/api/v1/groups'

const PERMISSION_A: FieldPermissionResponse = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  fieldKind: 'CORE',
  fieldKey: 'summary',
  groupId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  groupName: '개발팀',
  accessLevel: 'VIEW',
}

const PERMISSION_B: FieldPermissionResponse = {
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  fieldKind: 'CUSTOM',
  fieldKey: 'cf-priority',
  groupId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  groupName: '개발팀',
  accessLevel: 'EDIT',
}

const GROUP_A: GroupResponse = {
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  name: '개발팀',
  description: '개발 그룹',
  memberCount: 5,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const GROUP_B: GroupResponse = {
  id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  name: '디자인팀',
  description: null,
  memberCount: 3,
  createdAt: '2024-01-02T00:00:00Z',
  updatedAt: '2024-01-02T00:00:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 인메모리 store + MSW stateful 핸들러
// ─────────────────────────────────────────────────────────────────────────────

let permissionStore: Map<string, FieldPermissionResponse> = new Map([
  [PERMISSION_A.id, PERMISSION_A],
  [PERMISSION_B.id, PERMISSION_B],
])

function resetStore(): void {
  permissionStore = new Map([
    [PERMISSION_A.id, { ...PERMISSION_A }],
    [PERMISSION_B.id, { ...PERMISSION_B }],
  ])
}

const listHandler = http.get(BASE_URL, () => {
  const items = Array.from(permissionStore.values())
  return HttpResponse.json({ data: items })
})

const createHandler = http.post(BASE_URL, async ({ request }) => {
  const body = (await request.json()) as {
    fieldKind: string
    fieldKey: string
    groupId: string
    accessLevel: string
  }

  // 동일 fieldKind+fieldKey+groupId 중복 시 409
  const duplicate = Array.from(permissionStore.values()).find(
    (p) => p.fieldKind === body.fieldKind && p.fieldKey === body.fieldKey && p.groupId === body.groupId,
  )
  if (duplicate !== undefined) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/field-permission-duplicate',
        title: 'Field Permission Duplicate',
        status: 409,
        detail: '이미 동일한 규칙이 있습니다.',
        errorCode: 'FIELD_PERMISSION_DUPLICATE',
        timestamp: new Date().toISOString(),
      },
      { status: 409 },
    )
  }

  const newPermission: FieldPermissionResponse = {
    id: `eeeeeeee-eeee-4eee-8eee-${Date.now().toString(16).padStart(12, '0')}`,
    fieldKind: body.fieldKind as FieldPermissionResponse['fieldKind'],
    fieldKey: body.fieldKey,
    groupId: body.groupId,
    groupName: GROUP_A.name,
    accessLevel: body.accessLevel as FieldPermissionResponse['accessLevel'],
  }

  permissionStore.set(newPermission.id, newPermission)
  return HttpResponse.json(newPermission, { status: 201 })
})

const deleteHandler = http.delete(`${BASE_URL}/:id`, ({ params }) => {
  const id = params['id'] as string

  if (!permissionStore.has(id)) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/field-permission-not-found',
        title: 'Field Permission Not Found',
        status: 404,
        detail: `규칙을 찾을 수 없습니다: ${id}`,
        errorCode: 'FIELD_PERMISSION_NOT_FOUND',
        timestamp: new Date().toISOString(),
      },
      { status: 404 },
    )
  }

  permissionStore.delete(id)
  return new HttpResponse(null, { status: 204 })
})

const groupsHandler = http.get(GROUPS_URL, () => {
  return HttpResponse.json([GROUP_A, GROUP_B])
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
// FIELD_PERMISSION_KEYS — queryKey 팩토리
// ─────────────────────────────────────────────────────────────────────────────

describe('FIELD_PERMISSION_KEYS', () => {
  it('list 키는 projectKey를 포함한다', () => {
    const key = FIELD_PERMISSION_KEYS.list(PROJECT_KEY)
    expect(key).toContain(PROJECT_KEY)
  })

  it('list 키는 동일 projectKey에 대해 동일한 배열을 반환한다', () => {
    expect(FIELD_PERMISSION_KEYS.list(PROJECT_KEY)).toEqual(FIELD_PERMISSION_KEYS.list(PROJECT_KEY))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GROUP_KEYS — queryKey 팩토리
// ─────────────────────────────────────────────────────────────────────────────

describe('GROUP_KEYS', () => {
  it('all 키가 정의되어 있다', () => {
    expect(GROUP_KEYS.all).toBeDefined()
    expect(Array.isArray(GROUP_KEYS.all)).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useFieldPermissions — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useFieldPermissions', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler)
  })

  it('필드 권한 규칙 목록을 조회해 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useFieldPermissions(PROJECT_KEY), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(2)
  })

  it('초기 로딩 상태에서 isPending이 true다', () => {
    server.use(
      http.get(BASE_URL, async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ data: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useFieldPermissions(PROJECT_KEY), { wrapper })

    expect(result.current.isPending).toBe(true)
  })

  it('enabled: false 전달 시 fetch가 발생하지 않는다', async () => {
    const fetchSpy = vi.fn()
    server.use(
      http.get(BASE_URL, () => {
        fetchSpy()
        return HttpResponse.json({ data: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useFieldPermissions(PROJECT_KEY, { enabled: false }), {
      wrapper,
    })

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateFieldPermission — 생성 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateFieldPermission', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler, createHandler)
    vi.mocked(toast.error).mockClear()
  })

  it('생성 성공 시 목록 쿼리가 invalidate되어 refetch 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useFieldPermissions(PROJECT_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const initialCount =
      (client.getQueryData<FieldPermissionResponse[]>(FIELD_PERMISSION_KEYS.list(PROJECT_KEY)) ?? []).length

    const { result } = renderHook(() => useCreateFieldPermission(PROJECT_KEY), { wrapper })

    await act(async () => {
      result.current.mutate({
        fieldKind: 'CUSTOM',
        fieldKey: 'cf-new',
        groupId: GROUP_B.id,
        accessLevel: 'VIEW',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached =
        client.getQueryData<FieldPermissionResponse[]>(FIELD_PERMISSION_KEYS.list(PROJECT_KEY)) ?? []
      return cached.length > initialCount
    })
  })

  it('중복(409) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useCreateFieldPermission(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate({
        fieldKind: PERMISSION_A.fieldKind,
        fieldKey: PERMISSION_A.fieldKey,
        groupId: PERMISSION_A.groupId,
        accessLevel: PERMISSION_A.accessLevel,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })

  it('silent:true 옵션 시 중복(409) 에러에서도 toast.error가 호출되지 않는다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(
      () => useCreateFieldPermission(PROJECT_KEY, { silent: true }),
      { wrapper },
    )
    await act(async () => {
      result.current.mutate({
        fieldKind: PERMISSION_A.fieldKind,
        fieldKey: PERMISSION_A.fieldKey,
        groupId: PERMISSION_A.groupId,
        accessLevel: PERMISSION_A.accessLevel,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteFieldPermission — 삭제 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteFieldPermission', () => {
  beforeEach(() => {
    resetStore()
    server.use(listHandler, deleteHandler)
    vi.mocked(toast.error).mockClear()
  })

  it('삭제 성공 시 목록에서 해당 규칙이 사라진다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useFieldPermissions(PROJECT_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useDeleteFieldPermission(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate(PERMISSION_B.id)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached =
        client.getQueryData<FieldPermissionResponse[]>(FIELD_PERMISSION_KEYS.list(PROJECT_KEY)) ?? []
      return !cached.some((p) => p.id === PERMISSION_B.id)
    })
  })

  it('존재하지 않는 id 삭제(404) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDeleteFieldPermission(PROJECT_KEY), { wrapper })
    await act(async () => {
      result.current.mutate('00000000-dead-4000-8000-000000000000')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useGroups — 그룹 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useGroups', () => {
  beforeEach(() => {
    server.use(groupsHandler)
  })

  it('그룹 목록을 조회해 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useGroups(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(2)
    expect(result.current.data?.[0]?.name).toBe('개발팀')
  })

  it('초기 로딩 상태에서 isPending이 true다', () => {
    server.use(
      http.get(GROUPS_URL, async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json([])
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useGroups(), { wrapper })

    expect(result.current.isPending).toBe(true)
  })

  it('enabled: false 전달 시 fetch가 발생하지 않는다', async () => {
    const fetchSpy = vi.fn()
    server.use(
      http.get(GROUPS_URL, () => {
        fetchSpy()
        return HttpResponse.json([])
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useGroups({ enabled: false }), { wrapper })

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
