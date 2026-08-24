// 워크플로우 목록 모드 편집기 인수 테스트 — FR-WF-04 D6 (이름 수정 · 상태 추가/제거/순서 · 전환 이름)
import React from 'react'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { workflowAdminHandlers } from '@/mocks/workflow-admin-handlers'
import { resetWorkflowAdminStore } from '@/mocks/workflow-admin-fixtures'
import { workflowEditorLabels as L } from '@/i18n/workflow-editor-labels'
import { WorkflowEditorPage } from '../WorkflowEditorPage'

// jsdom 은 scrollIntoView 를 구현하지 않는다 — cmdk 가 부른다 (`ui/command.test.tsx:16` 처방)
if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
  window.HTMLElement.prototype.scrollIntoView = () => {}
}

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

beforeEach(() => {
  resetWorkflowAdminStore()
  server.use(...workflowHandlers, ...workflowAdminHandlers)
})

function renderEditor(workflowKey = 'software-default') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <WorkflowEditorPage workflowKey={workflowKey} />
    </QueryClientProvider>,
  )
}

/** 편집기가 데이터를 받아 그릴 때까지 기다린다 */
async function waitForLoaded() {
  await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
}

describe('편집기 셸', () => {
  it('h1 이 정확히 하나다 (§2 즉사 계약)', async () => {
    renderEditor()
    await waitForLoaded()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('워크플로우 이름을 h1 으로 보여준다', async () => {
    renderEditor()
    await waitForLoaded()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('소프트웨어 개발 기본 워크플로우')
  })

  it('상태 탭과 전환 탭이 있다', async () => {
    renderEditor()
    await waitForLoaded()
    const tabs = screen.getByRole('tablist', { name: L.editor.tabs })
    expect(within(tabs).getByRole('tab', { name: L.editor.statusTab })).toBeInTheDocument()
    expect(within(tabs).getByRole('tab', { name: L.editor.transitionTab })).toBeInTheDocument()
  })
})

describe('D6 — 이름 수정이 저장된다', () => {
  it('이름을 고치고 저장하면 서버 값이 바뀐다', async () => {
    renderEditor()
    await waitForLoaded()

    const nameInput = screen.getByRole('textbox', { name: L.editor.nameField })
    await userEvent.clear(nameInput)
    await userEvent.type(nameInput, '고친 워크플로우')
    await userEvent.click(screen.getByRole('button', { name: L.editor.save }))

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('고친 워크플로우'),
    )
  })
})

describe('D6 — 상태 추가·제거', () => {
  it('편성된 상태를 displayOrder 순서로 보여준다', async () => {
    renderEditor()
    await waitForLoaded()
    const list = screen.getByRole('list', { name: L.statusPanel.list })
    const names = within(list).getAllByRole('listitem').map((li) => li.textContent ?? '')
    expect(names[0]).toContain('Open')
    expect(names[names.length - 1]).toContain('Closed')
  })

  it('states 배열 순서가 displayOrder 와 어긋나도 displayOrder 를 따른다', async () => {
    // ★ 픽스처는 states 를 이미 displayOrder 순서로 담고 있어, 위 테스트만으로는 정렬을
    // 지워도 red 가 안 난다(뮤테이션 M3 실측 — 가짜 그린이었다). 배열 순서를 일부러
    // 뒤집은 응답을 태워야 판정이 **정렬 로직**을 본다.
    server.use(
      http.get('/api/v1/workflows/shuffled', () =>
        HttpResponse.json({
          data: {
            key: 'shuffled',
            name: '순서 뒤섞인 워크플로우',
            description: '',
            states: [
              { key: 'closed', name: 'Closed', category: 'DONE', displayOrder: 3 },
              { key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 },
              { key: 'done', name: 'Done', category: 'DONE', displayOrder: 2 },
            ],
            transitions: [],
          },
        }),
      ),
    )
    renderEditor('shuffled')
    await waitForLoaded()

    const list = screen.getByRole('list', { name: L.statusPanel.list })
    const order = within(list)
      .getAllByRole('listitem')
      .map((li) => li.textContent ?? '')
    expect(order[0]).toContain('Open')
    expect(order[1]).toContain('Done')
    expect(order[2]).toContain('Closed')
  })

  it('상태를 추가하면 목록에 나타난다', async () => {
    renderEditor()
    await waitForLoaded()

    await userEvent.click(screen.getByRole('button', { name: L.statusPanel.add }))
    const dialog = await screen.findByRole('dialog', { name: L.dialog.statusPicker })
    await userEvent.click(within(dialog).getByRole('combobox', { name: L.dialog.statusPicker }))
    await userEvent.click(await screen.findByRole('option', { name: 'Blocked' }))
    await userEvent.click(within(dialog).getByRole('button', { name: L.statusPanel.add }))

    await waitFor(() => {
      const list = screen.getByRole('list', { name: L.statusPanel.list })
      expect(within(list).getByText('Blocked')).toBeInTheDocument()
    })
  })

  it('전환이 안 걸린 상태를 빼면 목록에서 사라진다', async () => {
    renderEditor()
    await waitForLoaded()

    // 픽스처 상태는 전부 전환이 가리키므로 먼저 넣고 뺀다 — 성공 경로를 태우는 유일한 순서다.
    await userEvent.click(screen.getByRole('button', { name: L.statusPanel.add }))
    const picker = await screen.findByRole('dialog', { name: L.dialog.statusPicker })
    await userEvent.click(within(picker).getByRole('combobox', { name: L.dialog.statusPicker }))
    await userEvent.click(await screen.findByRole('option', { name: 'Blocked' }))
    await userEvent.click(within(picker).getByRole('button', { name: L.statusPanel.add }))
    await waitFor(() => expect(screen.getByText('Blocked')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: `${L.statusPanel.remove} Blocked` }))
    const confirm = await screen.findByRole('dialog', { name: `${L.statusPanel.remove} Blocked` })
    await userEvent.click(within(confirm).getByRole('button', { name: L.statusPanel.remove }))

    await waitFor(() => {
      const list = screen.getByRole('list', { name: L.statusPanel.list })
      expect(within(list).queryByText('Blocked')).not.toBeInTheDocument()
    })
  })

  it('제거 버튼 이름이 상태마다 다르다 — strict mode 충돌 방지', async () => {
    renderEditor()
    await waitForLoaded()
    const buttons = screen.getAllByRole('button', { name: new RegExp(`^${L.statusPanel.remove} `) })
    const names = buttons.map((b) => b.getAttribute('aria-label') ?? b.textContent)
    expect(new Set(names).size).toBe(names.length)
  })
})

