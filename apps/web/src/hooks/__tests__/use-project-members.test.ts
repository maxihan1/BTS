// FR-PM-01 프로젝트 멤버 TanStack Query 훅 테스트 — RED phase
import React from 'react'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { projectMemberHandlers } from '@/mocks/project-member-handlers'
import { usersHandlers } from '@/mocks/users-handlers'
import type { ProjectMember } from '@/api/project-members'
import {
  useProjectMembers,
  useAddMember,
  useChangeRole,
  useRemoveMember,
  PROJECT_MEMBER_KEYS,
} from '../use-project-members'
import { useUserSearch } from '../use-user-directory'
import { getProjectMemberErrorMessage } from '../project-member-error'

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
// PROJECT_MEMBER_KEYS
// ─────────────────────────────────────────────────────────────────────────────

describe('PROJECT_MEMBER_KEYS', () => {
  it('list 키는 projectKey를 포함한다', () => {
    const key = PROJECT_MEMBER_KEYS.list('ATLAS')
    expect(key).toContain('ATLAS')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useProjectMembers
// ─────────────────────────────────────────────────────────────────────────────

describe('useProjectMembers', () => {
  beforeEach(() => {
    server.use(...projectMemberHandlers)
  })

  it('ATLAS 프로젝트 멤버 목록을 조회해 반환한다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjectMembers('ATLAS'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toBeDefined()
    expect(result.current.data?.length).toBeGreaterThanOrEqual(2)
    const alice = result.current.data?.find((m) => m.username === 'alice')
    expect(alice?.role).toBe('PROJECT_ADMIN')
  })

  it('존재하지 않는 프로젝트는 isError가 true가 된다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjectMembers('NONEXISTENT'), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })

  it('초기 로딩 상태에서 isLoading이 true다', () => {
    server.use(
      http.get('/api/v1/projects/:projectKey/members', async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ members: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProjectMembers('ATLAS'), { wrapper })

    expect(result.current.isLoading || result.current.isPending).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useAddMember
// ─────────────────────────────────────────────────────────────────────────────

describe('useAddMember', () => {
  beforeEach(() => {
    server.use(...projectMemberHandlers)
  })

  it('이미 멤버인 경우(409 membership_already_exists) isError가 true가 된다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useAddMember('ATLAS'), { wrapper })

    await act(async () => {
      // alice는 이미 ATLAS 멤버 — X-MSW-Reset-Members로 store를 초기화한 뒤 확인
      result.current.mutate({ userId: 'fixture-alice-uuid', role: 'MEMBER' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })

  it('낙관적으로 캐시에 새 멤버를 추가한 뒤 onSettled에서 invalidate한다', async () => {
    const { client, wrapper } = createWrapper()

    // 초기 목록 캐시 채우기 (X-MSW-Reset-Members로 store 리셋)
    const listHook = renderHook(
      () => useProjectMembers('ATLAS'),
      { wrapper },
    )
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    // MSW store를 초기 상태로 리셋하는 추가 GET 요청
    server.use(
      http.get('/api/v1/projects/ATLAS/members', ({ request }) => {
        if (request.headers.get('X-MSW-Reset-Members') === 'true') {
          return undefined // projectMemberHandlers가 처리
        }
        return undefined
      }),
      ...projectMemberHandlers,
    )

    const beforeCount =
      client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))?.length ?? 0

    const { result: addResult } = renderHook(() => useAddMember('ATLAS'), { wrapper })

    act(() => {
      addResult.current.mutate({ userId: 'fixture-eve-uuid', role: 'MEMBER' })
    })

    // onMutate에서 낙관적 업데이트 — 캐시가 즉시 늘어야 한다
    await waitFor(() => {
      const cached = client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))
      return (cached?.length ?? 0) > beforeCount
    })

    await waitFor(() => expect(addResult.current.isSuccess).toBe(true))
  })

  it('멤버 추가 성공 후 onSettled invalidate로 서버 목록이 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useProjectMembers('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const initialCount =
      client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))?.length ?? 0

    const { result } = renderHook(() => useAddMember('ATLAS'), { wrapper })

    await act(async () => {
      result.current.mutate({ userId: 'fixture-carol-uuid', role: 'MEMBER' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached = client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))
      return (cached?.length ?? 0) > initialCount
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useChangeRole
// ─────────────────────────────────────────────────────────────────────────────

describe('useChangeRole', () => {
  beforeEach(() => {
    server.use(...projectMemberHandlers)
  })

  it('역할 변경 성공 시 onSettled invalidate 후 캐시 멤버의 role이 갱신된다', async () => {
    const { client, wrapper } = createWrapper()

    // ATLAS store 리셋 후 로드
    const listHook = renderHook(() => useProjectMembers('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useChangeRole('ATLAS'), { wrapper })

    await act(async () => {
      // bob(MEMBER)을 PROJECT_ADMIN으로 변경
      result.current.mutate({ userId: 'fixture-bob-uuid', role: 'PROJECT_ADMIN' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached = client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))
      return cached?.find((m) => m.userId === 'fixture-bob-uuid')?.role === 'PROJECT_ADMIN'
    })
  })

  it('마지막 admin 강등(409 last_admin_protected) 시 onError 롤백 후 isError가 true', async () => {
    // 이 테스트는 독립된 서버 핸들러로 409를 강제 반환한다
    server.use(
      http.get('/api/v1/projects/ATLAS/members', () =>
        HttpResponse.json({
          members: [
            {
              projectId: 'project-atlas-uuid',
              userId: 'fixture-alice-uuid',
              role: 'PROJECT_ADMIN',
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
              displayName: '앨리스',
              username: 'alice',
            },
          ],
        }),
      ),
      http.patch('/api/v1/projects/ATLAS/members/:userId', () =>
        HttpResponse.json({ error: 'last_admin_protected' }, { status: 409 }),
      ),
    )

    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useProjectMembers('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const aliceBefore = client
      .getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))
      ?.find((m) => m.userId === 'fixture-alice-uuid')

    const { result } = renderHook(() => useChangeRole('ATLAS'), { wrapper })

    await act(async () => {
      result.current.mutate({ userId: 'fixture-alice-uuid', role: 'MEMBER' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    // 롤백 — alice의 역할이 원래대로 돌아와야 한다
    const cached = client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))
    const aliceAfter = cached?.find((m) => m.userId === 'fixture-alice-uuid')
    expect(aliceAfter?.role).toBe(aliceBefore?.role)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useRemoveMember
// ─────────────────────────────────────────────────────────────────────────────

describe('useRemoveMember', () => {
  beforeEach(() => {
    server.use(...projectMemberHandlers)
  })

  it('멤버 제거 성공 시 낙관적으로 캐시에서 해당 멤버가 사라진다', async () => {
    const { client, wrapper } = createWrapper()

    // BTS 사용 — carol(admin) + dave(member), dave 제거
    const listHook = renderHook(() => useProjectMembers('BTS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useRemoveMember('BTS'), { wrapper })

    act(() => {
      result.current.mutate('fixture-dave-uuid')
    })

    // 낙관적 업데이트 — dave가 즉시 사라져야 한다
    await waitFor(() => {
      const cached = client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('BTS'))
      return cached?.every((m) => m.userId !== 'fixture-dave-uuid') === true
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('마지막 admin 제거(409 last_admin_protected) 시 롤백 + isError', async () => {
    // 독립 핸들러로 409를 강제 반환
    server.use(
      http.get('/api/v1/projects/ATLAS/members', () =>
        HttpResponse.json({
          members: [
            {
              projectId: 'project-atlas-uuid',
              userId: 'fixture-alice-uuid',
              role: 'PROJECT_ADMIN',
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
              displayName: '앨리스',
              username: 'alice',
            },
          ],
        }),
      ),
      http.delete('/api/v1/projects/ATLAS/members/:userId', () =>
        HttpResponse.json({ error: 'last_admin_protected' }, { status: 409 }),
      ),
    )

    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useProjectMembers('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const beforeCount =
      client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))?.length ?? 0

    const { result } = renderHook(() => useRemoveMember('ATLAS'), { wrapper })

    act(() => {
      result.current.mutate('fixture-alice-uuid')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    // 롤백 — 멤버 수가 복원돼야 한다
    const cached = client.getQueryData<ProjectMember[]>(PROJECT_MEMBER_KEYS.list('ATLAS'))
    expect(cached?.length).toBe(beforeCount)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUserSearch
// ─────────────────────────────────────────────────────────────────────────────

describe('useUserSearch', () => {
  beforeEach(() => {
    server.use(...usersHandlers)
  })

  it('query가 2자 미만이면 enabled:false여서 조회하지 않는다', () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useUserSearch('a'), { wrapper })

    // enabled:false → isFetching은 false
    expect(result.current.isFetching).toBe(false)
  })

  it('query가 2자 이상이면 검색 결과를 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useUserSearch('alice'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toBeDefined()
    const alice = result.current.data?.find((u) => u.username === 'alice')
    expect(alice).toBeDefined()
  })

  it('매칭 없는 query는 빈 배열을 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useUserSearch('zzznomatch999'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// getProjectMemberErrorMessage (에러코드 → 한국어 메시지 매핑)
// ─────────────────────────────────────────────────────────────────────────────

describe('getProjectMemberErrorMessage', () => {
  const cases: Array<[string, string]> = [
    ['not_project_admin', '프로젝트 관리자만 멤버를 변경할 수 있습니다'],
    ['last_admin_protected', '마지막 관리자는 제거하거나 강등할 수 없습니다'],
    ['membership_already_exists', '이미 멤버입니다'],
    ['user_not_found', '사용자를 찾을 수 없습니다'],
    ['member_not_found', '이미 제거된 멤버입니다'],
    ['invalid_role', '역할 값이 올바르지 않습니다'],
  ]

  it.each(cases)('errorCode "%s"는 "%s"를 반환한다', (code, expected) => {
    expect(getProjectMemberErrorMessage(code)).toBe(expected)
  })

  it('알 수 없는 errorCode는 폴백 메시지를 반환한다', () => {
    const msg = getProjectMemberErrorMessage('unknown_code')
    expect(msg.length).toBeGreaterThan(0)
  })
})
