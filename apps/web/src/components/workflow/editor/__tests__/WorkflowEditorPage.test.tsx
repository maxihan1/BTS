// 워크플로우 초안 편집기 인수 테스트 — FR-WF-07 D6 (편집 ≠ 배포 · 캐스케이드 · 발행 흐름)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { workflowAdminHandlers } from '@/mocks/workflow-admin-handlers'
import { workflowDraftHandlers } from '@/mocks/workflow-draft-handlers'
import { resetWorkflowAdminStore } from '@/mocks/workflow-admin-fixtures'
import { resetWorkflowDraftStore, pendingIssueStore } from '@/mocks/workflow-draft-fixtures'
import { workflowEditorLabels as L } from '@/i18n/workflow-editor-labels'
import { workflowPublishLabels as P } from '@/i18n/workflow-publish-labels'
import { fetchWorkflow } from '@/api/workflows'
import { WorkflowEditorPage } from '../WorkflowEditorPage'

// jsdom 은 scrollIntoView 를 구현하지 않는다 — cmdk 가 부른다 (`ui/command.test.tsx:16` 처방)
if (typeof window.HTMLElement.prototype.scrollIntoView !== 'function') {
  window.HTMLElement.prototype.scrollIntoView = () => {}
}

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

beforeEach(() => {
  resetWorkflowAdminStore()
  resetWorkflowDraftStore()
  server.use(...workflowHandlers, ...workflowAdminHandlers, ...workflowDraftHandlers)
})

function renderEditor(workflowKey = 'software-default') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <WorkflowEditorPage workflowKey={workflowKey} />
    </QueryClientProvider>,
  )
}

/** 초안이 로드돼 상태 목록이 그려질 때까지 기다린다 */
async function waitForLoaded() {
  await waitFor(() => {
    expect(screen.getByRole('list', { name: L.statusPanel.list })).toBeInTheDocument()
  })
}

/** 상태 목록의 항목 이름들 */
function statusNames(): string[] {
  return within(screen.getByRole('list', { name: L.statusPanel.list }))
    .getAllByRole('listitem')
    .map((li) => li.textContent ?? '')
}

