// 워크플로우 생성 라우트 판정 — 필드 결선 · 최초 상태 강제 · 생성 요청 형태
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { workflowAdminHandlers } from '@/mocks/workflow-admin-handlers'
import { resetWorkflowAdminStore } from '@/mocks/workflow-admin-fixtures'
import { workflowEditorLabels as L } from '@/i18n/workflow-editor-labels'
import { WorkflowNewPage } from '../admin.workflows.new'

// jsdom 은 scrollIntoView 를 구현하지 않는다 — cmdk 가 부른다 (`ui/command.test.tsx:16` 처방)
if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
  window.HTMLElement.prototype.scrollIntoView = () => {}
}

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }))
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

beforeEach(() => {
  vi.clearAllMocks()
  resetWorkflowAdminStore()
  server.use(...workflowHandlers, ...workflowAdminHandlers)
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <WorkflowNewPage />
    </QueryClientProvider>,
  )
}

/** 카탈로그가 도착해 폼이 그려질 때까지 기다린다 */
async function waitForForm() {
  await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
}

describe('WorkflowNewPage — 필드 결선', () => {
  it('최초 상태 라벨이 combobox 와 실제로 묶여 있다', async () => {
    // `<Label htmlFor>` 과 `Combobox id` 가 짝이 아니면 라벨을 눌러도 열리지 않고
    // 화면낭독기가 그 필드를 이름 없이 읽는다. 이 페이지는 판정이 0 이었다.
    renderPage()
    await waitForForm()
    const combobox = screen.getByRole('combobox', { name: L.editor.initialStatusField })
    expect(combobox).toHaveAttribute('id', 'workflow-initial-status')
    expect(screen.getByText(L.editor.initialStatusField).getAttribute('for')).toBe('workflow-initial-status')
  })

  it('라벨이 「상태 추가」(동작)를 빌려 쓰지 않는다', async () => {
    renderPage()
    await waitForForm()
    expect(screen.queryByRole('combobox', { name: L.statusPanel.add })).not.toBeInTheDocument()
  })
})

describe('WorkflowNewPage — 최초 상태 강제', () => {
  it('키·이름만 채우면 만들 수 없다 — 백엔드 invariant 가 상태 하나 이상을 요구한다', async () => {
    renderPage()
    await waitForForm()
    await userEvent.type(screen.getByRole('textbox', { name: L.list.columnKey }), 'brand-new')
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '새 워크플로우')
    expect(screen.getByRole('button', { name: L.list.create })).toBeDisabled()
  })

  it('최초 상태까지 고르면 만들 수 있다', async () => {
    renderPage()
    await waitForForm()
    await userEvent.type(screen.getByRole('textbox', { name: L.list.columnKey }), 'brand-new')
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '새 워크플로우')
    await userEvent.click(screen.getByRole('combobox', { name: L.editor.initialStatusField }))
    await userEvent.click(await screen.findByRole('option', { name: 'Open' }))
    expect(screen.getByRole('button', { name: L.list.create })).toBeEnabled()
  })
})

describe('WorkflowNewPage — 생성 요청', () => {
  it('고른 상태를 statuses 로 실어 보내고 편집기로 보낸다', async () => {
    renderPage()
    await waitForForm()
    await userEvent.type(screen.getByRole('textbox', { name: L.list.columnKey }), 'brand-new')
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '새 워크플로우')
    await userEvent.click(screen.getByRole('combobox', { name: L.editor.initialStatusField }))
    await userEvent.click(await screen.findByRole('option', { name: 'Open' }))
    await userEvent.click(screen.getByRole('button', { name: L.list.create }))

    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/admin/workflows/brand-new' }))
  })

  it('이미 쓰는 키면 만들지 못하고 편집기로 보내지 않는다', async () => {
    renderPage()
    await waitForForm()
    await userEvent.type(screen.getByRole('textbox', { name: L.list.columnKey }), 'simple')
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '중복')
    await userEvent.click(screen.getByRole('combobox', { name: L.editor.initialStatusField }))
    await userEvent.click(await screen.findByRole('option', { name: 'Open' }))
    await userEvent.click(screen.getByRole('button', { name: L.list.create }))

    await waitFor(() => expect(vi.mocked(navigate)).not.toHaveBeenCalledWith({ to: '/admin/workflows/simple' }))
  })
})
