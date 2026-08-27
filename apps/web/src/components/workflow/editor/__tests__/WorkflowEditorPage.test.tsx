// 워크플로우 목록 모드 편집기 인수 테스트 — FR-WF-04 D6 (이름 수정 · 상태 추가/제거/순서 · 전환 이름)
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
import { toast } from 'sonner'
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

    // 성공했으므로 확인 창도 닫힌다. 아래 실패 판정과 짝이다 — 이것만 재면 **항상 닫는**
    // 구현이, 저것만 재면 **아무 때도 안 닫는** 구현이 통과한다.
    expect(
      screen.queryByRole('dialog', { name: `${L.statusPanel.remove} Blocked` }),
    ).not.toBeInTheDocument()
  })

  /**
   * 제거에 실패하면 확인 창이 **열린 채 남는다.**
   *
   * 종전에는 공용 `ConfirmDialog` 가 `onConfirm()` 직후 스스로 닫아 이 상태가 **구조적으로
   * 불가능**했다(부채 139). 사용자는 창이 사라진 뒤에야 무엇이 안 됐는지 다른 곳에서 찾아야 했다.
   * 사유 자체는 이 화면이 toast 로 알린다 — toast 는 모달 오버레이 위에 뜬다.
   */
  it('상태 제거에 실패하면 확인 창이 열린 채 남는다', async () => {
    renderEditor()
    await waitForLoaded()

    await userEvent.click(screen.getByRole('button', { name: L.statusPanel.add }))
    const picker = await screen.findByRole('dialog', { name: L.dialog.statusPicker })
    await userEvent.click(within(picker).getByRole('combobox', { name: L.dialog.statusPicker }))
    await userEvent.click(await screen.findByRole('option', { name: 'Blocked' }))
    await userEvent.click(within(picker).getByRole('button', { name: L.statusPanel.add }))
    await waitFor(() => expect(screen.getByText('Blocked')).toBeInTheDocument())

    // 추가가 끝난 뒤에 실패를 심는다 — 먼저 심으면 추가 경로까지 함께 죽는다.
    server.use(
      http.delete(
        '/api/v1/workflows/:key/statuses/:statusId',
        () => new HttpResponse(null, { status: 500 }),
      ),
    )

    await userEvent.click(screen.getByRole('button', { name: `${L.statusPanel.remove} Blocked` }))
    const confirm = await screen.findByRole('dialog', { name: `${L.statusPanel.remove} Blocked` })
    const confirmButton = within(confirm).getByRole('button', { name: L.statusPanel.remove })
    await userEvent.click(confirmButton)

    // 실패가 확정될 때까지 기다린다 — 버튼이 다시 눌리는 상태로 돌아오는 것이 그 신호다.
    await waitFor(() => expect(confirmButton).not.toBeDisabled())

    const stillOpen = screen.getByRole('dialog', { name: `${L.statusPanel.remove} Blocked` })
    expect(stillOpen).toBeInTheDocument()

    // ★사유는 toast 가 알린다(훅의 `notifyWorkflowAdminError`) — 그래서 이 화면은 `error` prop 을
    //   쓰지 않는다. 그 설계 근거를 주석으로만 두지 않고 잰다.
    await waitFor(() => expect(toast.error).toHaveBeenCalled())

    // ★목록은 지금 볼 수 없다 — 모달이 열려 있으면 Radix 가 배경에 `aria-hidden` 을 걸어
    //   접근성 트리에서 사라진다. 실패 뒤 사용자가 실제로 하는 행동(취소로 닫기)을 태운 뒤에 본다.
    await userEvent.click(within(stillOpen).getByRole('button', { name: L.dialog.cancel }))
    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: `${L.statusPanel.remove} Blocked` }),
      ).not.toBeInTheDocument()
    })

    // 제거가 실패했으므로 상태는 목록에 그대로 남아 있다.
    const list = screen.getByRole('list', { name: L.statusPanel.list })
    expect(within(list).getByText('Blocked')).toBeInTheDocument()
  })

  it('제거 버튼 이름이 상태마다 다르다 — strict mode 충돌 방지', async () => {
    renderEditor()
    await waitForLoaded()
    const buttons = screen.getAllByRole('button', { name: new RegExp(`^${L.statusPanel.remove} `) })
    const names = buttons.map((b) => b.getAttribute('aria-label') ?? b.textContent)
    expect(new Set(names).size).toBe(names.length)
  })
})

