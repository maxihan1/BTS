// MappingTable 컴포넌트 단위 테스트 — 매핑 렌더/추가/삭제/default 강조/필터링
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { bulkOperationHandlers, registerStatusMigration } from '@/mocks/bulk-operation-handlers'
import { schemeHandlers } from '@/mocks/scheme-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { softwareDefaultSchemeFixture } from '@/mocks/scheme-fixtures'
import { MappingTable } from '@/components/admin/MappingTable'

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

function renderTable(schemeKey = 'software-default-scheme') {
  server.use(...schemeHandlers, ...issueTypeHandlers, ...workflowHandlers)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const { mappings } = softwareDefaultSchemeFixture
  return render(
    <QueryClientProvider client={client}>
      <MappingTable schemeKey={schemeKey} mappings={mappings} />
    </QueryClientProvider>,
  )
}

describe('MappingTable', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /**
   * MT-1. 테이블이 마운트되면 헤더 4개가 렌더된다.
   */
  it('MT-1: 테이블 헤더 4개가 렌더된다', () => {
    renderTable()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByText('이슈 타입')).toBeInTheDocument()
    expect(screen.getByText('워크플로우')).toBeInTheDocument()
    expect(screen.getByText('기본 여부')).toBeInTheDocument()
    expect(screen.getByText('액션')).toBeInTheDocument()
  })

  /**
   * MT-2. 매핑 데이터가 rows로 렌더된다.
   */
  it('MT-2: 매핑 rows가 렌더된다 (workflowName 포함)', () => {
    renderTable()
    expect(screen.getAllByText('소프트웨어 개발 기본 워크플로우').length).toBeGreaterThan(0)
    expect(screen.getByText('버그 추적 워크플로우')).toBeInTheDocument()
  })

  /**
   * MT-3. default 매핑(isDefault=true) row에 ★ prefix가 표시된다.
   */
  it('MT-3: default 매핑에 ★ 강조가 표시된다', () => {
    renderTable()
    expect(screen.getByText(/★/)).toBeInTheDocument()
  })

  /**
   * MT-4. 「+ 매핑 추가」 row (issueType select + workflow select + 추가 버튼)가 렌더된다.
   */
  it('MT-4: 매핑 추가 행이 렌더된다', () => {
    renderTable()
    expect(screen.getByRole('button', { name: /추가/ })).toBeInTheDocument()
  })

  /**
   * MT-5. issueType select에서 이미 매핑된 이슈 타입은 제외된다 (G1 보강).
   * software-default-scheme 에는 bug/story/task/epic + default 매핑이 존재.
   * 따라서 issueType select에 남은 옵션은 subtask + 기본값(기본값은 default 매핑 존재 시 제외).
   */
  it('MT-5: 이슈 타입 select에서 이미 매핑된 타입이 제외된다', async () => {
    renderTable()

    await waitFor(() =>
      expect(screen.getByTestId('issue-type-select')).toBeInTheDocument(),
    )

    // 이미 매핑된 타입(bug, story, task, epic, default)이 select에 없어야 함
    // subtask만 남아야 함 (default 매핑도 이미 존재하므로 제외)
    // trigger 클릭으로 옵션 확인
    const trigger = screen.getByTestId('issue-type-select')
    fireEvent.click(trigger)

    await waitFor(() => {
      // 남은 옵션: 하위 작업(subtask)
      expect(screen.queryByTestId('option-bug')).not.toBeInTheDocument()
      expect(screen.queryByTestId('option-story')).not.toBeInTheDocument()
    })
  })

  /**
   * MT-6. 삭제 버튼 클릭 시 확인 dialog가 표시된다.
   */
  it('MT-6: 삭제 버튼 클릭 시 확인 dialog가 노출된다', async () => {
    renderTable()

    const deleteButtons = await waitFor(() =>
      screen.getAllByRole('button', { name: /삭제/ }),
    )
    const firstDeleteBtn = deleteButtons[0]
    expect(firstDeleteBtn).toBeDefined()
    fireEvent.click(firstDeleteBtn!)

    // 확인 dialog가 열려야 함
    await waitFor(() => {
      const confirmBtn = screen.queryByRole('button', { name: /확인/ })
      expect(confirmBtn).toBeInTheDocument()
    })
  })

  /**
   * MT-7. 추가 버튼 클릭 시 useAddMapping.mutate가 호출된다 (MSW 성공 응답).
   * issueType select와 workflow select에 값 선택 후 추가.
   */
  it('MT-7: 이슈 타입/워크플로우 선택 후 추가 버튼 클릭 시 mutate가 호출된다', async () => {
    // custom-scheme-beta: subtask만 남음 (default 매핑만 있음 → bug/story/task/epic 모두 미매핑)
    server.use(...schemeHandlers, ...issueTypeHandlers, ...workflowHandlers)
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })

    const customBetaMappings = [
      {
        id: 60,
        issueTypeKey: null as string | null,
        issueTypeName: null as string | null,
        workflowKey: 'simple',
        workflowName: '단순 워크플로우 (TODO/DOING/DONE)',
        isDefault: true,
      },
    ]

    render(
      <QueryClientProvider client={client}>
        <MappingTable schemeKey="custom-scheme-beta" mappings={customBetaMappings} />
      </QueryClientProvider>,
    )

    // workflow select에 값 지정
    const workflowSelect = await waitFor(() => screen.getByTestId('workflow-select'))
    // 워크플로우 목록이 와야 그 값이 select 에 박힌다 — 먼저 바꾸면 조용히 빈 값으로 남는다.
    await waitFor(() => expect(within(workflowSelect).getAllByRole('option').length).toBeGreaterThan(1))
    fireEvent.change(workflowSelect, { target: { value: 'software-default' } })

    // issueType select에 bug 지정
    const issueTypeSelect = await waitFor(() => screen.getByTestId('issue-type-select'))
    fireEvent.change(issueTypeSelect, { target: { value: 'bug' } })

    const addBtn = screen.getByRole('button', { name: /추가/ })
    fireEvent.click(addBtn)

    // 초안에 쌓인다 — 서버에는 아직 안 갔고 「게시」 배너가 뜬다(R11 · 결정 D4)
    expect(await screen.findByText('게시하지 않은 변경이 있습니다. 게시해야 이슈에 반영됩니다.')).toBeInTheDocument()
    expect(screen.getAllByText('소프트웨어 개발 기본 워크플로우').length).toBeGreaterThan(0)
  })

  /**
   * MT-8. 삭제는 초안에만 쌓이고, 「게시」가 남은 매핑 전체를 한 번에 보낸다 (R11 A1).
   */
  it('MT-8: 삭제 후 게시하면 남은 매핑 전체를 publish 로 보낸다', async () => {
    let body: { mappings: Array<{ issueTypeKey: string | null; workflowKey: string }> } | undefined
    renderTable()
    server.use(
      http.post('/api/v1/workflow-schemes/:schemeKey/publish', async ({ request }) => {
        body = (await request.json()) as typeof body
        return HttpResponse.json({ data: { bulkOperationIds: [] } })
      }),
    )
    const before = softwareDefaultSchemeFixture.mappings.length

    fireEvent.click(screen.getByRole('button', { name: '매핑 삭제 (버그)' }))
    fireEvent.click(screen.getByRole('button', { name: /확인/ }))
    fireEvent.click(await screen.findByRole('button', { name: '게시' }))

    await waitFor(() => expect(body?.mappings).toHaveLength(before - 1))
    expect(body?.mappings.some((m) => m.issueTypeKey === 'bug')).toBe(false)
    await waitFor(() => expect(screen.queryByRole('button', { name: '게시' })).not.toBeInTheDocument())
  })

  /**
   * MT-9. 남는 이슈가 있으면 409 → 유형별 상태 매핑(1/2) → 다시 게시 → 진행률(2/2) (R11 B2 · B3).
   */
  it('MT-9: 409 남는 상태면 마법사로 매핑을 골라 다시 게시하고 진행률로 넘어간다', async () => {
    const opId = '00000000-0000-4000-8000-0000000000b1'
    const bodies: Array<{ statusMappings: unknown[] }> = []
    renderTable()
    server.use(
      ...bulkOperationHandlers,
      http.post('/api/v1/workflow-schemes/:schemeKey/publish', async ({ request }) => {
        const b = (await request.json()) as { statusMappings: unknown[] }
        bodies.push(b)
        if (b.statusMappings.length === 0) {
          return HttpResponse.json(
            {
              errorCode: 'SCHEME_CHANGE_STRANDS_ISSUES',
              detail: 'review 3건',
              strandedByType: [{ issueTypeId: 1, workflowKey: 'software-default', statuses: { review: 3 } }],
            },
            { status: 409 },
          )
        }
        return HttpResponse.json({ data: { bulkOperationIds: [opId] } })
      }),
    )
    registerStatusMigration(opId, { issueKeys: ['ATLAS-1'], mappings: { review: 'done' }, projectKeys: [], failKeys: new Set() }, () => {})

    fireEvent.click(screen.getByRole('button', { name: '매핑 삭제 (버그)' }))
    fireEvent.click(screen.getByRole('button', { name: /확인/ }))
    fireEvent.click(await screen.findByRole('button', { name: '게시' }))

    expect(await screen.findByText('상태 매핑 (1/2)')).toBeInTheDocument()
    const next = screen.getByRole('button', { name: '다음' })
    expect(next).toBeDisabled()
    const target = await screen.findByRole('combobox', { name: '버그 · review 이슈를 옮길 상태' })
    await waitFor(() => expect(within(target).getByRole('option', { name: 'Done' })).toBeInTheDocument())
    fireEvent.change(target, { target: { value: 'done' } })
    fireEvent.click(next)

    expect(await screen.findByText('이슈 옮기는 중 (2/2)')).toBeInTheDocument()
    expect(bodies[1]?.statusMappings).toEqual([{ issueTypeId: 1, fromStatusKey: 'review', toStatusKey: 'done' }])
  })
})
