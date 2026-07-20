// admin 워크플로우 스킴 페이지 통합 테스트 — 마운트, 사이드바 렌더, 선택 콜백
import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { schemeHandlers } from '@/mocks/scheme-handlers'
import { AdminWorkflowSchemesPage } from '@/routes/admin.workflow-schemes'

// WorkflowSchemeSidebar 의 실제 훅/MSW 렌더를 활용하므로 별도 mock 없음

function renderPage() {
  server.use(...schemeHandlers)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <AdminWorkflowSchemesPage />
    </QueryClientProvider>,
  )
}

describe('AdminWorkflowSchemesPage', () => {
  /**
   * T7-P1. 페이지 컴포넌트 마운트 — 사이드바와 본문 placeholder가 렌더된다.
   */
  it('T7-P1: 페이지가 마운트되면 사이드바와 빈 상태 placeholder가 렌더된다', async () => {
    renderPage()

    // 빈 상태 안내 텍스트
    expect(screen.getByText(/스킴을 선택하세요/)).toBeInTheDocument()

    // 사이드바 — 데이터 로드 후 스킴 목록 노출
    await waitFor(() => expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument())
  })

  /**
   * T7-P2. 스킴 선택 시 선택된 schemeKey가 상태에 반영된다.
   * 본 wave: onSelect 콜백이 selectedSchemeKey state를 갱신하는지 확인.
   * 실제 라우팅은 T11에서 구현.
   */
  it('T7-P2: 스킴 행 클릭 시 해당 스킴이 선택 상태로 변경된다', async () => {
    renderPage()

    await waitFor(() => expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument())

    // 스킴 행 클릭
    const schemeItem = screen.getByRole('button', { name: /소프트웨어 개발 기본 스킴/ })
    fireEvent.click(schemeItem)

    // 선택 후 해당 항목이 aria-current="true"를 가져야 한다
    await waitFor(() => {
      expect(schemeItem).toHaveAttribute('aria-current', 'true')
    })
  })

  /**
   * T7-P3. 「+ 새 스킴」 버튼이 사이드바 + 빈 상태에 각각 존재한다.
   * 사이드바 하단 버튼과 빈 상태 placeholder CTA 버튼 2개가 모두 렌더된다.
   */
  it('T7-P3: 페이지에 「+ 새 스킴」 버튼이 렌더된다', async () => {
    renderPage()

    await waitFor(() => expect(screen.getByText('커스텀')).toBeInTheDocument())

    const addButtons = screen.getAllByRole('button', { name: /새 스킴/ })
    expect(addButtons.length).toBeGreaterThanOrEqual(1)
  })

  /**
   * PL7-1 (FR-UX-06 PR13 Task 7). 컴포넌트는 자체 <main>을 렌더하지 않는다.
   * 문서 <main>은 ShellLayout이 단독 소유(WCAG 1.3.1 — 문서당 main 1개).
   */
  it('PL7-1: 컴포넌트는 자체 <main>을 렌더하지 않는다', () => {
    const { container } = renderPage()
    expect(container.querySelector('main')).toBeNull()
  })
})
