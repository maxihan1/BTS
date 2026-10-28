// IssueAutomationMenu — 수동 규칙 목록(열 때 조회) · 고르면 실행 POST + 토스트 · 빈 목록 · 404 안내 (R9)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import { IssueAutomationMenu } from './IssueAutomationMenu'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

const LIST_URL = '/api/v1/projects/ATLAS/automation/manual-rules'

function renderMenu(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <IssueAutomationMenu projectKey="ATLAS" issueKey="ATLAS-7" />
    </QueryClientProvider>,
  )
}

describe('IssueAutomationMenu', () => {
  beforeEach(() => {
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  it('메뉴를 열기 전에는 목록을 조회하지 않는다', () => {
    const listCalls = vi.fn()
    server.use(http.get(LIST_URL, () => { listCalls(); return HttpResponse.json([]) }))
    renderMenu()

    expect(screen.getByRole('button', { name: '자동화' })).toBeInTheDocument()
    expect(listCalls).not.toHaveBeenCalled()
  })

  it('규칙을 고르면 이 이슈 키로 실행 POST 를 보내고 성공 토스트를 띄운다', async () => {
    const runBodies: unknown[] = []
    server.use(
      http.get(LIST_URL, () => HttpResponse.json([{ id: 'r-1', name: '릴리스 노트 달기' }])),
      http.post(`${LIST_URL}/r-1/run`, async ({ request }) => {
        runBodies.push(await request.json())
        return new HttpResponse(null, { status: 202 })
      }),
    )
    renderMenu()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '자동화' }))
    await user.click(await screen.findByRole('menuitem', { name: '릴리스 노트 달기' }))

    await waitFor(() => expect(runBodies).toEqual([{ issueKey: 'ATLAS-7' }]))
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('릴리스 노트 달기')))
  })

  it('수동 규칙이 없으면 빈 안내를 보인다', async () => {
    server.use(http.get(LIST_URL, () => HttpResponse.json([])))
    renderMenu()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '자동화' }))

    expect(await screen.findByText('실행할 수 있는 수동 규칙이 없습니다.')).toBeInTheDocument()
  })

  it('실행이 404 면 「찾을 수 없음」 안내 토스트를 띄운다', async () => {
    server.use(
      http.get(LIST_URL, () => HttpResponse.json([{ id: 'r-2', name: '사라진 규칙' }])),
      http.post(`${LIST_URL}/r-2/run`, () =>
        HttpResponse.json({ errorCode: 'AUTOMATION_MANUAL_RUN_NOT_FOUND' }, { status: 404 }),
      ),
    )
    renderMenu()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '자동화' }))
    await user.click(await screen.findByRole('menuitem', { name: '사라진 규칙' }))

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('규칙이나 이슈를 찾을 수 없습니다. 목록을 다시 열어 주세요.'),
    )
  })
})