describe('편집기 셸', () => {
  it('h1 이 정확히 하나다 (§2 즉사 계약)', async () => {
    renderEditor()
    await waitForLoaded()

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('상태 탭과 전환 탭이 있다', async () => {
    renderEditor()
    await waitForLoaded()

    const tabs = screen.getByRole('tablist', { name: L.editor.tabs })
    expect(within(tabs).getByRole('tab', { name: L.editor.statusTab })).toBeInTheDocument()
    expect(within(tabs).getByRole('tab', { name: L.editor.transitionTab })).toBeInTheDocument()
  })

  it('없는 워크플로우는 404 전용 문구를 쓴다', async () => {
    renderEditor('does-not-exist')

    await waitFor(() => {
      expect(screen.getByText(L.editor.notFound)).toBeInTheDocument()
    })
  })

  it('초안 표시줄이 「발행해야 반영된다」를 항상 띄운다', async () => {
    renderEditor()
    await waitForLoaded()

    expect(screen.getByText(P.draft.unpublishedNotice)).toBeInTheDocument()
  })
})

describe('★ 편집은 배포가 아니다', () => {
  it('이름을 고쳐도 발행 전에는 서버 값이 그대로다', async () => {
    const before = await fetchWorkflow('software-default')
    renderEditor()
    await waitForLoaded()

    await userEvent.clear(screen.getByRole('textbox', { name: L.editor.nameField }))
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '고친 이름')

    // 자동저장이 돌아도 정규 정의는 안 바뀐다 — 초안에만 담긴다.
    await waitFor(() => {
      expect(screen.getByText(P.draft.saved)).toBeInTheDocument()
    })
    expect((await fetchWorkflow('software-default')).name).toBe(before.name)
  })

  it('상태를 추가해도 발행 전에는 서버 상태 수가 그대로다', async () => {
    const before = await fetchWorkflow('software-default')
    renderEditor()
    await waitForLoaded()
    const countBefore = statusNames().length

    await userEvent.click(screen.getByRole('button', { name: L.statusPanel.add }))
    const picker = screen.getByRole('dialog', { name: L.dialog.statusPicker })
    await userEvent.click(within(picker).getByRole('combobox', { name: L.dialog.statusPicker }))
    await userEvent.click(screen.getByRole('option', { name: 'Blocked' }))
    await userEvent.click(within(picker).getByRole('button', { name: L.statusPanel.add }))

    // 화면에는 늘었고
    await waitFor(() => {
      expect(statusNames()).toHaveLength(countBefore + 1)
    })
    // 서버는 그대로다
    expect((await fetchWorkflow('software-default')).states).toHaveLength(before.states.length)
  })
})

describe('상태 편집', () => {
  it('편성된 상태를 displayOrder 순서로 보여준다', async () => {
    renderEditor()
    await waitForLoaded()

    expect(statusNames()[0]).toContain('Open')
  })

  it('★ 상태를 빼면 그 상태를 가리키는 전환도 함께 사라진다', async () => {
    // 상태만 빼면 저장이 400 이 되고 그 뒤로 자동저장이 전부 실패한다 — 사용자는 원인을 모른다.
    renderEditor()
    await waitForLoaded()

    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))
    const before = within(screen.getByRole('list', { name: L.transitionPanel.list })).getAllByRole('listitem').length

    await userEvent.click(screen.getByRole('tab', { name: L.editor.statusTab }))
    await userEvent.click(screen.getByRole('button', { name: `${L.statusPanel.remove} In Progress` }))
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`^${L.statusPanel.remove}$`) }))

    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))
    const after = within(screen.getByRole('list', { name: L.transitionPanel.list })).getAllByRole('listitem').length
    expect(after).toBeLessThan(before)
  })

  it('제거 버튼 이름이 상태마다 다르다 — strict mode 충돌 방지', async () => {
    renderEditor()
    await waitForLoaded()

    expect(screen.getByRole('button', { name: `${L.statusPanel.remove} Open` })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: `${L.statusPanel.remove} Done` })).toBeInTheDocument()
  })
})

describe('전환 편집', () => {
  it('전환 탭에 전환 이름이 보인다', async () => {
    renderEditor()
    await waitForLoaded()
    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))

    expect(screen.getByRole('list', { name: L.transitionPanel.list })).toHaveTextContent('Start Work')
  })

  it('전환 이름을 고치면 목록이 새 이름을 보여준다', async () => {
    renderEditor()
    await waitForLoaded()
    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))

    await userEvent.click(screen.getByRole('button', { name: `${L.transitionPanel.edit} Start Work` }))
    const dialog = screen.getByRole('dialog', { name: L.dialog.transitionEdit })
    await userEvent.clear(within(dialog).getByRole('textbox', { name: L.transitionForm.name }))
    await userEvent.type(within(dialog).getByRole('textbox', { name: L.transitionForm.name }), '작업 시작하기')
    await userEvent.click(within(dialog).getByRole('button', { name: L.transitionForm.submit }))

    const list = screen.getByRole('list', { name: L.transitionPanel.list })
    await waitFor(() => {
      expect(list).toHaveTextContent('작업 시작하기')
    })
    expect(list).not.toHaveTextContent('Start Work')
  })

  it('전역·최초 전환에 종류 배지가 붙는다', async () => {
    renderEditor()
    await waitForLoaded()
    await userEvent.click(screen.getByRole('tab', { name: L.editor.transitionTab }))

    expect(screen.getByRole('list', { name: L.transitionPanel.list })).toHaveTextContent(
      L.transitionPanel.kindInitial,
    )
  })
})

