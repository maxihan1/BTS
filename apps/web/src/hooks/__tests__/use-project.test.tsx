// 프로젝트 단건 조회 + 생성/이름변경/아카이브/아카이브해제 TanStack Query 훅 테스트 (FR-PJ PR-5 Task 3)
import React from 'react'
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { projectHandlers, resetProjectStore } from '@/mocks/project-handlers'
import type { Project } from '@/api/projects'
import { useProjects } from '@/hooks/use-projects'
import { useProject } from '../use-project'
import {
  useCreateProject,
  useUpdateProjectName,
  useArchiveProject,
  useUnarchiveProject,
} from '../use-project-mutations'

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
// useProject — 단건 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useProject', () => {
  beforeEach(() => {
    resetProjectStore()
    server.use(...projectHandlers)
  })

  it('idOrKey로 프로젝트 단건을 조회한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProject('ATLAS'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.key).toBe('ATLAS')
  })

  it('queryKey는 [project, idOrKey] 형태로 캐시된다', async () => {
    const { client, wrapper } = createWrapper()
    const { result } = renderHook(() => useProject('ATLAS'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(client.getQueryData(['project', 'ATLAS'])).toBeDefined()
  })

  it('idOrKey가 빈 문자열이면 쿼리가 idle 상태로 유지된다', () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProject(''), { wrapper })

    expect(result.current.fetchStatus).toBe('idle')
  })

  it('존재하지 않는 프로젝트는 error 상태가 된다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useProject('UNKNOWN'), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateProject — 생성 + invalidate(['projects'])
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateProject', () => {
  beforeEach(() => {
    resetProjectStore()
    server.use(...projectHandlers)
  })

  it('생성 성공 시 프로젝트 목록(useProjects) 쿼리가 invalidate되어 새 프로젝트가 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    const listHook = renderHook(() => useProjects(false), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const initialCount = (client.getQueryData<Project[]>(['projects', false]) ?? []).length

    const { result } = renderHook(() => useCreateProject(), { wrapper })
    await act(async () => {
      result.current.mutate({ key: 'NOVA2', name: '노바2 프로젝트' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => {
      const cached = client.getQueryData<Project[]>(['projects', false]) ?? []
      expect(cached.length).toBeGreaterThan(initialCount)
    })
  })

  it('key 중복(409) 시 에러 상태가 된다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCreateProject(), { wrapper })

    await act(async () => {
      result.current.mutate({ key: 'ATLAS', name: '중복 프로젝트' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateProjectName — 이름 변경 + invalidate(['project', idOrKey])
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateProjectName', () => {
  beforeEach(() => {
    resetProjectStore()
    server.use(...projectHandlers)
  })

  it('수정 성공 시 단건(useProject) 쿼리가 invalidate되어 새 이름이 반영된다', async () => {
    const { wrapper } = createWrapper()

    const detailHook = renderHook(() => useProject('ATLAS'), { wrapper })
    await waitFor(() => expect(detailHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useUpdateProjectName(), { wrapper })
    await act(async () => {
      result.current.mutate({ idOrKey: 'ATLAS', name: '아틀라스 개명' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(detailHook.result.current.data?.name).toBe('아틀라스 개명'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useArchiveProject / useUnarchiveProject — 아카이브(해제) + invalidate stateful 반영
// ─────────────────────────────────────────────────────────────────────────────

describe('useArchiveProject', () => {
  beforeEach(() => {
    resetProjectStore()
    server.use(...projectHandlers)
  })

  it('아카이브 성공 시 단건(useProject) 쿼리가 invalidate되어 archived:true가 반영된다', async () => {
    const { wrapper } = createWrapper()

    const detailHook = renderHook(() => useProject('ATLAS'), { wrapper })
    await waitFor(() => expect(detailHook.result.current.isSuccess).toBe(true))
    expect(detailHook.result.current.data?.archived).toBe(false)

    const { result } = renderHook(() => useArchiveProject(), { wrapper })
    await act(async () => {
      result.current.mutate('ATLAS')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(detailHook.result.current.data?.archived).toBe(true))
  })
})

describe('useUnarchiveProject', () => {
  beforeEach(() => {
    resetProjectStore()
    server.use(...projectHandlers)
  })

  it('아카이브 해제 성공 시 단건(useProject) 쿼리가 invalidate되어 archived:false가 반영된다', async () => {
    const { wrapper } = createWrapper()

    // NOVA는 project-handlers.ts 시드에서 archived:true로 초기화된다
    const detailHook = renderHook(() => useProject('NOVA'), { wrapper })
    await waitFor(() => expect(detailHook.result.current.isSuccess).toBe(true))
    expect(detailHook.result.current.data?.archived).toBe(true)

    const { result } = renderHook(() => useUnarchiveProject(), { wrapper })
    await act(async () => {
      result.current.mutate('NOVA')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await waitFor(() => expect(detailHook.result.current.data?.archived).toBe(false))
  })
})
