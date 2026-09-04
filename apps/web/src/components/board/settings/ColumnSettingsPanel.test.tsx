// Columns 탭 읽기 렌더 동반 테스트 — 빈 상태 3종 · 카드 수 절단 표기 (부채 177 Task 5)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

/**
 * 편집 mutate 호출을 가로챈다.
 *
 * 훅 자체가 아니라 **호출부가 무엇을 보내는가**를 재려는 것이다 — 리뷰 B1 프로브 ②가
 * 그 자리에 판정이 0 건임을 실증했다.
 */
const mockUpdateColumn = vi.fn()
vi.mock('@/hooks/use-update-column', () => ({
  useUpdateColumn: () => ({ mutate: mockUpdateColumn, isPending: false }),
  useReorderColumns: () => ({ mutate: vi.fn(), isPending: false }),
}))
import type { BoardDetail, BoardColumn, ColumnState } from '@/api/boards'
import { ColumnSettingsPanel } from './ColumnSettingsPanel'
import { boardLabels } from '@/i18n/board-labels'

/**
 * 패널을 그린다.
 *
 * 패널이 상태 매핑 mutation 훅을 쓰므로 QueryClientProvider 가 필요하다 — 이 파일이 재는 것은
 * 렌더 결과이지 서버 왕복이 아니라, 클라이언트는 매 테스트 새로 만든다(캐시 누수 차단).
 */
function renderPanel(detail: BoardDetail, canConfigure = true): void {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <ColumnSettingsPanel board={detail} canConfigure={canConfigure} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  // mock 은 테스트 사이에 살아남는다 — 지우지 않으면 calls[0] 이 앞 테스트의 호출을 집어
  // 「이 조작이 무엇을 보냈나」 판정이 옆 테스트의 결과를 재게 된다.
  vi.clearAllMocks()
})

const STATE_OPEN: ColumnState = { key: 'open', name: '열림', category: 'TODO' }
const STATE_DONE: ColumnState = { key: 'closed', name: '완료', category: 'DONE' }

/** 카드 n장을 가진 컬럼 픽스처. */
function column(overrides: Partial<BoardColumn> & { columnId: string }): BoardColumn {
  return {
    states: [STATE_OPEN],
    name: '열림',
    category: 'TODO',
    displayOrder: 0,
    cards: [],
    wipLimit: null,
    wipExceeded: false,
    ...overrides,
  }
}

function board(overrides: Partial<BoardDetail> = {}): BoardDetail {
  return {
    boardId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
    projectKey: 'ATLAS',
    name: 'ATLAS 보드',
    columns: [column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891' })],
    truncated: false,
    unplacedCount: 0,
    unmappedStates: [],
    swimlaneField: 'NONE',
    quickFilters: [],
    boardType: 'KANBAN',
    activeSprint: null,
    ...overrides,
  }
}

describe('Columns 탭 — 컬럼 렌더 (R4 · J22)', () => {
  it('T-CP-1: 컬럼을 displayOrder 순서 그대로 그린다', () => {
    renderPanel(board({
          columns: [
            column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '열림', displayOrder: 0 }),
            column({ columnId: 'c3d4e5f6-a7b8-4012-9cde-f01234567892', name: '완료', displayOrder: 1 }),
          ],
        }))

    const cards = screen.getAllByRole('article')
    expect(cards.map((c) => c.getAttribute('aria-label'))).toEqual(['열림', '완료'])
  })

  it('T-CP-2: 컬럼이 담은 상태 이름을 그린다', () => {
    renderPanel(board({
          columns: [
            column({
              columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891',
              // 컬럼 이름을 상태 이름과 다르게 둔다 — 같으면 「컬럼 제목을 읽었나 상태 배지를
              // 읽었나」가 구분되지 않아 이 판정이 무의미해진다.
              name: '작업 대기열',
              states: [STATE_OPEN, STATE_DONE],
            }),
          ],
        }))

    expect(screen.getByText('열림')).toBeInTheDocument()
    expect(screen.getByText('완료')).toBeInTheDocument()
  })
})