describe('발행 흐름', () => {
  it('사라지는 상태가 없으면 발행 다이얼로그가 바로 발행을 준다', async () => {
    renderEditor()
    await waitForLoaded()

    await userEvent.clear(screen.getByRole('textbox', { name: L.editor.nameField }))
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '발행할 이름')
    await userEvent.click(screen.getByRole('button', { name: P.draft.publish }))

    const dialog = await screen.findByRole('dialog', { name: P.publish.dialogTitle })
    expect(within(dialog).getByText(P.publish.noRemoved)).toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: P.publish.confirm }))

    // ★ 발행해야 서버가 바뀐다.
    await waitFor(async () => {
      expect((await fetchWorkflow('software-default')).name).toBe('발행할 이름')
    })
  })

  it('★ 이슈가 남은 상태를 빼면 발행 대신 이관을 안내한다', async () => {
    renderEditor()
    await waitForLoaded()

    await userEvent.click(screen.getByRole('button', { name: `${L.statusPanel.remove} Done` }))
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`^${L.statusPanel.remove}$`) }))
    await userEvent.click(screen.getByRole('button', { name: P.draft.publish }))

    const dialog = await screen.findByRole('dialog', { name: P.publish.dialogTitle })
    // 눌러 보고 409 를 받게 두지 않는다.
    expect(within(dialog).queryByRole('button', { name: P.publish.confirm })).not.toBeInTheDocument()
    expect(within(dialog).getByText(P.publish.boardWarning)).toBeInTheDocument()
  })

  it('이슈가 없으면 그 상태를 빼고 발행할 수 있다', async () => {
    pendingIssueStore.set('done', 0)
    renderEditor()
    await waitForLoaded()

    await userEvent.click(screen.getByRole('button', { name: `${L.statusPanel.remove} Done` }))
    await userEvent.click(screen.getByRole('button', { name: new RegExp(`^${L.statusPanel.remove}$`) }))
    await userEvent.click(screen.getByRole('button', { name: P.draft.publish }))

    const dialog = await screen.findByRole('dialog', { name: P.publish.dialogTitle })
    expect(within(dialog).getByText(P.publish.noIssues)).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: P.publish.confirm }))

    await waitFor(async () => {
      expect((await fetchWorkflow('software-default')).states).not.toContainEqual(
        expect.objectContaining({ key: 'done' }),
      )
    })
  })

  it('★ 화면을 떠나지 않고 연속 두 번 발행할 수 있다', async () => {
    // ★ 발행은 `workflows.version` 을 올리고 초안 행을 지운다. 편집기가 그 새 판을 안 받으면
    //   다음 편집이 **죽은 앵커**로 초안을 만들고, 그 발행은 서버 CAS(`bumpVersionIfMatches`)에
    //   0 rows 로 걸려 영구 409 다 — 화면을 떠났다 와야만 낫는다.
    renderEditor()
    await waitForLoaded()

    const publishOnce = async (name: string) => {
      await userEvent.clear(screen.getByRole('textbox', { name: L.editor.nameField }))
      await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), name)
      await userEvent.click(screen.getByRole('button', { name: P.draft.publish }))
      const dialog = await screen.findByRole('dialog', { name: P.publish.dialogTitle })
      await userEvent.click(within(dialog).getByRole('button', { name: P.publish.confirm }))
      await waitFor(() => {
        expect(screen.queryByRole('dialog', { name: P.publish.dialogTitle })).not.toBeInTheDocument()
      })
    }

    await publishOnce('첫 번째 발행')
    await publishOnce('두 번째 발행')

    // 충돌 배너가 뜨면 두 번째가 막힌 것이다.
    expect(screen.queryByRole('alert', { name: P.conflict.banner })).not.toBeInTheDocument()
    expect((await fetchWorkflow('software-default')).name).toBe('두 번째 발행')
  })

  it('★ 초안 폐기가 앵커를 되살려 다시 발행할 수 있게 한다', async () => {
    // 충돌 배너가 약속하는 유일한 출구다. 폐기 뒤에도 리듀서가 죽은 앵커를 들고 있으면
    // 「초안을 버리고 다시 편집하기」가 새로고침 전까지 거짓말이 된다.
    renderEditor()
    await waitForLoaded()

    await userEvent.clear(screen.getByRole('textbox', { name: L.editor.nameField }))
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '발행할 이름')
    await userEvent.click(screen.getByRole('button', { name: P.draft.publish }))
    let dialog = await screen.findByRole('dialog', { name: P.publish.dialogTitle })
    await userEvent.click(within(dialog).getByRole('button', { name: P.publish.confirm }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: P.publish.dialogTitle })).not.toBeInTheDocument()
    })

    // 발행이 초안 행을 지웠으므로 폐기 버튼은 사라져야 한다 — 누르면 404 다.
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: P.draft.discard })).not.toBeInTheDocument()
    })

    // 그리고 이어서 편집·발행이 된다.
    await userEvent.clear(screen.getByRole('textbox', { name: L.editor.nameField }))
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '이어서 발행')
    await userEvent.click(screen.getByRole('button', { name: P.draft.publish }))
    dialog = await screen.findByRole('dialog', { name: P.publish.dialogTitle })
    await userEvent.click(within(dialog).getByRole('button', { name: P.publish.confirm }))

    await waitFor(async () => {
      expect((await fetchWorkflow('software-default')).name).toBe('이어서 발행')
    })
  })

  it('앵커 충돌은 배너로 남고 폐기만 준다', async () => {
    server.use(
      http.post('/api/v1/workflows/:key/publish', () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_VERSION_CONFLICT', message: '다른 사용자가 먼저 발행했습니다' } },
          { status: 409 },
        ),
      ),
    )
    renderEditor()
    await waitForLoaded()

    await userEvent.clear(screen.getByRole('textbox', { name: L.editor.nameField }))
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '충돌할 이름')
    await userEvent.click(screen.getByRole('button', { name: P.draft.publish }))
    const dialog = await screen.findByRole('dialog', { name: P.publish.dialogTitle })
    await userEvent.click(within(dialog).getByRole('button', { name: P.publish.confirm }))

    // 토스트로 사라지지 않고 배너로 남는다 — 재시도로는 안 풀리기 때문이다.
    const banner = await screen.findByRole('alert', { name: P.conflict.banner })
    expect(within(banner).getByRole('button', { name: P.conflict.action })).toBeInTheDocument()
  })
})

