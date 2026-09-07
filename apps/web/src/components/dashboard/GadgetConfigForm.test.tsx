// 가젯 설정 폼 — 스코프성 필드가 자유 입력이 아니라 선택기로 뜨는지
//
// ★이 PR 이 고치는 결함이 「보드 UUID 를 손으로 타이핑해야 한다」였다. 그래서 여기서 재는 것은
//   「값이 저장되나」가 아니라 **입력 위젯이 무엇인가**다 — combobox 여야 하고 textbox 면 안 된다.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import type { GadgetCatalogEntry } from '@/api/gadget-catalog'

vi.mock('@/hooks/use-projects', () => ({ useProjects: vi.fn() }))
vi.mock('@/hooks/use-boards', () => ({ useBoards: vi.fn() }))
vi.mock('@/api/saved-filters', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/saved-filters')>()
  return { ...actual, fetchOwnedFilters: vi.fn() }
})

const { useProjects } = await import('@/hooks/use-projects')
const { useBoards } = await import('@/hooks/use-boards')
const { fetchOwnedFilters } = await import('@/api/saved-filters')
const { GadgetConfigForm } = await import('./GadgetConfigForm')
const { gadgetPickerLabels } = await import('@/i18n/dashboard-labels')

const PROJECTS = [
  { key: 'BTS', name: 'Atlas' },
  { key: 'OPS', name: '운영' },
]
const BOARDS_BTS = [
  { boardId: 'b1111111-1111-4111-8111-111111111111', name: 'Atlas 스크럼', boardType: 'SCRUM' },
]
const BOARDS_OPS = [
  { boardId: 'b2222222-2222-4222-8222-222222222222', name: '운영 칸반', boardType: 'KANBAN' },
]

function entryWith(fields: Array<{ key: string; type: string; required: boolean }>): GadgetCatalogEntry {
  return {
    type: 'test_gadget',
    category: 'ISSUE',
    label: 'Test Gadget',
    enabled: true,
    configFields: fields,
    requireAtLeastOne: [],
  } as unknown as GadgetCatalogEntry
}

function wrapper({ children }: { readonly children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return createElement(QueryClientProvider, { client }, children)
}

function renderForm(fields: Array<{ key: string; type: string; required: boolean }>) {
  return render(
    <GadgetConfigForm entry={entryWith(fields)} onAdd={vi.fn()} onBack={vi.fn()} />,
    { wrapper },
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(useProjects).mockReturnValue({
    data: PROJECTS,
    isPending: false,
  } as unknown as ReturnType<typeof useProjects>)
  vi.mocked(useBoards).mockReturnValue({
    data: [],
    isPending: false,
  } as unknown as ReturnType<typeof useBoards>)
  vi.mocked(fetchOwnedFilters).mockResolvedValue([])
})

describe('GadgetConfigForm — 스코프성 필드는 선택기다', () => {
  it('★projectKey 는 텍스트 입력이 아니라 드롭다운이다', () => {
    renderForm([{ key: 'projectKey', type: 'STRING', required: true }])

    // combobox 로 뜬다 = <select>. textbox 면 이 PR 이 고치려던 상태 그대로다.
    expect(screen.getByRole('combobox', { name: /project key/i })).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /project key/i })).not.toBeInTheDocument()
  })

  it('프로젝트 목록이 옵션으로 뜬다', () => {
    renderForm([{ key: 'projectKey', type: 'STRING', required: true }])

    expect(screen.getByRole('option', { name: 'Atlas (BTS)' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '운영 (OPS)' })).toBeInTheDocument()
  })

  it('★filterId(UUID) 도 드롭다운이다 — 선재 결함 동반 해소', () => {
    renderForm([{ key: 'filterId', type: 'UUID', required: false }])

    expect(screen.getByRole('combobox', { name: /filter id/i })).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /filter id/i })).not.toBeInTheDocument()
  })

  it('스코프성이 아닌 필드는 그대로 텍스트 입력이다 (분기가 과하게 먹지 않는다)', () => {
    renderForm([{ key: 'markdown', type: 'STRING', required: true }])

    // markdown 은 전용 textarea 로 간다 — 선택기로 가로채면 안 된다.
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })
})

describe('GadgetConfigForm — 보드 선택기의 프로젝트 종속 (엣지 E8)', () => {
  it('보드는 프로젝트를 고르기 전에는 비활성이다', () => {
    renderForm([{ key: 'boardId', type: 'UUID', required: true }])

    const boardSelect = screen.getByRole('combobox', { name: /board id/i })
    expect(boardSelect).toBeDisabled()
    expect(screen.getByText(gadgetPickerLabels.selectProjectFirst)).toBeInTheDocument()
  })

  it('프로젝트를 고르면 그 프로젝트의 보드만 뜬다', async () => {
    const user = userEvent.setup()
    vi.mocked(useBoards).mockImplementation(
      (projectKey: string) =>
        ({
          data: projectKey === 'BTS' ? BOARDS_BTS : projectKey === 'OPS' ? BOARDS_OPS : [],
          isPending: false,
        }) as unknown as ReturnType<typeof useBoards>,
    )

    renderForm([{ key: 'boardId', type: 'UUID', required: true }])

    // 보드 선택기가 내장한 프로젝트 드롭다운 (id 가 `${fieldId}-project`)
    const projectSelect = screen.getAllByRole('combobox')[0] as HTMLSelectElement
    await user.selectOptions(projectSelect, 'BTS')

    await waitFor(() => {
      expect(screen.getByRole('option', { name: /Atlas 스크럼/ })).toBeInTheDocument()
    })
    expect(screen.queryByRole('option', { name: /운영 칸반/ })).not.toBeInTheDocument()
  })

  it('★프로젝트를 바꾸면 고른 보드를 비우고 그 사실을 알린다 (리뷰 D-4)', async () => {
    const user = userEvent.setup()
    vi.mocked(useBoards).mockImplementation(
      (projectKey: string) =>
        ({
          data: projectKey === 'BTS' ? BOARDS_BTS : projectKey === 'OPS' ? BOARDS_OPS : [],
          isPending: false,
        }) as unknown as ReturnType<typeof useBoards>,
    )

    renderForm([{ key: 'boardId', type: 'UUID', required: true }])

    const [projectSelect, boardSelect] = screen.getAllByRole('combobox') as HTMLSelectElement[]

    await user.selectOptions(projectSelect as HTMLSelectElement, 'BTS')
    await waitFor(() => {
      expect(screen.getByRole('option', { name: /Atlas 스크럼/ })).toBeInTheDocument()
    })
    await user.selectOptions(boardSelect as HTMLSelectElement, BOARDS_BTS[0]!.boardId)
    expect((boardSelect as HTMLSelectElement).value).toBe(BOARDS_BTS[0]!.boardId)

    // 프로젝트를 바꾼다 — 보드가 비고, 조용히 비지 않는다.
    await user.selectOptions(projectSelect as HTMLSelectElement, 'OPS')

    await waitFor(() => {
      expect(screen.getByText(gadgetPickerLabels.boardResetByProjectChange)).toBeInTheDocument()
    })
    expect((boardSelect as HTMLSelectElement).value).toBe('')
  })
})