describe('Columns 탭 — 빈 상태 3종은 온도가 다르다 (스펙 §8b)', () => {
  it('T-CP-3: 컬럼 0개는 행동 유도 문구다', () => {
    renderPanel(board({ columns: [] }))

    expect(screen.getByText(boardLabels.settings.columnsEmpty)).toBeInTheDocument()
  })

  it('T-CP-4: 상태 0개 컬럼은 「상태 없음」을 명시한다 — 빈 채로 두지 않는다 (E1)', () => {
    renderPanel(board({
          columns: [column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', states: [] })],
        }))

    // ★그냥 비워 두면 사용자가 미완성임을 모른다. 백엔드가 상태 0개 컬럼을 허용하므로(#444 E1)
    //   이 표시가 없으면 「만들었는데 보드에 아무것도 안 뜬다」가 된다.
    expect(screen.getByText(boardLabels.settings.columnNoStates)).toBeInTheDocument()
  })

  it('T-CP-5: 미매핑 0건은 안심 문구다 — 결손이 아니다', () => {
    renderPanel(board({ unmappedStates: [] }))

    expect(screen.getByText(boardLabels.settings.unmappedEmpty)).toBeInTheDocument()
    // 세 빈 상태가 같은 문구를 쓰면 사용자가 정상을 결손으로 읽는다. 서로 다름을 못박는다.
    expect(screen.queryByText(boardLabels.settings.columnsEmpty)).not.toBeInTheDocument()
    expect(screen.queryByText(boardLabels.settings.columnNoStates)).not.toBeInTheDocument()
  })

  it('T-CP-6: 미매핑 상태가 있으면 그 이름을 그린다', () => {
    renderPanel(board({ unmappedStates: [STATE_DONE] }))

    expect(screen.getByText('완료')).toBeInTheDocument()
    expect(screen.queryByText(boardLabels.settings.unmappedEmpty)).not.toBeInTheDocument()
  })
})

describe('Columns 탭 — 카드 수 (eng 리뷰 BLOCKER-1)', () => {
  it('T-CP-7: truncated=false 면 정확한 카드 수를 적는다', () => {
    const withCards = column({
      columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891',
      cards: [
        {
          issueKey: 'ATLAS-1',
          summary: '이슈',
          assigneeId: null,
          priority: 1,
          version: 1,
          typeKey: 'task',
          epicKey: null,
          rank: null,
          labels: [],
          originalEstimateSeconds: null,
        },
      ],
    })

    renderPanel(board({ columns: [withCards], truncated: false }))

    expect(screen.getByText(new RegExp(boardLabels.settings.cardCount(1)))).toBeInTheDocument()
  })

  it('T-CP-8: truncated=true 면 수를 주장하지 않는다', () => {
    const withCards = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', cards: [] })

    renderPanel(board({ columns: [withCards], truncated: true }))

    // ★잘린 목록의 길이는 거짓이다. 「카드 0개」라고 적으면 1000장 넘는 보드에서 거짓말이 된다.
    expect(screen.getAllByText(new RegExp(boardLabels.settings.cardCountTruncated)).length).toBeGreaterThan(0)
    expect(screen.queryByText(new RegExp(boardLabels.settings.cardCount(0)))).not.toBeInTheDocument()
  })
})

describe('Columns 탭 — 컬럼 추가 (R6 · J23 · X1)', () => {
  it('T-CP-9: 권한이 있으면 추가 버튼이 보인다', () => {
    renderPanel(board())

    expect(screen.getByRole('button', { name: boardLabels.settings.addColumn })).toBeInTheDocument()
  })

  it('T-CP-10: 권한이 없으면 추가 버튼이 없다', () => {
    renderPanel(board(), false)

    expect(
      screen.queryByRole('button', { name: boardLabels.settings.addColumn }),
    ).not.toBeInTheDocument()
  })

  it('T-CP-11: 컬럼 0개일 때도 추가 버튼이 빈 상태 안에 있다', () => {
    renderPanel(board({ columns: [] }))

    // 행동 유도 빈 상태의 「행동」이 실제로 있어야 한다. 문구만 있고 버튼이 없으면
    // 사용자는 어디서 만드는지 모른 채 화면을 떠난다.
    expect(screen.getByText(boardLabels.settings.columnsEmpty)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: boardLabels.settings.addColumn })).toBeInTheDocument()
  })
})

