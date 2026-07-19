// 프로젝트 목록 TanStack Query 훅 테스트 — 정렬 반환 + 빈 목록 + 에러 fail-safe 검증 (FR-UX-06 PR12 Task 1)
import React from 'react'
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { projectListHandlers, projectListFixtures } from '@/mocks/project-list-handlers'
import { useProjects } from '../use-projects'

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
  return { wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// useProjects
// ─────────────────────────────────────────────────────────────────────────────

describe('useProjects', () => {
  beforeEach(() => {
    server.use(...projectListHandlers)
  })

  it('name 오름차순으로 정렬된 프로젝트 목록을 반환한다', async () => {
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjects(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const names = result.current.data?.map((project) => project.name)
    const expectedSorted = [...projectListFixtures]
      .map((fixture) => fixture.name)
      .sort((a, b) => a.localeCompare(b))
    expect(names).toEqual(expectedSorted)
  })

  it('빈 응답({data:[]})이면 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects', () => HttpResponse.json({ data: [] })),
    )
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjects(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([])
  })

  it('에러 응답이면 throw하지 않고 쿼리가 error 상태가 된다', async () => {
    server.use(
      http.get('/api/v1/projects', () => HttpResponse.json({ detail: '서버 오류' }, { status: 500 })),
    )
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useProjects(), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.data).toBeUndefined()
  })
})
