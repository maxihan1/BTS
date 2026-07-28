// 활성 프로젝트 조합 훅 테스트 — useProjects + 저장값 + URL 키 (FR-UX-07 Task 4, RED)
import React from 'react'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
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
  return { wrapper }
}

/** 백엔드가 name 오름차순으로 내려주는 순서를 그대로 재현 (프론트 재정렬 없음) */
const PROJECTS = [
  { id: 'p-alpha', key: 'ALPHA', name: '가 프로젝트' },
  { id: 'p-infra', key: 'INFRA', name: '나 프로젝트' },
  { id: 'p-atlas', key: 'ATLAS', name: '다 프로젝트' },
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
    expect(result.current).toMatchObject({ projectKey: 'ALPHA', source: 'first' })
    expect(useActiveProject.getState().activeProjectKey).toBe('ALPHA')
  })

  it('T-RA-7 (S6): 저장값이 목록에 없으면 첫 원소로 내려가고 저장값이 교정된다', async () => {
    useActiveProject.setState({ activeProjectKey: 'GONE' })
    serveProjects()
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current).toMatchObject({ projectKey: 'ALPHA', source: 'first' })
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

  it('T-RA-9 (N3): 같은 입력이면 반환 참조가 안정적이다 — effect 무한루프 방지', async () => {
    serveProjects()
    const { wrapper } = createWrapper()

    const { result, rerender } = renderHook(() => useResolvedActiveProject(null), { wrapper })

    await waitFor(() => expect(result.current.status).toBe('ready'))
    const first = result.current
    rerender()
    expect(result.current).toBe(first)
  })
})