describe('Columns 탭 — 삭제 게이팅 (R7 · Sanity G1 · 부채 179)', () => {
  it('T-CP-12: 컬럼이 1개뿐이면 삭제가 비활성이고 사유가 붙는다', () => {
    const only = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '유일' })
    renderPanel(board({ columns: [only] }))

    const button = screen.getByRole('button', {
      name: boardLabels.settings.deleteColumnTitle('유일'),
    })
    // ★이 판정을 지우면 부채 179(컬럼 0개 조회 500)로 가는 클릭 한 번짜리 경로가 열린다.
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', boardLabels.settings.lastColumnLocked)
  })

  it('T-CP-13: 컬럼이 2개 이상이면 삭제가 활성이다', () => {
    renderPanel(
      board({
        columns: [
          column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '첫째' }),
          column({ columnId: 'c3d4e5f6-a7b8-4012-9cde-f01234567892', name: '둘째' }),
        ],
      }),
    )

    expect(
      screen.getByRole('button', { name: boardLabels.settings.deleteColumnTitle('첫째') }),
    ).toBeEnabled()
  })

  it('T-CP-14: 권한이 없으면 컬럼이 여럿이어도 삭제가 비활성이다', () => {
    renderPanel(
      board({
        columns: [
          column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '첫째' }),
          column({ columnId: 'c3d4e5f6-a7b8-4012-9cde-f01234567892', name: '둘째' }),
        ],
      }),
      false,
    )

    expect(
      screen.getByRole('button', { name: boardLabels.settings.deleteColumnTitle('첫째') }),
    ).toBeDisabled()
  })
})

describe('Columns 탭 — WIP 제한 입력 (R8 · J29 · 편차 X2)', () => {
  it('T-CP-15: 최대 카드 수 입력이 하나 있고, 최소치 입력은 없다', () => {
    const only = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '진행 중' })
    renderPanel(board({ columns: [only] }))

    // ★편차 X2 를 판정으로 지킨다. 지라는 "you can enter a minimum or maximum value"(J29) 로
    //   둘을 받지만 BTS 스키마에 최소치 칸이 없다. 문서에만 적어 두면 나중에 누가 「지라에는
    //   있는데」로 입력을 하나 더 붙여도 아무 판정이 안 깨진다.
    expect(
      screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중')),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('spinbutton')).toHaveLength(1)
  })

  it('T-CP-16: WIP 제한이 없으면 입력이 비어 있고 무제한을 안내한다', () => {
    const only = column({
      columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891',
      name: '진행 중',
      wipLimit: null,
    })
    renderPanel(board({ columns: [only] }))

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    // 빈 값이 「무제한」이다 — placeholder 가 그것을 말한다. 0 을 넣어 두면 「0장 제한」으로 읽힌다.
    expect(input).toHaveValue(null)
    expect(input).toHaveAttribute('placeholder', boardLabels.settings.wipUnlimited)
  })

  it('T-CP-17: WIP 제한이 있으면 그 값이 입력에 들어 있다', () => {
    const only = column({
      columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891',
      name: '진행 중',
      wipLimit: 3,
    })
    renderPanel(board({ columns: [only] }))

    expect(screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))).toHaveValue(3)
  })
})

describe('Columns 탭 — 이름 편집·순서 핸들 (R9 · R10 · J24 · J25)', () => {
  it('T-CP-18: 이름이 편집 가능한 입력이다 — 별도 편집 모드가 없다', () => {
    const only = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '진행 중' })
    renderPanel(board({ columns: [only] }))

    // 지라는 "Select a column's name to edit … then press Enter"(J24) 다.
    expect(
      screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중')),
    ).toHaveValue('진행 중')
  })

  it('T-CP-19: 권한이 없으면 이름·WIP 입력과 순서 핸들이 전부 잠긴다', () => {
    const only = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '진행 중' })
    renderPanel(board({ columns: [only] }), false)

    expect(screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중'))).toBeDisabled()
    expect(screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))).toBeDisabled()
    expect(
      screen.getByRole('button', { name: boardLabels.settings.reorderHandleLabel('진행 중') }),
    ).toBeDisabled()
  })

  it('T-CP-20: 순서 핸들에 접근성 이름이 있다 — 아이콘만 두지 않는다', () => {
    const only = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '진행 중' })
    renderPanel(board({ columns: [only] }))

    expect(
      screen.getByRole('button', { name: boardLabels.settings.reorderHandleLabel('진행 중') }),
    ).toBeInTheDocument()
  })
})

