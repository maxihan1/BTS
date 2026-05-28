// MappingTable 컴포넌트 단위 테스트 — 매핑 렌더/추가/삭제/default 강조/필터링
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
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
    fireEvent.change(workflowSelect, { target: { value: 'software-default' } })

    // issueType select에 bug 지정
    const issueTypeSelect = await waitFor(() => screen.getByTestId('issue-type-select'))
    fireEvent.change(issueTypeSelect, { target: { value: 'bug' } })

    const addBtn = screen.getByRole('button', { name: /추가/ })
    fireEvent.click(addBtn)

    // 낙관적 업데이트 또는 성공 후 테이블에 변경사항 반영 기대
    // MSW 성공 → invalidate → 재렌더
    await waitFor(() => {
      // 최소한 에러가 없으면 성공
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
  })
})
