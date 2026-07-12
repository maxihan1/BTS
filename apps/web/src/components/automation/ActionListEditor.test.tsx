// 다중 액션 리스트 편집기 테스트 — 추가/삭제/순서변경(위/아래)/빈상태 CTA/onChange 배열 방출 (FR-AT-02 D6 Task 5)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ActionListEditor } from './ActionListEditor'
import type { ActionFormState } from './ActionConfigEditor'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'

const setFieldAction: ActionFormState = { type: 'SET_FIELD', config: { field: 'summary', value: '' } }
const addCommentAction: ActionFormState = { type: 'ADD_COMMENT', config: { body: '' } }
const webhookAction: ActionFormState = {
  type: 'CALL_WEBHOOK',
  config: { url: '', method: 'POST', headers: {}, body: '' },
}
const DEFAULT_NEW_ACTION: ActionFormState = { type: 'SET_FIELD', config: { field: 'summary', value: '' } }

/** rows[index]를 noUncheckedIndexedAccess-safe하게 가져온다. 없으면 테스트를 즉시 실패시킨다. */
function requireRow(rows: readonly HTMLElement[], index: number): HTMLElement {
  const row = rows[index]
  if (row === undefined) {
    throw new Error(`row ${index} not found — got ${rows.length} rows`)
  }
  return row
}

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태 (EC5, design-review#2)
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionListEditor — 빈 상태', () => {
  it('액션이 0개면 안내 문구와 "액션 추가" CTA를 렌더하고 행은 없다', () => {
    render(<ActionListEditor projectKey={PROJECT_KEY} value={[]} onChange={vi.fn()} />)

    expect(screen.getByText(/아직 액션이 없습니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '액션 추가' })).toBeInTheDocument()
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
  })

  it('빈 상태에서 "액션 추가" 클릭 시 기본 SET_FIELD 액션 1건을 담아 onChange를 호출한다', async () => {
    const onChange = vi.fn()
    render(<ActionListEditor projectKey={PROJECT_KEY} value={[]} onChange={onChange} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '액션 추가' }))

    expect(onChange).toHaveBeenCalledWith([DEFAULT_NEW_ACTION])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 행 추가/삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionListEditor — 행 추가/삭제', () => {
  it('기존 액션이 있을 때 "액션 추가" 클릭 시 배열 끝에 기본 액션을 추가한다', async () => {
    const onChange = vi.fn()
    render(<ActionListEditor projectKey={PROJECT_KEY} value={[addCommentAction]} onChange={onChange} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '액션 추가' }))

    expect(onChange).toHaveBeenCalledWith([addCommentAction, DEFAULT_NEW_ACTION])
  })

  it('행의 삭제 버튼 클릭 시 해당 액션만 제거한 배열로 onChange를 호출한다', async () => {
    const onChange = vi.fn()
    render(
      <ActionListEditor projectKey={PROJECT_KEY} value={[setFieldAction, addCommentAction]} onChange={onChange} />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: '1번째 액션 삭제' }))

    expect(onChange).toHaveBeenCalledWith([addCommentAction])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 순서변경(위/아래) — S5, FR7
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionListEditor — 순서변경(위/아래)', () => {
  it('행이 1개면 위/아래 버튼이 모두 비활성화된다', () => {
    render(<ActionListEditor projectKey={PROJECT_KEY} value={[setFieldAction]} onChange={vi.fn()} />)

    expect(screen.getByRole('button', { name: '위로' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '아래로' })).toBeDisabled()
  })

  it('첫 행은 위로 버튼이 비활성화되고 마지막 행은 아래로 버튼이 비활성화된다', () => {
    render(
      <ActionListEditor
        projectKey={PROJECT_KEY}
        value={[setFieldAction, addCommentAction, webhookAction]}
        onChange={vi.fn()}
      />,
    )

    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    const firstRow = requireRow(rows, 0)
    const lastRow = requireRow(rows, 2)

    expect(within(firstRow).getByRole('button', { name: '위로' })).toBeDisabled()
    expect(within(firstRow).getByRole('button', { name: '아래로' })).not.toBeDisabled()
    expect(within(lastRow).getByRole('button', { name: '아래로' })).toBeDisabled()
    expect(within(lastRow).getByRole('button', { name: '위로' })).not.toBeDisabled()
  })

  it('두 번째 행에서 "위로" 클릭 시 첫 두 액션의 순서를 맞바꾼 배열로 onChange를 호출한다', async () => {
    const onChange = vi.fn()
    render(
      <ActionListEditor projectKey={PROJECT_KEY} value={[setFieldAction, addCommentAction]} onChange={onChange} />,
    )
    const user = userEvent.setup()

    const rows = screen.getAllByRole('listitem')
    const secondRow = requireRow(rows, 1)
    await user.click(within(secondRow).getByRole('button', { name: '위로' }))

    expect(onChange).toHaveBeenCalledWith([addCommentAction, setFieldAction])
  })

  it('첫 번째 행에서 "아래로" 클릭 시 인접 두 액션의 순서를 맞바꾼 배열로 onChange를 호출한다(S5)', async () => {
    const onChange = vi.fn()
    render(
      <ActionListEditor projectKey={PROJECT_KEY} value={[setFieldAction, addCommentAction]} onChange={onChange} />,
    )
    const user = userEvent.setup()

    const rows = screen.getAllByRole('listitem')
    const firstRow = requireRow(rows, 0)
    await user.click(within(firstRow).getByRole('button', { name: '아래로' }))

    expect(onChange).toHaveBeenCalledWith([addCommentAction, setFieldAction])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 행별 ActionConfigEditor 배선 — 인덱스 기반 격리
// ─────────────────────────────────────────────────────────────────────────────

describe('ActionListEditor — 행별 ActionConfigEditor 배선', () => {
  it('각 행에 해당 액션의 타입이 반영된 ActionConfigEditor를 렌더한다', () => {
    render(
      <ActionListEditor projectKey={PROJECT_KEY} value={[setFieldAction, addCommentAction]} onChange={vi.fn()} />,
    )

    const rows = screen.getAllByRole('listitem')
    const firstRow = requireRow(rows, 0)
    const secondRow = requireRow(rows, 1)
    const firstTypeSelect = within(firstRow).getByLabelText('액션 유형') as HTMLSelectElement
    const secondTypeSelect = within(secondRow).getByLabelText('액션 유형') as HTMLSelectElement

    expect(firstTypeSelect.value).toBe('SET_FIELD')
    expect(secondTypeSelect.value).toBe('ADD_COMMENT')
  })

  it('한 행의 값 입력은 해당 인덱스만 갱신한 배열로 onChange를 호출하고 다른 행에 영향을 주지 않는다', async () => {
    const onChange = vi.fn()
    render(
      <ActionListEditor projectKey={PROJECT_KEY} value={[setFieldAction, addCommentAction]} onChange={onChange} />,
    )
    const user = userEvent.setup()

    const rows = screen.getAllByRole('listitem')
    const firstRow = requireRow(rows, 0)
    await user.type(within(firstRow).getByLabelText('값'), 'x')

    expect(onChange).toHaveBeenCalledWith([
      { type: 'SET_FIELD', config: { field: 'summary', value: 'x' } },
      addCommentAction,
    ])
  })
})
