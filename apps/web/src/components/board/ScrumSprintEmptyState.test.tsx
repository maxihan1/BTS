// 스크럼 빈 상태 단위 테스트 — 문구 2종이 서로 다른지 · 백로그 링크 · 판정 함수 (FR-BD-04 E1·E2·E3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { BoardDetail, BoardColumn } from '@/api/boards'
import {
  ScrumSprintEmptyState,
  resolveScrumEmptyVariant,
  scrumEmptyStateLabels,
} from '@/components/board/ScrumSprintEmptyState'

// 라우터 Link — 라우터 컨텍스트 없이 마운트하기 위한 대역. `params` 치환과 `search` 직렬화까지
// 흉내 내 최종 href 를 그대로 잰다(대역이 경로를 삼키면 링크가 깨져도 초록이 된다).
//
// 🛑 **`search` 를 반드시 직렬화한다.** 대역이 그 prop 을 삼키면 컴포넌트가 보드 스코프를
//    버려도 유닛은 전부 초록이고 E2E 에서만 red 가 선다 — 이 저장소가 이미 밟은 양식
//    (memory `mock-swallowed-prop-is-invisible-to-unit-tests`).
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    search,
    children,
    className,
  }: {
    to: string
    params?: Record<string, string>
    search?: Record<string, string>
    children: React.ReactNode
    className?: string
  }) => {
    const resolvedTo =
      params !== undefined
        ? Object.entries(params).reduce((acc, [key, val]) => acc.replace(`$${key}`, val), to)
        : to
    const query =
      search === undefined ? '' : `?${new URLSearchParams(Object.entries(search)).toString()}`
    return (
      <a href={`${resolvedTo}${query}`} className={className}>
        {children}
      </a>
    )
  },
}))

/** 이 화면이 보고 있는 스크럼 보드 UUID. CTA 가 백로그로 실어 날라야 하는 값이다. */
const VIEWED_BOARD_ID = 'b0a1c2d3-e4f5-4678-9abc-def012345678'

// ─────────────────────────────────────────────────────────────────────────────
// fixture
// ─────────────────────────────────────────────────────────────────────────────

const EMPTY_COLUMN: BoardColumn = {
  columnId: 'c1d2e3f4-a5b6-4890-abcd-ef1234567801',
  stateKey: 'todo',
  name: '할 일',
  category: 'TODO',
  displayOrder: 1,
  wipLimit: null,
  wipExceeded: false,
  cards: [],
}

const FILLED_COLUMN: BoardColumn = {
  ...EMPTY_COLUMN,
  columnId: 'c1d2e3f4-a5b6-4890-abcd-ef1234567802',
  cards: [
    {
      issueKey: 'ATLAS-1',
      summary: '이슈',
      assigneeId: null,
      version: 1,
      priority: 1,
      epicKey: null,
      rank: null,
      typeKey: 'task',
      labels: [],
      originalEstimateSeconds: null,
    },
  ],
}

const BASE_BOARD: BoardDetail = {
  boardId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567891',
  projectKey: 'ATLAS',
  name: '스프린트 보드 A',
  columns: [EMPTY_COLUMN],
  truncated: false,
  unplacedCount: 0,
  swimlaneField: 'NONE',
  quickFilters: [],
  boardType: 'SCRUM',
  activeSprint: {
    sprintId: 'e5f6a7b8-c9d0-4890-abcd-ef1234567895',
    name: 'Sprint 3',
    startDate: '2026-09-01',
    endDate: '2026-09-15',
  },
}

