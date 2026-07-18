// 전역 권한 부여 TanStack Query 훅 + grantee 이름 해소 헬퍼 테스트 — RED phase (FR-PM-10 D6 Task 2)
import React from 'react'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { formatDate } from '@/lib/date-format'
import type { GrantResponse } from '@/api/global-permissions.types'
import type { UserSummary } from '@/api/users'
import type { GroupResponse } from '@/api/groups'
import {
  GLOBAL_PERMISSION_KEYS,
  useGlobalPermissions,
  useGrantGlobalPermission,
  useRevokeGlobalPermission,
  useGlobalPermissionRows,
  collectUserIds,
  collectGroupIds,
  toDisplayRows,
} from '../use-global-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BASE_URL = '/api/v1/admin/global-permissions'
const USERS_URL = '/api/v1/users'
const GROUPS_URL = '/api/v1/groups'

const USER_A_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const USER_B_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const USER_ORPHAN_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
const GROUP_A_ID = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const GROUP_ORPHAN_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const GRANT_A_ID = '11111111-1111-4111-8111-111111111111'
const GRANT_B_ID = '22222222-2222-4222-8222-222222222222'
const GRANT_C_ID = '33333333-3333-4333-8333-333333333333'

const USER_A: UserSummary = { id: USER_A_ID, username: 'alice', displayName: '앨리스', email: 'a@x.com' }
const USER_B: UserSummary = { id: USER_B_ID, username: 'bob', displayName: null, email: null }
const GROUP_A: GroupResponse = {
  id: GROUP_A_ID,
  name: '개발팀',
  description: null,
  memberCount: 3,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

/** grantedBy=USER_A, USER granteeType 대상 부여 — 가장 오래됨 */
const GRANT_A: GrantResponse = {
  id: GRANT_A_ID,
  permission: 'CREATE_PROJECT',
  granteeType: 'USER',
  granteeId: USER_B_ID,
  grantedBy: USER_A_ID,
  createdAt: '2026-01-01T00:00:00Z',
}

/** grantedBy=USER_A, GROUP granteeType 대상 부여 — 중간 */
const GRANT_B: GrantResponse = {
  id: GRANT_B_ID,
  permission: 'CREATE_PROJECT',
  granteeType: 'GROUP',
  granteeId: GROUP_A_ID,
  grantedBy: USER_A_ID,
  createdAt: '2026-02-01T00:00:00Z',
}

/** orphan USER grantee + orphan GROUP은 아니지만 grantedBy도 orphan — 가장 최신 */
const GRANT_C: GrantResponse = {
  id: GRANT_C_ID,
  permission: 'CREATE_PROJECT',
  granteeType: 'USER',
  granteeId: USER_ORPHAN_ID,
  grantedBy: USER_ORPHAN_ID,
  createdAt: '2026-03-01T00:00:00Z',
}

const GRANTS = [GRANT_A, GRANT_B, GRANT_C]

const listHandler = http.get(BASE_URL, () => HttpResponse.json(GRANTS))

const createHandler = http.post(BASE_URL, async ({ request }) => {
  const body = (await request.json()) as { permission: string; granteeType: string; granteeId: string }
  const created: GrantResponse = {
    id: '44444444-4444-4444-8444-444444444444',
    permission: body.permission,
    granteeType: body.granteeType as GrantResponse['granteeType'],
    granteeId: body.granteeId,
    grantedBy: USER_A_ID,
    createdAt: '2026-04-01T00:00:00Z',
  }
  return HttpResponse.json(created, { status: 201 })
})

const deleteHandler = http.delete(`${BASE_URL}/:id`, () => new HttpResponse(null, { status: 204 }))

const usersHandler = http.get(USERS_URL, () => HttpResponse.json([USER_A, USER_B]))
const groupsHandler = http.get(GROUPS_URL, () => HttpResponse.json([GROUP_A]))

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
// GLOBAL_PERMISSION_KEYS — queryKey 팩토리
// ─────────────────────────────────────────────────────────────────────────────

describe('GLOBAL_PERMISSION_KEYS', () => {
  it('all 키는 [\'global-permissions\']다', () => {
    expect(GLOBAL_PERMISSION_KEYS.all).toEqual(['global-permissions'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useGlobalPermissions — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useGlobalPermissions', () => {
  beforeEach(() => {
    server.use(listHandler)
  })

  it('전역 권한 부여 목록을 조회해 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useGlobalPermissions(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(3)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useGrantGlobalPermission — 부여 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useGrantGlobalPermission', () => {
  beforeEach(() => {
    server.use(createHandler)
  })

  it('부여 성공 시 GLOBAL_PERMISSION_KEYS.all 쿼리를 invalidate한다', async () => {
    const { client, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')

    const { result } = renderHook(() => useGrantGlobalPermission(), { wrapper })

    await act(async () => {
      result.current.mutate({ permission: 'CREATE_PROJECT', granteeType: 'GROUP', granteeId: GROUP_A_ID })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: GLOBAL_PERMISSION_KEYS.all })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useRevokeGlobalPermission — 회수 + invalidate-only
// ─────────────────────────────────────────────────────────────────────────────

describe('useRevokeGlobalPermission', () => {
  beforeEach(() => {
    server.use(deleteHandler)
  })

  it('회수 성공 시 GLOBAL_PERMISSION_KEYS.all 쿼리를 invalidate한다', async () => {
    const { client, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries')

    const { result } = renderHook(() => useRevokeGlobalPermission(), { wrapper })

    await act(async () => {
      result.current.mutate(GRANT_A_ID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: GLOBAL_PERMISSION_KEYS.all })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// collectUserIds — 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

describe('collectUserIds', () => {
  it('USER granteeId + 모든 grantedBy를 중복 제거해 수집한다', () => {
    const ids = collectUserIds(GRANTS)
    // USER_B_ID(GRANT_A granteeId) · USER_A_ID(GRANT_A·GRANT_B grantedBy 중복) · USER_ORPHAN_ID(GRANT_C granteeId·grantedBy 중복)
    expect(new Set(ids)).toEqual(new Set([USER_B_ID, USER_A_ID, USER_ORPHAN_ID]))
    expect(ids).toHaveLength(3)
  })

  it('GROUP granteeId는 수집하지 않는다', () => {
    const ids = collectUserIds([GRANT_B])
    expect(ids).not.toContain(GROUP_A_ID)
    expect(ids).toContain(USER_A_ID)
  })

  it('빈 배열이면 빈 배열을 반환한다', () => {
    expect(collectUserIds([])).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// collectGroupIds — 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

describe('collectGroupIds', () => {
  it('GROUP granteeId만 중복 제거해 수집한다', () => {
    const ids = collectGroupIds(GRANTS)
    expect(ids).toEqual([GROUP_A_ID])
  })

  it('USER 종류 grant뿐이면 빈 배열을 반환한다', () => {
    expect(collectGroupIds([GRANT_A])).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// toDisplayRows — 순수 헬퍼 (orphan 폴백 + 정렬)
// ─────────────────────────────────────────────────────────────────────────────

describe('toDisplayRows', () => {
  it('grant를 표시행으로 변환한다 (권한 라벨·종류 라벨·이름·시각)', () => {
    const rows = toDisplayRows([GRANT_A], [USER_A, USER_B], [GROUP_A])

    expect(rows).toHaveLength(1)
    const row = rows[0]
    expect(row).toBeDefined()
    if (row === undefined) return
    expect(row.id).toBe(GRANT_A_ID)
    expect(row.permissionLabel).toBe('프로젝트 생성')
    expect(row.granteeTypeLabel).toBe('사용자')
    expect(row.granteeName).toBe('bob') // USER_B displayName null → username 폴백
    expect(row.grantedByName).toBe('앨리스') // USER_A displayName
    expect(row.createdAtLabel).toBe(formatDate(GRANT_A.createdAt))
  })

  it('GROUP grantee는 group.name으로 표시된다', () => {
    const rows = toDisplayRows([GRANT_B], [USER_A], [GROUP_A])
    const row = rows[0]
    expect(row).toBeDefined()
    if (row === undefined) return
    expect(row.granteeTypeLabel).toBe('그룹')
    expect(row.granteeName).toBe('개발팀')
  })

  it('EC-1 — 미존재 USER grantee/grantedBy는 "삭제된 사용자"로 폴백한다', () => {
    const rows = toDisplayRows([GRANT_C], [], [])
    const row = rows[0]
    expect(row).toBeDefined()
    if (row === undefined) return
    expect(row.granteeName).toBe('삭제된 사용자')
    expect(row.grantedByName).toBe('삭제된 사용자')
  })

  it('EC-2 — 미존재 GROUP grantee는 "삭제된 그룹"으로 폴백한다', () => {
    const orphanGroupGrant: GrantResponse = { ...GRANT_B, granteeId: GROUP_ORPHAN_ID }
    const rows = toDisplayRows([orphanGroupGrant], [USER_A], [])
    const row = rows[0]
    expect(row).toBeDefined()
    if (row === undefined) return
    expect(row.granteeName).toBe('삭제된 그룹')
  })

  it('B-4 — createdAt 내림차순으로 정렬한다', () => {
    const rows = toDisplayRows(GRANTS, [USER_A, USER_B], [GROUP_A])
    expect(rows.map((r) => r.id)).toEqual([GRANT_C_ID, GRANT_B_ID, GRANT_A_ID])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useGlobalPermissionRows — 조합 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useGlobalPermissionRows', () => {
  beforeEach(() => {
    server.use(listHandler, usersHandler, groupsHandler)
  })

  it('목록+사용자+그룹 조회를 조합해 표시행을 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useGlobalPermissionRows(), { wrapper })

    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(result.current.rows).toHaveLength(3)
    expect(result.current.rows.map((r) => r.id)).toEqual([GRANT_C_ID, GRANT_B_ID, GRANT_A_ID])
  })
})
