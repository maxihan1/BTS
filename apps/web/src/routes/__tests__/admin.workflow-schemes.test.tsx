// admin 워크플로우 스킴 페이지 통합 테스트 — 마운트, 사이드바 렌더, 상세/생성 라우팅
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { schemeHandlers } from '@/mocks/scheme-handlers'
import { AdminWorkflowSchemesPage } from '@/routes/admin.workflow-schemes'

// WorkflowSchemeSidebar 의 실제 훅/MSW 렌더를 활용하므로 사이드바 mock 은 없다.
// 라우터만 mock 한다 — 형제 페이지(admin.workflow-schemes.$schemeKey.test.tsx)와 같은 형태.
const { mockNavigate } = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
}))

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
  beforeEach(() => {
    mockNavigate.mockReset()
  })

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
   * NAV-1 (재발 판별식). 스킴 행 클릭은 **상세 라우트로 이동**해야 한다.
   *
   * ★ 이 판정이 없어서 스텁이 살아남았다. 예전 T7-P2 는 `aria-current="true"` 만 봤고,
   * 그 단언은 이동 없이 로컬 state 만 갱신해도 통과한다. 그래서 목록 화면이 스킴 클릭에
   * "상세 (T11 구현 예정)" 문구를 띄우는 상태로 초록을 유지했다 — 상세 라우트는
   * router.tsx 에 이미 등록돼 있는데도. 이동 대상을 직접 단언해야 재발이 잡힌다.
   */
  it('NAV-1: 스킴 행 클릭 시 해당 스킴의 상세 라우트로 이동한다', async () => {
    renderPage()

    await waitFor(() => expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /소프트웨어 개발 기본 스킴/ }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/admin/workflow-schemes/$schemeKey',
        params: { schemeKey: 'software-default-scheme' },
      })
    })
  })

  /**
   * NAV-2 (재발 판별식). 커스텀 스킴도 같은 경로로 이동한다 — 표준 스킴만 되면 안 된다.
   */
  it('NAV-2: 커스텀 스킴 행 클릭도 상세 라우트로 이동한다', async () => {
    renderPage()

    await waitFor(() => expect(screen.getByText('사내 개발팀 커스텀 스킴')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /사내 개발팀 커스텀 스킴/ }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/admin/workflow-schemes/$schemeKey',
        params: { schemeKey: 'custom-scheme-alpha' },
      })
    })
  })

  /**
   * T7-P3. 「+ 새 스킴」 버튼이 사이드바 + 빈 상태에 각각 존재한다.
   */
  it('T7-P3: 페이지에 「+ 새 스킴」 버튼이 렌더된다', async () => {
    renderPage()

    await waitFor(() => expect(screen.getByText('커스텀')).toBeInTheDocument())

    const addButtons = screen.getAllByRole('button', { name: /새 스킴/ })
    expect(addButtons.length).toBeGreaterThanOrEqual(1)
  })

  /**
   * NAV-3 (재발 판별식). 「+ 새 스킴」 버튼은 생성 라우트로 이동해야 한다.
   *
   * ★ 이 버튼이 죽어 있으면 커스텀 스킴을 만들 경로가 UI 에서 사라진다. 그러면 DB 에는
   * 마이그레이션이 시드한 표준 스킴 4건만 남고, 프로젝트 설정의 스킴 드롭다운에도
   * 그 4건(템플릿)만 뜬다 — 「템플릿 스킴만 설정 가능」 증상의 실제 출처다.
   * 사이드바 하단 버튼과 빈 상태 CTA 둘 다 같은 곳으로 가야 한다.
   */
  it('NAV-3: 「+ 새 스킴」 버튼 전량이 생성 라우트로 이동한다', async () => {
    renderPage()

    await waitFor(() => expect(screen.getByText('커스텀')).toBeInTheDocument())

    const addButtons = screen.getAllByRole('button', { name: /새 스킴/ })
    expect(addButtons.length).toBeGreaterThanOrEqual(2)

    for (const button of addButtons) {
      mockNavigate.mockReset()
      fireEvent.click(button)
      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalledWith({ to: '/admin/workflow-schemes/new' })
      })
    }
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
