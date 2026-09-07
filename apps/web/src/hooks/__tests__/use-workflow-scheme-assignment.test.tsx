// 프로젝트-스킴 할당 TanStack Query hooks 테스트 — RED phase
import { renderHook, waitFor, act } from '@testing-library/react'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { AssignedScheme, AssignmentRecord } from '@/api/workflow-schemes'
import {
  useGetAssignment,
  useUpdateAssignment,
} from '../use-workflow-scheme-assignment'

/** 테스트마다 독립 캐시를 가진 QueryClient 래퍼 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

describe('useGetAssignment', () => {
  it('할당이 있을 때 AssignmentResponse를 반환한다', async () => {
    const assignment: AssignedScheme = {
      id: 1,
      key: 'custom-scheme-alpha',
      name: '사내 개발팀 커스텀 스킴',
      description: null,
      isStandard: false,
    }

    server.use(
      http.get('/api/v1/projects/ATLAS/workflow-scheme', () =>
        HttpResponse.json({ data: assignment }),
      ),
    )

    const { result } = renderHook(() => useGetAssignment('ATLAS'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(assignment)
  })

  /**
   * 404 를 성공(null)으로 삼키지 않는다.
   *
   * 백엔드는 배정 없는 프로젝트에 software-scheme 을 **자동 배정해 200** 을 돌려주므로
   * (`WorkflowSchemeApplicationService.findAssignedScheme`, EC-1 D10), 이 엔드포인트의 404 는
   * 「프로젝트 없음」 하나뿐이다. 예전처럼 null 로 삼키면 화면이 「스킴 미할당」이라는
   * 존재하지 않는 상태를 사용자에게 보여준다.
   */
  it('404 (프로젝트 없음) 를 성공으로 삼키지 않고 에러 상태가 된다', async () => {
    server.use(
      http.get('/api/v1/projects/NO-SUCH-PROJECT/workflow-scheme', () =>
        HttpResponse.json({ code: 'PROJECT_NOT_FOUND', detail: '없음' }, { status: 404 }),
      ),
    )

    const { result } = renderHook(() => useGetAssignment('NO-SUCH-PROJECT'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.data).toBeUndefined()
    expect(result.current.error).toMatchObject({ status: 404 })
  })

  it('로딩 중일 때 isPending이 true다', () => {
    server.use(
      http.get('/api/v1/projects/LOADING/workflow-scheme', () =>
        HttpResponse.json({ data: null }),
      ),
    )

    const { result } = renderHook(() => useGetAssignment('LOADING'), {
      wrapper: createWrapper(),
    })

    expect(result.current.isPending).toBe(true)
  })
})

