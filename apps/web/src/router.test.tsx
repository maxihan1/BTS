// TanStack Router 라우트 트리 단위 테스트 — memory history 기반 렌더 검증
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from './router'

function renderWithRoute(path: string) {
  const memoryHistory = createMemoryHistory({ initialEntries: [path] })
  const testRouter = createRouter({ routeTree, history: memoryHistory })
  return render(<RouterProvider router={testRouter} />)
}

describe('Router', () => {
  it('/login 라우트 마운트 → LoginPage placeholder 렌더', async () => {
    renderWithRoute('/login')
    expect(await screen.findByText(/로그인 페이지/)).toBeInTheDocument()
  })

  it('/dashboard 라우트 마운트 → DashboardPage placeholder 렌더', async () => {
    renderWithRoute('/dashboard')
    expect(await screen.findByText(/대시보드/)).toBeInTheDocument()
  })

  it('/ 라우트 마운트 → 인덱스 placeholder 렌더', async () => {
    renderWithRoute('/')
    expect(await screen.findByText(/홈/)).toBeInTheDocument()
  })
})
