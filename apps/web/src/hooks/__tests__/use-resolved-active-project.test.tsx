// 활성 프로젝트 조합 훅 테스트 — useProjects + 저장값 + URL 키 (FR-UX-07 Task 4, RED)
import React from 'react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useActiveProject } from '../use-active-project'
import { useResolvedActiveProject } from '../use-resolved-active-project'

/** 테스트마다 독립된 QueryClient + Provider 래퍼 (use-projects.test.tsx 선례) */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return { wrapper, client }
}

/**
 * 백엔드가 name 오름차순으로 내려주는 순서를 그대로 재현 (프론트 재정렬 없음).
 *
 * ⚠️ `id`는 반드시 유효한 UUID여야 한다 — `projectSchema.id`가 `z.string().uuid()`라
 * 아무 문자열이나 넣으면 Zod parse 실패 → `useProjects`가 error 상태가 되고, 이 훅은
 * status='error'를 반환해 **테스트가 원인과 무관한 메시지로 깨진다**.
 */
const PROJECTS = [
  { id: '11111111-1111-4111-8111-111111111111', key: 'ALPHA', name: '가 프로젝트' },
  { id: '22222222-2222-4222-8222-222222222222', key: 'INFRA', name: '나 프로젝트' },
  { id: '33333333-3333-4333-8333-333333333333', key: 'ATLAS', name: '다 프로젝트' },
]

function serveProjects(data: unknown[] = PROJECTS) {
  server.use(http.get('/api/v1/projects', () => HttpResponse.json({ data })))
}