describe('useUpdateAssignment', () => {
  it('UPSERT 성공 시 배정 이력(AssignmentRecord)을 반환한다', async () => {
    // 백엔드 PUT 응답은 배정 이력이다 — GET(스킴 객체)과 형태가 다르다.
    const updated: AssignmentRecord = {
      projectId: '11111111-1111-4111-8111-111111111111',
      workflowSchemeId: 1,
      assignedAt: '2026-01-01T00:00:00Z',
      assignedBy: '22222222-2222-4222-8222-222222222222',
    }

    server.use(
      http.put('/api/v1/projects/BTS/workflow-scheme', () =>
        HttpResponse.json({ data: updated }),
      ),
    )

    const { result } = renderHook(() => useUpdateAssignment('BTS'), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate({ schemeKey: 'software-default-scheme' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(updated)
  })

  /**
   * onError 롤백(이전 값 복원) 분기 검증.
   *
   * ★ 이 테스트를 공허하게 만드는 함정이 둘 있다 — 둘 다 밟지 않도록 구성한다.
   *   1. **배정 후보 캐시를 안 채우면** `onMutate` 가 낙관값을 찾지 못해 쓰기를 통째로 생략하고
   *      (`applied: false`) 롤백도 건너뛴다. 캐시는 처음부터 끝까지 그대로라 롤백 코드를
   *      전부 지워도 통과한다.
   *   2. **`useGetAssignment` 를 마운트하면** `onSettled` 의 무효화가 활성 쿼리를 재조회해
   *      롤백 여부와 무관하게 GET 응답으로 캐시를 덮는다. 그래서 관찰자 없이
   *      `setQueryData` 로만 착수 전 상태를 심는다.
   */
  it('낙관적 업데이트 후 실패 시 이전 값으로 롤백한다', async () => {
    const existing: AssignedScheme = {
      id: 1,
      key: 'software-default-scheme',
      name: '소프트웨어 개발 기본 스킴',
      description: null,
      isStandard: false,
    }
    const candidates: AssignedScheme[] = [
      existing,
      { id: 2, key: 'doomed-scheme', name: '실패할 스킴', description: null, isStandard: true },
    ]

    // 낙관값을 관측할 창을 테스트가 직접 연다 — 즉시 실패하면 쓰기와 롤백을 구분할 수 없다.
    let releaseFailure!: () => void
    const failureGate = new Promise<void>((res) => { releaseFailure = res })

    server.use(
      http.put('/api/v1/projects/BTS/workflow-scheme', async () => {
        await failureGate
        return HttpResponse.json({ code: 'SCHEME_NOT_FOUND', detail: '없음' }, { status: 404 })
      }),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const assignmentKey = ['projects', 'BTS', 'workflow-scheme']
    client.setQueryData(assignmentKey, existing)
    client.setQueryData(['assignable-workflow-schemes', 'BTS'], candidates)

    const { result } = renderHook(() => useUpdateAssignment('BTS'), { wrapper })

    act(() => {
      result.current.mutate({ schemeKey: 'doomed-scheme' })
    })

    // 1) 낙관적 쓰기가 실제로 일어났는지 먼저 확인한다. 이 단언이 없으면 아래 단언은
    //    「아무것도 안 건드렸다」로도 통과해버린다.
    await waitFor(() =>
      expect(client.getQueryData<AssignedScheme>(assignmentKey)?.key).toBe('doomed-scheme'),
    )

    releaseFailure()
    await waitFor(() => expect(result.current.isError).toBe(true))

    // 2) 롤백 후 착수 전 값으로 복구
    expect(client.getQueryData<AssignedScheme | null>(assignmentKey)).toEqual(existing)
  })

  it('성공 시 toast.success를 호출한다', async () => {
    const updated: AssignmentRecord = {
      projectId: '11111111-1111-4111-8111-111111111111',
      workflowSchemeId: 9,
      assignedAt: '2026-01-01T00:00:00Z',
      assignedBy: '22222222-2222-4222-8222-222222222222',
    }

    server.use(
      http.put('/api/v1/projects/BTS/workflow-scheme', () =>
        HttpResponse.json({ data: updated }),
      ),
    )

    const onSuccess = vi.fn()
    const { result } = renderHook(() => useUpdateAssignment('BTS'), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate({ schemeKey: 'new-scheme' }, { onSuccess })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(onSuccess).toHaveBeenCalledOnce()
  })

  /**
   * 롤백 가드 비대칭 회귀 방지 (TODOS §apps/web 롤백 가드 비대칭).
   *
   * 위 '실패 시 롤백' 테스트는 `useGetAssignment` 로 **캐시를 먼저 채우고** 시작하므로
   * `prevAssignment !== undefined` 경로만 검증한다. 캐시 엔트리가 아예 없는 경로 —
   * 배정 화면에 처음 들어와 조회가 끝나기 전에 배정을 누르는 실사용 시나리오 — 는
   * 어느 테스트도 밟지 않았다.
   *
   * 그 경로에서 낙관적 쓰기는 **캐시에 없던 항목을 새로 만들고**, 롤백 가드
   * (`prevAssignment !== undefined`)는 거짓이 되어 건너뛴다. ⇒ 요청이 실패했는데
   * 낙관값이 캐시에 남아 화면이 「배정됨」으로 보인다.
   *
   * ★ 비우는 것은 **배정 캐시 하나뿐**이다. 배정 후보 캐시까지 비우면 낙관적 쓰기 자체가
   *   생략되어(`applied: false`) 이 테스트가 겨냥한 `removeQueries` 분기를 한 번도 밟지 않는다.
   *   그 상태에서는 「엔트리 없음 → 여전히 엔트리 없음」이라 롤백 코드를 지워도 통과한다.
   */
  it('캐시가 비어 있을 때 실패해도 낙관값을 캐시에 남기지 않는다', async () => {
    const candidates: AssignedScheme[] = [
      { id: 2, key: 'doomed-scheme', name: '실패할 스킴', description: null, isStandard: true },
    ]

    // 낙관값을 관측할 창을 테스트가 직접 연다 (위 롤백 테스트와 같은 이유).
    let releaseFailure!: () => void
    const failureGate = new Promise<void>((res) => { releaseFailure = res })

    server.use(
      http.put('/api/v1/projects/BTS/workflow-scheme', async () => {
        await failureGate
        return HttpResponse.json({ code: 'SCHEME_NOT_FOUND', detail: '없음' }, { status: 404 })
      }),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    // ★ 위 테스트와 달리 **배정 캐시만** 비워둔다. 후보 캐시는 채운다.
    const key = ['projects', 'BTS', 'workflow-scheme']
    client.setQueryData(['assignable-workflow-schemes', 'BTS'], candidates)
    expect(client.getQueryData(key)).toBeUndefined()

    const { result } = renderHook(() => useUpdateAssignment('BTS'), { wrapper })

    act(() => {
      result.current.mutate({ schemeKey: 'doomed-scheme' })
    })

    // 1) 없던 엔트리가 낙관적 쓰기로 실제로 생겼는지 먼저 확인한다.
    await waitFor(() =>
      expect(client.getQueryData<AssignedScheme>(key)?.key).toBe('doomed-scheme'),
    )

    releaseFailure()
    await waitFor(() => expect(result.current.isError).toBe(true))

    // 2) 실패했으므로 캐시는 착수 전 상태(엔트리 없음)로 돌아가야 한다.
    expect(client.getQueryData(key)).toBeUndefined()
    expect(client.getQueryCache().find({ queryKey: key })).toBeUndefined()
  })

  /**
   * 낙관적 배정의 key↔name 불일치 회귀 방지 (TODOS §낙관적 배정의 key↔name 불일치).
   *
   * 예전 낙관값은 `key` 만 새 스킴으로 갈아끼우고 `name`·`description`·`isStandard` 는
   * 직전 스킴 값을 그대로 남겼다 ⇒ **한 객체가 두 스킴을 가리킨다.** 배정 화면
   * (`projects.$projectKey.settings.workflow-scheme.tsx`)은 `assignment.name` 을 그리므로
   * 「적용」 직후 재조회가 끝날 때까지 카드에 **옛 스킴 이름**이 보였다.
   *
   * 정답은 같은 화면이 이미 들고 있다 — `useAssignableWorkflowSchemes` 가 채운
   * 배정 후보 캐시에 정합한 객체가 통째로 있다.
   */
  it('배정 후보 캐시에서 정합한 낙관값을 가져온다 (key 와 name 이 같은 스킴)', async () => {
    const candidates: AssignedScheme[] = [
      { id: 1, key: 'old-scheme', name: '옛 스킴', description: null, isStandard: false },
      { id: 2, key: 'new-scheme', name: '새 스킴', description: '설명', isStandard: true },
    ]
    const existing = candidates[0]!

    const updated: AssignmentRecord = {
      projectId: '11111111-1111-4111-8111-111111111111',
      workflowSchemeId: 2,
      assignedAt: '2026-01-01T00:00:00Z',
      assignedBy: '22222222-2222-4222-8222-222222222222',
    }

    server.use(
      http.put('/api/v1/projects/BTS/workflow-scheme', async () => {
        // 낙관값을 관측할 시간을 준다 (즉시 응답하면 onSettled 가 곧바로 덮어쓴다).
        await new Promise((r) => setTimeout(r, 50))
        return HttpResponse.json({ data: updated })
      }),
      http.get('/api/v1/projects/BTS/workflow-scheme', () =>
        HttpResponse.json({ data: candidates[1] }),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const assignmentKey = ['projects', 'BTS', 'workflow-scheme']
    client.setQueryData(assignmentKey, existing)
    // 배정 후보 캐시 — useAssignableWorkflowSchemes 가 채우는 것과 같은 키.
    client.setQueryData(['assignable-workflow-schemes', 'BTS'], candidates)

    const { result } = renderHook(() => useUpdateAssignment('BTS'), { wrapper })

    act(() => {
      result.current.mutate({ schemeKey: 'new-scheme' })
    })

    // 서버 응답 전 낙관값 — key 와 name 이 **같은 스킴**을 가리켜야 한다.
    await waitFor(() => {
      const optimistic = client.getQueryData<AssignedScheme | null>(assignmentKey)
      expect(optimistic?.key).toBe('new-scheme')
      expect(optimistic?.name).toBe('새 스킴')
    })

    // 🛑 붙잡은 것을 반드시 푼다. 이 테스트는 낙관값을 관측하려고 PUT 을 50ms 붙잡는데,
    //    그대로 끝내면 전역 미결 mutation 가드(`src/test/setup.ts`)가 teardown 에서 잡는다.
    //    **단독 실행에서는 통과한다** — 워커에 여유가 있어 teardown 전에 스스로 정착하기
    //    때문이다. 전량 병렬 실행에서만 red 가 되는 flake 였고, 실제로 2026-09-07 배포를
    //    프론트 게이트에서 한 번 중단시켰다. 가드의 오류 메시지가 지시하는 처방 그대로다.
    await settlePendingMutations()
  })
})
