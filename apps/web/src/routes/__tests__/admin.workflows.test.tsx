// 워크플로우 관리 목록 라우트 판정 — 삭제 확인 창의 닫힘·잠김·실패 유지 (부채 139)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { workflowAdminHandlers } from '@/mocks/workflow-admin-handlers'
import { resetWorkflowAdminStore } from '@/mocks/workflow-admin-fixtures'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { AdminWorkflowsPage } from '../admin.workflows'

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }))
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

beforeEach(() => {
  vi.clearAllMocks()
  resetWorkflowAdminStore()
  server.use(...workflowHandlers, ...workflowAdminHandlers)
})

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <AdminWorkflowsPage />
    </QueryClientProvider>,
  )
}

/** 목록이 도착해 삭제 버튼이 그려질 때까지 기다린 뒤 첫 행의 삭제 버튼을 돌려준다. */
async function firstDeleteButton(): Promise<HTMLElement> {
  const pattern = new RegExp(`^${labels.list.remove} `)
  return (await screen.findAllByRole('button', { name: pattern }))[0] as HTMLElement
}

/** 열려 있는 삭제 확인 창. 이름은 `${remove} ${워크플로우 이름}` 이라 접두로 찾는다. */
function openConfirm(): HTMLElement {
  const pattern = new RegExp(`^${labels.list.remove} `)
  return screen.getByRole('dialog', { name: pattern })
}

function queryConfirm(): HTMLElement | null {
  const pattern = new RegExp(`^${labels.list.remove} `)
  return screen.queryByRole('dialog', { name: pattern })
}

/**
 * 이 파일이 왜 있나.
 *
 * `/admin/workflows` 목록에는 판정이 **하나도 없었다** — `admin.workflows.new.test.tsx`(생성)만
 * 있었다. 부채 139 가 이 화면의 삭제 확인 창 동작을 바꾸므로(닫는 책임이 프리미티브에서 소비자로
 * 옮겨졌다) 회귀를 잡을 자리가 필요하다.
 *
 * 세 판정이 한 벌이다. 「성공하면 닫힌다」만 재면 **항상 닫는** 구현이 통과하고, 「실패하면 남는다」만
 * 재면 **아무 때도 안 닫는** 구현이 통과한다.
 */
describe('AdminWorkflowsPage — 삭제 확인 창', () => {
  it('삭제에 성공하면 확인 창이 닫힌다', async () => {
    renderPage()
    fireEvent.click(await firstDeleteButton())

    const confirm = openConfirm()
    fireEvent.click(within(confirm).getByRole('button', { name: labels.list.remove }))

    await waitFor(() => {
      expect(queryConfirm()).not.toBeInTheDocument()
    })
  })

  it('삭제에 실패하면 확인 창이 열린 채 남는다', async () => {
    // 종전에는 프리미티브가 확인 직후 스스로 닫아 이 상태가 **구조적으로 불가능**했다.
    server.use(
      http.delete('/api/v1/workflows/:key', () => new HttpResponse(null, { status: 500 })),
    )

    renderPage()
    fireEvent.click(await firstDeleteButton())

    const confirm = openConfirm()
    const confirmButton = within(confirm).getByRole('button', { name: labels.list.remove })
    fireEvent.click(confirmButton)

    // 실패가 확정될 때까지 기다린다 — 버튼이 다시 눌리는 상태로 돌아오는 것이 그 신호다.
    await waitFor(() => {
      expect(confirmButton).not.toBeDisabled()
    })
    expect(queryConfirm()).toBeInTheDocument()

    // 사유는 이 화면이 toast 로 알린다(훅의 `notifyWorkflowAdminError`). toast 는 모달
    // 오버레이 **위**에 뜨므로 창이 열려 있어도 읽힌다 — 그래서 `error` prop 을 쓰지 않는다.
  })

  it('삭제 처리 중에는 확인 버튼이 잠긴다 (도달 가능해진 상태)', async () => {
    // `confirm-dialog.test.tsx` 는 `confirming` 을 직접 넘겨 재므로 prop 계약만 본다.
    // 종전에는 확인 직후 창이 닫혀 이 상태에 **도달 자체가 불가능**했다
    // (`unreachable-state-fixture-is-fake-green`). 여기서 응답을 붙잡아 실제로 만든다.
    let release: (() => void) | undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.delete('/api/v1/workflows/:key', async () => {
        await held
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderPage()
    fireEvent.click(await firstDeleteButton())

    const confirm = openConfirm()
    const confirmButton = within(confirm).getByRole('button', { name: labels.list.remove })
    fireEvent.click(confirmButton)

    await waitFor(() => {
      expect(confirmButton).toBeDisabled()
    })

    release?.()
    // 잠긴 채 영원히 남지 않는다 — 응답이 오면 닫힌다.
    await waitFor(() => {
      expect(queryConfirm()).not.toBeInTheDocument()
    })
  })
})
