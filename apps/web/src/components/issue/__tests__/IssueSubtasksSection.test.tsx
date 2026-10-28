// IssueSubtasksSection 단위 테스트 — 목록 · 진행률 · 연속 입력 행 (JA-D-2 · JA-C-4)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { createdIssueFixture } from '@/mocks/issue-handlers'
import { subtaskIssueTypeFixture, taskIssueTypeFixture } from '@/mocks/issue-type-fixtures'
import { IssueSubtasksSection } from '@/components/issue/IssueSubtasksSection'
import { subtaskLabels } from '@/i18n/subtask-labels'

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, params }: { children: React.ReactNode; params: { key: string } }) => (
    <a href={`/issues/${params.key}`}>{children}</a>
  ),
}))

vi.mock('@/hooks/use-workflows', () => ({
  useStatusMetaByKey: () =>
    new Map([
      ['open', { name: '할 일', category: 'TODO' }],
      ['done', { name: '완료', category: 'DONE' }],
    ]),
}))

const PARENT = 'ATLAS-1'

/** 부모 ATLAS-1 의 자식 둘(하나 완료) + 무관한 링크 · 이 이슈의 부모 엣지. */
const graphHandler = http.get(`/api/v1/issues/${PARENT}/graph`, () =>
  HttpResponse.json({
    data: {
      center: PARENT,
      depth: 1,
      truncated: false,
      nodes: [
        { key: PARENT, summary: '부모', statusKey: 'open', depth: 0 },
        { key: 'ATLAS-2', summary: '첫 하위 작업', statusKey: 'done', depth: 1 },
        { key: 'ATLAS-3', summary: '둘째 하위 작업', statusKey: 'open', depth: 1 },
        { key: 'ATLAS-9', summary: '차단 링크', statusKey: 'open', depth: 1 },
        { key: 'ATLAS-0', summary: '내 부모', statusKey: 'open', depth: 1 },
      ],
      edges: [
        { from: PARENT, to: 'ATLAS-2', type: 'PARENT' },
        { from: PARENT, to: 'ATLAS-3', type: 'PARENT' },
        { from: PARENT, to: 'ATLAS-9', type: 'BLOCKS' },
        { from: 'ATLAS-0', to: PARENT, type: 'PARENT' },
      ],
    },
  }),
)

beforeEach(() => {
  server.use(...issueTypeHandlers, graphHandler)
})

function renderSection(typeId: number = taskIssueTypeFixture.id, canCreate = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <IssueSubtasksSection issueKey={PARENT} projectKey="ATLAS" typeId={typeId} canCreate={canCreate} />
    </QueryClientProvider>,
  )
}

describe('IssueSubtasksSection', () => {
  it('부모 → 자식 PARENT 엣지만 하위 작업으로 보이고 완료 비율을 보여 준다', async () => {
    renderSection()

    const section = await screen.findByRole('region', { name: subtaskLabels.sectionTitle })
    expect(await screen.findByText('첫 하위 작업')).toBeInTheDocument()
    expect(screen.getByText('둘째 하위 작업')).toBeInTheDocument()
    expect(screen.queryByText('차단 링크')).toBeNull()
    expect(screen.queryByText('내 부모')).toBeNull()
    expect(section).toHaveTextContent(subtaskLabels.progress(1, 2))
  })

  it('「하위 작업 추가」 → Enter 로 부모 · 하위 작업 유형을 실어 만들고 입력 행은 열린 채 비운다', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json({ data: createdIssueFixture }, { status: 201 })
      }),
    )
    renderSection()

    await user.click(await screen.findByRole('button', { name: subtaskLabels.addButton }))
    const input = screen.getByRole('textbox', { name: subtaskLabels.inputLabel })
    await user.type(input, '새 하위 작업{Enter}')

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toMatchObject({
      projectKey: 'ATLAS',
      summary: '새 하위 작업',
      typeId: subtaskIssueTypeFixture.id,
      parentKey: PARENT,
    })
    await waitFor(() => expect(screen.getByRole('textbox', { name: subtaskLabels.inputLabel })).toHaveValue(''))
    expect(screen.getByRole('textbox', { name: subtaskLabels.inputLabel })).toHaveFocus()
  })

  it('Esc 로 입력 행을 닫는다', async () => {
    const user = userEvent.setup()
    renderSection()

    await user.click(await screen.findByRole('button', { name: subtaskLabels.addButton }))
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('textbox', { name: subtaskLabels.inputLabel })).toBeNull()
  })

  it('하위 작업 이슈에는 섹션이 없다', async () => {
    renderSection(subtaskIssueTypeFixture.id)

    // 자식이 있는 그래프라 유형 도착 전엔 잠깐 보인다 — 도착 뒤 사라지는지 본다.
    await waitFor(() => expect(screen.queryByRole('region', { name: subtaskLabels.sectionTitle })).toBeNull())
  })
})
