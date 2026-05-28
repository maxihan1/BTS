// admin 워크플로우 스킴 상세 페이지 통합 테스트 — 3컬럼 렌더/매핑/표준보호/삭제모달
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { schemeHandlers } from '@/mocks/scheme-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { WorkflowSchemeDetailPage } from '@/routes/admin.workflow-schemes.$schemeKey'

// vi.hoisted로 mock 함수 선언
const { mockNavigate } = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ schemeKey: 'software-default-scheme' }),
}))

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

function renderDetailPage(schemeKey = 'software-default-scheme') {
  server.use(...schemeHandlers, ...issueTypeHandlers)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <WorkflowSchemeDetailPage schemeKey={schemeKey} />
    </QueryClientProvider>,
  )
}

describe('WorkflowSchemeDetailPage', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
  })

  /**
   * S2-1. 페이지 마운트 시 로딩 상태가 표시된다.
   */
  it('S2-1: 마운트 시 로딩 스켈레톤이 표시된다', () => {
    renderDetailPage()
    // 로딩 중 스켈레톤 or 로딩 UI 존재
    const loadingEl =
      screen.queryByRole('status') ??
      screen.queryByTestId('detail-skeleton') ??
      document.querySelector('[class*="animate-pulse"]')
    expect(loadingEl).toBeTruthy()
  })

  /**
   * S2-2. 데이터 로드 후 3컬럼 레이아웃이 렌더된다 (사이드바 + 매핑 테이블 + 메타 패널).
   */
  it('S2-2: 데이터 로드 후 MappingTable과 SchemeMetaPanel이 렌더된다', async () => {
    renderDetailPage()

    // 매핑 테이블 헤더 확인
    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())
    // 메타 패널 — 스킴 이름이 표시됨
    await waitFor(() =>
      expect(screen.getByDisplayValue('소프트웨어 개발 기본 스킴')).toBeInTheDocument(),
    )
  })

  /**
   * S2-3. 매핑 목록이 렌더된다.
   */
  it('S2-3: 스킴 매핑 rows가 렌더된다', async () => {
    renderDetailPage()

    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument())
    // software-default-scheme는 5 매핑 (bug, story, task, epic, default)
    await waitFor(() =>
      expect(screen.getByText('버그 추적 워크플로우')).toBeInTheDocument(),
    )
  })

  /**
   * S7-1. isStandard === true 시 name input이 disabled.
   */
  it('S7-1: 표준 스킴에서 name input이 disabled이다', async () => {
    renderDetailPage('software-default-scheme')

    await waitFor(() =>
      expect(screen.getByDisplayValue('소프트웨어 개발 기본 스킴')).toBeInTheDocument(),
    )
    const nameInput = screen.getByDisplayValue('소프트웨어 개발 기본 스킴')
    expect(nameInput).toBeDisabled()
  })

  /**
   * S7-2. isStandard === true 시 「삭제」 버튼이 disabled.
   */
  it('S7-2: 표준 스킴에서 삭제 버튼이 disabled이다', async () => {
    renderDetailPage('software-default-scheme')

    await waitFor(() =>
      expect(screen.getByDisplayValue('소프트웨어 개발 기본 스킴')).toBeInTheDocument(),
    )
    const deleteBtn = screen.getByRole('button', { name: /삭제/ })
    expect(deleteBtn).toBeDisabled()
  })

  /**
   * S7-3. isStandard === true여도 「+ 매핑 추가」 row는 존재한다 (매핑 변경 가능).
   */
  it('S7-3: 표준 스킴에서도 매핑 추가 행이 존재한다', async () => {
    renderDetailPage('software-default-scheme')

    await waitFor(() =>
      expect(screen.getByRole('table')).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /추가/ })).toBeInTheDocument(),
    )
  })

  /**
   * S8-1. 커스텀 스킴 + usedByProjectsCount > 0 → 삭제 시도 → SCHEME_IN_USE 모달.
   */
  it('S8-1: SCHEME_IN_USE 삭제 시도 시 SchemeInUseModal이 열린다', async () => {
    // custom-scheme-alpha: isStandard=false, usedByProjectsCount=2
    renderDetailPage('custom-scheme-alpha')

    await waitFor(() =>
      expect(screen.getByDisplayValue('사내 개발팀 커스텀 스킴')).toBeInTheDocument(),
    )

    const deleteBtn = screen.getByRole('button', { name: /삭제/ })
    expect(deleteBtn).not.toBeDisabled()
    fireEvent.click(deleteBtn)

    await waitFor(() =>
      expect(screen.getByRole('alertdialog')).toBeInTheDocument(),
    )
    expect(screen.getByText(/사용 중인 프로젝트/)).toBeInTheDocument()
  })

  /**
   * S8-2. SchemeInUseModal 「확인」 버튼 클릭 시 모달이 닫힌다.
   */
  it('S8-2: SchemeInUseModal 확인 버튼 클릭 시 모달이 닫힌다', async () => {
    renderDetailPage('custom-scheme-alpha')

    await waitFor(() =>
      expect(screen.getByDisplayValue('사내 개발팀 커스텀 스킴')).toBeInTheDocument(),
    )

    fireEvent.click(screen.getByRole('button', { name: /삭제/ }))
    await waitFor(() => expect(screen.getByRole('alertdialog')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: /확인/ }))
    await waitFor(() =>
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument(),
    )
  })
})