describe('useResolvedActiveProject — URL·저장값·목록 조합', () => {
  beforeEach(() => {
    localStorage.clear()
    useActiveProject.setState({ activeProjectKey: null })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('T-RA-1 (E1): 프로젝트 목록 로딩 중에는 status=loading — 소비처가 조회를 보류한다', () => {
    serveProjects()
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    expect(result.current.status).toBe('loading')
  })

  it('T-RA-2 (E2): 목록 조회 실패 → status=error, 저장값으로 추측 진행하지 않는다', async () => {
    useActiveProject.setState({ activeProjectKey: 'INFRA' })
    server.use(http.get('/api/v1/projects', () => new HttpResponse(null, { status: 500 })))
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('error'))
  })

  it('T-RA-3 (S5): 프로젝트 0개 → status=empty', async () => {
    serveProjects([])
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('empty'))
  })

  it('T-RA-4 (S1/FR4): URL 키가 최우선이고 저장값에 반영된다', async () => {
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    serveProjects()
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject('INFRA'), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current).toMatchObject({ status: 'ready', projectKey: 'INFRA', source: 'url' })
    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
  })

  it('T-RA-5 (S2/E7): 저장값에서 해소되면 localStorage write 를 다시 하지 않는다', async () => {
    useActiveProject.setState({ activeProjectKey: 'INFRA' })
    serveProjects()
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem')
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current).toMatchObject({ projectKey: 'INFRA', source: 'stored' })
    expect(setItemSpy).not.toHaveBeenCalled()
  })

  it('T-RA-6 (S3/B3): 첫 원소로 해소돼도 저장값에 반영된다 — 목록 재조회로 프로젝트가 갈아타는 것 방지', async () => {
    serveProjects()
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current).toMatchObject({ projectKey: 'ALPHA' })
    expect(useActiveProject.getState().activeProjectKey).toBe('ALPHA')
    // 저장 직후 다음 렌더는 저장값에서 해소되므로 출처가 first → stored 로 정착한다.
    // 이 전이 자체가 "저장이 일어났다"는 증거다(키는 그대로라 화면 변화 없음).
    expect(result.current).toMatchObject({ source: 'stored' })
  })

  it('T-RA-7 (S6): 저장값이 목록에 없으면 첫 원소로 내려가고 저장값이 교정된다', async () => {
    useActiveProject.setState({ activeProjectKey: 'GONE' })
    serveProjects()
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current).toMatchObject({ projectKey: 'ALPHA' })
    // 낡은 'GONE' 이 교정됐다 — 저장값이 목록에 실재하는 키로 바뀐다
    expect(useActiveProject.getState().activeProjectKey).toBe('ALPHA')
  })

  it('T-RA-8 (S7/E4): 접근 불가한 URL 키는 그대로 쓰되 저장값을 갱신하지 않는다', async () => {
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    serveProjects()
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject('NOPERM'), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current).toMatchObject({ projectKey: 'NOPERM', source: 'url' })
    // 목록에 없는 키를 저장하면 다음 방문에 낡은 값으로 되살아난다 — 저장하지 않는다
    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('T-RA-10 (CR1): 캐시가 있으면 재조회 실패에도 ready 를 유지한다 — 가용성 회귀 가드', async () => {
    serveProjects()
    const { wrapper, client } = createWrapper()

    // ★ rerender 를 반드시 받는다 — 없으면 마지막 단언이 refetch 이전 렌더를 읽어 공허해진다
    const { result, rerender } = renderHook(() => useResolvedActiveProject(null), { wrapper })
    await waitFor(() => expect(result.current.status).toBe('ready'))

    // 이후 재조회가 실패해도(refetchOnWindowFocus + retry:false 조합의 현실) 화면이
    // 통째로 에러가 되면 안 된다. TanStack v5 의 isError 는 데이터가 있어도 true 가 된다
    // (query-core 가 isRefetchError 를 `isError && hasData` 로 정의하는 것이 증거).
    server.use(http.get('/api/v1/projects', () => new HttpResponse(null, { status: 500 })))
    await act(async () => {
      await client.refetchQueries({ queryKey: ['projects'] })
    })

    // ① 양성 대조군 — 전제("재조회는 실패했고 캐시는 남았다")를 캐시에서 직접 확인한다.
    //    없으면 refetch 가 아무 일도 안 했을 때조차 초록이 된다.
    const state = client.getQueryState(['projects', false])
    expect(state?.status).toBe('error')
    expect(state?.data).toBeDefined()

    // ② 관측 렌더 최신화 — TanStack notifyManager 는 배치라 알림이 act 창 밖에 도착한다
    rerender()
    // 재조회는 실패했지만 캐시가 살아 있으므로 계속 쓴다
    expect(result.current.status).toBe('ready')
  })

  it('T-RA-11 (CR6): 첫 원소의 키가 빈 문자열이면 건너뛴다 — 빈 스코프 유출 차단', async () => {
    serveProjects([
      { id: '44444444-4444-4444-8444-444444444444', key: '', name: '깨진 프로젝트' },
      ...PROJECTS,
    ])
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current).toMatchObject({ projectKey: 'ALPHA' })
  })

  it('T-RA-9 (N3): 같은 입력이면 반환 참조가 안정적이다 — effect 무한루프 방지', async () => {
    serveProjects()
    const { wrapper } = createWrapper()

    const { result, rerender } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    const first = result.current
    rerender()
    expect(result.current).toBe(first)
  })

  it('T-RA-12a (F6): 빈 캐시가 재조회 실패까지 이어지면 error 로 승격한다 — empty 오인 방지', async () => {
    serveProjects([])
    const { wrapper, client } = createWrapper()

    const { result, rerender } = renderHook(() => useResolvedActiveProject(null), { wrapper })
    await waitFor(() => expect(result.current.status).toBe('empty'))

    // 빈 캐시 상태에서 재조회까지 실패하면 '0개'가 아니라 '못 불러왔다'다
    server.use(http.get('/api/v1/projects', () => new HttpResponse(null, { status: 500 })))
    await act(async () => {
      await client.refetchQueries({ queryKey: ['projects'] })
    })
    rerender()

    expect(result.current.status).toBe('error')
  })

  it('T-RA-12b (F6): 빈 캐시 + 재조회 실패에도 URL 키는 목록 대조 없이 통과한다 — E4 유지', async () => {
    serveProjects([])
    const { wrapper, client } = createWrapper()

    const { result, rerender } = renderHook(() => useResolvedActiveProject('ATLAS'), { wrapper })
    await waitFor(() => expect(result.current.status).toBe('ready'))

    server.use(http.get('/api/v1/projects', () => new HttpResponse(null, { status: 500 })))
    await act(async () => {
      await client.refetchQueries({ queryKey: ['projects'] })
    })
    rerender()

    // URL 키는 목록 대조를 거치지 않으므로(lib/active-project.ts:69-70) '배열 길이' 처방과
    // 달리 여전히 ready 다. 다만 목록 대조 실패라 저장값은 갱신하지 않는다(E4).
    expect(result.current).toMatchObject({ status: 'ready', projectKey: 'ATLAS', source: 'url' })
    expect(useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('T-RA-13 (F1): retry 는 재조회를 트리거해 error → ready 로 회복시킨다 — 훅 배선 가드', async () => {
    server.use(http.get('/api/v1/projects', () => new HttpResponse(null, { status: 500 })))
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })
    await waitFor(() => expect(result.current.status).toBe('error'))
    const errored = result.current
    if (errored.status !== 'error') throw new Error('상태가 error 여야 한다')

    serveProjects()
    act(() => {
      errored.retry()
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))
  })
})
