// 보드 컬럼 인라인 생성 테스트 — 그 컬럼 상태로 전환 · 활성 스프린트 배정 · 부분 실패 경고 (JB-B-1 · 결정 D2)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import type { BoardColumn } from '@/api/boards'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { createdIssueFixture } from '@/mocks/issue-handlers'
import { storyIssueTypeFixture } from '@/mocks/issue-type-fixtures'
import { issueCreateStrings } from '@/i18n/ko'
import { boardLabels } from '@/i18n/board-labels'
import { BoardColumnInlineCreate } from './BoardColumnInlineCreate'

vi.mock('sonner', () => ({ toast: { warning: vi.fn(), error: vi.fn() } }))

const SPRINT_ID = '00000000-0000-4000-8000-0000000000a1'
const inProgress: Pick<BoardColumn, 'name' | 'states'> = {
  name: '진행 중',
  states: [
    { key: 'in_progress', name: '진행 중', category: 'IN_PROGRESS' },
    { key: 'review', name: '리뷰', category: 'IN_PROGRESS' },
  ],
}

let created: unknown[]
let transitions: unknown[]
let sprintAdds: unknown[]

beforeEach(() => {
  vi.mocked(toast.warning).mockClear()
  created = []
  transitions = []
  sprintAdds = []
  server.use(
    ...issueTypeHandlers,
    http.post('/api/v1/issues', async ({ request }) => {
      created.push(await request.json())
      return HttpResponse.json({ data: { ...createdIssueFixture, currentStateKey: 'open', version: 3 } }, { status: 201 })
    }),
    http.post(`/api/v1/issues/${createdIssueFixture.key}/transition`, async ({ request }) => {
      transitions.push(await request.json())
      return HttpResponse.json({ data: { ...createdIssueFixture, currentStateKey: 'in_progress', version: 4 } })
    }),
    http.post(`/api/v1/sprints/${SPRINT_ID}/issues`, async ({ request }) => {
      sprintAdds.push(await request.json())
      return new HttpResponse(null, { status: 201 })
    }),
  )
})

function renderRow(column = inProgress, sprintId: string | null = null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <BoardColumnInlineCreate projectKey="ATLAS" column={column} sprintId={sprintId} />
    </QueryClientProvider>,
  )
}

async function typeAndEnter(text: string) {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: boardLabels.column.inlineCreate(inProgress.name) }))
  // 유형 목록이 도착해 기본값(스토리)이 잡힌 뒤 입력한다
  await waitFor(() =>
    expect(screen.getByRole('combobox', { name: issueCreateStrings.inlineTypeLabel })).toHaveValue(
      String(storyIssueTypeFixture.id),
    ),
  )
  await user.type(screen.getByRole('textbox', { name: issueCreateStrings.inlineSummaryLabel }), `${text}{Enter}`)
}

describe('BoardColumnInlineCreate', () => {
  it('Enter 로 스토리를 만들고, 초기 상태가 컬럼 밖이면 컬럼 첫 상태로 전환한다 — 입력 행은 열린 채 비운다', async () => {
    renderRow()
    await typeAndEnter('새 카드')

    await waitFor(() => expect(transitions).toHaveLength(1))
    expect(created[0]).toMatchObject({ projectKey: 'ATLAS', summary: '새 카드', typeId: storyIssueTypeFixture.id })
    expect(transitions[0]).toEqual({ toStatusKey: 'in_progress', expectedVersion: 3 })
    expect(sprintAdds).toHaveLength(0)
    expect(screen.getByRole('textbox', { name: issueCreateStrings.inlineSummaryLabel })).toHaveValue('')
  })

  it('초기 상태가 이미 컬럼에 매핑돼 있으면 전환하지 않는다', async () => {
    renderRow({ name: '할 일', states: [{ key: 'open', name: '할 일', category: 'TODO' }] })
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: boardLabels.column.inlineCreate('할 일') }))
    await user.type(screen.getByRole('textbox', { name: issueCreateStrings.inlineSummaryLabel }), '첫 열{Enter}')

    await waitFor(() => expect(created).toHaveLength(1))
    expect(transitions).toHaveLength(0)
  })

  it('스크럼 보드면 활성 스프린트에 넣는다', async () => {
    renderRow(inProgress, SPRINT_ID)
    await typeAndEnter('스프린트 카드')

    await waitFor(() => expect(sprintAdds).toEqual([{ issueKey: createdIssueFixture.key }]))
  })

  it('전환이 막히면 이슈 키와 함께 경고한다(생성 실패로 말하지 않는다)', async () => {
    server.use(
      http.post(`/api/v1/issues/${createdIssueFixture.key}/transition`, () =>
        HttpResponse.json({ error: { code: 'TRANSITION_NOT_ALLOWED' } }, { status: 409 }),
      ),
    )
    renderRow()
    await typeAndEnter('막힌 카드')

    await waitFor(() =>
      expect(toast.warning).toHaveBeenCalledWith(issueCreateStrings.columnMoveFailed(createdIssueFixture.key, '진행 중')),
    )
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('유형 목록에 에픽 · 하위 작업이 없다', async () => {
    renderRow()
    await userEvent.setup().click(screen.getByRole('button', { name: boardLabels.column.inlineCreate(inProgress.name) }))
    const select = screen.getByRole('combobox', { name: issueCreateStrings.inlineTypeLabel })
    await waitFor(() => expect(select).toHaveValue(String(storyIssueTypeFixture.id)))
    const names = Array.from(select.querySelectorAll('option')).map((o) => o.textContent)
    expect(names).not.toContain('에픽')
    expect(names).not.toContain('하위 작업')
  })

  it('Esc 로 닫는다', async () => {
    renderRow()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: boardLabels.column.inlineCreate(inProgress.name) }))
    await user.type(screen.getByRole('textbox', { name: issueCreateStrings.inlineSummaryLabel }), '취소{Escape}')

    expect(screen.queryByRole('textbox', { name: issueCreateStrings.inlineSummaryLabel })).toBeNull()
    expect(created).toHaveLength(0)
  })
})