describe('Columns 탭 — 편집이 서버로 나가는 형태 (리뷰 B1 프로브 ②)', () => {
  it('T-CP-21: 이름만 바꾸면 wipLimit 키를 싣지 않는다', async () => {
    const user = userEvent.setup()
    const only = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '진행 중' })
    renderPanel(board({ columns: [only] }))

    const input = screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '검수{Enter}')

    // ★리뷰어가 `patch: { name, wipLimit: column.wipLimit }` 로 바꿔도 전부 초록임을 실증했다.
    //   그 변경은 Task 1 이 WipLimitChange 로 막은 바로 그 결함(이름만 바꿔도 WIP 해제)을
    //   프론트에서 되살린다. 키 부재를 직접 못박는 판정은 이것 하나뿐이다.
    await waitFor(() => {
      expect(mockUpdateColumn).toHaveBeenCalled()
    })
    const [vars] = mockUpdateColumn.mock.calls[0] as [{ patch: Record<string, unknown> }]
    expect(vars.patch).toEqual({ name: '검수' })
    expect(vars.patch).not.toHaveProperty('wipLimit')
  })

  it('T-CP-22: WIP 만 바꾸면 name 키를 싣지 않는다', async () => {
    const user = userEvent.setup()
    const only = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '진행 중' })
    renderPanel(board({ columns: [only] }))

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    await user.type(input, '3{Enter}')

    // T-CP-21 의 대칭. 「항상 두 키를 싣는다」로 고치면 둘 중 하나는 반드시 red 다.
    await waitFor(() => {
      expect(mockUpdateColumn).toHaveBeenCalled()
    })
    const [vars] = mockUpdateColumn.mock.calls[0] as [{ patch: Record<string, unknown> }]
    expect(vars.patch).toEqual({ wipLimit: 3 })
  })
})

describe('Columns 탭 — 실패하면 입력이 서버 값으로 돌아온다 (스펙 §8b · 리뷰 CONCERNS C1)', () => {
  it('T-CP-23: 이름 저장 실패 시 호출부가 revert 를 불러 옛 이름이 복원된다', async () => {
    const user = userEvent.setup()
    // 카드가 준 revert 를 호출부가 **실제로 부르는지** 잰다. 카드 쪽 T-CC-9 는 revert 를
    // 직접 호출해 동작만 확인하므로, 이 배선이 끊겨도 그쪽은 초록이다.
    mockUpdateColumn.mockImplementation(
      (_vars: unknown, opts: { onError?: (e: unknown) => void }) => {
        opts.onError?.(new Error('500'))
      },
    )
    const only = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '진행 중' })
    renderPanel(board({ columns: [only] }))

    const input = screen.getByLabelText(boardLabels.settings.renameColumnLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '검수{Enter}')

    await waitFor(() => {
      expect(input).toHaveValue('진행 중')
    })
  })

  it('T-CP-24: WIP 저장 실패 시에도 서버 값으로 돌아온다', async () => {
    const user = userEvent.setup()
    mockUpdateColumn.mockImplementation(
      (_vars: unknown, opts: { onError?: (e: unknown) => void }) => {
        opts.onError?.(new Error('500'))
      },
    )
    const only = column({
      columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891',
      name: '진행 중',
      wipLimit: 5,
    })
    renderPanel(board({ columns: [only] }))

    const input = screen.getByLabelText(boardLabels.settings.wipLimitInputLabel('진행 중'))
    await user.clear(input)
    await user.type(input, '9{Enter}')

    await waitFor(() => {
      expect(input).toHaveValue(5)
    })
  })
})