describe('D6 — 전환 이름', () => {
  it('전환 탭에 전환 이름이 보인다', async () => {
    renderEditor()
    await waitForLoaded()
    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))
    const list = await screen.findByRole('list', { name: L.transitionPanel.list })
    expect(within(list).getByText('Start Work')).toBeInTheDocument()
  })

  it('전환 이름을 고치면 목록이 새 이름을 보여준다', async () => {
    renderEditor()
    await waitForLoaded()
    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))
    await screen.findByRole('list', { name: L.transitionPanel.list })

    await userEvent.click(screen.getByRole('button', { name: `${L.transitionPanel.edit} Start Work` }))
    const dialog = await screen.findByRole('dialog', { name: L.dialog.transitionEdit })
    const nameInput = within(dialog).getByRole('textbox', { name: L.transitionPanel.edit })
    await userEvent.clear(nameInput)
    await userEvent.type(nameInput, '작업 시작하기')
    await userEvent.click(within(dialog).getByRole('button', { name: L.dialog.confirm }))

    await waitFor(() => {
      const list = screen.getByRole('list', { name: L.transitionPanel.list })
      expect(within(list).getByText('작업 시작하기')).toBeInTheDocument()
    })
  })

  it('전역·최초 전환에 종류 배지가 붙는다 — 출발 상태 없음을 사용자가 안다', async () => {
    renderEditor()
    await waitForLoaded()
    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))
    const list = await screen.findByRole('list', { name: L.transitionPanel.list })
    expect(within(list).getByText(L.transitionPanel.kindInitial)).toBeInTheDocument()
  })
})