describe('★ 이관 중 새로고침 (G-2)', () => {
  // 이관 진행률을 URL 에 실은 뒤 새로고침하면 편집기는 마법사 없이 다시 뜬다 —
  // `flow.preview` 가 null 이라 발행 다이얼로그가 아예 렌더되지 않기 때문이다. 그 자리에서
  // 진행 상황이 보이지 않으면 사용자는 회색 버튼 셋만 보고 이관이 실패했다고 읽는다.
  const MIGRATION_ID = '3fa85f64-5717-4562-b3fc-2c963f66afa6'

  /** 진행률 폴링 응답 하나를 고정한다. RUNNING 은 종료로 넘어가지 않아 「진행 중」을 잴 수 있다. */
  function serveMigration(status: 'RUNNING' | 'COMPLETED', processed: number, total: number): void {
    server.use(
      http.get('/api/v1/bulk-operations/:id', () =>
        HttpResponse.json({
          data: {
            id: MIGRATION_ID,
            operationType: 'STATUS_MIGRATION',
            status,
            payload: { mappings: { done: 'open' }, projectKeys: [] },
            totalCount: total,
            processedCount: processed,
            succeededCount: processed,
            failedCount: 0,
            items: [],
          },
        }),
      ),
    )
  }

  /** 새로고침으로 돌아온 상태를 만든다 — 훅은 raw `window.location` 을 읽는다(라우터 없음). */
  function enterWithMigrationInUrl(): void {
    window.history.replaceState(null, '', `/?migration=${MIGRATION_ID}`)
  }

  afterEach(() => {
    // 다음 테스트가 죽은 이관 id 를 들고 시작하지 않게 주소를 되돌린다.
    window.history.replaceState(null, '', '/')
  })

  it('★ 마법사가 없어도 진행률이 화면에 남는다', async () => {
    serveMigration('RUNNING', 3, 10)
    enterWithMigrationInUrl()
    renderEditor()
    await waitForLoaded()

    expect(await screen.findByText(P.migration.inProgress)).toBeInTheDocument()
    expect(await screen.findByText('3 / 10')).toBeInTheDocument()
    // 마법사가 그려지지 않는다는 것이 이 표시가 필요한 이유다 — 전제를 함께 잰다.
    expect(screen.queryByRole('dialog', { name: P.publish.dialogTitle })).not.toBeInTheDocument()
  })

  it('잠긴 버튼 옆에 잠긴 이유가 함께 있다', async () => {
    // 이관 폴링이 발행·폐기·복원을 함께 잠근다(G-3). 이유 없이 회색인 버튼만 남기지 않는다.
    serveMigration('RUNNING', 3, 10)
    enterWithMigrationInUrl()
    renderEditor()
    await waitForLoaded()
    await screen.findByText(P.migration.inProgress)

    expect(screen.getByRole('button', { name: P.draft.publish })).toBeDisabled()
    expect(screen.getByText(P.migration.inProgressNotice)).toBeInTheDocument()
  })

  it('이관이 없으면 진행 표시도 없고 발행도 열려 있다', async () => {
    // 위 두 단언이 공허하지 않다는 짝 — 주소에 이관 id 가 없으면 이 줄 자체가 없어야 한다.
    renderEditor()
    await waitForLoaded()

    expect(screen.queryByText(P.migration.inProgress)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: P.draft.publish })).toBeEnabled()
  })

  it('이관이 끝나면 마법사가 사는 발행 다이얼로그로 다시 갈 수 있다', async () => {
    // 진행 중에는 발행이 잠겨 있다가, 폴링이 종료 상태를 받으면 되돌아온다.
    serveMigration('COMPLETED', 10, 10)
    enterWithMigrationInUrl()
    renderEditor()
    await waitForLoaded()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: P.draft.publish })).toBeEnabled()
    })
    expect(screen.queryByText(P.migration.inProgress)).not.toBeInTheDocument()

    await userEvent.clear(screen.getByRole('textbox', { name: L.editor.nameField }))
    await userEvent.type(screen.getByRole('textbox', { name: L.editor.nameField }), '이관 뒤 발행')
    await userEvent.click(screen.getByRole('button', { name: P.draft.publish }))

    expect(await screen.findByRole('dialog', { name: P.publish.dialogTitle })).toBeInTheDocument()
  })
})

describe('기본값 복원', () => {
  it('표준 워크플로우에는 복원 버튼이 있다', async () => {
    renderEditor()
    await waitForLoaded()

    expect(screen.getByRole('button', { name: P.draft.reset })).toBeInTheDocument()
  })

  it('복원은 초안까지만 간다 — 서버 이름은 그대로다', async () => {
    const before = await fetchWorkflow('software-default')
    renderEditor()
    await waitForLoaded()

    await userEvent.click(screen.getByRole('button', { name: P.draft.reset }))
    const dialog = screen.getByRole('dialog', { name: P.reset.dialogTitle })
    await userEvent.click(within(dialog).getByRole('button', { name: P.reset.confirm }))

    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: L.editor.nameField })).toHaveValue(
        `${before.name} (기본값)`,
      )
    })
    expect((await fetchWorkflow('software-default')).name).toBe(before.name)
  })
})
