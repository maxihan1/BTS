// Columns 탭 읽기 렌더 동반 테스트 — 빈 상태 3종 · 카드 수 절단 표기 (부채 177 Task 5)
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { BoardDetail, BoardColumn, ColumnState } from '@/api/boards'
import { ColumnSettingsPanel } from './ColumnSettingsPanel'
import { boardLabels } from '@/i18n/board-labels'

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
    render(
      <ColumnSettingsPanel
        board={board({
          columns: [
            column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', name: '열림', displayOrder: 0 }),
            column({ columnId: 'c3d4e5f6-a7b8-4012-9cde-f01234567892', name: '완료', displayOrder: 1 }),
          ],
        })}
      />,
    )

    const cards = screen.getAllByRole('article')
    expect(cards.map((c) => c.getAttribute('aria-label'))).toEqual(['열림', '완료'])
  })

  it('T-CP-2: 컬럼이 담은 상태 이름을 그린다', () => {
    render(
      <ColumnSettingsPanel
        board={board({
          columns: [
            column({
              columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891',
              // 컬럼 이름을 상태 이름과 다르게 둔다 — 같으면 「컬럼 제목을 읽었나 상태 배지를
              // 읽었나」가 구분되지 않아 이 판정이 무의미해진다.
              name: '작업 대기열',
              states: [STATE_OPEN, STATE_DONE],
            }),
          ],
        })}
      />,
    )

    expect(screen.getByText('열림')).toBeInTheDocument()
    expect(screen.getByText('완료')).toBeInTheDocument()
  })
})

describe('Columns 탭 — 빈 상태 3종은 온도가 다르다 (스펙 §8b)', () => {
  it('T-CP-3: 컬럼 0개는 행동 유도 문구다', () => {
    render(<ColumnSettingsPanel board={board({ columns: [] })} />)

    expect(screen.getByText(boardLabels.settings.columnsEmpty)).toBeInTheDocument()
  })

  it('T-CP-4: 상태 0개 컬럼은 「상태 없음」을 명시한다 — 빈 채로 두지 않는다 (E1)', () => {
    render(
      <ColumnSettingsPanel
        board={board({
          columns: [column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', states: [] })],
        })}
      />,
    )

    // ★그냥 비워 두면 사용자가 미완성임을 모른다. 백엔드가 상태 0개 컬럼을 허용하므로(#444 E1)
    //   이 표시가 없으면 「만들었는데 보드에 아무것도 안 뜬다」가 된다.
    expect(screen.getByText(boardLabels.settings.columnNoStates)).toBeInTheDocument()
  })

  it('T-CP-5: 미매핑 0건은 안심 문구다 — 결손이 아니다', () => {
    render(<ColumnSettingsPanel board={board({ unmappedStates: [] })} />)

    expect(screen.getByText(boardLabels.settings.unmappedEmpty)).toBeInTheDocument()
    // 세 빈 상태가 같은 문구를 쓰면 사용자가 정상을 결손으로 읽는다. 서로 다름을 못박는다.
    expect(screen.queryByText(boardLabels.settings.columnsEmpty)).not.toBeInTheDocument()
    expect(screen.queryByText(boardLabels.settings.columnNoStates)).not.toBeInTheDocument()
  })

  it('T-CP-6: 미매핑 상태가 있으면 그 이름을 그린다', () => {
    render(<ColumnSettingsPanel board={board({ unmappedStates: [STATE_DONE] })} />)

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

    render(<ColumnSettingsPanel board={board({ columns: [withCards], truncated: false })} />)

    expect(screen.getByText(new RegExp(boardLabels.settings.cardCount(1)))).toBeInTheDocument()
  })

  it('T-CP-8: truncated=true 면 수를 주장하지 않는다', () => {
    const withCards = column({ columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891', cards: [] })

    render(<ColumnSettingsPanel board={board({ columns: [withCards], truncated: true })} />)

    // ★잘린 목록의 길이는 거짓이다. 「카드 0개」라고 적으면 1000장 넘는 보드에서 거짓말이 된다.
    expect(screen.getByText(new RegExp(boardLabels.settings.cardCountTruncated))).toBeInTheDocument()
    expect(screen.queryByText(new RegExp(boardLabels.settings.cardCount(0)))).not.toBeInTheDocument()
  })
})
