// 전환 화면 대화상자 단위 테스트 — 화면 칸만 그리고 필수가 비면 막으며 화면에 있는 값만 싣는다 (FR-WF-01 D11)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TransitionScreenDialog } from '../TransitionScreenDialog'
import { toScreenValues, initialScreenForm } from '@/lib/transition-screen'
import type { Resolution } from '@/api/resolutions'

const RESOLUTION: Resolution = {
  id: '00000000-0000-4000-8000-000000000001',
  key: 'fixed',
  name: 'Fixed',
  description: null,
  displayOrder: 1,
  isStandard: true,
}
const ASSIGNEE = '11111111-1111-4111-8111-111111111111'

vi.mock('@/hooks/use-resolutions', () => ({
  useResolutions: () => ({ data: [RESOLUTION], isLoading: false }),
}))

vi.mock('@/hooks/use-users', () => ({
  useUsers: () => ({ data: [] }),
  useUsersByIds: (ids: string[]) => ({
    data: ids.map((id) => ({ id, username: 'alice', displayName: '앨리스' })),
  }),
}))

// jsdom 은 Radix Select 포털의 pointer-capture 를 못 다룬다 — 네이티브 select 로 바꾼다 (옛 ResolutionModal 테스트와 같은 처방)
vi.mock('@/components/ui/select', async () => {
  const React = await vi.importActual<typeof import('react')>('react')
  const Ctx = React.createContext<(v: string) => void>(() => undefined)
  return {
    Select: ({ onValueChange, children }: { onValueChange: (v: string) => void; children: React.ReactNode }) =>
      React.createElement(Ctx.Provider, { value: onValueChange }, children),
    SelectTrigger: ({ children }: { children: React.ReactNode }) => children,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) => children,
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const onValueChange = React.useContext(Ctx)
      return React.createElement('button', { type: 'button', onClick: () => onValueChange(value) }, children)
    },
  }
})

describe('TransitionScreenDialog', () => {
  const onConfirm = vi.fn()

  beforeEach(() => onConfirm.mockClear())

  function renderDialog(
    screenFields: { field: string; required: boolean }[],
    prefill = { resolutionId: null as string | null, assigneeId: null as string | null },
  ) {
    render(
      <TransitionScreenDialog
        open
        transitionName="Resolve"
        screen={screenFields}
        prefill={prefill}
        onConfirm={onConfirm}
        onCancel={vi.fn()}
      />,
    )
  }

  it('화면에 있는 칸만 그리고 필수에는 별표를 붙인다', () => {
    renderDialog([
      { field: 'resolution', required: true },
      { field: 'comment', required: false },
    ])

    expect(screen.getByText('해결 결과')).toBeInTheDocument()
    expect(screen.getByText('코멘트')).toBeInTheDocument()
    expect(screen.queryByText('담당자')).not.toBeInTheDocument()
    expect(screen.getAllByLabelText('필수')).toHaveLength(1)
  })

  it('필수 코멘트가 공백뿐이면 확인이 꺼지고 쓰면 켜진다 — 서버와 같은 규칙', async () => {
    renderDialog([{ field: 'comment', required: true }])
    const confirm = screen.getByRole('button', { name: 'Resolve' })

    await userEvent.type(screen.getByRole('textbox'), '   ')
    expect(confirm).toBeDisabled()

    await userEvent.type(screen.getByRole('textbox'), '확인함')
    expect(confirm).toBeEnabled()
    await userEvent.click(confirm)
    expect(onConfirm).toHaveBeenCalledWith({ resolutionId: undefined, assigneeId: undefined, comment: '확인함' })
  })

  it('필수 해결 결과는 고르면 그 id 를 싣는다', async () => {
    renderDialog([{ field: 'resolution', required: true }])
    expect(screen.getByRole('button', { name: 'Resolve' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Fixed' }))
    await userEvent.click(screen.getByRole('button', { name: 'Resolve' }))

    expect(onConfirm).toHaveBeenCalledWith(expect.objectContaining({ resolutionId: RESOLUTION.id }))
  })

  it('이슈에 이미 있는 값으로 미리 채워 필수를 바로 통과한다 (Jira 와 같다)', async () => {
    renderDialog([{ field: 'assignee', required: true }], { resolutionId: null, assigneeId: ASSIGNEE })

    expect(screen.getByTestId('assignee-current-name')).toHaveTextContent('앨리스')
    // 담당자를 비우는 요청은 서버가 표현하지 않는다 — 해제 버튼을 보이지 않는다
    expect(screen.queryByRole('button', { name: /해제/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Resolve' }))

    expect(onConfirm).toHaveBeenCalledWith(expect.objectContaining({ assigneeId: ASSIGNEE }))
  })
})

describe('toScreenValues', () => {
  it('화면에 없는 칸은 미리 채운 값이 있어도 싣지 않는다 — 서버가 해결 결과를 비우는 종전 규칙', () => {
    const form = initialScreenForm({ resolutionId: RESOLUTION.id, assigneeId: ASSIGNEE })

    expect(toScreenValues([{ field: 'comment', required: false }], form)).toEqual({
      resolutionId: undefined,
      assigneeId: undefined,
      comment: undefined,
    })
  })
})