describe('실패와 「없음」을 가른다', () => {
  it('상세 조회가 실패하면 목록 화면의 빈 상태 문구를 띄우지 않는다', async () => {
    // 종전에는 「워크플로우가 없습니다 / 첫 워크플로우를 만들어…」라는 **목록** 문구가
    // 상세 화면에 떴다. 사용자가 「내가 만든 워크플로우가 사라졌다」로 읽는다.
    server.use(
      http.get('/api/v1/workflows/boom', () =>
        HttpResponse.json({ error: { code: 'WORKFLOW_UNAVAILABLE', message: '' } }, { status: 503 }),
      ),
    )
    renderEditor('boom')
    expect(await screen.findByText(L.editor.loadFailed)).toBeInTheDocument()
    expect(screen.queryByText(L.list.emptyTitle)).not.toBeInTheDocument()
  })

  it('카탈로그가 실패하면 「편성된 상태가 없습니다」라고 거짓말하지 않는다', async () => {
    // 조인이 통째로 비어 상태가 0개로 보인다. 「상태가 없다」와 「못 그린다」는 다른 사실이다.
    //
    // ★ 제목이 지목한 문자열의 **부재**를 반드시 본다. 처음엔 공지가 뜨는 것만 단언했는데,
    //   그때 패널이 공지 바로 아래 함께 렌더돼 거짓 문구가 화면에 그대로 남아 있었다.
    //   제목이 말하는 것을 단언이 안 보면 그 판정은 아무것도 증명하지 않는다.
    server.use(http.get('/api/v1/statuses', () => new HttpResponse(null, { status: 500 })))
    renderEditor()
    expect(await screen.findByText(L.editor.catalogFailed)).toBeInTheDocument()
    expect(screen.queryByText(L.statusPanel.empty)).not.toBeInTheDocument()
  })

  it('카탈로그가 실패하면 상태 패널을 아예 그리지 않는다 — 부분 목록을 보내면 400 이다', async () => {
    server.use(http.get('/api/v1/statuses', () => new HttpResponse(null, { status: 500 })))
    renderEditor()
    await screen.findByText(L.editor.catalogFailed)
    expect(screen.queryByRole('list', { name: L.statusPanel.list })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: L.statusPanel.add })).not.toBeInTheDocument()
  })

  it('일부 상태만 카탈로그에 없으면 나머지는 그대로 보인다 — 순서 변경만 잠긴다', async () => {
    // ★ 없애려던 거짓말은 조인이 **통째로 비었을 때만** 뜬다. `droppedKeys` 가 하나 있다고
    //   패널을 안 그리면 카탈로그가 멀쩡한데도 나머지 상태가 화면에서 사라지고 추가·제거
    //   버튼까지 없어진다 — 수정이 근거보다 넓었던 자리다.
    server.use(
      http.get('/api/v1/workflows/partial', () =>
        HttpResponse.json({
          data: {
            key: 'partial',
            name: '일부만 아는 워크플로우',
            description: '',
            states: [
              { key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 },
              { key: 'ghost', name: '유령 상태', category: 'TODO', displayOrder: 2 },
            ],
            transitions: [],
          },
        }),
      ),
    )
    renderEditor('partial')
    await waitForLoaded()

    // 공지는 뜬다
    expect(screen.getByText(L.editor.unknownStatuses)).toBeInTheDocument()
    // 그러나 아는 상태는 그대로 보이고
    const list = screen.getByRole('list', { name: L.statusPanel.list })
    expect(within(list).getByText('Open')).toBeInTheDocument()
    // 추가·제거는 여전히 쓸 수 있다
    expect(screen.getByRole('button', { name: L.statusPanel.add })).toBeEnabled()
    expect(screen.getByRole('button', { name: `${L.statusPanel.remove} Open` })).toBeEnabled()
    // 잠기는 것은 순서 변경뿐이다
    expect(screen.getByRole('button', { name: `${L.statusPanel.dragHandle} Open` })).toBeDisabled()
  })

  it('아는 상태가 하나도 없으면 패널을 그리지 않는다 — 「없다」는 거짓말을 피한다', async () => {
    server.use(
      http.get('/api/v1/workflows/allghost', () =>
        HttpResponse.json({
          data: {
            key: 'allghost',
            name: '전부 모르는 워크플로우',
            description: '',
            states: [{ key: 'ghost', name: '유령 상태', category: 'TODO', displayOrder: 1 }],
            transitions: [],
          },
        }),
      ),
    )
    renderEditor('allghost')
    await waitForLoaded()
    expect(screen.getByText(L.editor.unknownStatuses)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: L.statusPanel.list })).not.toBeInTheDocument()
    expect(screen.queryByText(L.statusPanel.empty)).not.toBeInTheDocument()
  })

  it('없는 워크플로우는 404 전용 문구를 쓴다 — 「불러오지 못했습니다」와 구별된다', async () => {
    server.use(
      http.get('/api/v1/workflows/nope', () =>
        HttpResponse.json({ error: { code: 'WORKFLOW_NOT_FOUND', message: '' } }, { status: 404 }),
      ),
    )
    renderEditor('nope')
    expect(await screen.findByText(L.editor.notFound)).toBeInTheDocument()
    expect(screen.queryByText(L.editor.loadFailed)).not.toBeInTheDocument()
  })

  it('실패 화면에 내부 예외 문구를 그리지 않는다', async () => {
    // `ApiError.message` 는 `API ${status}` 다. 그대로 그리면 「API 503」이 뜬다.
    server.use(http.get('/api/v1/workflows/boom2', () => new HttpResponse(null, { status: 503 })))
    renderEditor('boom2')
    await screen.findByText(L.editor.loadFailed)
    expect(screen.queryByText(/API 5\d\d/)).not.toBeInTheDocument()
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
    const nameInput = within(dialog).getByRole('textbox', { name: L.transitionForm.name })
    await userEvent.clear(nameInput)
    await userEvent.type(nameInput, '작업 시작하기')
    await userEvent.click(within(dialog).getByRole('button', { name: L.transitionForm.submit }))

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

  /** 전환 탭을 열고 `Start Work` 의 삭제 확인 창을 띄운다. */
  async function openTransitionDeleteConfirm(): Promise<HTMLElement> {
    renderEditor()
    await waitForLoaded()
    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))
    await screen.findByRole('list', { name: L.transitionPanel.list })
    await userEvent.click(
      screen.getByRole('button', { name: `${L.transitionPanel.remove} Start Work` }),
    )
    return screen.findByRole('dialog', { name: `${L.transitionPanel.remove} Start Work` })
  }

  /**
   * 전환 삭제의 확인 창 짝.
   *
   * ★이 두 판정이 없던 동안 `WorkflowEditorPage.tsx` 의 전환 삭제 `onSuccess` 를 통째로 지워도
   * **전량 599 파일 9896 테스트가 초록**이었다(2026-08-26 리뷰 실측). 그 화면의 확인 창이 영영
   * 안 닫히는데 아무도 몰랐다는 뜻이다. e2e 도 이 경로를 만지지 않는다.
   *
   * 짝이어야 하는 이유는 상태 제거 쪽과 같다 — 「성공하면 닫힌다」만 재면 항상 닫는 구현이,
   * 「실패하면 남는다」만 재면 아무 때도 안 닫는 구현이 통과한다.
   */
  it('전환 삭제에 성공하면 확인 창이 닫힌다', async () => {
    const confirm = await openTransitionDeleteConfirm()
    await userEvent.click(
      within(confirm).getByRole('button', { name: L.transitionPanel.remove }),
    )

    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: `${L.transitionPanel.remove} Start Work` }),
      ).not.toBeInTheDocument()
    })
  })

  it('전환 삭제에 실패하면 확인 창이 열린 채 남는다', async () => {
    server.use(
      http.delete(
        '/api/v1/workflows/:key/transitions/:transitionId',
        () => new HttpResponse(null, { status: 500 }),
      ),
    )

    const confirm = await openTransitionDeleteConfirm()
    const confirmButton = within(confirm).getByRole('button', { name: L.transitionPanel.remove })
    await userEvent.click(confirmButton)

    // 실패가 확정될 때까지 기다린다 — 버튼이 다시 눌리는 상태로 돌아오는 것이 그 신호다.
    await waitFor(() => expect(confirmButton).not.toBeDisabled())

    expect(
      screen.getByRole('dialog', { name: `${L.transitionPanel.remove} Start Work` }),
    ).toBeInTheDocument()
    await waitFor(() => expect(toast.error).toHaveBeenCalled())
  })
})
