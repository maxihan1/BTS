// 보드 툴바 「스프린트 완료」 테스트 — 권한 게이팅 · 열 때만 백로그 조회 · 그 스프린트의 완료 다이얼로그 (JB-B-3)
import { describe, it, expect } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { BacklogView } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'
import { BoardCompleteSprintButton } from './BoardCompleteSprintButton'

const BOARD_ID = '00000000-0000-4000-8000-0000000000b1'
const SPRINT_ID = '00000000-0000-4000-8000-0000000000a1'

const view: BacklogView = {
  backlog: [],
  sprints: [
    {
      sprint: {
        sprintId: SPRINT_ID,
        boardId: BOARD_ID,
        name: 'ATLAS 스프린트 1',
        goal: null,
        status: 'ACTIVE',
        startDate: '2026-10-20',
        endDate: '2026-11-03',
        version: 1,
      },
      issues: [],
    },
  ],
  truncated: false,
}

function renderButton(canManageSprint = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <BoardCompleteSprintButton
        projectKey="ATLAS"
        boardId={BOARD_ID}
        sprintId={SPRINT_ID}
        canManageSprint={canManageSprint}
        canReorderIssue
      />
    </QueryClientProvider>,
  )
}

describe('BoardCompleteSprintButton', () => {
  it('스프린트 관리 권한이 없으면 버튼이 없다', () => {
    renderButton(false)
    expect(screen.queryByRole('button', { name: backlogLabels.completeSprint })).toBeNull()
  })

  it('누르기 전에는 백로그를 읽지 않고, 누르면 그 스프린트의 완료 다이얼로그가 떠서 그 스프린트를 완료한다', async () => {
    let backlogCalls = 0
    let completed = ''
    server.use(
      http.get('/api/v1/projects/ATLAS/backlog', ({ request }) => {
        backlogCalls += 1
        expect(new URL(request.url).searchParams.get('board')).toBe(BOARD_ID)
        return HttpResponse.json({ data: view })
      }),
      http.get('/api/v1/workflows', () => HttpResponse.json({ data: [] })),
      http.post('/api/v1/sprints/:id/complete', ({ params }) => {
        completed = String(params.id)
        return HttpResponse.json({ data: { ...view.sprints[0]?.sprint, status: 'COMPLETED' } })
      }),
    )
    const user = userEvent.setup()
    renderButton()
    expect(backlogCalls).toBe(0)

    await user.click(screen.getByRole('button', { name: backlogLabels.completeSprint }))

    const dialog = await screen.findByRole('dialog')
    expect(backlogCalls).toBe(1)
    await user.click(within(dialog).getByRole('button', { name: backlogLabels.completeSprint }))
    await waitFor(() => expect(completed).toBe(SPRINT_ID))
  })
})
