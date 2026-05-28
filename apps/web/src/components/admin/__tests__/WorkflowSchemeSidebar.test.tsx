// 워크플로우 스킴 사이드바 컴포넌트 단위 테스트 — 로딩/그룹 분리/버튼/키보드 접근성
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { schemeHandlers } from '@/mocks/scheme-handlers'
import { WorkflowSchemeSidebar } from '@/components/admin/WorkflowSchemeSidebar'

function renderSidebar(props: {
  selectedSchemeKey?: string
  onSelect?: (schemeKey: string) => void
  onAddNew?: () => void
}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onSelect = props.onSelect ?? vi.fn()
  const onAddNew = props.onAddNew ?? vi.fn()
  const result = render(
    <QueryClientProvider client={client}>
      <WorkflowSchemeSidebar
        selectedSchemeKey={props.selectedSchemeKey}
        onSelect={onSelect}
        onAddNew={onAddNew}
      />
    </QueryClientProvider>,
  )
  return { ...result, onSelect, onAddNew }
}

describe('WorkflowSchemeSidebar', () => {
  beforeEach(() => {
    server.use(...schemeHandlers)
  })

  /**
   * T7-1. 로딩 상태 — 데이터 로드 전 로딩 UI가 노출되어야 한다.
   */
  it('T7-1: 데이터 로드 전 로딩 상태를 표시한다', () => {
    renderSidebar({})
    // 스켈레톤 또는 로딩 텍스트가 존재해야 한다
    const loading = screen.queryByRole('status') ?? screen.queryByText(/로딩/)
    expect(loading).toBeTruthy()
  })

  /**
   * T7-2. 데이터 로드 후 표준 group(4건) + 커스텀 group(2건) 분리 렌더.
   * scheme-fixtures: 표준 4 (software-default-scheme, service-management-scheme, business-project-scheme, it-service-management-scheme)
   * 커스텀 2 (custom-scheme-alpha, custom-scheme-beta)
   */
  it('T7-2: 데이터 로드 후 표준 group(4건)과 커스텀 group(2건)이 분리 렌더된다', async () => {
    renderSidebar({})

    // 그룹 헤더 확인
    await waitFor(() => expect(screen.getByText('표준')).toBeInTheDocument())
    expect(screen.getByText('커스텀')).toBeInTheDocument()

    // 표준 스킴 4건 이름 확인
    expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument()
    expect(screen.getByText('서비스 관리 스킴')).toBeInTheDocument()
    expect(screen.getByText('비즈니스 프로젝트 스킴')).toBeInTheDocument()
    expect(screen.getByText('IT 서비스 관리 스킴')).toBeInTheDocument()

    // 커스텀 스킴 2건 이름 확인
    expect(screen.getByText('사내 개발팀 커스텀 스킴')).toBeInTheDocument()
    expect(screen.getByText('파일럿 프로젝트 스킴')).toBeInTheDocument()
  })

  /**
   * T7-3. 「+ 새 스킴」 버튼 클릭 → onAddNew 콜백 호출.
   */
  it('T7-3: 「+ 새 스킴」 버튼 클릭 시 onAddNew 콜백이 호출된다', async () => {
    const onAddNew = vi.fn()
    renderSidebar({ onAddNew })

    await waitFor(() => expect(screen.getByText('커스텀')).toBeInTheDocument())

    const addButton = screen.getByRole('button', { name: /새 스킴/ })
    fireEvent.click(addButton)
    expect(onAddNew).toHaveBeenCalledOnce()
  })

  /**
   * T7-4. 행 클릭 → onSelect(schemeKey) 콜백 호출.
   */
  it('T7-4: 스킴 행 클릭 시 onSelect(schemeKey)가 호출된다', async () => {
    const onSelect = vi.fn()
    renderSidebar({ onSelect })

    await waitFor(() => expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument())

    const schemeItem = screen.getByRole('button', { name: /소프트웨어 개발 기본 스킴/ })
    fireEvent.click(schemeItem)
    expect(onSelect).toHaveBeenCalledWith('software-default-scheme')
  })

  /**
   * T7-5. 키보드 탐색 — Enter 키로 스킴 선택 (WCAG AA).
   */
  it('T7-5: 스킴 행에서 Enter 키를 누르면 onSelect가 호출된다', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    renderSidebar({ onSelect })

    await waitFor(() => expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument())

    const schemeItem = screen.getByRole('button', { name: /소프트웨어 개발 기본 스킴/ })
    schemeItem.focus()
    await user.keyboard('{Enter}')
    expect(onSelect).toHaveBeenCalledWith('software-default-scheme')
  })

  /**
   * T7-6. selectedSchemeKey 전달 시 해당 항목이 active 상태 (aria-current="true" 또는 aria-selected).
   */
  it('T7-6: selectedSchemeKey와 일치하는 항목이 선택 강조 상태로 렌더된다', async () => {
    renderSidebar({ selectedSchemeKey: 'software-default-scheme' })

    await waitFor(() => expect(screen.getByText('소프트웨어 개발 기본 스킴')).toBeInTheDocument())

    const schemeItem = screen.getByRole('button', { name: /소프트웨어 개발 기본 스킴/ })
    expect(schemeItem).toHaveAttribute('aria-current', 'true')
  })
})
