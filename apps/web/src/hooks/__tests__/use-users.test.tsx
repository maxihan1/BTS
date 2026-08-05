// useUsers TanStack Query 훅 단위 테스트 — fetchUsers 호출 + 쿼리 변경 시 재조회 + 50 묶음 청크
import { describe, it, expect } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { UserSummary } from '@/api/users'
import {
  USERS_BY_IDS_CHUNK_SIZE,
  chunkUserIds,
  useUsers,
  useUsersByIds,
  useUsersByIdsChunked,
} from '../use-users'

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

const usersFixture: UserSummary[] = [
  { id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890', username: 'alice', displayName: '김앨리스', email: null },
  { id: 'b2c3d4e5-f6a7-4891-bcde-ef2345678901', username: 'bob', displayName: null, email: null },
]

describe('useUsers', () => {
  it('T-UU-1: query 없이 호출 시 전체 사용자 목록을 반환한다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json(usersFixture)),
    )

    const { result } = renderHook(() => useUsers(''), { wrapper: createWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(2)
    expect(result.current.data?.[0]?.username).toBe('alice')
  })

  it('T-UU-2: query 문자열 전달 시 fetchUsers(query)를 호출한다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/users', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([usersFixture[0]])
      }),
    )

    const { result } = renderHook(() => useUsers('alice'), { wrapper: createWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(capturedUrl).toContain('query=alice')
    expect(result.current.data).toHaveLength(1)
  })

  it('T-UU-3: query 변경 시 새 쿼리키로 재조회한다', async () => {
    let fetchCount = 0
    server.use(
      http.get('/api/v1/users', () => {
        fetchCount++
        return HttpResponse.json(usersFixture)
      }),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const { result, rerender } = renderHook(
      ({ query }: { query: string }) => useUsers(query),
      { wrapper, initialProps: { query: '' } },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const firstFetchCount = fetchCount

    rerender({ query: 'alice' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(fetchCount).toBeGreaterThan(firstFetchCount)
  })

  it('T-UU-4: UserSummary[] 타입으로 데이터가 반환된다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json(usersFixture)),
    )

    const { result } = renderHook(() => useUsers(''), { wrapper: createWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const data = result.current.data ?? []
    for (const user of data) {
      expect(typeof user.id).toBe('string')
      expect(typeof user.username).toBe('string')
    }
  })
})

describe('useUsersByIds', () => {
  it('T-UU-5a: ids 배열 전달 시 사용자 목록을 반환한다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json([usersFixture[0]])),
    )

    const { result } = renderHook(
      () => useUsersByIds(['a1b2c3d4-e5f6-4890-abcd-ef1234567890']),
      { wrapper: createWrapper() },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]?.username).toBe('alice')
  })

  it('T-UU-5b: ids가 빈 배열이면 쿼리가 실행되지 않는다(enabled:false)', async () => {
    let fetchCalled = false
    server.use(
      http.get('/api/v1/users', () => {
        fetchCalled = true
        return HttpResponse.json(usersFixture)
      }),
    )

    const { result } = renderHook(
      () => useUsersByIds([]),
      { wrapper: createWrapper() },
    )

    // fetchCalled가 false인 채로 상태가 idle이어야 한다
    expect(result.current.isFetching).toBe(false)
    expect(fetchCalled).toBe(false)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // T-UU-6 — keepPreviousWhileIdsChange 옵션 계약 (리뷰 C3 후속)
  //
  // queryKey 에 `ids` 가 들어가므로 id 하나만 늘어도 **캐시 미스**가 난다. 이 결과로 이름
  // 맵을 만드는 화면(이슈 목록)은 그 순간 **이미 알던 이름까지 잃는다** — 담당자 낙관
  // 갱신에서 실제로 "바꾸지도 않은 다른 행이 미배정으로 깜빡이는" 증상이 났다.
  //
  // ★**짝으로** 잰다. 한쪽만 재면 옵션이 아무 일도 안 해도 통과한다.
  //   (a) 옵션 ON  → ids 가 바뀌는 동안 **이전 결과 유지**
  //   (b) 옵션 OFF → ids 가 바뀌는 동안 **결과 없음**  ← 기본 동작 보존의 증인이기도 하다
  //
  // 두 번째 응답을 **보류(gate)** 시켜 그 중간 창을 결정적으로 관측한다. 지연(setTimeout)
  // 으로 재면 느린 CI 에서 창을 놓쳐 flaky 가 된다.
  // ───────────────────────────────────────────────────────────────────────────
  describe('keepPreviousWhileIdsChange (리뷰 C3 후속)', () => {
    const ID_A = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
    const ID_B = 'b2c3d4e5-f6a7-4891-bcde-ef2345678901'

    /**
     * ids 가 A → A,B 로 바뀌는 동안의 **중간 상태**를 관측한다.
     *
     * @param options useUsersByIds 에 전달할 옵션
     * @returns 두 번째 조회가 보류된 시점의 `data`
     */
    async function dataWhileSecondFetchPending(
      options?: Parameters<typeof useUsersByIds>[1],
    ): Promise<UserSummary[] | undefined> {
      let releaseSecond: (() => void) | null = null
      let callCount = 0

      server.use(
        http.get('/api/v1/users', async () => {
          callCount += 1
          if (callCount >= 2) {
            // 두 번째 조회는 풀어 줄 때까지 응답하지 않는다 — 중간 창을 열어 둔다
            await new Promise<void>((resolve) => {
              releaseSecond = resolve
            })
          }
          return HttpResponse.json(usersFixture)
        }),
      )

      const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const wrapper = ({ children }: { children: React.ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      )

      const { result, rerender } = renderHook(
        ({ ids }: { ids: string[] }) => useUsersByIds(ids, options),
        { wrapper, initialProps: { ids: [ID_A] } },
      )
      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      rerender({ ids: [ID_A, ID_B] })
      // 두 번째 조회가 시작될 때까지 기다린다 — 시작 전에 재면 첫 결과를 보고 있는 것뿐이다
      await waitFor(() => expect(callCount).toBe(2))

      const snapshot = result.current.data

      // 보류를 풀어 핸들러가 매달리지 않게 한다
      if (releaseSecond !== null) (releaseSecond as () => void)()
      return snapshot
    }

    it('T-UU-6a: 옵션이 있으면 ids 가 바뀌는 동안 이전 결과를 유지한다', async () => {
      const data = await dataWhileSecondFetchPending({ keepPreviousWhileIdsChange: true })

      expect(data).toHaveLength(usersFixture.length)
      expect(data?.[0]?.username).toBe('alice')
    })

    it('T-UU-6b: 옵션이 없으면(기본) ids 가 바뀌는 동안 결과가 없다', async () => {
      // ★기본 동작 보존의 증인. "id 는 있는데 조회 결과에 없다" 를 삭제/비활성 사용자의
      // 신호로 쓰는 소비처(projects.$projectKey.settings.project-lead.tsx)가 있어
      // 전역 기본값을 바꾸지 않았다 — 그 판단이 지켜지는지 여기서 잰다.
      const data = await dataWhileSecondFetchPending()

      expect(data).toBeUndefined()
    })
  })

  it('T-UU-5c: queryKey에 ids 배열이 포함된다 (캐시 분리)', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json(usersFixture)),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const ids = ['a1b2c3d4-e5f6-4890-abcd-ef1234567890']
    const { result } = renderHook(() => useUsersByIds(ids), { wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 캐시에 byIds queryKey로 저장됐는지 확인
    const cached = client.getQueryData(['users', 'byIds', ids])
    expect(cached).toBeDefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-13 F5 — 50 묶음 청크 (백로그 담당자 이름)
//
// 백엔드 `UsersController.MAX_RESULTS`(50)를 넘겨 `?ids=` 를 부르면 400 이다. 백로그는
// 담당자가 50명을 넘길 수 있어 프론트가 먼저 묶음을 잘라야 한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 순수 함수 검증용 더미 id 목록을 만든다 (UUID 형식일 필요 없음 — 요청 파라미터로만 쓰인다). */
const makeIds = (n: number): string[] => Array.from({ length: n }, (_, i) => `id-${i}`)

describe('chunkUserIds', () => {
  it('T2-1: 50개는 묶음 1개다 (백엔드 상한 경계)', () => {
    expect(chunkUserIds(makeIds(50))).toHaveLength(1)
  })

  it('T2-2: 51개는 50 + 1 두 묶음이다 (초과 시 400 회피)', () => {
    const chunks = chunkUserIds(makeIds(51))
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
  /**
   * `?ids=` 로 들어온 묶음을 기록하고 묶음당 사용자 1명씩 돌려주는 핸들러를 설치한다.
   *
   * 묶음당 1명만 주는 이유. 합쳐진 결과 길이가 곧 **호출된 묶음 수**가 되어
   * flatMap 합치기가 실제로 동작했는지 길이 하나로 잰다.
   *
   * @returns 요청된 id 묶음 배열 — 핸들러가 불릴 때마다 push 된다
   */
  function captureIdChunks(): string[][] {
    const requested: string[][] = []
    server.use(
      http.get('/api/v1/users', ({ request }) => {
        const raw = new URL(request.url).searchParams.get('ids') ?? ''
        requested.push(raw === '' ? [] : raw.split(','))
        const user = usersFixture[(requested.length - 1) % usersFixture.length]
        return HttpResponse.json(user === undefined ? [] : [user])
      }),
    )
    return requested
  }

  it('T2-5: 60개 id 는 서버를 2회 부르고 결과를 합친다', async () => {
    const requested = captureIdChunks()

    const { result } = renderHook(() => useUsersByIdsChunked(makeIds(60)), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(result.current.data).toHaveLength(2)
    })
    expect(requested).toHaveLength(2)
    // 두 묶음은 병렬로 나가므로 도착 순서를 단언하지 않는다 — 재는 것은 묶음 크기다
    expect([...requested.map((c) => c.length)].sort((a, b) => b - a)).toEqual([50, 10])
    expect(requested.every((c) => c.length <= USERS_BY_IDS_CHUNK_SIZE)).toBe(true)
  })

  it('T2-6: 담당자가 0명이면 서버를 부르지 않는다', async () => {
    const requested = captureIdChunks()

    const { result } = renderHook(() => useUsersByIdsChunked([]), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(result.current.data).toEqual([])
    })
    // 대기 중인 요청이 있었다면 여기서 기록된다 — 없음 단언을 공허하지 않게 만든다
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(requested).toHaveLength(0)
  })

  it('T2-7: 한 묶음이 실패하면 isError 가 true 다 (소비처가 fail-soft 판정에 쓴다)', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json({ message: 'boom' }, { status: 500 })),
    )

    const { result } = renderHook(() => useUsersByIdsChunked(makeIds(3)), {
      wrapper: createWrapper(),
    })

    await waitFor(() => {
      expect(result.current.isError).toBe(true)
    })
  })

  it('T2-8: 재렌더돼도 data 참조가 유지된다 (소비처 memo 보호)', async () => {
    // BacklogColumn 은 memo(BacklogColumnInner) 라 assigneeNames 참조가 매번 바뀌면
    // 재렌더 스킵이 통째로 죽는다. 드래그 중에는 BacklogBoard 가 상시 재렌더되므로 실제로 물린다.
    captureIdChunks()
    // ids 참조를 고정한다 — 매번 새 배열을 넘기면 chunks useMemo 가 다시 계산돼
    // combine 이 아니라 호출부 실수를 재게 된다
    const ids = makeIds(1)

    const { result, rerender } = renderHook(() => useUsersByIdsChunked(ids), {
      wrapper: createWrapper(),
    })
    await waitFor(() => {
      expect(result.current.data).toHaveLength(1)
    })

    const before = result.current.data
    rerender()

    // combine 이 없으면 매 렌더 flatMap 이 새 배열을 만들어 이 단언이 깨진다
    expect(result.current.data).toBe(before)
  })
})
