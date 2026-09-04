// 컬럼 카드 편집 조작 판정 — Enter·blur 가 실제로 커밋을 부르는가 (부채 177 · 리뷰 B1)
//
// ★이 파일이 없는 동안 「WIP 저장을 통째로 무력화(`if (true) return`)」해도 746건이 전부
//   초록이었다(코드리뷰 프로브 ①). 렌더 단언만으로는 **조작이 요청을 만드는지**를 못 잰다.
//   E2E 도 못 잡는다 — 입력이 로컬 draft 상태라 서버에 안 보내도 화면 값은 그대로다.
import { describe, it, expect, vi } from 'vitest'
import type { Mock } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DndContext } from '@dnd-kit/core'
import type { JSX } from 'react'
import type { BoardColumn, ColumnState } from '@/api/boards'
import { ColumnSettingsCard } from './ColumnSettingsCard'
import { boardLabels } from '@/i18n/board-labels'

const STATE_OPEN: ColumnState = { key: 'open', name: '열림', category: 'TODO' }
const COLUMN_ID = 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891'

function column(overrides: Partial<BoardColumn> = {}): BoardColumn {
  return {
    columnId: COLUMN_ID,
    states: [STATE_OPEN],
    name: '진행 중',
    category: 'TODO',
    displayOrder: 0,
    cards: [],
    wipLimit: null,
    wipExceeded: false,
    ...overrides,
  }
}

interface Handlers {
  onRenameCommit: Mock<(name: string, revert: () => void) => void>
  onWipLimitCommit: Mock<(wipLimit: number | null, revert: () => void) => void>
}

/** 카드를 그린다. `useDroppable`·`useDraggable` 이 DndContext 를 요구한다. */
function renderCard(col: BoardColumn = column(), draggable = true): Handlers {
  const handlers: Handlers = {
    onRenameCommit: vi.fn<(name: string, revert: () => void) => void>(),
    onWipLimitCommit: vi.fn<(wipLimit: number | null, revert: () => void) => void>(),
  }
  function Harness(): JSX.Element {
    return (
      <DndContext>
        <ColumnSettingsCard
          column={col}
          truncated={false}
          draggable={draggable}
          canDelete
          onRequestDelete={vi.fn()}
          onRenameCommit={handlers.onRenameCommit}
          onWipLimitCommit={handlers.onWipLimitCommit}
        />
      </DndContext>
    )
  }
  render(<Harness />)
  return handlers
}

describe('컬럼 이름 편집 — Enter 가 커밋을 부른다 (R9 · J24)', () => {
  it('T-CC-1: 이름을 바꾸고 Enter 를 누르면 새 이름으로 커밋한다', async () => {
    const user = userEvent.setup()
    const h = renderCard()

    const input = screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '검수{Enter}')

    expect(h.onRenameCommit).toHaveBeenCalledWith('검수', expect.any(Function))
  })

  it('T-CC-2: 값이 그대로면 커밋하지 않는다 — 무의미한 요청을 막는다', async () => {
    const user = userEvent.setup()
    const h = renderCard()

    const input = screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중'))
    await user.click(input)
    await user.tab() // blur

    expect(h.onRenameCommit).not.toHaveBeenCalled()
  })

  it('T-CC-3: 공백만 남기면 커밋하지 않고 원래 이름으로 되돌린다', async () => {
    const user = userEvent.setup()
    const h = renderCard()

    const input = screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '   {Enter}')

    expect(h.onRenameCommit).not.toHaveBeenCalled()
    expect(input).toHaveValue('진행 중')
  })
})

describe('WIP 제한 편집 — 빈 값이 해제다 (R8 · J29)', () => {
  it('T-CC-4: 숫자를 넣고 Enter 를 누르면 그 값으로 커밋한다', async () => {
    const user = userEvent.setup()
    const h = renderCard()

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    await user.type(input, '3{Enter}')

    // ★이 단언이 없는 동안 commitWip 첫 줄에 `if (true) return` 을 넣어도 전부 초록이었다.
    expect(h.onWipLimitCommit).toHaveBeenCalledWith(3, expect.any(Function))
  })

  it('T-CC-5: 값을 지우면 null 로 커밋한다 — 해제이지 0 이 아니다', async () => {
    const user = userEvent.setup()
    const h = renderCard(column({ wipLimit: 5 }))

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '{Enter}')

    expect(h.onWipLimitCommit).toHaveBeenCalledWith(null, expect.any(Function))
  })

  it('T-CC-6: 0 이하는 커밋하지 않고 서버 값으로 되돌린다', async () => {
    const user = userEvent.setup()
    const h = renderCard(column({ wipLimit: 5 }))

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '0{Enter}')

    expect(h.onWipLimitCommit).not.toHaveBeenCalled()
    expect(input).toHaveValue(5)
  })

  it('T-CC-7: 이미 없는 제한을 비운 채 두면 커밋하지 않는다', async () => {
    const user = userEvent.setup()
    const h = renderCard(column({ wipLimit: null }))

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    await user.click(input)
    await user.tab()

    expect(h.onWipLimitCommit).not.toHaveBeenCalled()
  })
})

describe('권한 없음 — 조작이 잠긴다 (S7)', () => {
  it('T-CC-8: draggable=false 면 이름 입력이 잠겨 커밋이 불가능하다', async () => {
    const h = renderCard(column(), false)

    const input = screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중'))
    expect(input).toBeDisabled()
    expect(h.onRenameCommit).not.toHaveBeenCalled()
  })
})

describe('커밋 실패 — 값이 서버 값으로 돌아온다 (스펙 §8b · 리뷰 CONCERNS C1)', () => {
  it('T-CC-9: 이름 저장이 실패하면 revert 로 옛 이름이 복원된다', async () => {
    const user = userEvent.setup()
    const h = renderCard()

    const input = screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '검수{Enter}')

    // ★입력은 이 컴포넌트의 로컬 draft 다. 실패해도 서버 값이 안 바뀌므로 prop 도 그대로고,
    //   무효화 재조회는 아무것도 되돌리지 못한다. 되돌리는 유일한 수단이 이 콜백이다.
    expect(input).toHaveValue('검수')
    const [, revert] = h.onRenameCommit.mock.calls[0] ?? [undefined, () => undefined]
    act(() => {
      revert()
    })

    expect(input).toHaveValue('진행 중')
  })

  it('T-CC-10: WIP 저장이 실패하면 revert 로 서버 값이 복원된다', async () => {
    const user = userEvent.setup()
    const h = renderCard(column({ wipLimit: 5 }))

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '9{Enter}')

    expect(input).toHaveValue(9)
    act(() => {
      h.onWipLimitCommit.mock.calls[0]?.[1]()
    })

    expect(input).toHaveValue(5)
  })

  it('T-CC-11: WIP 해제가 실패하면 빈 칸이 옛 제한으로 돌아온다', async () => {
    const user = userEvent.setup()
    const h = renderCard(column({ wipLimit: 5 }))

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '{Enter}')

    act(() => {
      h.onWipLimitCommit.mock.calls[0]?.[1]()
    })

    // 해제 실패는 「빈 칸인데 서버엔 5」라는 가장 헷갈리는 상태를 남긴다.
    expect(input).toHaveValue(5)
  })
})