// ─────────────────────────────────────────────────────────────────────────────
// resolveScrumEmptyVariant — 판정 순서가 계약이다
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveScrumEmptyVariant', () => {
  /** T-BD04-EV-1. 스크럼 · 활성 스프린트 없음 → no-active-sprint (E1) */
  it('T-BD04-EV-1: 활성 스프린트가 없으면 no-active-sprint 다', () => {
    expect(resolveScrumEmptyVariant({ ...BASE_BOARD, activeSprint: null }, false)).toBe(
      'no-active-sprint',
    )
  })

  /**
   * T-BD04-EV-2. 활성 스프린트가 없으면 **필터보다 앞선다**.
   * 필터를 초기화해도 카드가 생기지 않으므로 초기화 CTA 를 내밀면 거짓 안내가 된다.
   */
  it('T-BD04-EV-2: 활성 스프린트가 없으면 필터 0건이어도 no-active-sprint 다', () => {
    expect(resolveScrumEmptyVariant({ ...BASE_BOARD, activeSprint: null }, true)).toBe(
      'no-active-sprint',
    )
  })

  /** T-BD04-EV-3. 스크럼 · 활성 스프린트 있고 카드 0건 → empty-sprint (E2) */
  it('T-BD04-EV-3: 활성 스프린트가 있는데 카드가 0건이면 empty-sprint 다', () => {
    expect(resolveScrumEmptyVariant(BASE_BOARD, false)).toBe('empty-sprint')
  })

  /** T-BD04-EV-4. 카드가 있으면 빈 상태가 아니다 */
  it('T-BD04-EV-4: 카드가 있으면 null 이다', () => {
    expect(resolveScrumEmptyVariant({ ...BASE_BOARD, columns: [FILLED_COLUMN] }, false)).toBeNull()
  })

  /** T-BD04-EV-5. 필터가 0건을 만든 것이면 FilteredEmptyState 소관이다 */
  it('T-BD04-EV-5: 활성 스프린트가 있고 필터 0건이면 null 이다', () => {
    expect(resolveScrumEmptyVariant(BASE_BOARD, true)).toBeNull()
  })

  /** T-BD04-EV-6. 컬럼이 0개면 원인이 스프린트가 아니라 보드 설정이다 */
  it('T-BD04-EV-6: 컬럼이 0개면 null 이다', () => {
    expect(resolveScrumEmptyVariant({ ...BASE_BOARD, columns: [] }, false)).toBeNull()
  })

  /** T-BD04-EV-7. **E3** — 칸반은 어떤 조합에서도 null 이다. 화면이 이 축을 안 본다 */
  it('T-BD04-EV-7: 칸반 보드는 카드가 0건이어도 null 이다', () => {
    expect(
      resolveScrumEmptyVariant({ ...BASE_BOARD, boardType: 'KANBAN', activeSprint: null }, false),
    ).toBeNull()
  })

  /** T-BD04-EV-8. 보드 상세 로딩 중(undefined)은 빈 상태가 아니다 — 스켈레톤 소관 */
  it('T-BD04-EV-8: 보드 상세가 undefined 면 null 이다', () => {
    expect(resolveScrumEmptyVariant(undefined, false)).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ScrumSprintEmptyState — 문구 2종 + 백로그 링크
// ─────────────────────────────────────────────────────────────────────────────

describe('ScrumSprintEmptyState', () => {
  /** T-BD04-ES-1. no-active-sprint → 「백로그에서 스프린트를 시작하세요」 + 백로그 링크 (FR-1) */
  it('T-BD04-ES-1: no-active-sprint 는 스프린트를 시작하라고 안내하고 백로그로 링크한다', () => {
    render(<ScrumSprintEmptyState variant="no-active-sprint" projectKey="ATLAS" boardId={VIEWED_BOARD_ID} />)

    expect(screen.getByText(scrumEmptyStateLabels.noActiveSprint.title)).toBeInTheDocument()
    expect(screen.getByText(scrumEmptyStateLabels.noActiveSprint.description)).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: scrumEmptyStateLabels.backlogLink }),
    ).toHaveAttribute('href', `/projects/ATLAS/backlog?board=${VIEWED_BOARD_ID}`)
  })

  /**
   * T-BD04-ES-1b. CTA 가 **보고 있던 보드**를 백로그로 실어 나른다 (PR ⑥).
   *
   * 없으면 서버가 `findScrumBoardIdByProject`(created_at ASC LIMIT 1)로 **첫 번째** 스크럼
   * 보드에 폴백한다 — 두 번째 보드에서 안내를 따랐는데 다른 보드의 백로그가 열린다.
   * E2E `scrum-board.spec.ts` S3→S4 가 그 결함을 `switchToBoard` 수동 재선택으로 우회하고 있었다.
   */
  it('T-BD04-ES-1b: 백로그 링크가 보고 있던 보드를 ?board= 로 싣는다', () => {
    const otherBoardId = 'c9d8e7f6-a5b4-4321-8fed-cba987654321'
    render(
      <ScrumSprintEmptyState variant="empty-sprint" projectKey="ATLAS" boardId={otherBoardId} />,
    )

    expect(
      screen.getByRole('link', { name: scrumEmptyStateLabels.backlogLink }),
    ).toHaveAttribute('href', `/projects/ATLAS/backlog?board=${otherBoardId}`)
  })

  /** T-BD04-ES-2. empty-sprint → 「이 스프린트에 이슈가 없습니다」 (E2) */
  it('T-BD04-ES-2: empty-sprint 는 이 스프린트에 이슈가 없다고 안내한다', () => {
    render(<ScrumSprintEmptyState variant="empty-sprint" projectKey="ATLAS" boardId={VIEWED_BOARD_ID} />)

    expect(screen.getByText(scrumEmptyStateLabels.emptySprint.title)).toBeInTheDocument()
    // ①의 문구는 나오지 않는다 — 「스프린트를 시작하라」는 이미 시작한 사람에게 할 말이 아니다
    expect(
      screen.queryByText(scrumEmptyStateLabels.noActiveSprint.title),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByText(scrumEmptyStateLabels.noActiveSprint.description),
    ).not.toBeInTheDocument()
  })

  /**
   * T-BD04-ES-3. 두 문구는 **서로 다른 문장**이어야 한다 (plan Sanity Check 2).
   * 상수 단계에서 재 둬야 「같은 문자열을 복사해 넣는」 회귀가 렌더 테스트를 통과하지 못한다.
   */
  it('T-BD04-ES-3: 두 빈 상태의 문구가 서로 다르다', () => {
    expect(scrumEmptyStateLabels.noActiveSprint.title).not.toBe(
      scrumEmptyStateLabels.emptySprint.title,
    )
    expect(scrumEmptyStateLabels.noActiveSprint.description).not.toBe(
      scrumEmptyStateLabels.emptySprint.description,
    )
  })
})
